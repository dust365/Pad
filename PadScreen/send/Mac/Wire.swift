import Darwin
import Foundation

final class Connection {
    private let fd: Int32

    init(host: String, port: Int) throws {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        if fd < 0 {
            throw NSError(domain: "PadScreen", code: 7, userInfo: [NSLocalizedDescriptionKey: "无法创建连接"])
        }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(UInt16(port)).bigEndian
        if inet_pton(AF_INET, host, &address.sin_addr) != 1 {
            Darwin.close(fd)
            throw NSError(domain: "PadScreen", code: 8, userInfo: [NSLocalizedDescriptionKey: "地址无效"])
        }
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if connected != 0 {
            Darwin.close(fd)
            throw NSError(domain: "PadScreen", code: 9, userInfo: [NSLocalizedDescriptionKey: "连接 \(host) 失败"])
        }
        var flag: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, socklen_t(MemoryLayout<Int32>.size))
        var sendBuffer = 128 * 1024
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sendBuffer, socklen_t(MemoryLayout<Int32>.size))
    }

    func close() {
        Darwin.close(fd)
    }

    fileprivate func writeAll(_ data: Data) throws {
        var remaining = data
        while !remaining.isEmpty {
            let count = remaining.withUnsafeBytes { raw -> Int in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else {
                    return -1
                }
                return Darwin.send(fd, base, raw.count, 0)
            }
            if count <= 0 {
                throw NSError(domain: "PadScreen", code: 3, userInfo: [NSLocalizedDescriptionKey: "发送失败"])
            }
            remaining.removeFirst(count)
        }
    }

    fileprivate func readFully(_ length: Int) throws -> Data {
        var data = Data(count: length)
        var offset = 0
        while offset < length {
            let count = data.withUnsafeMutableBytes { raw -> Int in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else {
                    return -1
                }
                return Darwin.recv(fd, base.advanced(by: offset), length - offset, 0)
            }
            if count < 0 {
                throw NSError(domain: "PadScreen", code: 4, userInfo: [NSLocalizedDescriptionKey: "读取失败"])
            }
            if count == 0 {
                throw NSError(domain: "PadScreen", code: 5, userInfo: [NSLocalizedDescriptionKey: "连接已断开"])
            }
            offset += count
        }
        return data
    }
}

enum Wire {
    static let hello = 1
    static let jpeg = 2
    static let touch = 3
    static let patch = 4
    static let touchDown = 0
    static let touchMove = 1
    static let touchUp = 2
    static let maxPayload = 2 * 1024 * 1024

    static func writeFrame(to link: Connection, type: Int, payload: Data) throws {
        var header = [UInt8](repeating: 0, count: 5)
        header[0] = UInt8(type)
        let length = payload.count
        header[1] = UInt8((length >> 24) & 0xff)
        header[2] = UInt8((length >> 16) & 0xff)
        header[3] = UInt8((length >> 8) & 0xff)
        header[4] = UInt8(length & 0xff)
        try link.writeAll(Data(header))
        if length > 0 {
            try link.writeAll(payload)
        }
    }

    static func readFrame(from link: Connection) throws -> (Int, Data) {
        let header = try link.readFully(5)
        let type = Int(header[0])
        let length = (Int(header[1]) << 24) | (Int(header[2]) << 16) | (Int(header[3]) << 8) | Int(header[4])
        if length < 0 || length > maxPayload {
            throw NSError(domain: "PadScreen", code: 2, userInfo: [NSLocalizedDescriptionKey: "帧长度无效"])
        }
        if length == 0 {
            return (type, Data())
        }
        return (type, try link.readFully(length))
    }

    static func parseHello(_ payload: Data) -> (Int, Int, String)? {
        if payload.count < 4 {
            return nil
        }
        let width = (Int(payload[0]) << 8) | Int(payload[1])
        let height = (Int(payload[2]) << 8) | Int(payload[3])
        let name = String(data: payload.subdata(in: 4..<payload.count), encoding: .utf8) ?? ""
        return (width, height, name)
    }

    static func parseTouch(_ payload: Data) -> (Int, Int, Int)? {
        if payload.count < 5 {
            return nil
        }
        let action = Int(payload[0])
        let x = (Int(payload[1]) << 8) | Int(payload[2])
        let y = (Int(payload[3]) << 8) | Int(payload[4])
        return (action, x, y)
    }

}
