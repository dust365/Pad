namespace PadScreen;

sealed class TrayApp : ApplicationContext
{
    private static TrayApp? current;
    private readonly NotifyIcon icon;
    private readonly Finder finder = new();
    private readonly Dictionary<string, Session> sessions = new();
    private readonly Form pump = new();
    private string? errorText;

    public TrayApp()
    {
        current = this;
        pump.ShowInTaskbar = false;
        pump.FormBorderStyle = FormBorderStyle.FixedToolWindow;
        pump.Opacity = 0;
        pump.Show();
        pump.Hide();
        icon = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Visible = true,
            Text = "副屏"
        };
        icon.ContextMenuStrip = new ContextMenuStrip();
        finder.Changed += Rebuild;
        finder.Start();
        Rebuild();
    }

    public static void OnSessionFailed(Session session, string message)
    {
        var app = current;
        if (app == null)
        {
            return;
        }
        app.pump.BeginInvoke(() =>
        {
            if (app.sessions.TryGetValue(session.Key, out var existing) && ReferenceEquals(existing, session))
            {
                existing.Stop();
                app.sessions.Remove(session.Key);
            }
            app.errorText = message;
            app.Rebuild();
        });
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            foreach (var session in sessions.Values.ToList())
            {
                session.Stop();
            }
            finder.Dispose();
            icon.Visible = false;
            icon.Dispose();
            pump.Dispose();
        }
        base.Dispose(disposing);
    }

    private void Rebuild()
    {
        var live = sessions.Values.Count(item => item.Live);
        var connecting = sessions.Values.Any(item => !item.Live);
        icon.Text = live >= 2 ? $"副屏 · {live}" : live == 1 ? "副屏 · 已连接" : connecting ? "副屏 · 连接中" : "副屏";
        var menu = new ContextMenuStrip();
        if (!string.IsNullOrEmpty(errorText))
        {
            var error = new ToolStripMenuItem(errorText) { Enabled = false };
            menu.Items.Add(error);
            menu.Items.Add(new ToolStripSeparator());
        }
        if (!string.IsNullOrEmpty(finder.SocketNote))
        {
            menu.Items.Add(new ToolStripMenuItem(finder.SocketNote) { Enabled = false });
        }
        var listed = new HashSet<string>();
        if (finder.Pads.Count == 0 && sessions.Count == 0)
        {
            menu.Items.Add(new ToolStripMenuItem(finder.Scanning ? "正在扫描…" : "没有发现设备") { Enabled = false });
        }
        else if (finder.Pads.Count > 0)
        {
            menu.Items.Add(new ToolStripMenuItem($"已发现 {finder.Pads.Count} 台") { Enabled = false });
        }
        foreach (var pad in finder.Pads)
        {
            listed.Add(pad.Key);
            AddPad(menu, pad.Key, pad.Name, pad.Host, pad);
        }
        foreach (var pair in sessions)
        {
            if (!listed.Contains(pair.Key))
            {
                AddPad(menu, pair.Key, pair.Value.Name, "", null);
            }
        }
        menu.Items.Add(new ToolStripSeparator());
        var rescan = new ToolStripMenuItem(finder.Scanning ? "正在扫描…" : "重新扫描");
        rescan.Enabled = !finder.Scanning;
        rescan.Click += (_, _) =>
        {
            errorText = null;
            finder.Rescan();
        };
        menu.Items.Add(rescan);
        var prepare = new ToolStripMenuItem("准备虚拟屏幕");
        prepare.Click += (_, _) =>
        {
            try
            {
                errorText = VirtualDisplaySetup.Prepare();
            }
            catch (Exception ex)
            {
                errorText = ex.Message;
            }
            Rebuild();
        };
        menu.Items.Add(prepare);
        var exit = new ToolStripMenuItem("退出");
        exit.Click += (_, _) => ExitThread();
        menu.Items.Add(exit);
        icon.ContextMenuStrip = menu;
    }

    private void AddPad(ContextMenuStrip menu, string key, string name, string host, FoundPad? pad)
    {
        if (sessions.TryGetValue(key, out var session))
        {
            var state = session.Live ? "已连接" : "连接中";
            var title = string.IsNullOrEmpty(host) ? $"{session.Name}  {state}" : $"{session.Name}  {host}  {state}";
            menu.Items.Add(new ToolStripMenuItem(title) { Enabled = false });
            var disconnect = new ToolStripMenuItem("断开 " + session.Name);
            disconnect.Click += (_, _) =>
            {
                session.Stop();
                sessions.Remove(key);
                errorText = null;
                Rebuild();
            };
            menu.Items.Add(disconnect);
            return;
        }
        if (pad == null)
        {
            return;
        }
        var item = new ToolStripMenuItem($"{pad.Name}  {pad.Host}");
        item.Click += (_, _) => Connect(pad);
        menu.Items.Add(item);
    }

    private void Connect(FoundPad pad)
    {
        if (sessions.ContainsKey(pad.Key))
        {
            return;
        }
        errorText = null;
        var session = new Session(pad, () => pump.BeginInvoke(new Action(Rebuild)));
        sessions[pad.Key] = session;
        session.Start();
        Rebuild();
    }
}
