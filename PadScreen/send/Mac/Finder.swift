import Darwin
import Foundation

final class FoundPad: NSObject {
    let name: String
    let host: String
    let port: Int

    init(name: String, host: String, port: Int) {
        self.name = name
        self.host = host
        self.port = port
    }
}

final class Finder: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var scanGeneration = 0
    private(set) var pads: [FoundPad] = []
    private(set) var scanning = false
    var onChange: () -> Void = {}

    func start() {
        rescan()
    }

    /// 清掉上次的结果再搜一次。列表只保留这次还能解析到的设备。
    func rescan() {
        scanGeneration += 1
        let generation = scanGeneration
        browser.stop()
        services.removeAll()
        pads = []
        scanning = true
        browser.delegate = self
        browser.searchForServices(ofType: "_padscreen._tcp.", inDomain: "local.")
        DispatchQueue.main.async {
            self.onChange()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            guard let self, self.scanGeneration == generation else {
                return
            }
            self.scanning = false
            self.onChange()
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        if !services.contains(where: { $0 == service }) {
            service.delegate = self
            services.append(service)
            service.resolve(withTimeout: 5)
        }
        if !moreComing {
            publish()
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        services.removeAll { $0 == service }
        if !moreComing {
            publish()
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch error: [String: NSNumber]) {
        NSLog("PadScreen browse failed %@", error.description)
        publish()
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        publish()
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        NSLog("PadScreen resolve failed %@", errorDict.description)
    }

    private func publish() {
        var next: [FoundPad] = []
        for service in services {
            guard let host = ipv4(of: service), service.port > 0 else {
                continue
            }
            let name = service.name.isEmpty ? host : service.name
            if next.contains(where: { $0.host == host && $0.port == service.port }) {
                continue
            }
            next.append(FoundPad(name: name, host: host, port: service.port))
        }
        next.sort { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        pads = next
        if !next.isEmpty {
            scanning = false
        }
        DispatchQueue.main.async {
            self.onChange()
        }
    }

    private func ipv4(of service: NetService) -> String? {
        guard let addresses = service.addresses else {
            return nil
        }
        for address in addresses {
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result: Int32 = address.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else {
                    return -1
                }
                let pointer = base.assumingMemoryBound(to: sockaddr.self)
                return getnameinfo(pointer, socklen_t(address.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            }
            if result == 0 {
                let text = String(cString: host)
                if !text.contains(":") && text != "0.0.0.0" {
                    return text
                }
            }
        }
        return nil
    }
}
