using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Pocketpad;

/// <summary>
/// Tiny local file transfer over TCP, no internet needed.
/// Wire format (both directions): [2-byte big-endian name length][name UTF-8][8-byte big-endian size][bytes]
/// The laptop listens on 39501 (phone -> laptop); the phone listens on 39502 (laptop -> phone).
/// </summary>
sealed class FileService : IDisposable
{
    public const int ReceivePort = 39501;
    public const int PhonePort = 39502;

    TcpListener? listener;
    volatile bool running;

    public string ReceiveFolder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Pocketpad");

    public event Action<string>? FileReceived;
    public event Action<string>? Error;

    public void Start()
    {
        Directory.CreateDirectory(ReceiveFolder);
        try
        {
            listener = new TcpListener(IPAddress.Any, ReceivePort);
            listener.Start();
            running = true;
            new Thread(AcceptLoop) { IsBackground = true, Name = "pocketpad-files" }.Start();
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

                string path = UniquePath(Path.Combine(ReceiveFolder, name));
                using (var f = File.Create(path))
                {
                    var buf = new byte[256 * 1024];
                    long left = size;
                    while (left > 0)
                    {
                        int n = s.Read(buf, 0, (int)Math.Min(buf.Length, left));
                        if (n <= 0) throw new IOException("Connection closed early");
                        f.Write(buf, 0, n);
                        left -= n;
                    }
                }
                FileReceived?.Invoke(path);
            }
        }
        catch (Exception e)
        {
            Error?.Invoke("Receive failed: " + e.Message);
        }
    }

    static string UniquePath(string path)
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

    public static void SendTo(IPAddress phone, string path)
    {
        using var c = new TcpClient();
        c.Connect(phone, PhonePort);
        using var s = c.GetStream();

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
    }
}
