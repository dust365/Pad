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
    private byte[] previous = Array.Empty<byte>();
    private int previousStride;
    private DateTime lastKeyframe = DateTime.MinValue;
    private bool sending;

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
        while (!stopped)
        {
            var started = Environment.TickCount64;
            var grabbed = false;
            try
            {
                grabbed = !sending && Grab(bitmap);
            }
            catch (Exception ex)
            {
                Log.Write("采集失败：" + ex.Message);
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

    private bool Grab(Bitmap target)
    {
        if (!Monitors.TryBounds(deviceName, out var x, out var y, out var width, out var height))
        {
            return false;
        }
        using var raw = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using (var graphics = Graphics.FromImage(raw))
        {
            graphics.CopyFromScreen(x, y, 0, 0, new Size(width, height), CopyPixelOperation.SourceCopy);
        }
        using var canvas = Graphics.FromImage(target);
        canvas.InterpolationMode = InterpolationMode.HighQualityBicubic;
        canvas.DrawImage(raw, new Rectangle(0, 0, padWidth, padHeight));
        return true;
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
        if (!Monitors.TryBounds(deviceName, out var left, out var top, out var width, out var height))
        {
            return;
        }
        var screenX = left + x * width / padWidth;
        var screenY = top + y * height / padHeight;
        var virtualLeft = GetSystemMetrics(76);
        var virtualTop = GetSystemMetrics(77);
        var virtualWidth = Math.Max(1, GetSystemMetrics(78));
        var virtualHeight = Math.Max(1, GetSystemMetrics(79));
        var absoluteX = (int)((screenX - virtualLeft) * 65535L / (virtualWidth - 1));
        var absoluteY = (int)((screenY - virtualTop) * 65535L / (virtualHeight - 1));
        var flags = 0x8000 | 0x4000;
        if (action == Wire.TouchDown)
        {
            flags |= 0x0002;
            buttonDown = true;
        }
        else if (action == Wire.TouchUp)
        {
            flags |= 0x0004;
            buttonDown = false;
        }
        else
        {
            flags |= 0x0001;
        }
        var input = new Input
        {
            Type = 0,
            X = absoluteX,
            Y = absoluteY,
            Flags = flags
        };
        SendInput(1, new[] { input }, Marshal.SizeOf<Input>());
    }

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [DllImport("user32.dll")]
    private static extern uint SendInput(uint count, Input[] inputs, int size);

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
}
