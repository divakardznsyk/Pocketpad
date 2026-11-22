using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Pocketpad;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

sealed class MainForm : Form
{
    readonly GamepadHost host = new();
    readonly FileService files = new();
    readonly Label status = new();
    readonly Label address = new();
    readonly Label driver = new();
    readonly Button sendBtn = new();
    readonly Button openBtn = new();
    readonly ListBox log = new();
    readonly System.Windows.Forms.Timer tick = new() { Interval = 400 };

    public MainForm()
    {
        Text = "Pocketpad";
        ClientSize = new Size(460, 440);
        MinimumSize = new Size(420, 420);
        BackColor = Color.FromArgb(18, 18, 20);
        ForeColor = Color.FromArgb(235, 235, 235);
        Font = new Font("Segoe UI", 10f);
        AllowDrop = true;

        var title = new Label
        {
            Text = "Pocketpad",
            Font = new Font("Segoe UI Semibold", 20f),
            AutoSize = true,
            Location = new Point(18, 14),
        };
        status.AutoSize = false;
        status.Location = new Point(20, 62);
        status.Size = new Size(420, 26);
        status.Font = new Font("Segoe UI Semibold", 11f);

        address.AutoSize = false;
        address.Location = new Point(20, 92);
        address.Size = new Size(420, 44);
        address.ForeColor = Color.FromArgb(170, 170, 175);

        driver.AutoSize = false;
        driver.Location = new Point(20, 138);
        driver.Size = new Size(420, 44);
        driver.ForeColor = Color.FromArgb(255, 130, 120);

        sendBtn.Text = "Send file to phone…";
        sendBtn.Location = new Point(20, 188);
        sendBtn.Size = new Size(200, 36);
        StyleButton(sendBtn);
        sendBtn.Click += (_, _) => PickAndSend();

        openBtn.Text = "Open received files";
        openBtn.Location = new Point(232, 188);
        openBtn.Size = new Size(200, 36);
        StyleButton(openBtn);
        openBtn.Click += (_, _) => Process.Start(new ProcessStartInfo(files.ReceiveFolder) { UseShellExecute = true });

        var hint = new Label
        {
            Text = "Tip: you can also drag a file onto this window.",
            AutoSize = true,
            ForeColor = Color.FromArgb(130, 130, 135),
            Location = new Point(20, 232),
        };

        log.Location = new Point(20, 262);
        log.Size = new Size(420, 160);
        log.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
        log.BackColor = Color.FromArgb(28, 28, 32);
        log.ForeColor = Color.FromArgb(200, 200, 205);
        log.BorderStyle = BorderStyle.None;

        Controls.AddRange(new Control[] { title, status, address, driver, sendBtn, openBtn, hint, log });

        DragEnter += (_, e) =>
        {
            if (e.Data?.GetDataPresent(DataFormats.FileDrop) == true) e.Effect = DragDropEffects.Copy;
        };
        DragDrop += (_, e) =>
        {
            if (e.Data?.GetData(DataFormats.FileDrop) is string[] paths)
                foreach (var p in paths) SendAsync(p);
        };

        files.FileReceived += p => Ui(() => Log("Received: " + Path.GetFileName(p)));
        files.Error += m => Ui(() => Log(m));

        tick.Tick += (_, _) => Refresh_();
        Shown += (_, _) =>
        {
            host.Start();
            files.Start();
            address.Text = "Your laptop address (type this on the phone if it doesn't find the laptop by itself):\n" + LocalAddresses();
            if (host.DriverError != null) driver.Text = host.DriverError;
            else if (host.NetworkError != null) driver.Text = host.NetworkError;
            Refresh_();
            tick.Start();
        };
        FormClosing += (_, _) =>
        {
            tick.Stop();
            host.Dispose();
            files.Dispose();
        };
    }

    static void StyleButton(Button b)
    {
        b.FlatStyle = FlatStyle.Flat;
        b.FlatAppearance.BorderColor = Color.FromArgb(70, 70, 78);
        b.BackColor = Color.FromArgb(36, 36, 42);
        b.ForeColor = Color.White;
        b.Cursor = Cursors.Hand;
    }

    void Refresh_()
    {
        host.ReleaseIfStale();
        bool active = host.PhoneActive;
        var ip = host.PhoneIp;
        if (active)
        {
            status.Text = "Phone connected  •  " + ip;
            status.ForeColor = Color.FromArgb(110, 220, 140);
        }
        else if (ip != null)
        {
            status.Text = "Phone seen (" + ip + ") — open the controller screen to play";
            status.ForeColor = Color.FromArgb(240, 200, 100);
        }
        else
        {
            status.Text = "Waiting for phone…";
            status.ForeColor = Color.FromArgb(170, 170, 175);
        }
        sendBtn.Enabled = ip != null;
    }

    void PickAndSend()
    {
        using var dlg = new OpenFileDialog { Multiselect = true, Title = "Choose files to send to your phone" };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        foreach (var p in dlg.FileNames) SendAsync(p);
    }

    void SendAsync(string path)
    {
        var ip = host.PhoneIp;
        if (ip == null)
        {
            Log("No phone found yet. Open Pocketpad on the phone first.");
            return;
        }
        if (!File.Exists(path)) return;
        string name = Path.GetFileName(path);
        Log("Sending: " + name + " …");
        Task.Run(() =>
        {
            try
            {
                FileService.SendTo(ip, path);
                Ui(() => Log("Sent: " + name));
            }
            catch (Exception e)
            {
                Ui(() => Log("Send failed (is the phone app open on the Share tab?): " + e.Message));
            }
        });
    }

    void Log(string msg)
    {
        log.Items.Insert(0, DateTime.Now.ToString("HH:mm:ss") + "  " + msg);
        while (log.Items.Count > 100) log.Items.RemoveAt(log.Items.Count - 1);
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
        return list.Count == 0 ? "(not connected to any network)" : string.Join("   ", list);
    }
}
