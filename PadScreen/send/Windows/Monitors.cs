using System.Drawing;
using System.Runtime.InteropServices;

namespace PadScreen;

sealed class MonitorInfo
{
    public string DeviceName = "";
    public string Adapter = "";
    public string MonitorName = "";
    public bool Primary;
    public bool Virtual;
    public int X;
    public int Y;
    public int Width;
    public int Height;
}

static class Monitors
{
    private static readonly object Gate = new();
    private static readonly Dictionary<string, string> Claims = new();

    public static string Claim(string key, int width, int height)
    {
        lock (Gate)
        {
            var all = List();
            foreach (var monitor in all)
            {
                Log.Write($"屏幕 {monitor.DeviceName} {monitor.MonitorName} {monitor.Adapter} {monitor.Width}x{monitor.Height} virtual={monitor.Virtual} primary={monitor.Primary}");
            }
            if (Claims.TryGetValue(key, out var owned))
            {
                var existing = all.FirstOrDefault(item => item.DeviceName == owned);
                if (existing != null)
                {
                    ApplyMode(existing.DeviceName, width, height);
                    return existing.DeviceName;
                }
                Claims.Remove(key);
            }
            var used = new HashSet<string>(Claims.Values);
            var free = all.Where(item => item.Virtual && !item.Primary && !used.Contains(item.DeviceName)).ToList();
            if (free.Count == 0)
            {
                throw new IOException("没有空闲的虚拟屏幕。先在菜单里点「准备虚拟屏幕」，装好驱动并让它出现在系统的显示设置里。");
            }
            var chosen = free[0];
            ApplyMode(chosen.DeviceName, width, height);
            Claims[key] = chosen.DeviceName;
            return chosen.DeviceName;
        }
    }

    public static void Release(string key)
    {
        lock (Gate)
        {
            Claims.Remove(key);
        }
    }

    public static bool TryBounds(string deviceName, out int x, out int y, out int width, out int height)
    {
        var mode = new DevMode();
        mode.dmDeviceName = new string('\0', 32);
        mode.dmFormName = new string('\0', 32);
        mode.dmSize = (short)Marshal.SizeOf<DevMode>();
        if (!EnumDisplaySettings(deviceName, -1, ref mode))
        {
            x = y = width = height = 0;
            return false;
        }
        x = mode.dmPositionX;
        y = mode.dmPositionY;
        width = mode.dmPelsWidth;
        height = mode.dmPelsHeight;
        return width > 0 && height > 0;
    }

    private static List<MonitorInfo> List()
    {
        var result = new List<MonitorInfo>();
        for (uint index = 0; index < 16; index++)
        {
            var adapter = new DisplayDevice();
            adapter.cb = Marshal.SizeOf<DisplayDevice>();
            if (!EnumDisplayDevices(null, index, ref adapter, 0))
            {
                break;
            }
            if ((adapter.StateFlags & 1) == 0 || (adapter.StateFlags & 8) != 0)
            {
                continue;
            }
            var monitor = new DisplayDevice();
            monitor.cb = Marshal.SizeOf<DisplayDevice>();
            var hasMonitor = EnumDisplayDevices(adapter.DeviceName, 0, ref monitor, 0);
            var info = new MonitorInfo
            {
                DeviceName = adapter.DeviceName,
                Adapter = adapter.DeviceString ?? "",
                MonitorName = hasMonitor ? monitor.DeviceString ?? "" : "",
                Primary = (adapter.StateFlags & 4) != 0,
                Virtual = IsVirtual(adapter, hasMonitor ? monitor : null)
            };
            if (TryBounds(adapter.DeviceName, out var x, out var y, out var width, out var height))
            {
                info.X = x;
                info.Y = y;
                info.Width = width;
                info.Height = height;
            }
            result.Add(info);
        }
        return result;
    }

    private static bool IsVirtual(DisplayDevice adapter, DisplayDevice? monitor)
    {
        var text = ((adapter.DeviceString ?? "") + " " + (adapter.DeviceID ?? "") + " " + (monitor?.DeviceString ?? "") + " " + (monitor?.DeviceID ?? "")).ToLowerInvariant();
        string[] marks = { "virtual", "indirect", "vdd", "idd", "mtt", "padscreen" };
        return marks.Any(text.Contains);
    }

    private static void ApplyMode(string deviceName, int width, int height)
    {
        var mode = new DevMode();
        mode.dmDeviceName = new string('\0', 32);
        mode.dmFormName = new string('\0', 32);
        mode.dmSize = (short)Marshal.SizeOf<DevMode>();
        if (!EnumDisplaySettings(deviceName, -1, ref mode))
        {
            throw new IOException("读不到虚拟屏幕的当前模式");
        }
        mode.dmPelsWidth = width;
        mode.dmPelsHeight = height;
        mode.dmDisplayFrequency = 60;
        if (Overlaps(deviceName, mode.dmPositionX, mode.dmPositionY, width, height))
        {
            mode.dmPositionX = RightEdge(deviceName);
            mode.dmPositionY = 0;
        }
        mode.dmFields = 0x20 | 0x80000 | 0x100000 | 0x400000;
        var changed = ChangeDisplaySettingsEx(deviceName, ref mode, IntPtr.Zero, 0x1 | 0x10000000, IntPtr.Zero);
        var applied = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
        if (changed != 0 || applied != 0)
        {
            throw new IOException($"系统没有接受 {width}×{height}。在「准备虚拟屏幕」里写入分辨率后，到设备管理器禁用再启用 Virtual Display Driver。代码 {changed}/{applied}");
        }
    }

    private static bool Overlaps(string deviceName, int x, int y, int width, int height)
    {
        var mine = new Rectangle(x, y, width, height);
        foreach (var monitor in List())
        {
            if (monitor.DeviceName == deviceName || monitor.Width <= 0 || monitor.Height <= 0)
            {
                continue;
            }
            var other = new Rectangle(monitor.X, monitor.Y, monitor.Width, monitor.Height);
            if (mine.IntersectsWith(other))
            {
                return true;
            }
        }
        return false;
    }

    private static int RightEdge(string except)
    {
        var edge = 0;
        foreach (var monitor in List())
        {
            if (monitor.DeviceName == except)
            {
                continue;
            }
            edge = Math.Max(edge, monitor.X + monitor.Width);
        }
        return edge;
    }

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool EnumDisplayDevices(string? device, uint index, ref DisplayDevice display, uint flags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool EnumDisplaySettings(string device, int mode, ref DevMode devMode);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern int ChangeDisplaySettingsEx(string? device, ref DevMode devMode, IntPtr hwnd, uint flags, IntPtr param);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern int ChangeDisplaySettingsEx(string? device, IntPtr devMode, IntPtr hwnd, uint flags, IntPtr param);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct DisplayDevice
    {
        public int cb;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceString;
        public int StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string DeviceKey;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct DevMode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string dmDeviceName;
        public short dmSpecVersion;
        public short dmDriverVersion;
        public short dmSize;
        public short dmDriverExtra;
        public int dmFields;
        public int dmPositionX;
        public int dmPositionY;
        public int dmDisplayOrientation;
        public int dmDisplayFixedOutput;
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel;
        public int dmPelsWidth;
        public int dmPelsHeight;
        public int dmDisplayFlags;
        public int dmDisplayFrequency;
        public int dmICMMethod;
        public int dmICMIntent;
        public int dmMediaType;
        public int dmDitherType;
        public int dmReserved1;
        public int dmReserved2;
        public int dmPanningWidth;
        public int dmPanningHeight;
    }
}
