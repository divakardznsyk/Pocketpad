using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Pocketpad;

/// <summary>
/// Tiny local file transfer over TCP, no internet needed.
/// Wire format (both directions): [2-byte big-endian name length][name UTF-8][8-byte big-endian size][bytes]
/// Phone -> laptop: the phone connects to 39501 and uploads.
/// Laptop -> phone: the laptop sends a UDP offer (PPOF + token); the phone connects to 39503, sends the token and downloads.
/// </summary>
sealed class FileService : IDisposable
{
    public const int ReceivePort = 39501;
    public const int OfferPort = 39503;

    TcpListener? listener;
    TcpListener? offerListener;
    readonly Dictionary<long, (string Path, TaskCompletionSource Done)> offers = new();
    volatile bool running;

    /// <summary>Called with a temp file and the original name; the window decides where it is saved.</summary>
    public event Action<string, string>? FileArrived;
    public event Action<string>? Error;

    public void Start()
    {
        try
        {
            listener = new TcpListener(IPAddress.Any, ReceivePort);
            listener.Start();
            running = true;
            new Thread(AcceptLoop) { IsBackground = true, Name = "pocketpad-files" }.Start();
            offerListener = new TcpListener(IPAddress.Any, OfferPort);
            offerListener.Start();
            new Thread(OfferLoop) { IsBackground = true, Name = "pocketpad-offers" }.Start();
        }
        catch (Exception e)
        {
            Error?.Invoke("File receiving unavailable: " + e.Message);
        }
    }

    void AcceptLoop()
    {
        while (running && listener != null)
        {
            TcpClient c;
            try { c = listener.AcceptTcpClient(); }
            catch { break; }
            new Thread(() => HandleIncoming(c)) { IsBackground = true }.Start();
        }
    }

    void HandleIncoming(TcpClient c)
    {
        try
        {
            using (c)
            using (var s = c.GetStream())
            {
                var lenBuf = new byte[2];
                s.ReadExactly(lenBuf);
                int nameLen = BinaryPrimitives.ReadUInt16BigEndian(lenBuf);
                var nameBuf = new byte[nameLen];
                s.ReadExactly(nameBuf);
                string name = Path.GetFileName(Encoding.UTF8.GetString(nameBuf));
                if (string.IsNullOrWhiteSpace(name)) name = "file";

                var sizeBuf = new byte[8];
                s.ReadExactly(sizeBuf);
                long size = BinaryPrimitives.ReadInt64BigEndian(sizeBuf);

                string path = Path.Combine(Path.GetTempPath(), "pocketpad-" + Guid.NewGuid().ToString("N"));
                using (var f = File.Create(path))
                {
                    var buf = new byte[256 * 1024];
                    long left = size;
                    while (left > 0)
                    {
                        int n = s.Read(buf, 0, (int)Math.Min(buf.Length, left));
                        if (n <= 0)
                        {
                            f.Dispose();
                            try { File.Delete(path); } catch { /* ignore */ }
                            throw new IOException("Connection closed early");
                        }
                        f.Write(buf, 0, n);
                        left -= n;
                    }
                }
                FileArrived?.Invoke(path, name);
            }
        }
        catch (Exception e)
        {
            Error?.Invoke("Receive failed: " + e.Message);
        }
    }

    public static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        string dir = Path.GetDirectoryName(path)!;
        string stem = Path.GetFileNameWithoutExtension(path);
        string ext = Path.GetExtension(path);
        for (int i = 1; ; i++)
        {
            string p = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(p)) return p;
        }
    }

    void OfferLoop()
    {
        while (running && offerListener != null)
        {
            TcpClient c;
            try { c = offerListener.AcceptTcpClient(); }
            catch { break; }
            new Thread(() => Serve(c)) { IsBackground = true }.Start();
        }
    }

    void Serve(TcpClient c)
    {
        TaskCompletionSource? done = null;
        try
        {
            using (c)
            using (var s = c.GetStream())
            {
                var tb = new byte[8];
                s.ReadExactly(tb);
                long token = BinaryPrimitives.ReadInt64BigEndian(tb);
                (string Path, TaskCompletionSource Done) o;
                lock (offers)
                {
                    if (!offers.Remove(token, out o)) return; // unknown or already taken
                }
                done = o.Done;
                WriteFile(s, o.Path);
            }
            done.SetResult();
        }
        catch (Exception e)
        {
            done?.TrySetException(e);
        }
    }

    /// <summary>
    /// Offer a file to the phone: ping it over UDP until it connects and pulls the file.
    /// Throws TimeoutException when the phone never answers.
    /// </summary>
    public async Task OfferAsync(string path, Func<byte[], bool> sendUdp)
    {
        long token = Random.Shared.NextInt64();
        var tcs = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (offers) offers[token] = (path, tcs);
        var pkt = new byte[12];
        Encoding.ASCII.GetBytes("PPOF").CopyTo(pkt, 0);
        BinaryPrimitives.WriteInt64BigEndian(pkt.AsSpan(4), token);
        for (int i = 0; i < 24 && !tcs.Task.IsCompleted; i++)
        {
            sendUdp(pkt);
            if (await Task.WhenAny(tcs.Task, Task.Delay(500)) == tcs.Task) break;
        }
        if (!tcs.Task.IsCompleted)
        {
            lock (offers) offers.Remove(token);
            throw new TimeoutException("the phone didn't answer");
        }
        await tcs.Task;
    }

    static void WriteFile(Stream s, string path)
    {
        byte[] name = Encoding.UTF8.GetBytes(Path.GetFileName(path));
        var lenBuf = new byte[2];
        BinaryPrimitives.WriteUInt16BigEndian(lenBuf, (ushort)name.Length);
        s.Write(lenBuf);
        s.Write(name);

        using var f = File.OpenRead(path);
        var sizeBuf = new byte[8];
        BinaryPrimitives.WriteInt64BigEndian(sizeBuf, f.Length);
        s.Write(sizeBuf);
        f.CopyTo(s, 256 * 1024);
        s.Flush();
    }

    public void Dispose()
    {
        running = false;
        try { listener?.Stop(); } catch { /* ignore */ }
        try { offerListener?.Stop(); } catch { /* ignore */ }
    }
}
