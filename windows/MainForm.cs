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

/// <summary>One custom-drawn AMOLED window: status, controller, link, files, address.</summary>
sealed class MainForm : Form
{
    static readonly Color Bg = Color.Black;
    static readonly Color Surface = Color.FromArgb(10, 10, 9);
    static readonly Color Line = Color.FromArgb(44, 43, 34);
    static readonly Color Outline = Color.FromArgb(88, 86, 66);
    static readonly Color Ink = Color.FromArgb(232, 228, 208);
    static readonly Color Sub = Color.FromArgb(132, 128, 104);
    static readonly Color Accent = Color.FromArgb(222, 208, 64);
    static readonly Color Cyan = Color.FromArgb(62, 182, 188);
    static readonly Color Red = Color.FromArgb(206, 66, 76);

    readonly GamepadHost host = new();
    readonly FileService files = new();
    readonly NotifyIcon tray = new();
    readonly System.Windows.Forms.Timer tick = new() { Interval = 500 };
    readonly System.Windows.Forms.Timer egg = new() { Interval = 45 };
    readonly List<(string Text, bool Ok, string? Path)> activity = new();
    readonly Queue<(string Tmp, string Name)> incoming = new();

    static Font Make(string family, float size, FontStyle st)
    {
        var f = new Font(family, size, st);
        return f.Name.Equals(family, StringComparison.OrdinalIgnoreCase) ? f : new Font("Segoe UI", size, st);
    }

    readonly Font fTitle = Make("Bahnschrift", 17f, FontStyle.Bold);
    readonly Font fHero = Make("Bahnschrift", 24f, FontStyle.Bold);
    readonly Font fLabel = Make("Bahnschrift", 8.5f, FontStyle.Bold);
    readonly Font fValue = Make("Bahnschrift", 12f, FontStyle.Bold);
    readonly Font fBody = Make("Bahnschrift", 10f, FontStyle.Regular);
    readonly Font fSmall = Make("Bahnschrift", 9f, FontStyle.Regular);
    readonly Font fMono = new("Consolas", 12f, FontStyle.Bold);

    string addresses = "";
    string link = "NONE";
    string titleText = "POCKETPAD";
    Color titleColor = Ink;
    int tickCount, eggStep;
    string eggTarget = "";
    bool dragging, hoverZone, hoverChip, hoverTitle, hoverLog, quitting, tipShown, busy, eggBusy;
    string? saveFolder;

    static readonly string SettingsFile =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Pocketpad", "folder.txt");

    float S => DeviceDpi / 96f;
    RectangleF R(float x, float y, float w, float h) => new(x * S, y * S, w * S, h * S);
    RectangleF Zone => R(44, 326, 352, 78);
    RectangleF AskChip => R(44, 436, 172, 28);
    RectangleF FolderChip => R(224, 436, 172, 28);
    RectangleF TitleRect => R(24, 18, 220, 32);
    RectangleF LogLine(int i) => R(44, 494 + i * 22, 352, 20);
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
        ClientSize = new Size((int)(440 * S), (int)(698 * S));

        try { if (File.Exists(SettingsFile)) saveFolder = File.ReadAllText(SettingsFile).Trim(); } catch { /* ignore */ }
        if (string.IsNullOrWhiteSpace(saveFolder) || !Directory.Exists(saveFolder)) saveFolder = null;

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

        files.FileArrived += (tmp, name) => Ui(() =>
        {
            incoming.Enqueue((tmp, name));
            if (!Visible) tray.ShowBalloonTip(2000, "Pocketpad", "Received " + name, ToolTipIcon.Info);
            Pump();
        });
        files.Error += m => Ui(() => Add(m, false));

