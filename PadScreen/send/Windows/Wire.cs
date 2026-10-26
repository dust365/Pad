namespace PadScreen;

static class Wire
{
    public const int Hello = 1;
    public const int Jpeg = 2;
    public const int Touch = 3;
    public const int Patch = 4;
    public const int TouchDown = 0;
    public const int TouchMove = 1;
    public const int TouchUp = 2;
    public const int MaxPayload = 2 * 1024 * 1024;

    public static byte[] Frame(int type, byte[] payload)
    {
        var length = payload.Length;
        var header = new byte[5 + length];
        header[0] = (byte)type;
        header[1] = (byte)((length >> 24) & 0xff);
        header[2] = (byte)((length >> 16) & 0xff);
        header[3] = (byte)((length >> 8) & 0xff);
        header[4] = (byte)(length & 0xff);
        if (length > 0)
        {
            Buffer.BlockCopy(payload, 0, header, 5, length);
        }
        return header;
    }

    public static (int Width, int Height, string Name)? ParseHello(byte[] payload)
    {
        if (payload.Length < 4)
        {
            return null;
        }
        var width = (payload[0] << 8) | payload[1];
        var height = (payload[2] << 8) | payload[3];
        var name = System.Text.Encoding.UTF8.GetString(payload, 4, payload.Length - 4);
        return (width, height, name);
    }

    public static (int Action, int X, int Y)? ParseTouch(byte[] payload)
    {
        if (payload.Length < 5)
        {
            return null;
        }
        var action = payload[0];
        var x = (payload[1] << 8) | payload[2];
        var y = (payload[3] << 8) | payload[4];
        return (action, x, y);
    }

    public static byte[] PackPatch(int x, int y, int w, int h, byte[] jpeg)
    {
        var payload = new byte[8 + jpeg.Length];
        payload[0] = (byte)((x >> 8) & 0xff);
        payload[1] = (byte)(x & 0xff);
        payload[2] = (byte)((y >> 8) & 0xff);
        payload[3] = (byte)(y & 0xff);
        payload[4] = (byte)((w >> 8) & 0xff);
        payload[5] = (byte)(w & 0xff);
        payload[6] = (byte)((h >> 8) & 0xff);
        payload[7] = (byte)(h & 0xff);
        Buffer.BlockCopy(jpeg, 0, payload, 8, jpeg.Length);
        return payload;
    }
}

sealed class Link : IDisposable
{
    private readonly object gate = new();
    private readonly System.Net.Sockets.TcpClient client;
    private readonly Stream stream;

    public Link(string host, int port)
    {
        client = new System.Net.Sockets.TcpClient { NoDelay = true };
        var task = client.ConnectAsync(host, port);
        if (!task.Wait(TimeSpan.FromSeconds(4)))
        {
            client.Dispose();
            throw new IOException($"连接 {host} 超时");
        }
        task.GetAwaiter().GetResult();
        client.SendBufferSize = 128 * 1024;
        stream = client.GetStream();
    }

    public void WriteFrame(int type, byte[] payload)
    {
        var frame = Wire.Frame(type, payload);
        lock (gate)
        {
            stream.Write(frame, 0, frame.Length);
            stream.Flush();
        }
    }

    public (int Type, byte[] Payload) ReadFrame()
    {
        return ReadFrameLocked();
    }

    private (int Type, byte[] Payload) ReadFrameLocked()
    {
        var header = ReadFully(5);
        var type = header[0];
        var length = (header[1] << 24) | (header[2] << 16) | (header[3] << 8) | header[4];
        if (length < 0 || length > Wire.MaxPayload)
        {
            throw new IOException("帧长度无效");
        }
        if (length == 0)
        {
            return (type, Array.Empty<byte>());
        }
        return (type, ReadFully(length));
    }

    public void Dispose()
    {
        try
        {
            client.Dispose();
        }
        catch (Exception)
        {
        }
    }

    private byte[] ReadFully(int length)
    {
        var buffer = new byte[length];
        var offset = 0;
        while (offset < length)
        {
            var count = stream.Read(buffer, offset, length - offset);
            if (count <= 0)
            {
                throw new IOException("连接已断开");
            }
            offset += count;
        }
        return buffer;
    }
}
