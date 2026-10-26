using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace PadScreen;

sealed class Session
{
    private readonly FoundPad pad;
    private readonly Action refresh;
    private Link? link;
    private Thread? reader;
    private Thread? capture;
    private volatile bool stopped;
    private string deviceName = "";
    private int padWidth = 1024;
    private int padHeight = 600;
    private bool buttonDown;
    private int missedBounds;
    private int injectFailures;
    private Bitmap? scratch;
    private byte[]? opaque;
    private readonly object pointer = new();
    private byte[] previous = Array.Empty<byte>();
    private int previousStride;
    private DateTime lastKeyframe = DateTime.MinValue;
    private bool sending;
    private bool cursorLogged;
    private long captureLogAt;

    private const int MouseMove = 0x0001;
    private const int MouseLeftDown = 0x0002;
    private const int MouseLeftUp = 0x0004;
    private const int MouseAbsolute = 0x8000;
    private const int MouseVirtualDesk = 0x4000;
    private const int MousePlace = MouseMove | MouseAbsolute | MouseVirtualDesk;

    public Session(FoundPad pad, Action refresh)
    {
        this.pad = pad;
        this.refresh = refresh;
        Name = pad.Name;
    }

    public string Key => pad.Key;
    public string Name { get; private set; }
    public bool Live { get; private set; }

    public void Start()
    {
        stopped = false;
        reader = new Thread(ReadLoop) { IsBackground = true, Name = "pad-read-" + pad.Host };
        reader.Start();
    }

    public void Stop()
    {
        stopped = true;
        Live = false;
        ReleaseButton();
        try
        {
            link?.Dispose();
        }
        catch (Exception)
        {
        }
        link = null;
        Monitors.Release(Key);
    }

    private void ReadLoop()
    {
        try
        {
            var current = new Link(pad.Host, pad.Port);
            link = current;
            var (type, payload) = current.ReadFrame();
            if (type != Wire.Hello)
            {
                throw new IOException("平板没有完成握手");
            }
            var hello = Wire.ParseHello(payload) ?? throw new IOException("平板握手无效");
            if (hello.Width < 2 || hello.Height < 2 || hello.Width > 4096 || hello.Height > 4096)
            {
                throw new IOException("平板分辨率无效");
            }
            padWidth = hello.Width;
            padHeight = hello.Height;
            if (!string.IsNullOrEmpty(hello.Name))
            {
                Name = hello.Name;
            }
            Log.Write($"握手 {Name} {padWidth}x{padHeight} {pad.Host}");
            deviceName = Monitors.Claim(Key, padWidth, padHeight);
            Live = true;
            refresh();
            TrayApp.Notify("已连接 " + Name);
            capture = new Thread(CaptureLoop) { IsBackground = true, Name = "pad-capture-" + pad.Host };
            capture.Start();
            while (!stopped)
            {
                var (frameType, frame) = current.ReadFrame();
                if (frameType == Wire.Touch)
                {
                    var touch = Wire.ParseTouch(frame);
                    if (touch != null)
                    {
                        PostTouch(touch.Value.Action, touch.Value.X, touch.Value.Y);
                    }
                }
            }
        }
            catch (Exception ex)
            {
                if (!stopped)
                {
                    Log.Write(Name + "：" + ex.Message);
                    TrayApp.OnSessionFailed(this, ex.Message);
                }
            }
    }