        tick.Tick += (_, _) =>
        {
            host.ReleaseIfStale();
            if (tickCount++ % 10 == 0) addresses = LocalAddresses();
            link = LinkName(host.PhoneIp);
            Invalidate();
        };
        egg.Tick += (_, _) => EggStep();
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
            int colour = 0x00000000;                              // 0x00BBGGRR of the window background
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
        egg.Stop();
        tray.Visible = false;
        tray.Dispose();
        host.Dispose();
        files.Dispose();
        base.OnFormClosing(e);
    }

    // ---------- easter egg ----------

    void StartEgg()
    {
        if (eggBusy) return;
        eggBusy = true;
        eggTarget = "BY DIVAKAR ;)";
        eggStep = 0;
        egg.Start();
    }

    void EggStep()
    {
        const int total = 14;
        const string glyphs = "#@$%&01<>/|";
        var rnd = Random.Shared;
        int n = eggTarget.Length * Math.Min(eggStep, total) / total;
        var sb = new System.Text.StringBuilder(eggTarget[..n]);
        for (int i = n; i < eggTarget.Length; i++)
            sb.Append(eggTarget[i] == ' ' ? ' ' : glyphs[rnd.Next(glyphs.Length)]);
        titleText = sb.ToString();
        titleColor = eggStep >= total ? Accent : eggStep % 2 == 0 ? Cyan : Red;
        eggStep++;
        // hold the finished text for ~1.6 s (36 ticks), then scramble back
        if (eggStep == total + 36 && eggTarget != "POCKETPAD") { eggTarget = "POCKETPAD"; eggStep = 0; }
        else if (eggStep > total && eggTarget == "POCKETPAD")
        {
            egg.Stop();
            titleColor = Ink;
            eggBusy = false;
        }
        Invalidate();
    }

    // ---------- drawing ----------

    static GraphicsPath Chamfer(RectangleF r, float cut)
    {
        var p = new GraphicsPath();
        p.AddPolygon(new[]
        {
            new PointF(r.Left + cut, r.Top), new PointF(r.Right, r.Top), new PointF(r.Right, r.Bottom - cut),
            new PointF(r.Right - cut, r.Bottom), new PointF(r.Left, r.Bottom), new PointF(r.Left, r.Top + cut),
        });
        return p;
    }

    void Panel(Graphics g, RectangleF r, float cut, Color fill, Color line, bool tick = false, bool dashed = false)
    {
        float c = cut * S;
        using var path = Chamfer(r, c);
        using var b = new SolidBrush(fill);
        g.FillPath(b, path);
        using var pen = new Pen(line, Math.Max(1f, S));
        if (dashed) pen.DashStyle = DashStyle.Dash;
        g.DrawPath(pen, path);
        if (tick)
        {
            using var tp = new Pen(Accent, Math.Max(2f, 2.2f * S));
            g.DrawLine(tp, r.Left + c, r.Top, r.Left, r.Top + c);
            g.DrawLine(tp, r.Right, r.Bottom - c, r.Right - c, r.Bottom);
        }
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

    void Dot(Graphics g, float cx, float cy, float rad, Color c)
    {
        float r = rad * S;
        using var b = new SolidBrush(c);
        g.FillEllipse(b, cx * S - r, cy * S - r, r * 2, r * 2);
    }

    string FolderLabel => saveFolder == null ? "CHOOSE FOLDER" : "FOLDER: " + (Path.GetFileName(saveFolder.TrimEnd('\\')) is { Length: > 0 } n ? n : saveFolder).ToUpperInvariant();

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        g.Clear(Bg);

        bool active = host.PhoneActive;
        bool known = PhoneKnown;
        var ip = host.PhoneIp;
        Color state = active ? Cyan : known ? Accent : Red;

        // header
        Txt(g, titleText, fTitle, titleColor, TitleRect, v: StringAlignment.Center);
        Panel(g, R(296, 18, 120, 32), 8, Surface, Outline);
        Dot(g, 314, 34, 4f, state);
        Txt(g, active ? link.Replace(" CABLE", "") : "OFFLINE", fLabel, Ink, R(326, 18, 86, 32), v: StringAlignment.Center);

        // hero
        Panel(g, R(24, 70, 392, 124), 14, Surface, Line, tick: true);
        Txt(g, "// CONTROLLER LINK", fLabel, Cyan, R(44, 84, 300, 16));
        string word = active ? "CONNECTED" : known ? "NEARBY" : "WAITING";
        Txt(g, word, fHero, state, R(44, 104, 352, 40));
        string sub = active ? $"PHONE {ip}  //  {link}"
            : known ? "PHONE FOUND  //  OPEN THE CONTROLLER ON IT"
            : "OPEN POCKETPAD ON YOUR PHONE";
        Txt(g, sub, fLabel, Sub, R(44, 156, 352, 18));

        // two cells
        bool driverOk = host.DriverError == null;
        Panel(g, R(24, 206, 190, 70), 10, Surface, Line);
        Txt(g, "CONTROLLER", fLabel, Sub, R(40, 216, 160, 16));
        Txt(g, driverOk ? "XBOX 360" : "NO DRIVER", fValue, Ink, R(40, 232, 160, 22));
        Txt(g, driverOk ? "VIRTUAL PAD  +  MOUSE" : "REINSTALL POCKETPAD", fLabel, driverOk ? Sub : Red, R(40, 254, 170, 16));
        Panel(g, R(226, 206, 190, 70), 10, Surface, Line);
        Txt(g, "LINK", fLabel, Sub, R(242, 216, 160, 16));
        Txt(g, known ? link : "NONE", fValue, Ink, R(242, 232, 160, 22));
        Txt(g, known ? "WIRELESS OR CABLE" : "NO PHONE YET", fLabel, Sub, R(242, 254, 160, 16));
        if (host.NetworkError != null)
            Txt(g, host.NetworkError, fSmall, Red, R(24, 276, 392, 12));

        // files
        Panel(g, R(24, 288, 392, 278), 14, Surface, Line, tick: true);
        Txt(g, "// FILES", fLabel, Cyan, R(44, 300, 200, 16));
        var zone = Zone;
        Color zoneLine = dragging || (hoverZone && known) ? Accent : Outline;
        Panel(g, zone, 10, Color.Black, zoneLine, dashed: true);
        float zy = zone.Y / S;
        if (known)
        {
            Txt(g, "Drop files here to send to your phone", fBody, Ink, R(44, zy + 18, 352, 22), StringAlignment.Center);
            Txt(g, "OR CLICK TO CHOOSE", fLabel, Sub, R(44, zy + 44, 352, 16), StringAlignment.Center);
        }
        else
        {
            Txt(g, "Waiting for your phone", fBody, Sub, R(44, zy + 18, 352, 22), StringAlignment.Center);
            Txt(g, "OPEN POCKETPAD ON THE PHONE FIRST", fLabel, Sub, R(44, zy + 44, 352, 16), StringAlignment.Center);
        }
        Txt(g, "SAVE RECEIVED FILES", fLabel, Sub, R(44, 414, 352, 16));
        Panel(g, AskChip, 8, saveFolder == null ? Accent : Surface, saveFolder == null ? Accent : Outline);
        Txt(g, "ASK EVERY TIME", fLabel, saveFolder == null ? Bg : Ink, AskChip, StringAlignment.Center, StringAlignment.Center);
        Panel(g, FolderChip, 8, saveFolder != null ? Accent : Surface, saveFolder != null ? Accent : Outline);
        Txt(g, FolderLabel, fLabel, saveFolder != null ? Bg : Ink, FolderChip, StringAlignment.Center, StringAlignment.Center);

        if (activity.Count == 0)
        {
            Txt(g, saveFolder == null ? "You choose where each received file goes." : "Received files go to " + saveFolder,
                fSmall, Sub, R(44, 494, 352, 20));
        }
        else
        {
            for (int i = 0; i < Math.Min(3, activity.Count); i++)
            {
                var a = activity[i];
                Txt(g, a.Text + (a.Path != null ? "   >" : ""), fSmall, a.Ok ? Ink : Red, LogLine(i));
            }
        }

        // address
        Panel(g, R(24, 578, 392, 96), 14, Surface, Line);
        Txt(g, "// LAPTOP ADDRESS", fLabel, Cyan, R(44, 590, 300, 16));
        Txt(g, addresses, fMono, Ink, R(44, 610, 352, 24));
        Txt(g, "Type this on your phone if it doesn't connect by itself.", fSmall, Sub, R(44, 638, 352, 16));
    }

    // ---------- mouse and drag-drop ----------

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        bool z = Zone.Contains(e.Location) && PhoneKnown;
        bool c = AskChip.Contains(e.Location) || FolderChip.Contains(e.Location);
        bool t = TitleRect.Contains(e.Location);
        bool l = LogHit(e.Location) >= 0;
        if (z != hoverZone || c != hoverChip || t != hoverTitle || l != hoverLog)
        {
            hoverZone = z;
            hoverChip = c;
            hoverTitle = t;
            hoverLog = l;
            Cursor = z || c || t || l ? Cursors.Hand : Cursors.Default;
            Invalidate();
        }
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        base.OnMouseLeave(e);
        hoverZone = hoverChip = hoverTitle = hoverLog = false;
        Cursor = Cursors.Default;
        Invalidate();
    }

    int LogHit(Point p)
    {
        for (int i = 0; i < Math.Min(3, activity.Count); i++)
            if (activity[i].Path != null && LogLine(i).Contains(p)) return i;
        return -1;
    }

    protected override void OnMouseClick(MouseEventArgs e)
    {
        base.OnMouseClick(e);
        if (e.Button != MouseButtons.Left) return;
        int li = LogHit(e.Location);
        if (TitleRect.Contains(e.Location)) StartEgg();
        else if (AskChip.Contains(e.Location)) SetFolder(null);
        else if (FolderChip.Contains(e.Location))
        {
            using var dlg = new FolderBrowserDialog { Description = "Where should received files go?", UseDescriptionForTitle = true };
            if (saveFolder != null) dlg.SelectedPath = saveFolder;
            if (dlg.ShowDialog(this) == DialogResult.OK) SetFolder(dlg.SelectedPath);
        }
        else if (li >= 0)
        {
            try { Process.Start("explorer.exe", $"/select,\"{activity[li].Path}\""); } catch { /* ignore */ }
        }
        else if (Zone.Contains(e.Location) && PhoneKnown)
        {
            using var dlg = new OpenFileDialog { Multiselect = true, Title = "Choose files to send to your phone" };
            if (dlg.ShowDialog(this) == DialogResult.OK)
                foreach (var p in dlg.FileNames) SendAsync(p);
        }
    }

    void SetFolder(string? path)
    {
        saveFolder = path;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsFile)!);
            File.WriteAllText(SettingsFile, path ?? "");
        }
        catch { /* setting just won't persist */ }
        Invalidate();
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

    /// <summary>Save each received file: into the chosen folder, or ask where, every time.</summary>
    void Pump()
    {
        if (busy) return;
        busy = true;
        try
        {
            while (incoming.Count > 0)
            {
                var (tmp, name) = incoming.Dequeue();
                try
                {
                    string dest;
                    if (saveFolder != null && Directory.Exists(saveFolder))
                    {
                        dest = FileService.UniquePath(Path.Combine(saveFolder, name));
                    }
                    else
                    {
                        ShowMe();
                        using var dlg = new SaveFileDialog { FileName = name, Title = "Save received file", OverwritePrompt = true };
                        if (dlg.ShowDialog(this) != DialogResult.OK)
                        {
                            File.Delete(tmp);
                            Add("Discarded " + name, false);
                            continue;
                        }
                        dest = dlg.FileName;
                    }
                    File.Move(tmp, dest, true);
                    Add("Saved " + Path.GetFileName(dest) + "  //  " + Path.GetDirectoryName(dest), true, dest);
                }
                catch (Exception ex)
                {
                    Add("Couldn't save " + name + " (" + ex.Message + ")", false);
                }
            }
        }
        finally
        {
            busy = false;
        }
    }

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

    void Add(string msg, bool ok, string? path = null)
    {
        activity.Insert(0, (msg, ok, path));
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
