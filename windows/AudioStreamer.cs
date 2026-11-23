using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace Pocketpad;

/// <summary>
/// Captures everything the laptop plays (WASAPI loopback) and sends it to the phone as 16-bit stereo PCM over UDP.
/// Packet: "PPAU", sample rate (int32 LE), channels (always 2), then 300 frames of samples (1200 bytes).
/// Optionally mutes the laptop's speakers while streaming and puts the old mute state back afterwards.
/// </summary>
sealed class AudioStreamer : IDisposable
{
    const int FramesPerPacket = 300;

    WasapiLoopbackCapture? cap;
    MMDevice? device;
    bool muted, wasMuted;
    byte[] pkt = new byte[9 + FramesPerPacket * 4];
    int fill;
    Func<byte[], int, bool>? send;

    public bool Running => cap != null;

    public void Start(Func<byte[], int, bool> sendUdp, bool muteLaptop)
    {
        if (cap != null) return;
        send = sendUdp;
        cap = new WasapiLoopbackCapture();
        var wf = cap.WaveFormat;
        int ch = wf.Channels;
        bool isFloat = wf.BitsPerSample == 32; // shared-mode mix format is 32-bit float
        System.Text.Encoding.ASCII.GetBytes("PPAU").CopyTo(pkt, 0);
        BitConverter.GetBytes(wf.SampleRate).CopyTo(pkt, 4);
        pkt[8] = 2;
        fill = 0;
        cap.DataAvailable += (_, e) => OnData(e.Buffer, e.BytesRecorded, ch, isFloat, wf.BitsPerSample / 8);
        cap.StartRecording();

        if (muteLaptop)
        {
            try
            {
                device = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                wasMuted = device.AudioEndpointVolume.Mute;
                device.AudioEndpointVolume.Mute = true;
                muted = true;
            }
            catch { /* streaming still works, the laptop just keeps playing */ }
        }
    }

    void OnData(byte[] buf, int bytes, int ch, bool isFloat, int sampleBytes)
    {
        int frameBytes = ch * sampleBytes;
        for (int pos = 0; pos + frameBytes <= bytes; pos += frameBytes)
        {
            short l = Sample(buf, pos, isFloat, sampleBytes);
            short r = ch > 1 ? Sample(buf, pos + sampleBytes, isFloat, sampleBytes) : l;
            pkt[9 + fill++] = (byte)l;
            pkt[9 + fill++] = (byte)(l >> 8);
            pkt[9 + fill++] = (byte)r;
            pkt[9 + fill++] = (byte)(r >> 8);
            if (fill == FramesPerPacket * 4)
            {
                send?.Invoke(pkt, pkt.Length);
                fill = 0;
            }
        }
    }

    static short Sample(byte[] b, int pos, bool isFloat, int sampleBytes)
    {
        if (isFloat)
        {
            float f = BitConverter.ToSingle(b, pos);
            return (short)(Math.Clamp(f, -1f, 1f) * 32767f);
        }
        return sampleBytes == 2 ? BitConverter.ToInt16(b, pos) : (short)(BitConverter.ToInt32(b, pos) >> 16);
    }

    public void Stop()
    {
        var c = cap;
        cap = null;
        if (c != null)
        {
            try { c.StopRecording(); } catch { /* ignore */ }
            c.Dispose();
        }
        if (muted && device != null)
        {
            try { device.AudioEndpointVolume.Mute = wasMuted; } catch { /* ignore */ }
        }
        muted = false;
        device?.Dispose();
        device = null;
    }

    public void Dispose() => Stop();
}
