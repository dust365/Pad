using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Xml.Linq;

namespace PadScreen;

static class VirtualDisplaySetup
{
    public const string DirectoryPath = @"C:\VirtualDisplayDriver";
    public const string SettingsPath = @"C:\VirtualDisplayDriver\vdd_settings.xml";

    public static string Prepare()
    {
        Directory.CreateDirectory(DirectoryPath);
        WriteSettings();
        if (Monitors.HasVirtual())
        {
            Monitors.SyncLayout();
            var ready = "驱动已经就绪。没连平板时桌面不扩展；连上后，虚拟屏幕才接到主屏幕右侧。";
            Log.Write(ready);
            return ready;
        }
        var inf = FindDriverInf();
        var script = Path.Combine(AppContext.BaseDirectory, "install-vdd.ps1");
        if (inf != null && File.Exists(script))
        {
            try
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = "-NoExit -NoProfile -ExecutionPolicy Bypass -File \"" + script + "\" \"" + inf + "\"",
                    UseShellExecute = true,
                    Verb = "runas"
                });
                var started = "分辨率已经写入。请在弹出的窗口里允许安装虚拟显示驱动，装完后再点平板。";
                Log.Write(started);
                return started;
            }
            catch (Exception ex)
            {
                Log.Write("安装驱动失败：" + ex.Message);
                return "分辨率已经写入。安装虚拟显示驱动没有开始：" + ex.Message;
            }
        }
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = "/k winget install --id=VirtualDrivers.Virtual-Display-Driver -e --accept-package-agreements --accept-source-agreements",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            Log.Write("启动 winget 失败：" + ex.Message);
            return "分辨率已经写入 " + SettingsPath + "。本机没有 winget，请到 Virtual Display Driver 的发布页安装驱动，然后在设备管理器里禁用再启用它。";
        }
        var winget = "分辨率已经写入，并打开了安装窗口。装完后在设备管理器里禁用再启用 Virtual Display Driver，然后点平板。";
        Log.Write(winget);
        return winget;
    }

    private static string? FindDriverInf()
    {
        var packages = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WinGet", "Packages");
        if (!Directory.Exists(packages))
        {
            return null;
        }
        string[] found;
        try
        {
            found = Directory.GetFiles(packages, "MttVDD.inf", SearchOption.AllDirectories);
        }
        catch (Exception)
        {
            return null;
        }
        var arm = RuntimeInformation.ProcessArchitecture == Architecture.Arm64;
        return found.FirstOrDefault(path => path.Contains("ARM64", StringComparison.OrdinalIgnoreCase) == arm);
    }

    private static void WriteSettings()
    {
        XDocument document;
        if (File.Exists(SettingsPath))
        {
            document = XDocument.Load(SettingsPath);
        }
        else
        {
            document = new XDocument(new XElement("vdd_settings"));
        }
        var root = document.Root ?? new XElement("vdd_settings");
        if (document.Root == null)
        {
            document.Add(root);
        }
        var monitors = root.Element("monitors") ?? new XElement("monitors");
        if (monitors.Parent == null)
        {
            root.Add(monitors);
        }
        var count = monitors.Element("count");
        var current = 0;
        if (count != null)
        {
            int.TryParse(count.Value, out current);
        }
        if (current < 2)
        {
            if (count == null)
            {
                monitors.Add(new XElement("count", 2));
            }
            else
            {
                count.Value = "2";
            }
        }
        if (root.Element("gpu") == null)
        {
            root.Add(new XElement("gpu", new XElement("friendlyname", "default")));
        }
        var global = root.Element("global");
        if (global == null)
        {
            root.Add(new XElement("global", new XElement("g_refresh_rate", 60)));
        }
        var resolutions = root.Element("resolutions") ?? new XElement("resolutions");
        if (resolutions.Parent == null)
        {
            root.Add(resolutions);
        }
        EnsureResolution(resolutions, 1024, 600);
        EnsureResolution(resolutions, 800, 480);
        EnsureSection(root, "logging",
            new XElement("SendLogsThroughPipe", false),
            new XElement("logging", false),
            new XElement("debuglogging", false));
        EnsureSection(root, "colour",
            new XElement("SDR10bit", false),
            new XElement("HDRPlus", false),
            new XElement("ColourFormat", "RGB"));
        EnsureSection(root, "cursor",
            new XElement("HardwareCursor", true),
            new XElement("AlphaCursorSupport", true));
        EnsureSection(root, "edid",
            new XElement("CustomEdid", false),
            new XElement("PreventSpoof", false),
            new XElement("EdidCeaOverride", false));
        document.Save(SettingsPath);
        Log.Write("已写入 " + SettingsPath);
    }

    private static void EnsureSection(XElement root, string name, params XElement[] children)
    {
        if (root.Element(name) != null)
        {
            return;
        }
        root.Add(new XElement(name, children));
    }

    private static void EnsureResolution(XElement resolutions, int width, int height)
    {
        var exists = resolutions.Elements("resolution").Any(item =>
            Item(item, "width") == width && Item(item, "height") == height);
        if (exists)
        {
            return;
        }
        resolutions.Add(new XElement("resolution",
            new XElement("width", width),
            new XElement("height", height),
            new XElement("refresh_rate", 60)));
    }

    private static int Item(XElement element, string name)
    {
        int.TryParse(element.Element(name)?.Value, out var value);
        return value;
    }
}

static class Log
{
    private static readonly object Gate = new();

    public static void Write(string message)
    {
        var line = DateTime.Now.ToString("HH:mm:ss ") + message;
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PadScreen", "log.txt");
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.AppendAllText(path, line + Environment.NewLine);
            }
        }
        catch (Exception)
        {
        }
    }
}
