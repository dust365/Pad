using System.Diagnostics;
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
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "winget",
                Arguments = "install --id=VirtualDrivers.Virtual-Display-Driver -e --accept-package-agreements --accept-source-agreements",
                UseShellExecute = true
            });
        }
        catch (Exception ex)
        {
            Log.Write("启动 winget 失败：" + ex.Message);
            return "分辨率已经写入 " + SettingsPath + "。本机没有 winget，请到 Virtual Display Driver 的发布页安装驱动，然后在设备管理器里禁用再启用它。";
        }
        return "已写入 1024×600 和 800×480，并开始安装虚拟显示驱动。装完后在设备管理器里禁用再启用 Virtual Display Driver，然后重新扫描。";
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
        document.Save(SettingsPath);
        Log.Write("已写入 " + SettingsPath);
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