    private void CaptureLoop()
    {
        using var bitmap = new Bitmap(padWidth, padHeight, PixelFormat.Format32bppArgb);
        try
        {
            while (!stopped)
            {
                var started = Environment.TickCount64;
                var grabbed = false;
                var missed = false;
                var busy = sending;
                try
                {
                    if (!busy)
                    {
                        grabbed = Grab(bitmap);
                        missed = !grabbed;
                    }
                }
                catch (Exception ex)
                {
                    var now = Environment.TickCount64;
                    if (now - captureLogAt > 2000)
                    {
                        captureLogAt = now;
                        Log.Write("采集失败：" + ex.Message);
                    }
                }
                if (missed)
                {
                    missedBounds++;
                    if (missedBounds >= 30)
                    {
                        TrayApp.OnSessionFailed(this, "虚拟屏幕不见了");
                        return;
                    }
                }
                else if (grabbed)
                {
                    missedBounds = 0;
                }
                if (grabbed)
                {
                    try
                    {
                        var frames = MakeFrames(bitmap);
                        if (frames.Count > 0)
                        {
                            sending = true;
                            try
                            {
                                var current = link;
                                if (current != null && !stopped)
                                {
                                    foreach (var frame in frames)
                                    {
                                        current.WriteFrame(frame.Type, frame.Payload);
                                    }
                                }
                            }
                            finally
                            {
                                sending = false;
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        if (!stopped)
                        {
                            TrayApp.OnSessionFailed(this, ex.Message);
                            return;
                        }
                    }
                }
                var spent = Environment.TickCount64 - started;
                if (spent < 66)
                {
                    Thread.Sleep((int)(66 - spent));
                }
            }
        }
        finally
        {
            scratch?.Dispose();
            scratch = null;
        }
    }

    private bool Grab(Bitmap target)
    {
        if (!Monitors.TryBounds(deviceName, out var originX, out var originY, out var width, out var height))
        {
            return false;
        }
        var direct = width == target.Width && height == target.Height;
        var surface = direct ? target : Scratch(width, height);
        using (var graphics = Graphics.FromImage(surface))
        {
            graphics.CopyFromScreen(originX, originY, 0, 0, new Size(width, height), CopyPixelOperation.SourceCopy);
        }
        // CopyFromScreen 留下的 alpha 是 0。先补成不透明，光标才不会画到一块全透明的图上。
        ForceOpaque(surface);
        using (var graphics = Graphics.FromImage(surface))
        {
            DrawCursor(graphics, originX, originY, width, height);
        }
        ForceOpaque(surface);
        if (!direct)
        {
            using var canvas = Graphics.FromImage(target);
            canvas.CompositingMode = CompositingMode.SourceCopy;
            canvas.InterpolationMode = InterpolationMode.HighQualityBicubic;
            canvas.PixelOffsetMode = PixelOffsetMode.HighQuality;
            canvas.DrawImage(surface, new Rectangle(0, 0, target.Width, target.Height));
        }
        return true;
    }

    private Bitmap Scratch(int width, int height)
    {
        if (scratch == null || scratch.Width != width || scratch.Height != height)
        {
            scratch?.Dispose();
            scratch = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        }
        return scratch;
    }

    private List<(int Type, byte[] Payload)> MakeFrames(Bitmap bitmap)
    {
        byte[] pixels;
        int stride;
        var data = bitmap.LockBits(new Rectangle(0, 0, bitmap.Width, bitmap.Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            stride = data.Stride;
            pixels = new byte[stride * bitmap.Height];
            Marshal.Copy(data.Scan0, pixels, 0, pixels.Length);
        }
        finally
        {
            bitmap.UnlockBits(data);
        }
        var sizeChanged = previous.Length != pixels.Length || previousStride != stride;
        if (sizeChanged)
        {
            return FullFrame(bitmap, pixels, stride);
        }
        var regions = ChangedRegions(pixels, bitmap.Width, bitmap.Height, stride);
        if (regions == null || (regions.Count > 0 && DateTime.UtcNow - lastKeyframe >= TimeSpan.FromSeconds(20)))
        {
            return FullFrame(bitmap, pixels, stride);
        }
        if (regions.Count == 0)
        {
            return new List<(int, byte[])>();
        }
        var frames = new List<(int Type, byte[] Payload)>();
        foreach (var region in regions)
        {
            using var tile = bitmap.Clone(new Rectangle(region.X, region.Y, region.W, region.H), PixelFormat.Format32bppArgb);
            var jpeg = Encode(tile, 92);
            frames.Add((Wire.Patch, Wire.PackPatch(region.X, region.Y, region.W, region.H, jpeg)));
        }
        previous = pixels;
        previousStride = stride;
        return frames;
    }

    private List<(int Type, byte[] Payload)> FullFrame(Bitmap bitmap, byte[] pixels, int stride)
    {
        previous = pixels;
        previousStride = stride;
        lastKeyframe = DateTime.UtcNow;
        return new List<(int, byte[])> { (Wire.Jpeg, Encode(bitmap, 86)) };
    }

    private List<Patch>? ChangedRegions(byte[] pixels, int width, int height, int stride)
    {
        const int tile = 32;
        var cols = (width + tile - 1) / tile;
        var rows = (height + tile - 1) / tile;
        var changed = new bool[cols * rows];
        var changedCount = 0;
        for (var row = 0; row < rows; row++)
        {
            for (var col = 0; col < cols; col++)
            {
                var x = col * tile;
                var y = row * tile;
                var w = Math.Min(tile, width - x);
                var h = Math.Min(tile, height - y);
                var dirty = false;
                for (var line = y; line < y + h; line++)
                {
                    var offset = line * stride + x * 4;
                    if (!Same(pixels, previous, offset, w * 4))
                    {
                        dirty = true;
                        break;
                    }
                }
                if (dirty)
                {
                    changed[row * cols + col] = true;
                    changedCount++;
                }
            }
        }
        if (changedCount == 0)
        {
            return new List<Patch>();
        }
        if (changedCount * tile * tile * 2 > width * height)
        {
            return null;
        }
        var seen = new bool[changed.Length];
        var regions = new List<Patch>();
        for (var row = 0; row < rows; row++)
        {
            for (var col = 0; col < cols; col++)
            {
                var start = row * cols + col;
                if (!changed[start] || seen[start])
                {
                    continue;
                }
                var stack = new List<(int Col, int Row)> { (col, row) };
                seen[start] = true;
                var minCol = col;
                var maxCol = col;
                var minRow = row;
                var maxRow = row;
                for (var index = 0; index < stack.Count; index++)
                {
                    var current = stack[index];
                    var neighbors = new[]
                    {
                        (current.Col - 1, current.Row),
                        (current.Col + 1, current.Row),
                        (current.Col, current.Row - 1),
                        (current.Col, current.Row + 1)
                    };
                    foreach (var neighbor in neighbors)
                    {
                        if (neighbor.Item1 < 0 || neighbor.Item2 < 0 || neighbor.Item1 >= cols || neighbor.Item2 >= rows)
                        {
                            continue;
                        }
                        var neighborIndex = neighbor.Item2 * cols + neighbor.Item1;
                        if (!changed[neighborIndex] || seen[neighborIndex])
                        {
                            continue;
                        }
                        seen[neighborIndex] = true;
                        stack.Add((neighbor.Item1, neighbor.Item2));
                        minCol = Math.Min(minCol, neighbor.Item1);
                        maxCol = Math.Max(maxCol, neighbor.Item1);
                        minRow = Math.Min(minRow, neighbor.Item2);
                        maxRow = Math.Max(maxRow, neighbor.Item2);
                    }
                }
                var rx = minCol * tile;
                var ry = minRow * tile;
                var rw = Math.Min(width - rx, (maxCol - minCol + 1) * tile);
                var rh = Math.Min(height - ry, (maxRow - minRow + 1) * tile);
                if (rw < 2 || rh < 2)
                {
                    continue;
                }
                regions.Add(new Patch(rx, ry, rw, rh));
                if (regions.Count > 3)
                {
                    return null;
                }
            }
        }
        var area = regions.Sum(region => region.W * region.H);
        if (area * 2 > width * height)
        {
            return null;
        }
        return regions;
    }

    private static bool Same(byte[] left, byte[] right, int offset, int length)
    {
        for (var i = 0; i < length; i++)
        {
            if (left[offset + i] != right[offset + i])
            {
                return false;
            }
        }
        return true;
    }

    private static byte[] Encode(Image image, long quality)
    {
        using var stream = new MemoryStream();
        var codec = ImageCodecInfo.GetImageEncoders().First(item => item.FormatID == ImageFormat.Jpeg.Guid);
        using var parameters = new EncoderParameters(1);
        parameters.Param[0] = new EncoderParameter(Encoder.Quality, quality);
        image.Save(stream, codec, parameters);
        return stream.ToArray();
    }

    private void PostTouch(int action, int x, int y)
    {
        if (stopped || Marshal.SizeOf<Input>() != 40)
        {
            if (Marshal.SizeOf<Input>() != 40 && injectFailures == 0)
            {
                injectFailures++;
                Log.Write("鼠标注入只支持 64 位进程");
            }
            return;
        }
        if (!TryAbsolute(x, y, out var absoluteX, out var absoluteY))
        {
            return;
        }
        // 按下和抬起必须先移动。否则 Windows 在当前光标处点击，不看绝对坐标。
        lock (pointer)
        {
            if (stopped)
            {
                return;
            }
            Input[] inputs;
            if (action == Wire.TouchDown)
            {
                inputs = new[]
                {
                    Pointer(absoluteX, absoluteY, MousePlace),
                    Pointer(absoluteX, absoluteY, MousePlace | MouseLeftDown)
                };
                buttonDown = true;
            }
            else if (action == Wire.TouchUp)
            {
                inputs = buttonDown
                    ? new[]
                    {
                        Pointer(absoluteX, absoluteY, MousePlace),
                        Pointer(absoluteX, absoluteY, MousePlace | MouseLeftUp)
                    }
                    : new[] { Pointer(absoluteX, absoluteY, MousePlace) };
                buttonDown = false;
            }
            else
            {
                inputs = new[] { Pointer(absoluteX, absoluteY, MousePlace) };
            }
            var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
            if (sent != inputs.Length && injectFailures < 5)
            {
                injectFailures++;
                Log.Write("鼠标注入失败，已注入 " + sent + "/" + inputs.Length);
            }
        }
    }

    private void ReleaseButton()
    {
        lock (pointer)
        {
            if (!buttonDown)
            {
                return;
            }
            buttonDown = false;
            var input = new Input { Type = 0, Flags = MouseLeftUp, Extra = PointerGuard.Mark };
            var sent = SendInput(1, new[] { input }, Marshal.SizeOf<Input>());
            if (sent != 1)
            {
                Log.Write("松开鼠标失败");
            }
        }
    }

    private bool TryAbsolute(int x, int y, out int absoluteX, out int absoluteY)
    {
        absoluteX = 0;
        absoluteY = 0;
        if (padWidth < 2 || padHeight < 2)
        {
            return false;
        }
        if (!Monitors.TryBounds(deviceName, out var left, out var top, out var width, out var height))
        {
            return false;
        }
        if (x < 0)
        {
            x = 0;
        }
        if (y < 0)
        {
            y = 0;
        }
        var localX = (int)((long)x * width / padWidth);
        var localY = (int)((long)y * height / padHeight);
        if (localX >= width)
        {
            localX = width - 1;
        }
        if (localY >= height)
        {
            localY = height - 1;
        }
        var screenX = left + localX;
        var screenY = top + localY;
        var virtualLeft = GetSystemMetrics(76);
        var virtualTop = GetSystemMetrics(77);
        var spanX = Math.Max(1, GetSystemMetrics(78) - 1);
        var spanY = Math.Max(1, GetSystemMetrics(79) - 1);
        absoluteX = (int)((screenX - virtualLeft) * 65535L / spanX);
        absoluteY = (int)((screenY - virtualTop) * 65535L / spanY);
        if (absoluteX < 0)
        {
            absoluteX = 0;
        }
        else if (absoluteX > 65535)
        {
            absoluteX = 65535;
        }
        if (absoluteY < 0)
        {
            absoluteY = 0;
        }
        else if (absoluteY > 65535)
        {
            absoluteY = 65535;
        }
        return true;
    }

    private static Input Pointer(int absoluteX, int absoluteY, int flags)
    {
        return new Input
        {
            Type = 0,
            X = absoluteX,
            Y = absoluteY,
            Flags = flags,
            Extra = PointerGuard.Mark
        };
    }

    private void ForceOpaque(Bitmap bitmap)
    {
        var data = bitmap.LockBits(new Rectangle(0, 0, bitmap.Width, bitmap.Height), ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        try
        {
            var length = data.Stride * bitmap.Height;
            if (length <= 0)
            {
                return;
            }
            if (opaque == null || opaque.Length < length)
            {
                opaque = new byte[length];
            }
            Marshal.Copy(data.Scan0, opaque, 0, length);
            for (var row = 0; row < bitmap.Height; row++)
            {
                var start = row * data.Stride;
                var end = start + bitmap.Width * 4;
                for (var i = start + 3; i < end; i += 4)
                {
                    opaque[i] = 255;
                }
            }
            Marshal.Copy(opaque, 0, data.Scan0, length);
        }
        finally
        {
            bitmap.UnlockBits(data);
        }
    }

    private void DrawCursor(Graphics graphics, int originX, int originY, int width, int height)
    {
        try
        {
            if (Marshal.SizeOf<CursorInfo>() != 24 || Marshal.SizeOf<IconInfo>() != 32)
            {
                NoteCursor("光标结构尺寸不对");
                return;
            }
            var info = new CursorInfo { Size = Marshal.SizeOf<CursorInfo>() };
            if (!GetCursorInfo(ref info) || (info.Flags & 1) == 0 || info.Cursor == IntPtr.Zero)
            {
                return;
            }
            if (info.X < originX || info.Y < originY || info.X >= originX + width || info.Y >= originY + height)
            {
                return;
            }
            if (!GetIconInfo(info.Cursor, out var icon))
            {
                return;
            }
            try
            {
                var drawX = info.X - originX - icon.HotspotX;
                var drawY = info.Y - originY - icon.HotspotY;
                var hdc = graphics.GetHdc();
                try
                {
                    DrawIconEx(hdc, drawX, drawY, info.Cursor, 0, 0, 0, IntPtr.Zero, 3);
                }
                finally
                {
                    graphics.ReleaseHdc(hdc);
                }
            }
            finally
            {
                if (icon.Mask != IntPtr.Zero)
                {
                    DeleteObject(icon.Mask);
                }
                if (icon.Color != IntPtr.Zero)
                {
                    DeleteObject(icon.Color);
                }
            }
        }
        catch (Exception ex)
        {
            NoteCursor(ex.Message);
        }
    }

    private void NoteCursor(string message)
    {
        if (cursorLogged)
        {
            return;
        }
        cursorLogged = true;
        Log.Write("光标没画上：" + message);
    }

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

    [DllImport("user32.dll")]
    private static extern bool GetCursorInfo(ref CursorInfo info);

    [DllImport("user32.dll")]
    private static extern bool GetIconInfo(IntPtr cursor, out IconInfo icon);

    [DllImport("user32.dll")]
    private static extern bool DrawIconEx(IntPtr hdc, int x, int y, IntPtr icon, int width, int height, uint step, IntPtr brush, uint flags);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr handle);

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

    private readonly record struct Patch(int X, int Y, int W, int H);

    [StructLayout(LayoutKind.Sequential)]
    private struct CursorInfo
    {
        public int Size;
        public int Flags;
        public IntPtr Cursor;
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IconInfo
    {
        public int Icon;
        public int HotspotX;
        public int HotspotY;
        public IntPtr Mask;
        public IntPtr Color;
    }
}
