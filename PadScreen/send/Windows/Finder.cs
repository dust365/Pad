using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Windows.Forms;

namespace PadScreen;

sealed class FoundPad
{
    public FoundPad(string name, string host, int port)
    {
        Name = name;
        Host = host;
        Port = port;
    }

    public string Name { get; }
    public string Host { get; }
    public int Port { get; }
    public string Key => $"{Host}:{Port}";
}

/// <summary>
/// 监听局域网里的 _padscreen._tcp，不主动连平板，避免占掉它唯一的连接。
/// </summary>
sealed class Finder : IDisposable
{
    private const string Service = "_padscreen._tcp.local";
    private readonly object gate = new();
    private readonly Dictionary<string, ServiceRecord> records = new();
    private Socket? socket;
    private Socket? multicast;
    private CancellationTokenSource? cancel;
    private Thread? thread;
    private int generation;

    public bool Scanning { get; private set; }
    public string? SocketNote { get; private set; }
    public IReadOnlyList<FoundPad> Pads { get; private set; } = Array.Empty<FoundPad>();
    public event Action? Changed;

    public void Start()
    {
        Rescan();
    }

    public void Rescan()
    {
        lock (gate)
        {
            generation++;
            records.Clear();
            Pads = Array.Empty<FoundPad>();
            Scanning = true;
        }
        EnsureSocket();
        SendQuery();
        Raise();
        var seen = generation;
        Task.Run(async () =>
        {
            await Task.Delay(2500);
            lock (gate)
            {
                if (generation != seen)
                {
                    return;
                }
                Scanning = false;
            }
            Raise();
        });
    }

    public void Dispose()
    {
        cancel?.Cancel();
        try
        {
            socket?.Dispose();
        }
        catch (Exception)
        {
        }
        try
        {
            multicast?.Dispose();
        }
        catch (Exception)
        {
        }
        socket = null;
        multicast = null;
    }

    private void EnsureSocket()
    {
        if (socket != null)
        {
            return;
        }
        // 查询从临时端口发出，并要求单播应答。Windows 的系统服务已经占着 5353，
        // 发到这个端口的单播回复不会进到本程序。
        var created = OpenUdp(0);
        created.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
        socket = created;
        var local = (IPEndPoint)created.LocalEndPoint!;
        Log.Write("mDNS 查询端口 " + local.Port);
        try
        {
            var group = OpenUdp(5353);
            group.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.AddMembership, new MulticastOption(IPAddress.Parse("224.0.0.251")));
            multicast = group;
        }
        catch (Exception ex)
        {
            SocketNote = "5353 端口被占用，只接收单播应答：" + ex.Message;
            Log.Write(SocketNote);
        }
        cancel = new CancellationTokenSource();
        thread = new Thread(ReceiveLoop) { IsBackground = true, Name = "pad-mdns" };
        thread.Start();
    }

    private void SendQuery()
    {
        if (socket == null)
        {
            return;
        }
        var query = DnsPacket.Query(Service);
        try
        {
            socket.SendTo(query, new IPEndPoint(IPAddress.Parse("224.0.0.251"), 5353));
        }
        catch (Exception ex)
        {
            Log.Write("mDNS 查询失败：" + ex.Message);
        }
    }

    private void ReceiveLoop()
    {
        var buffer = new byte[4096];
        var lastQuery = DateTime.UtcNow;
        while (cancel != null && !cancel.IsCancellationRequested && socket != null)
        {
            if (DateTime.UtcNow - lastQuery > TimeSpan.FromSeconds(3))
            {
                SendQuery();
                lastQuery = DateTime.UtcNow;
            }
            Pump(socket, buffer);
            Pump(multicast, buffer);
        }
    }

    private static Socket OpenUdp(int port)
    {
        var created = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        created.ExclusiveAddressUse = false;
        created.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        try
        {
            created.IOControl(-1744830452, new byte[] { 0, 0, 0, 0 }, null);
        }
        catch (Exception)
        {
        }
        created.Bind(new IPEndPoint(IPAddress.Any, port));
        created.ReceiveTimeout = 200;
        return created;
    }

    private void Pump(Socket? current, byte[] buffer)
    {
        if (current == null)
        {
            return;
        }
        try
        {
            if (!current.Poll(200000, SelectMode.SelectRead) || current.Available <= 0)
            {
                return;
            }
            EndPoint remote = new IPEndPoint(IPAddress.Any, 0);
            var count = current.ReceiveFrom(buffer, ref remote);
            if (count > 0)
            {
                Accept(buffer, count);
            }
        }
        catch (SocketException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
        catch (Exception ex)
        {
            Log.Write("mDNS 读取失败：" + ex.Message);
        }
    }

    private void Accept(byte[] buffer, int count)
    {
        if (!DnsPacket.TryParse(buffer, count, out var parsed))
        {
            return;
        }
        var changed = false;
        lock (gate)
        {
            foreach (var record in parsed)
            {
                if (!records.TryGetValue(record.Instance, out var existing))
                {
                    existing = new ServiceRecord { Instance = record.Instance };
                    records[record.Instance] = existing;
                    changed = true;
                }
                if (record.Port > 0 && existing.Port != record.Port)
                {
                    existing.Port = record.Port;
                    changed = true;
                }
                if (!string.IsNullOrEmpty(record.Host) && existing.Host != record.Host)
                {
                    existing.Host = record.Host;
                    changed = true;
                }
                existing.Seen = DateTime.UtcNow;
            }
            var cutoff = DateTime.UtcNow.AddSeconds(-40);
            var stale = records.Where(pair => pair.Value.Seen < cutoff).Select(pair => pair.Key).ToList();
            foreach (var key in stale)
            {
                records.Remove(key);
                changed = true;
            }
            if (changed)
            {
                Pads = records.Values
                    .Where(item => item.Port > 0 && !string.IsNullOrEmpty(item.Host))
                    .Select(item => new FoundPad(item.Instance, item.Host, item.Port))
                    .OrderBy(item => item.Name, StringComparer.CurrentCulture)
                    .ToList();
                foreach (var pad in Pads)
                {
                    Log.Write($"发现 {pad.Name} {pad.Host}:{pad.Port}");
                }
                if (Pads.Count > 0)
                {
                    Scanning = false;
                }
            }
        }
        if (changed)
        {
            Raise();
        }
    }

    private void Raise()
    {
        var handler = Changed;
        if (handler == null)
        {
            return;
        }
        if (Application.OpenForms.Count > 0)
        {
            try
            {
                var form = Application.OpenForms[0];
                if (form != null && form.IsHandleCreated)
                {
                    form.BeginInvoke(handler);
                    return;
                }
            }
            catch (Exception)
            {
            }
        }
    }

    private sealed class ServiceRecord
    {
        public string Instance = "";
        public string Host = "";
        public int Port;
        public DateTime Seen = DateTime.UtcNow;
    }
}

