using System.Drawing;
using System.Runtime.InteropServices;

namespace PadScreen;

static class PointerGuard
{
    public static readonly IntPtr Mark = (IntPtr)0x50414401;

    private const int MouseMove = 0x0001;
    private const int MouseLeftDown = 0x0002;
    private const int MouseLeftUp = 0x0004;
    private const int MouseRightDown = 0x0008;
    private const int MouseRightUp = 0x0010;
    private const int MouseMiddleDown = 0x0020;
    private const int MouseMiddleUp = 0x0040;
    private const int MouseWheel = 0x0800;
    private const int MouseHWheel = 0x1000;
    private const int MouseAbsolute = 0x8000;
    private const int MouseVirtualDesk = 0x4000;
    private const int Place = MouseMove | MouseAbsolute | MouseVirtualDesk;
    private const int Buttons = MouseLeftDown | MouseLeftUp | MouseRightDown | MouseRightUp | MouseMiddleDown | MouseMiddleUp | MouseWheel | MouseHWheel;

    private static readonly HookProc Callback = OnMouse;
    private static readonly Queue<Stroke> Pending = new();
    private static readonly AutoResetEvent Pulse = new(false);
    private static IntPtr hook;
    private static bool armed;
    private static int echoX = int.MinValue;
    private static int echoY = int.MinValue;
    private static long echoAt;

    public static void Start()
    {
        if (!Monitors.PrimaryIsParallels() || Marshal.SizeOf<Input>() != 40)
        {
            return;
        }
        var sender = new Thread(SendLoop) { IsBackground = true, Name = "pad-pointer" };
        sender.Start();
        hook = SetWindowsHookEx(14, Callback, IntPtr.Zero, 0);
        armed = hook != IntPtr.Zero;
        Log.Write(armed
            ? "Parallels 只显示主屏幕。虚拟屏幕打开时，鼠标仍留在主屏幕。"
            : "没能挂上鼠标校正");
    }

    private static IntPtr OnMouse(int code, IntPtr wParam, IntPtr lParam)
    {
        if (code < 0 || !armed)
        {
            return CallNextHookEx(hook, code, wParam, lParam);
        }
        var info = Marshal.PtrToStructure<HookInfo>(lParam);
        if (info.Extra == Mark || Echo(info.X, info.Y))
        {
            return CallNextHookEx(hook, code, wParam, lParam);
        }
        if (!Monitors.TryLayout(out var primary, out var desktop))
        {
            return CallNextHookEx(hook, code, wParam, lParam);
        }
        if (desktop.Width <= primary.Width && desktop.Height <= primary.Height)
        {
            return CallNextHookEx(hook, code, wParam, lParam);
        }
        if (!Flags(wParam.ToInt32(), info.Data, out var flags, out var data))
        {
            return CallNextHookEx(hook, code, wParam, lParam);
        }
        var fx = desktop.Width <= 0 ? 0 : (info.X - desktop.X) / (double)desktop.Width;
        var fy = desktop.Height <= 0 ? 0 : (info.Y - desktop.Y) / (double)desktop.Height;
        fx = Math.Clamp(fx, 0, 1);
        fy = Math.Clamp(fy, 0, 1);
        var x = primary.X + (int)Math.Round(fx * Math.Max(1, primary.Width - 1));
        var y = primary.Y + (int)Math.Round(fy * Math.Max(1, primary.Height - 1));
        if (Math.Abs(x - info.X) <= 2 && Math.Abs(y - info.Y) <= 2)
        {
            return CallNextHookEx(hook, code, wParam, lParam);
        }
        var stroke = new Stroke
        {
            X = x,
            Y = y,
            AbsoluteX = Absolute(x, true),
            AbsoluteY = Absolute(y, false),
            Flags = flags,
            Data = data
        };
        echoX = x;
        echoY = y;
        echoAt = Environment.TickCount64;
        lock (Pending)
        {
            Pending.Enqueue(stroke);
        }
        Pulse.Set();
        if ((flags & (MouseLeftDown | MouseRightDown | MouseMiddleDown)) != 0)
        {
            Log.Write($"主屏幕点击 {x},{y}，原坐标 {info.X},{info.Y}");
        }
        return (IntPtr)1;
    }

    private static bool Echo(int x, int y)
    {
        return Environment.TickCount64 - echoAt < 80 && Math.Abs(x - echoX) <= 6 && Math.Abs(y - echoY) <= 6;
    }

    private static void SendLoop()
    {
        while (true)
        {
            Pulse.WaitOne();
            while (true)
            {
                Stroke stroke;
                lock (Pending)
                {
                    if (Pending.Count == 0)
                    {
                        break;
                    }
                    stroke = Pending.Dequeue();
                }
                Deliver(stroke);
            }
        }
    }

    private static void Deliver(Stroke stroke)
    {
        SetCursorPos(stroke.X, stroke.Y);
        var button = stroke.Flags & Buttons;
        var move = new Input
        {
            Type = 0,
            X = stroke.AbsoluteX,
            Y = stroke.AbsoluteY,
            Flags = Place,
            Extra = Mark
        };
        Input[] inputs;
        if (button == 0)
        {
            inputs = new[] { move };
        }
        else
        {
            inputs = new[]
            {
                move,
                new Input
                {
                    Type = 0,
                    X = stroke.AbsoluteX,
                    Y = stroke.AbsoluteY,
                    Data = stroke.Data,
                    Flags = Place | button,
                    Extra = Mark
                }
            };
        }
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        if (sent != inputs.Length)
        {
            Log.Write("主屏幕点击没有送出去");
        }
    }

    private static bool Flags(int message, int hookData, out int flags, out int data)
    {
        data = hookData >> 16;
        flags = message switch
        {
            0x0200 => Place,
            0x0201 => Place | MouseLeftDown,
            0x0202 => Place | MouseLeftUp,
            0x0204 => Place | MouseRightDown,
            0x0205 => Place | MouseRightUp,
            0x0207 => Place | MouseMiddleDown,
            0x0208 => Place | MouseMiddleUp,
            0x020A => Place | MouseWheel,
            0x020E => Place | MouseHWheel,
            _ => 0
        };
        return flags != 0;
    }

    private static int Absolute(int pixel, bool horizontal)
    {
        var origin = GetSystemMetrics(horizontal ? 76 : 77);
        var span = Math.Max(1, GetSystemMetrics(horizontal ? 78 : 79) - 1);
        var value = (int)((pixel - origin) * 65535L / span);
        if (value < 0)
        {
            return 0;
        }
        return value > 65535 ? 65535 : value;
    }

    private struct Stroke
    {
        public int X;
        public int Y;
        public int AbsoluteX;
        public int AbsoluteY;
        public int Flags;
        public int Data;
    }

    private delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int id, HookProc callback, IntPtr module, uint thread);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [StructLayout(LayoutKind.Sequential)]
    private struct HookInfo
    {
        public int X;
        public int Y;
        public int Data;
        public int Flags;
        public int Time;
        public IntPtr Extra;
    }

    [StructLayout(LayoutKind.Explicit, Size = 40)]
    private struct Input
    {
        [FieldOffset(0)] public int Type;
        [FieldOffset(8)] public int X;
        [FieldOffset(12)] public int Y;
        [FieldOffset(16)] public int Data;
        [FieldOffset(20)] public int Flags;
        [FieldOffset(24)] public int Time;
        [FieldOffset(32)] public IntPtr Extra;
    }
}
