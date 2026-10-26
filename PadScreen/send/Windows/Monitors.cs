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
    public bool Attached;
    public int X;
    public int Y;
    public int Width;
    public int Height;
}

static class Monitors
{
    private static readonly object Gate = new();
    private static readonly Dictionary<string, string> Claims = new();
    private static long detachWindow;
    private static int detachBurst;

    public static string Claim(string key, int width, int height)
    {
        lock (Gate)
        {
            var all = List(false);
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
                throw new IOException("没有空闲的虚拟屏幕。先在菜单里点「准备虚拟屏幕」，装好 Virtual Display Driver。");
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
            if (Claims.Count == 0)
            {
                DetachUnlocked();
            }
            else
            {
                ParkUnlocked();
            }
        }
    }

    public static void DetachAll()
    {
        lock (Gate)
        {
            Claims.Clear();
            DetachUnlocked();
        }
    }

    public static void SyncLayout()
    {
        lock (Gate)
        {
            if (Claims.Count == 0)
            {
                var now = Environment.TickCount64;
                if (now - detachWindow > 10000)
                {
                    detachWindow = now;
                    detachBurst = 0;
                }
                detachBurst++;
                if (detachBurst > 4)
                {
                    if (detachBurst == 5)
                    {
                        Log.Write("虚拟屏幕会反复接上，不再收起。鼠标留在主屏幕。");
                    }
                    ParkUnlocked();
                    return;
                }
                DetachUnlocked();
                return;
            }
            ParkUnlocked();
        }
    }

    public static bool HasVirtual()
    {
        return List(false).Any(item => item.Virtual && !item.Primary);
    }

    public static bool PrimaryIsParallels()
    {
        return List(true).Any(item => item.Primary && item.Adapter.Contains("Parallels", StringComparison.OrdinalIgnoreCase));
    }

    public static bool TryLayout(out Rectangle primary, out Rectangle desktop)
    {
        var all = List(true).Where(item => item.Width > 0 && item.Height > 0).ToList();
        var main = all.FirstOrDefault(item => item.Primary);
        if (main == null || !all.Any(item => item.Virtual && !item.Primary))
        {
            primary = Rectangle.Empty;
            desktop = Rectangle.Empty;
            return false;
        }
        primary = new Rectangle(main.X, main.Y, main.Width, main.Height);
        var left = all.Min(item => item.X);
        var top = all.Min(item => item.Y);
        var right = all.Max(item => item.X + item.Width);
        var bottom = all.Max(item => item.Y + item.Height);
        desktop = new Rectangle(left, top, Math.Max(1, right - left), Math.Max(1, bottom - top));
        return true;
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

    private static List<MonitorInfo> List(bool attachedOnly)
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
            var attached = (adapter.StateFlags & 1) != 0;
            if ((attachedOnly && !attached) || (adapter.StateFlags & 8) != 0)
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
                Virtual = IsVirtual(adapter, hasMonitor ? monitor : null),
                Attached = attached
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
        if (!ReadMode(deviceName, out var mode))
        {
            throw new IOException("读不到虚拟屏幕的当前模式");
        }
        var origin = OutsideOrigin(deviceName);
        mode.dmPelsWidth = width;
        mode.dmPelsHeight = height;
        mode.dmDisplayFrequency = 60;
        mode.dmPositionX = origin.X;
        mode.dmPositionY = origin.Y;
        mode.dmFields = 0x20 | 0x80000 | 0x100000 | 0x400000;
        if (!Commit(deviceName, ref mode, out var changed, out var applied))
        {
            throw new IOException($"系统没有接受 {width}×{height}。在「准备虚拟屏幕」里写入分辨率后，到设备管理器禁用再启用 Virtual Display Driver。代码 {changed}/{applied}");
        }
        Log.Write($"虚拟屏幕 {deviceName} 接到主屏幕右侧 {origin.X},{origin.Y} {width}x{height}");
        ParkUnlocked();
    }

