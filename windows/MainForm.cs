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

/// <summary>One custom-drawn dark window: status, controller, files, address.</summary>
sealed class MainForm : Form
{
    static readonly Color Bg = Color.FromArgb(10, 10, 12);
    static readonly Color CardBg = Color.FromArgb(20, 20, 23);
    static readonly Color Line = Color.FromArgb(38, 38, 44);
    static readonly Color Ink = Color.FromArgb(242, 242, 244);
    static readonly Color Sub = Color.FromArgb(139, 139, 149);
    static readonly Color Accent = Color.FromArgb(53, 224, 161);
    static readonly Color Bad = Color.FromArgb(255, 107, 99);
    static readonly Color Warn = Color.FromArgb(245, 197, 66);

    readonly GamepadHost host = new();
    readonly FileService files = new();
    readonly NotifyIcon tray = new();
    readonly System.Windows.Forms.Timer tick = new() { Interval = 500 };
    readonly List<(string Text, bool Ok)> activity = new();

    readonly Font fTitle = new("Segoe UI Semibold", 20f);
    readonly Font fHead = new("Segoe UI Semibold", 12f);
    readonly Font fBody = new("Segoe UI", 10f);
    readonly Font fSmall = new("Segoe UI", 9f);
    readonly Font fMono = new("Consolas", 12f);

    string addresses = "";
    int tickCount;
    bool dragging, hoverZone, hoverFolder, quitting, tipShown;

    float S => DeviceDpi / 96f;
    RectangleF R(float x, float y, float w, float h) => new(x * S, y * S, w * S, h * S);
    RectangleF Zone => R(44, 274, 352, 96);
    RectangleF FolderLink => R(296, 238, 100, 24);
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
        ClientSize = new Size((int)(440 * S), (int)(600 * S));

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
            int colour = 0x000C0A0A;                              // 0x00BBGGRR of the window background
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
        float d = rad * 2;
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

    void Dot(Graphics g, float cx, float cy, Color c)
    {
        using var b = new SolidBrush(c);
        float r = 4.5f * S;
        g.FillEllipse(b, cx * S - r, cy * S - r, r * 2, r * 2);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.Clear(Bg);

        bool active = host.PhoneActive;
        bool known = PhoneKnown;

        // Header
        Txt(g, "Pocketpad", fTitle, Ink, R(24, 16, 240, 40), v: StringAlignment.Center);
        Box(g, R(240, 22, 176, 34), 17, CardBg, Line);
        Dot(g, 258, 39, active ? Accent : known ? Warn : Sub);
        Txt(g, active ? "Phone connected" : known ? "Phone nearby" : "Waiting for phone",
            fSmall, Ink, R(272, 22, 140, 34), v: StringAlignment.Center);

        // Controller card
        Box(g, R(24, 80, 392, 128), 20, CardBg, Line);
        Txt(g, "Controller", fHead, Ink, R(44, 96, 200, 24));
        bool driverOk = host.DriverError == null;
        Dot(g, 49, 141, driverOk ? Accent : Bad);
        Txt(g, driverOk ? "Virtual Xbox controller ready" : "Controller driver missing",
            fBody, Ink, R(62, 129, 330, 24), v: StringAlignment.Center);
        string sub = host.NetworkError ??
            (!driverOk ? "Run the Pocketpad installer again to add it, then reopen the app."
            : active ? "Your phone is playing. Games see it as an Xbox controller."
            : "On your phone, open Pocketpad and tap Start playing.");
        Txt(g, sub, fSmall, host.NetworkError != null ? Bad : Sub, R(44, 160, 352, 40));

        // Files card
        Box(g, R(24, 222, 392, 248), 20, CardBg, Line);
        Txt(g, "Files", fHead, Ink, R(44, 238, 200, 24));
        Txt(g, "Open folder", fSmall, hoverFolder ? Ink : Accent, FolderLink, StringAlignment.Far, StringAlignment.Center);

        var zone = Zone;
        Color zoneLine = dragging || (hoverZone && known) ? Accent : Color.FromArgb(62, 62, 70);
        Box(g, zone, 14, Color.FromArgb(13, 13, 15), zoneLine, dashed: true);
        if (known)
        {
            Txt(g, "Drop files here to send to your phone", fBody, Ink,
                R(zone.X / S, zone.Y / S + 22, 352, 24), StringAlignment.Center);
            Txt(g, "or click to choose files", fSmall, Sub,
                R(zone.X / S, zone.Y / S + 50, 352, 20), StringAlignment.Center);
        }
        else
        {
            Txt(g, "Waiting for your phone…", fBody, Sub,
                R(zone.X / S, zone.Y / S + 22, 352, 24), StringAlignment.Center);
            Txt(g, "Open Pocketpad on the phone first", fSmall, Sub,
                R(zone.X / S, zone.Y / S + 50, 352, 20), StringAlignment.Center);
        }

        if (activity.Count == 0)
        {
            Txt(g, "Received files go to Downloads\\Pocketpad", fSmall, Sub, R(44, 384, 352, 20));
        }
        else
        {
            for (int i = 0; i < Math.Min(3, activity.Count); i++)
            {
                var a = activity[i];
                Txt(g, a.Text, fSmall, a.Ok ? Accent : Bad, R(44, 384 + i * 24, 352, 20));
            }
        }

        // Address card
        Box(g, R(24, 484, 392, 92), 20, CardBg, Line);
        Txt(g, "Laptop address", fSmall, Sub, R(44, 498, 300, 18));
        Txt(g, addresses, fMono, Ink, R(44, 518, 352, 26));
        Txt(g, "Type this on your phone if it doesn't connect by itself.", fSmall, Sub, R(44, 548, 352, 20));
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
        Add("Sending " + name + "…", true);
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
}
