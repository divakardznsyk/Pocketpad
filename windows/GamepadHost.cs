using System.Net;
using System.Net.Sockets;
using System.Text;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace Pocketpad;

/// <summary>
/// Listens for controller packets from the phone over UDP and feeds them
/// into a virtual Xbox 360 controller (via the ViGEmBus driver).
///
/// Control packet (14 bytes, little endian):
///   [0] 0x01 (type)   [1] version
///   [2..3] buttons bitmask   [4] left trigger   [5] right trigger
///   [6..7] LX  [8..9] LY  [10..11] RX  [12..13] RY   (Xbox convention, up = positive)
/// Buttons bits: 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 Back, 7 Start,
///               8 L3, 9 R3, 10 Up, 11 Down, 12 Left, 13 Right, 14 Guide
/// Discovery: phone broadcasts "PPAD?", we answer "PPAD!" + computer name.
/// Ping: phone sends "PPNG" + 8 bytes, we echo the packet back unchanged.
/// </summary>
sealed class GamepadHost : IDisposable
{
    public const int Port = 39500;

    static readonly Xbox360Button[] ButtonMap =
    {
        Xbox360Button.A, Xbox360Button.B, Xbox360Button.X, Xbox360Button.Y,
        Xbox360Button.LeftShoulder, Xbox360Button.RightShoulder,
        Xbox360Button.Back, Xbox360Button.Start,
        Xbox360Button.LeftThumb, Xbox360Button.RightThumb,
        Xbox360Button.Up, Xbox360Button.Down, Xbox360Button.Left, Xbox360Button.Right,
        Xbox360Button.Guide,
    };

    readonly object padLock = new();
    ViGEmClient? client;
    IXbox360Controller? pad;
    UdpClient? udp;
    Thread? thread;
    volatile bool running;
    bool wasNeutral = true;

    public string? DriverError { get; private set; }
    public string? NetworkError { get; private set; }
    public volatile IPAddress? PhoneIp;
    public long LastPacketTick;

    public void Start()
    {
        try
        {
            client = new ViGEmClient();
            pad = client.CreateXbox360Controller();
            pad.AutoSubmitReport = false;
            pad.Connect();
        }
        catch (Exception e)
        {
            DriverError = "ViGEmBus driver not found. Install it, then restart Pocketpad. (" + e.Message + ")";
            pad = null;
        }

        try
        {
            udp = new UdpClient(new IPEndPoint(IPAddress.Any, Port));
            udp.EnableBroadcast = true;
            running = true;
            thread = new Thread(Loop) { IsBackground = true, Name = "pocketpad-udp" };
            thread.Start();
        }
        catch (Exception e)
        {
            NetworkError = "Could not open port " + Port + ": " + e.Message;
        }
    }

    void Loop()
    {
        var ep = new IPEndPoint(IPAddress.Any, 0);
        while (running && udp != null)
        {
            byte[] data;
            try
            {
                data = udp.Receive(ref ep);
            }
            catch (SocketException)
            {
                if (!running) break;
                continue;
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            PhoneIp = ep.Address;

            if (data.Length >= 14 && data[0] == 0x01)
            {
                LastPacketTick = Environment.TickCount64;
                Apply(data);
            }
            else if (data.Length >= 4 && Encoding.ASCII.GetString(data, 0, 4) == "PPNG")
            {
                // Latency check from the phone: bounce it straight back.
                try { udp.Send(data, data.Length, ep); } catch { /* ignore */ }
            }
            else if (data.Length >= 5 && Encoding.ASCII.GetString(data, 0, 5) == "PPAD?")
            {
                var reply = Encoding.UTF8.GetBytes("PPAD!" + Environment.MachineName);
                try { udp.Send(reply, reply.Length, ep); } catch { /* ignore */ }
            }
        }
    }

    void Apply(byte[] d)
    {
        ushort buttons = BitConverter.ToUInt16(d, 2);
        byte lt = d[4], rt = d[5];
        short lx = BitConverter.ToInt16(d, 6);
        short ly = BitConverter.ToInt16(d, 8);
        short rx = BitConverter.ToInt16(d, 10);
        short ry = BitConverter.ToInt16(d, 12);

        lock (padLock)
        {
            if (pad == null) return;
            for (int i = 0; i < ButtonMap.Length; i++)
                pad.SetButtonState(ButtonMap[i], (buttons & (1 << i)) != 0);
            pad.SetSliderValue(Xbox360Slider.LeftTrigger, lt);
            pad.SetSliderValue(Xbox360Slider.RightTrigger, rt);
            pad.SetAxisValue(Xbox360Axis.LeftThumbX, lx);
            pad.SetAxisValue(Xbox360Axis.LeftThumbY, ly);
            pad.SetAxisValue(Xbox360Axis.RightThumbX, rx);
            pad.SetAxisValue(Xbox360Axis.RightThumbY, ry);
            pad.SubmitReport();
            wasNeutral = false;
        }
    }

    /// <summary>True when a packet arrived in the last 1.5 seconds.</summary>
    public bool PhoneActive => LastPacketTick != 0 && Environment.TickCount64 - LastPacketTick < 1500;

    /// <summary>If the phone went silent, release every button so nothing stays stuck.</summary>
    public void ReleaseIfStale()
    {
        if (PhoneActive) return;
        lock (padLock)
        {
            if (pad == null || wasNeutral) return;
            for (int i = 0; i < ButtonMap.Length; i++)
                pad.SetButtonState(ButtonMap[i], false);
            pad.SetSliderValue(Xbox360Slider.LeftTrigger, 0);
            pad.SetSliderValue(Xbox360Slider.RightTrigger, 0);
            pad.SetAxisValue(Xbox360Axis.LeftThumbX, 0);
            pad.SetAxisValue(Xbox360Axis.LeftThumbY, 0);
            pad.SetAxisValue(Xbox360Axis.RightThumbX, 0);
            pad.SetAxisValue(Xbox360Axis.RightThumbY, 0);
            pad.SubmitReport();
            wasNeutral = true;
        }
    }

    public void Dispose()
    {
        running = false;
        try { udp?.Close(); } catch { /* ignore */ }
        lock (padLock)
        {
            try { pad?.Disconnect(); } catch { /* ignore */ }
            try { client?.Dispose(); } catch { /* ignore */ }
        }
    }
}