sealed class ParsedRecord
{
    public string Instance = "";
    public string Host = "";
    public int Port;
}

static class DnsPacket
{
    private const string Service = "_padscreen._tcp.local";
    private static long lastParseLog;

    public static byte[] Query(string service)
    {
        var name = EncodeName(service);
        var packet = new byte[12 + name.Length + 4];
        packet[0] = 0;
        packet[1] = 1;
        packet[5] = 1;
        Buffer.BlockCopy(name, 0, packet, 12, name.Length);
        var at = 12 + name.Length;
        packet[at] = 0;
        packet[at + 1] = 12;
        packet[at + 2] = 0x80;
        packet[at + 3] = 1;
        return packet;
    }

    public static bool TryParse(byte[] buffer, int count, out List<ParsedRecord> records)
    {
        records = new List<ParsedRecord>();
        if (count < 12)
        {
            return false;
        }
        var flags = (buffer[2] << 8) | buffer[3];
        if ((flags & 0x8000) == 0)
        {
            return false;
        }
        var questions = (buffer[4] << 8) | buffer[5];
        var answers = (buffer[6] << 8) | buffer[7];
        var authorities = (buffer[8] << 8) | buffer[9];
        var additional = (buffer[10] << 8) | buffer[11];
        var cursor = new Cursor(buffer, count, 12);
        var byTarget = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var pending = new List<(string Instance, string Target, int Port)>();
        try
        {
            for (var i = 0; i < questions; i++)
            {
                cursor.ReadName();
                cursor.Read16();
                cursor.Read16();
            }
            var total = answers + authorities + additional;
            for (var i = 0; i < total; i++)
            {
                var owner = cursor.ReadName();
                var type = cursor.Read16();
                cursor.Read16();
                cursor.Read32();
                var length = cursor.Read16();
                var start = cursor.Position;
                if (type == 12)
                {
                    var target = cursor.ReadName();
                    var instance = InstanceName(target);
                    if (IsPadService(owner) && instance.Length > 0)
                    {
                        records.Add(new ParsedRecord { Instance = instance });
                    }
                }
                else if (type == 33)
                {
                    cursor.Read16();
                    cursor.Read16();
                    var port = cursor.Read16();
                    var target = cursor.ReadName();
                    var instance = InstanceName(owner);
                    if (instance.Length > 0 && port > 0)
                    {
                        pending.Add((instance, target, port));
                    }
                }
                else if (type == 1 && length == 4)
                {
                    var ip = $"{buffer[cursor.Position]}.{buffer[cursor.Position + 1]}.{buffer[cursor.Position + 2]}.{buffer[cursor.Position + 3]}";
                    byTarget[owner] = ip;
                    byTarget[owner.TrimEnd('.')] = ip;
                    cursor.Position += 4;
                }
                else
                {
                    cursor.Position = start + length;
                }
                if (cursor.Position < start + length)
                {
                    cursor.Position = start + length;
                }
            }
        }
        catch (Exception ex)
        {
            var now = Environment.TickCount64;
            if (now - lastParseLog > 30000)
            {
                lastParseLog = now;
                Log.Write("mDNS 解析中断：" + ex.Message);
            }
        }
        foreach (var item in pending)
        {
            var host = "";
            if (!byTarget.TryGetValue(item.Target, out host))
            {
                byTarget.TryGetValue(item.Target.TrimEnd('.'), out host!);
            }
            var existing = records.FirstOrDefault(record => record.Instance.Equals(item.Instance, StringComparison.OrdinalIgnoreCase));
            if (existing == null)
            {
                existing = new ParsedRecord { Instance = item.Instance };
                records.Add(existing);
            }
            existing.Port = item.Port;
            if (!string.IsNullOrEmpty(host))
            {
                existing.Host = host;
            }
        }
        records = records.Where(record => record.Instance.Length > 0 && record.Port > 0 && !string.IsNullOrEmpty(record.Host)).ToList();
        return records.Count > 0;
    }