    private static void ParkUnlocked()
    {
        var all = List(true);
        var primary = all.FirstOrDefault(item => item.Primary && item.Width > 0);
        if (primary == null)
        {
            return;
        }
        var x = primary.X + primary.Width;
        foreach (var monitor in all.Where(item => item.Virtual && !item.Primary && item.Width > 0).OrderBy(item => item.DeviceName))
        {
            if (monitor.X == x && monitor.Y == primary.Y)
            {
                x += monitor.Width;
                continue;
            }
            if (!ReadMode(monitor.DeviceName, out var mode))
            {
                continue;
            }
            mode.dmPositionX = x;
            mode.dmPositionY = primary.Y;
            mode.dmFields = 0x20 | 0x80000 | 0x100000 | 0x400000;
            if (Commit(monitor.DeviceName, ref mode, out var changed, out var applied))
            {
                Log.Write($"虚拟屏幕 {monitor.DeviceName} 移到 {x},{primary.Y}");
            }
            else
            {
                Log.Write($"虚拟屏幕 {monitor.DeviceName} 没能移到主屏幕右侧。代码 {changed}/{applied}");
            }
            x += monitor.Width;
        }
    }

    private static void DetachUnlocked()
    {
        foreach (var monitor in List(true))
        {
            if (!monitor.Virtual || monitor.Primary || Claims.ContainsValue(monitor.DeviceName))
            {
                continue;
            }
            if (!ReadMode(monitor.DeviceName, out var mode))
            {
                continue;
            }
            mode.dmPelsWidth = 0;
            mode.dmPelsHeight = 0;
            mode.dmPositionX = 0;
            mode.dmPositionY = 0;
            mode.dmFields = 0x20 | 0x80000 | 0x100000;
            if (Commit(monitor.DeviceName, ref mode, out var changed, out var applied))
            {
                Log.Write("已收起虚拟屏幕 " + monitor.DeviceName);
            }
            else
            {
                Log.Write($"没能收起虚拟屏幕 {monitor.DeviceName}。代码 {changed}/{applied}");
            }
        }
    }

    private static Point OutsideOrigin(string deviceName)
    {
        var all = List(true);
        var primary = all.FirstOrDefault(item => item.Primary && item.Width > 0);
        var x = primary == null ? 0 : primary.X + primary.Width;
        var y = primary?.Y ?? 0;
        foreach (var monitor in all.Where(item => item.Virtual && !item.Primary && item.Width > 0 && item.DeviceName != deviceName).OrderBy(item => item.DeviceName))
        {
            if (string.Compare(monitor.DeviceName, deviceName, StringComparison.Ordinal) < 0)
            {
                x += monitor.Width;
            }
        }
        return new Point(x, y);
    }

    private static bool ReadMode(string deviceName, out DevMode mode)
    {
        mode = new DevMode();
        mode.dmDeviceName = new string('\0', 32);
        mode.dmFormName = new string('\0', 32);
        mode.dmSize = (short)Marshal.SizeOf<DevMode>();
        if (EnumDisplaySettings(deviceName, -1, ref mode))
        {
            return true;
        }
        mode.dmDeviceName = new string('\0', 32);
        mode.dmFormName = new string('\0', 32);
        mode.dmSize = (short)Marshal.SizeOf<DevMode>();
        return EnumDisplaySettings(deviceName, -2, ref mode);
    }

    private static bool Commit(string deviceName, ref DevMode mode, out int changed, out int applied)
    {
        changed = ChangeDisplaySettingsEx(deviceName, ref mode, IntPtr.Zero, 0x1 | 0x10000000, IntPtr.Zero);
        applied = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
        if (changed == 0 && applied == 0)
        {
            return true;
        }
        changed = ChangeDisplaySettingsEx(deviceName, ref mode, IntPtr.Zero, 0x1 | 0x40000000, IntPtr.Zero);
        applied = 0;
        return changed == 0;
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
