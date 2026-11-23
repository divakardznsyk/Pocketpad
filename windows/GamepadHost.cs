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
/// Mouse (type 2, 6 bytes): [0]=2 [1]=buttons (1 left, 2 right, 4 middle) [2..3] dx int16 [4..5] dy int16.
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
    int mouseButtons;

    public string? DriverError { get; private set; }
    public string? NetworkError { get; private set; }
    public volatile IPAddress? PhoneIp;
    public long LastPacketTick;
    public long LastSeenTick;
    public volatile IPEndPoint? PhoneEp; // where the phone's keep-alive socket listens: file offers go here

    /// <summary>Any packet (controller, ping, keep-alive) in the last 6 seconds.</summary>
    public bool PhoneSeen => LastSeenTick != 0 && Environment.TickCount64 - LastSeenTick < 6000;

    public event Action<string>? TextReceived;

    public bool SendToPhone(byte[] d)
    {
        var ep = PhoneEp;
        if (ep == null || udp == null) return false;
        try { udp.Send(d, d.Length, ep); return true; } catch { return false; }
    }

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
            thread = new Thread(Loop) { IsBackground = true, Name = "pocketpad-udp", Priority = ThreadPriority.AboveNormal };
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
            LastSeenTick = Environment.TickCount64;

            if (data.Length >= 14 && data[0] == 0x01)
            {
                LastPacketTick = Environment.TickCount64;
                Apply(data);
            }
            else if (data.Length >= 6 && data[0] == 0x02)
            {
                LastPacketTick = Environment.TickCount64;
                ApplyMouse(data);
            }
            else if (data.Length >= 4 && Encoding.ASCII.GetString(data, 0, 4) == "PPTX")
            {
                PhoneEp = new IPEndPoint(ep.Address, ep.Port);
                TextReceived?.Invoke(Encoding.UTF8.GetString(data, 4, data.Length - 4));
            }
            else if (data.Length >= 4 && Encoding.ASCII.GetString(data, 0, 4) == "PPNG")
            {
                // Latency check from the phone: bounce it straight back.
                try { udp.Send(data, data.Length, ep); } catch { /* ignore */ }
            }
            else if (data.Length >= 5 && Encoding.ASCII.GetString(data, 0, 5) == "PPAD?")
            {
                PhoneEp = new IPEndPoint(ep.Address, ep.Port);
                var reply = Encoding.UTF8.GetBytes("PPAD!" + Environment.MachineName);
                try { udp.Send(reply, reply.Length, ep); } catch { /* ignore */ }
            }
        }
    }

    void ApplyMouse(byte[] d)
    {
        int b = d[1];
        short dx = BitConverter.ToInt16(d, 2);
        short dy = BitConverter.ToInt16(d, 4);
        if (dx != 0 || dy != 0) MouseInjector.Move(dx, dy);
        int diff = b ^ mouseButtons;
        if ((diff & 1) != 0) MouseInjector.Button(MouseInjector.Left, (b & 1) != 0);
        if ((diff & 2) != 0) MouseInjector.Button(MouseInjector.Right, (b & 2) != 0);
        if ((diff & 4) != 0) MouseInjector.Button(MouseInjector.Middle, (b & 4) != 0);
        mouseButtons = b;
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
        if (mouseButtons != 0) ApplyMouse(new byte[] { 2, 0, 0, 0, 0, 0 });
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
