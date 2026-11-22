using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

namespace Pocketpad;

static class Program
{
    [STAThread]
    static void Main()
    {
        // One copy only: a second one could not open the same network ports anyway.
        using var mutex = new Mutex(true, "Pocketpad.SingleInstance", out bool first);
        if (!first) return;
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

/// <summary>One custom-drawn dark window: status in dots, controller, link, files, address.</summary>
sealed class MainForm : Form
{
    static readonly Color Bg = Color.FromArgb(11, 11, 12);
    static readonly Color Surface = Color.FromArgb(13, 13, 14);
    static readonly Color Line = Color.FromArgb(46, 46, 49);
    static readonly Color Outline = Color.FromArgb(78, 78, 82);
    static readonly Color Ink = Color.FromArgb(237, 237, 232);
    static readonly Color Sub = Color.FromArgb(138, 138, 134);
    static readonly Color Accent = Color.FromArgb(255, 77, 46);

    readonly GamepadHost host = new();
    readonly FileService files = new();
    readonly NotifyIcon tray = new();
    readonly System.Windows.Forms.Timer tick = new() { Interval = 500 };
    readonly List<(string Text, bool Ok)> activity = new();

    readonly Font fLabel = new("Consolas", 8.5f, FontStyle.Bold);
    readonly Font fValue = new("Consolas", 10.5f, FontStyle.Bold);
    readonly Font fBody = new("Segoe UI", 10f);
    readonly Font fSmall = new("Segoe UI", 9f);
    readonly Font fMono = new("Consolas", 12f, FontStyle.Bold);

    string addresses = "";
    string link = "NONE";
    int tickCount;
    bool dragging, hoverZone, hoverFolder, quitting, tipShown;

    float S => DeviceDpi / 96f;
    RectangleF R(float x, float y, float w, float h) => new(x * S, y * S, w * S, h * S);
    RectangleF Zone => R(24, 346, 392, 92);
    RectangleF FolderLink => R(296, 314, 120, 22);
    bool PhoneKnown => host.PhoneIp != null;

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    public MainForm()
    {
        Text = "Pocketpad";
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Bg;
        AllowDrop = true;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
        ClientSize = new Size((int)(440 * S), (int)(644 * S));

        var icon = AppIcon();
        if (icon != null) Icon = icon;
        tray.Icon = icon ?? SystemIcons.Application;
        tray.Text = "Pocketpad";
        tray.Visible = true;
        tray.DoubleClick += (_, _) => ShowMe();
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Pocketpad", null, (_, _) => ShowMe());
        menu.Items.Add("Quit", null, (_, _) =>
        {
            quitting = true;
            Close();
        });
        tray.ContextMenuStrip = menu;

        files.FileReceived += p => Ui(() =>
        {
            string n = Path.GetFileName(p);
            Add("Received " + n, true);
            if (!Visible) tray.ShowBalloonTip(2000, "Pocketpad", "Received " + n, ToolTipIcon.Info);
        });
        files.Error += m => Ui(() => Add(m, false));

        tick.Tick += (_, _) =>
        {
            host.ReleaseIfStale();
            if (tickCount++ % 10 == 0) addresses = LocalAddresses();
            link = LinkName(host.PhoneIp);
            Invalidate();
        };
        Shown += (_, _) =>
        {
            host.Start();
            files.Start();
            addresses = LocalAddresses();
            tick.Start();
        };
    }

    static Icon? AppIcon()
    {
        try
        {
            string? exe = Environment.ProcessPath;
            return exe == null ? null : Icon.ExtractAssociatedIcon(exe);
        }
        catch
        {
            return null;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        try
        {
            int on = 1;
            DwmSetWindowAttribute(Handle, 20, ref on, 4);        // dark title bar
            int colour = 0x000C0B0B;                              // 0x00BBGGRR of the window background
            DwmSetWindowAttribute(Handle, 35, ref colour, 4);    // title bar colour (Windows 11)
            DwmSetWindowAttribute(Handle, 34, ref colour, 4);    // border colour (Windows 11)
        }
        catch
        {
            // Older Windows: normal title bar, no problem.
        }
    }

    void ShowMe()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!quitting && e.CloseReason == CloseReason.UserClosing)
        {
            // Keep running in the tray so the phone can stay connected while you play.
            e.Cancel = true;
            Hide();
            if (!tipShown)
            {
                tipShown = true;
                tray.ShowBalloonTip(3000, "Pocketpad is still running",
                    "It stays here so your phone can connect. Right-click the icon to quit.", ToolTipIcon.Info);
            }
            return;
        }
        tick.Stop();
        tray.Visible = false;
        tray.Dispose();
        host.Dispose();
        files.Dispose();
        base.OnFormClosing(e);
    }

    // ---------- drawing ----------

    static GraphicsPath Round(RectangleF r, float rad)
    {
        float d = Math.Min(rad * 2, Math.Min(r.Width, r.Height));
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    void Box(Graphics g, RectangleF r, float rad, Color fill, Color line, bool dashed = false)
    {
        using var path = Round(r, rad * S);
        using var b = new SolidBrush(fill);
        g.FillPath(b, path);
        using var pen = new Pen(line, Math.Max(1f, S));
        if (dashed) pen.DashStyle = DashStyle.Dash;
        g.DrawPath(pen, path);
    }

    void Hair(Graphics g, float x1, float y1, float x2, float y2)
    {
        using var pen = new Pen(Line, Math.Max(1f, S));
        g.DrawLine(pen, x1 * S, y1 * S, x2 * S, y2 * S);
    }

    void Txt(Graphics g, string t, Font f, Color c, RectangleF r,
        StringAlignment h = StringAlignment.Near, StringAlignment v = StringAlignment.Near)
    {
        using var br = new SolidBrush(c);
        using var sf = new StringFormat(StringFormat.GenericTypographic)
        {
            Alignment = h,
            LineAlignment = v,
            Trimming = StringTrimming.EllipsisCharacter,
        };
        g.DrawString(t, f, br, r, sf);
    }

    void Dot(Graphics g, float cx, float cy, float rad, bool filled, Color c)
    {
        float r = rad * S;
        if (filled)
        {
            using var b = new SolidBrush(c);
            g.FillEllipse(b, cx * S - r, cy * S - r, r * 2, r * 2);
        }
        else
        {
            using var pen = new Pen(c, Math.Max(1f, S));
            g.DrawEllipse(pen, cx * S - r, cy * S - r, r * 2, r * 2);
        }
    }

    /// <summary>Dot-matrix text. x, y and pitch are in 96-dpi design pixels.</summary>
    void DotText(Graphics g, string t, float x, float y, float pitch, Color lit)
    {
        float p = pitch * S;
        float r = p * 0.34f;
        using var on = new SolidBrush(lit);
        using var off = new SolidBrush(Color.FromArgb(46, 255, 255, 255));
        for (int i = 0; i < t.Length; i++)
        {
            var rows = DotFont.Rows(t[i]);
            for (int row = 0; row < 7; row++)
            {
                for (int col = 0; col < 5; col++)
                {
                    bool isLit = ((rows[row] >> (4 - col)) & 1) == 1;
                    float cx = x * S + (i * 6 + col) * p + p / 2;
                    float cy = y * S + row * p + p / 2;
                    g.FillEllipse(isLit ? on : off, cx - r, cy - r, r * 2, r * 2);
                }
            }
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.Clear(Bg);

        bool active = host.PhoneActive;
        bool known = PhoneKnown;
        var ip = host.PhoneIp;

        // header: name in dots + link pill
        DotText(g, "POCKETPAD", 24, 24, 3f, Ink);
        Box(g, R(304, 18, 112, 32), 16, Color.Transparent, Outline);
        Dot(g, 322, 34, 4f, active || known, active ? Accent : Sub);
        Txt(g, active ? link.Replace(" CABLE", "") : "OFFLINE", fLabel, Ink, R(334, 18, 78, 32), v: StringAlignment.Center);

        // hero: big dot-matrix status
        string word = active ? "CONNECTED" : known ? "NEARBY" : "WAITING";
        float pitch = Math.Min(8f, 392f / (word.Length * 6 - 1));
        DotText(g, word, 24, 84, pitch, Ink);
        Dot(g, 408, 90, 5f, active, active ? Accent : Sub);
        string sub = active ? $"PHONE  {ip}  -  {link}"
            : known ? "PHONE FOUND  -  OPEN THE CONTROLLER ON IT"
            : "OPEN POCKETPAD ON YOUR PHONE";
        Txt(g, sub, fLabel, Sub, R(24, 84 + 7 * pitch + 16, 392, 20));

        Hair(g, 24, 196, 416, 196);

        // two cells: controller + link
        bool driverOk = host.DriverError == null;
        Txt(g, "CONTROLLER", fLabel, Sub, R(24, 214, 180, 18));
        Txt(g, driverOk ? "XBOX 360" : "NO DRIVER", fValue, Ink, R(24, 236, 180, 22));
        Txt(g, driverOk ? "VIRTUAL PAD READY" : "REINSTALL POCKETPAD", fLabel, driverOk ? Sub : Accent, R(24, 260, 190, 18));
        Hair(g, 220, 208, 220, 282);
        Txt(g, "LINK", fLabel, Sub, R(244, 214, 170, 18));
        Txt(g, known ? link : "NONE", fValue, Ink, R(244, 236, 170, 22));
        Txt(g, known ? "WIRELESS OR CABLE" : "NO PHONE YET", fLabel, Sub, R(244, 260, 170, 18));
        if (host.NetworkError != null)
            Txt(g, host.NetworkError, fSmall, Accent, R(24, 284, 392, 18));

        Hair(g, 24, 304, 416, 304);

        // files
        Txt(g, "FILES", fLabel, Sub, R(24, 316, 200, 18));
        Txt(g, "OPEN FOLDER", fLabel, hoverFolder ? Ink : Accent, FolderLink, StringAlignment.Far, StringAlignment.Near);

        var zone = Zone;
        Color zoneLine = dragging || (hoverZone && known) ? Accent : Outline;
        Box(g, zone, 16, Surface, zoneLine, dashed: true);
        float zy = zone.Y / S;
        if (known)
        {
            Txt(g, "Drop files here to send to your phone", fBody, Ink, R(24, zy + 22, 392, 24), StringAlignment.Center);
            Txt(g, "OR CLICK TO CHOOSE", fLabel, Sub, R(24, zy + 52, 392, 18), StringAlignment.Center);
        }
        else
        {
            Txt(g, "Waiting for your phone", fBody, Sub, R(24, zy + 22, 392, 24), StringAlignment.Center);
            Txt(g, "OPEN POCKETPAD ON THE PHONE FIRST", fLabel, Sub, R(24, zy + 52, 392, 18), StringAlignment.Center);
        }

        if (activity.Count == 0)
        {
            Txt(g, "Received files go to Downloads\\Pocketpad", fSmall, Sub, R(24, 452, 392, 20));
        }
        else
        {
            for (int i = 0; i < Math.Min(3, activity.Count); i++)
            {
                var a = activity[i];
                Txt(g, a.Text, fSmall, a.Ok ? Ink : Accent, R(24, 452 + i * 22, 392, 20));
            }
        }

        Hair(g, 24, 528, 416, 528);

        // address
        Txt(g, "LAPTOP ADDRESS", fLabel, Sub, R(24, 542, 300, 18));
        Txt(g, addresses, fMono, Ink, R(24, 562, 392, 26));
        Txt(g, "Type this on your phone if it doesn't connect by itself.", fSmall, Sub, R(24, 592, 392, 18));
        Txt(g, "USB: plug in the cable and turn on USB tethering on the phone.", fSmall, Sub, R(24, 612, 392, 18));
    }

    // ---------- mouse and drag-drop ----------

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        bool z = Zone.Contains(e.Location) && PhoneKnown;
        bool f = FolderLink.Contains(e.Location);
        if (z != hoverZone || f != hoverFolder)
        {
            hoverZone = z;
            hoverFolder = f;
            Cursor = z || f ? Cursors.Hand : Cursors.Default;
            Invalidate();
        }
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        hoverZone = hoverFolder = false;
        Cursor = Cursors.Default;
        Invalidate();
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        base.OnMouseClick(e);
        if (e.Button != MouseButtons.Left) return;
        if (FolderLink.Contains(e.Location))
        {
            Directory.CreateDirectory(files.ReceiveFolder);
            Process.Start(new ProcessStartInfo(files.ReceiveFolder) { UseShellExecute = true });
        }
        else if (Zone.Contains(e.Location) && PhoneKnown)
        {
            using var dlg = new OpenFileDialog { Multiselect = true, Title = "Choose files to send to your phone" };
            if (dlg.ShowDialog(this) == DialogResult.OK)
                foreach (var p in dlg.FileNames) SendAsync(p);
        }
    }

    protected override void OnDragEnter(DragEventArgs e)
    {
        base.OnDragEnter(e);
        if (e.Data?.GetDataPresent(DataFormats.FileDrop) == true)
        {
            e.Effect = DragDropEffects.Copy;
            dragging = true;
            Invalidate();
        }
    }

    protected override void OnDragLeave(EventArgs e)
    {
        base.OnDragLeave(e);
        dragging = false;
        Invalidate();
    }

    protected override void OnDragDrop(DragEventArgs e)
    {
        base.OnDragDrop(e);
        dragging = false;
        if (e.Data?.GetData(DataFormats.FileDrop) is string[] paths)
            foreach (var p in paths) SendAsync(p);
        Invalidate();
    }

    // ---------- actions ----------

    void SendAsync(string path)
    {
        var ip = host.PhoneIp;
        if (ip == null)
        {
            Add("No phone found yet. Open Pocketpad on the phone first.", false);
            return;
        }
        if (!File.Exists(path)) return;
        string name = Path.GetFileName(path);
        Add("Sending " + name + "...", true);
        Task.Run(() =>
        {
            try
            {
                FileService.SendTo(ip, path);
                Ui(() => Add("Sent " + name, true));
            }
            catch (Exception ex)
            {
                Ui(() => Add("Couldn't send " + name + ". Is the phone app open? (" + ex.Message + ")", false));
            }
        });
    }

    void Add(string msg, bool ok)
    {
        activity.Insert(0, (msg, ok));
        if (activity.Count > 20) activity.RemoveAt(activity.Count - 1);
        Invalidate();
    }

    void Ui(Action a)
    {
        if (IsHandleCreated && !IsDisposed) BeginInvoke(a);
    }

    static string LocalAddresses()
    {
        var list = new List<string>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                string s = ua.Address.ToString();
                if (s.StartsWith("169.254.")) continue;
                list.Add(s);
            }
        }
        return list.Count == 0 ? "Not on any network" : string.Join("   ", list);
    }

    /// <summary>Which of our network adapters shares a subnet with the phone: a USB cable, Wi-Fi or something else.</summary>
    static string LinkName(IPAddress? phone)
    {
        if (phone == null) return "NONE";
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                    if (!SameSubnet(ua.Address, phone, ua.IPv4Mask)) continue;
                    string d = (ni.Description + " " + ni.Name).ToLowerInvariant();
                    if (d.Contains("rndis") || d.Contains("remote ndis") || d.Contains("usb")) return "USB CABLE";
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211) return "WI-FI";
                    return "NETWORK";
                }
            }
        }
        catch
        {
            // fall through
        }
        return "NETWORK";
    }

    static bool SameSubnet(IPAddress a, IPAddress b, IPAddress mask)
    {
        var x = a.GetAddressBytes();
        var y = b.GetAddressBytes();
        var m = mask.GetAddressBytes();
        if (x.Length != 4 || y.Length != 4 || m.Length != 4) return false;
        for (int i = 0; i < 4; i++)
            if ((x[i] & m[i]) != (y[i] & m[i])) return false;
        return true;
    }
}
