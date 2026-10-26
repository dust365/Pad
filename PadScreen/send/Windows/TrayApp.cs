using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Win32;

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
        PointerGuard.Start();
        SystemEvents.DisplaySettingsChanged += (_, _) =>
        {
            try
            {
                pump.BeginInvoke(new Action(Monitors.SyncLayout));
            }
            catch (Exception)
            {
                Monitors.SyncLayout();
            }
        };
        Monitors.SyncLayout();
        icon = new NotifyIcon
        {
            Icon = LoadIcon(),
            Visible = true,
            Text = "副屏"
        };
        icon.MouseUp += (_, e) =>
        {
            try
            {
                SetForegroundWindow(pump.Handle);
            }
            catch (Exception)
            {
            }
            if (e.Button == MouseButtons.Left)
            {
                ShowMenu();
            }
        };
        icon.ContextMenuStrip = new ContextMenuStrip();
        finder.Changed += Rebuild;
        finder.Start();
        Rebuild();
        var promote = new System.Windows.Forms.Timer { Interval = 600 };
        promote.Tick += (_, _) =>
        {
            promote.Stop();
            promote.Dispose();
            PromoteIcon();
        };
        promote.Start();
        WatchShowRequest();
        Log.Write("托盘图标已显示");
    }

    private void ShowMenu()
    {
        try
        {
            SetForegroundWindow(pump.Handle);
            typeof(NotifyIcon).GetMethod("ShowContextMenu", BindingFlags.Instance | BindingFlags.NonPublic)?.Invoke(icon, null);
        }
        catch (Exception ex)
        {
            Log.Write("打开菜单失败：" + ex.Message);
        }
    }

    private void WatchShowRequest()
    {
        var show = new EventWaitHandle(false, EventResetMode.AutoReset, "ai.pad.screen.windows.show");
        var thread = new Thread(() =>
        {
            while (true)
            {
                try
                {
                    show.WaitOne();
                    pump.BeginInvoke(ShowMenu);
                }
                catch (Exception)
                {
                    break;
                }
            }
        })
        { IsBackground = true, Name = "pad-show" };
        thread.Start();
    }

    private static Icon LoadIcon()
    {
        try
        {
            var path = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(path))
            {
                var extracted = Icon.ExtractAssociatedIcon(path);
                if (extracted != null)
                {
                    return extracted;
                }
            }
        }
        catch (Exception ex)
        {
            Log.Write("读取图标失败：" + ex.Message);
        }
        return SystemIcons.Application;
    }

    private static void PromoteIcon()
    {
        try
        {
            using var root = Registry.CurrentUser.OpenSubKey(@"Control Panel\NotifyIconSettings", true);
            var exe = Environment.ProcessPath;
            if (root == null || string.IsNullOrEmpty(exe))
            {
                return;
            }
            foreach (var name in root.GetSubKeyNames())
            {
                using var sub = root.OpenSubKey(name, true);
                var path = sub?.GetValue("ExecutablePath") as string;
                if (path != null && path.Equals(exe, StringComparison.OrdinalIgnoreCase))
                {
                    sub!.SetValue("IsPromoted", 1, RegistryValueKind.DWord);
                    Log.Write("托盘图标已固定到任务栏");
                }
            }
        }
        catch (Exception ex)
        {
            Log.Write("固定托盘图标失败：" + ex.Message);
        }
    }

    public static void Notify(string message)
    {
        var app = current;
        if (app == null || string.IsNullOrEmpty(message))
        {
            return;
        }
        try
        {
            app.pump.BeginInvoke(() =>
            {
                try
                {
                    app.icon.ShowBalloonTip(4000, "副屏", message, ToolTipIcon.Info);
                }
                catch (Exception)
                {
                }
            });
        }
        catch (Exception)
        {
        }
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
            try
            {
                MessageBox.Show(app.pump, message, "副屏");
            }
            catch (Exception)
            {
            }
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
            Monitors.DetachAll();
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
            Log.Write("点击准备虚拟屏幕");
            try
            {
                errorText = VirtualDisplaySetup.Prepare();
            }
            catch (Exception ex)
            {
                errorText = ex.Message;
                Log.Write(ex.Message);
            }
            Rebuild();
            if (!string.IsNullOrEmpty(errorText))
            {
                try
                {
                    MessageBox.Show(pump, errorText, "副屏");
                }
                catch (Exception)
                {
                }
            }
        };
        menu.Items.Add(prepare);
        var exit = new ToolStripMenuItem("退出");
        exit.Click += (_, _) =>
        {
            Log.Write("点击退出");
            ExitThread();
        };
        menu.Items.Add(exit);
        var previous = icon.ContextMenuStrip;
        icon.ContextMenuStrip = menu;
        if (previous != null)
        {
            try
            {
                pump.BeginInvoke(new Action(() =>
                {
                    try
                    {
                        if (previous.Visible)
                        {
                            previous.Closed += (_, _) =>
                            {
                                try
                                {
                                    previous.Dispose();
                                }
                                catch (Exception)
                                {
                                }
                            };
                        }
                        else
                        {
                            previous.Dispose();
                        }
                    }
                    catch (Exception)
                    {
                    }
                }));
            }
            catch (Exception)
            {
            }
        }
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
        Log.Write("点击连接 " + pad.Name + " " + pad.Host);
        errorText = null;
        try
        {
            icon.ShowBalloonTip(3000, "副屏", "正在连接 " + pad.Name, ToolTipIcon.Info);
        }
        catch (Exception)
        {
        }
        var session = new Session(pad, () => pump.BeginInvoke(new Action(Rebuild)));
        sessions[pad.Key] = session;
        session.Start();
        Rebuild();
    }

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}