    private static bool IsPadService(string name)
    {
        return name.EndsWith(Service, StringComparison.OrdinalIgnoreCase)
            || name.EndsWith("._padscreen._tcp.local", StringComparison.OrdinalIgnoreCase)
            || name.Contains("._padscreen._tcp.", StringComparison.OrdinalIgnoreCase);
    }

    private static string InstanceName(string owner)
    {
        var marker = "._padscreen._tcp";
        var index = owner.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
        if (index <= 0)
        {
            return "";
        }
        return owner.Substring(0, index);
    }

    private static byte[] EncodeName(string name)
    {
        var labels = name.TrimEnd('.').Split('.');
        using var stream = new MemoryStream();
        foreach (var label in labels)
        {
            var bytes = Encoding.ASCII.GetBytes(label);
            stream.WriteByte((byte)bytes.Length);
            stream.Write(bytes, 0, bytes.Length);
        }
        stream.WriteByte(0);
        return stream.ToArray();
    }

    private sealed class Cursor
    {
        private readonly byte[] buffer;
        private readonly int count;
        public int Position;

        public Cursor(byte[] buffer, int count, int position)
        {
            this.buffer = buffer;
            this.count = count;
            Position = position;
        }

        public int Read16()
        {
            if (Position < 0 || Position + 2 > count)
            {
                throw new InvalidDataException("mDNS 包不完整");
            }
            var value = (buffer[Position] << 8) | buffer[Position + 1];
            Position += 2;
            return value;
        }

        public int Read32()
        {
            if (Position < 0 || Position + 4 > count)
            {
                throw new InvalidDataException("mDNS 包不完整");
            }
            var value = (buffer[Position] << 24) | (buffer[Position + 1] << 16) | (buffer[Position + 2] << 8) | buffer[Position + 3];
            Position += 4;
            return value;
        }

        public string ReadName()
        {
            var labels = new List<string>();
            var hops = 0;
            var returned = false;
            var position = Position;
            while (position < count && hops < 20)
            {
                var length = buffer[position];
                if (length == 0)
                {
                    position++;
                    if (!returned)
                    {
                        Position = position;
                    }
                    break;
                }
                if ((length & 0xc0) == 0xc0)
                {
                    if (position + 1 >= count)
                    {
                        throw new InvalidDataException("mDNS 名称不完整");
                    }
                    var pointer = ((length & 0x3f) << 8) | buffer[position + 1];
                    if (pointer < 0 || pointer >= count)
                    {
                        throw new InvalidDataException("mDNS 名称越界");
                    }
                    if (!returned)
                    {
                        Position = position + 2;
                        returned = true;
                    }
                    position = pointer;
                    hops++;
                    continue;
                }
                if (length > 63 || position + 1 + length > count)
                {
                    throw new InvalidDataException("mDNS 名称不完整");
                }
                position++;
                labels.Add(Encoding.ASCII.GetString(buffer, position, length));
                position += length;
                hops++;
            }
            return string.Join(".", labels);
        }
    }
}
