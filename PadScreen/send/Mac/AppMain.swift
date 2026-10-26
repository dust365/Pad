import AppKit
import ApplicationServices
import CoreImage
import CoreMedia
import CoreVideo
import Darwin
import Foundation
import ImageIO
import ScreenCaptureKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let finder = Finder()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private var sessions: [String: PadSession] = [:]
    private var displaysByName: [String: VirtualDisplayController] = [:]
    private var statusText = "副屏"
    private var errorText: String?
    private var accessibilityPrompted = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem.button?.title = statusText
        finder.onChange = { [weak self] in
            self?.rebuildMenu()
        }
        finder.start()
        rebuildMenu()
        _ = promptAccessibility(prompt: false)
    }

    func applicationWillTerminate(_ notification: Notification) {
        let current = Array(sessions.values)
        for session in current {
            session.stop(message: nil)
        }
    }

    func adoptDisplay(named name: String, current: VirtualDisplayController) -> VirtualDisplayController {
        if let existing = displaysByName[name] {
            return existing
        }
        displaysByName[name] = current
        return current
    }

    func noteStopped(_ session: PadSession, message: String?) {
        if sessions[session.key] === session {
            sessions.removeValue(forKey: session.key)
        }
        if let message {
            errorText = "\(session.padName)：\(message)"
        } else {
            errorText = nil
        }
        rebuildMenu()
    }

    func notePhase(_ session: PadSession) {
        rebuildMenu()
    }

    func wantsAccessibilityPrompt() -> Bool {
        if promptAccessibility(prompt: false) {
            return true
        }
        if accessibilityPrompted {
            return false
        }
        accessibilityPrompted = true
        return promptAccessibility(prompt: true)
    }

    func explainCaptureError(_ error: Error) -> String {
        let text = error.localizedDescription
        let denied = text.contains("TCC") || text.contains("捕捉") || text.localizedCaseInsensitiveContains("denied")
        if !denied {
            return text
        }
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_ScreenCapture")!)
        return "需要允许录屏。在「录屏与系统录音」里打开 PadScreen，然后退出并重新打开副屏。"
    }

    private func promptAccessibility(prompt: Bool) -> Bool {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        let options = [key: prompt] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    private func rebuildMenu() {
        let liveCount = sessions.values.filter { $0.phase == .live }.count
        let connecting = sessions.values.contains { $0.phase == .connecting }
        if liveCount >= 2 {
            statusText = "副屏 · \(liveCount)"
        } else if liveCount == 1 {
            statusText = "副屏 · 已连接"
        } else if connecting {
            statusText = "副屏 · 连接中"
        } else {
            statusText = "副屏"
        }

        let menu = NSMenu()
        if let errorText {
            let item = NSMenuItem(title: errorText, action: nil, keyEquivalent: "")
            item.isEnabled = false
            menu.addItem(item)
            menu.addItem(.separator())
        }
        var listed: Set<String> = []
        if finder.pads.isEmpty && sessions.isEmpty {
            let title = finder.scanning ? "正在扫描…" : "没有发现设备"
            menu.addItem(item(title, action: nil, enabled: false))
        } else if !finder.pads.isEmpty {
            menu.addItem(item("已发现 \(finder.pads.count) 台", action: nil, enabled: false))
        }
        for pad in finder.pads {
            let key = PadSession.key(host: pad.host, port: pad.port)
            listed.insert(key)
            appendSessionMenu(menu, key: key, fallbackName: pad.name, host: pad.host, pad: pad)
        }
        for (key, session) in sessions where !listed.contains(key) {
            appendSessionMenu(menu, key: key, fallbackName: session.padName, host: session.host, pad: nil)
        }
        menu.addItem(.separator())
        let rescanTitle = finder.scanning ? "正在扫描…" : "重新扫描"
        menu.addItem(item(rescanTitle, action: #selector(rescanClicked), enabled: !finder.scanning))
        menu.addItem(item("退出", action: #selector(quitClicked), enabled: true))
        statusItem.menu = menu
        statusItem.button?.title = statusText
    }

    private func appendSessionMenu(_ menu: NSMenu, key: String, fallbackName: String, host: String, pad: FoundPad?) {
        if let session = sessions[key] {
            let name = session.padName.isEmpty ? fallbackName : session.padName
            let state = session.phase == .live ? "已连接" : "连接中"
            menu.addItem(item("\(name)  \(host)  \(state)", action: nil, enabled: false))
            let disconnect = item("断开 \(name)", action: #selector(disconnectClicked(_:)), enabled: true)
            disconnect.representedObject = key
            menu.addItem(disconnect)
            return
        }
        guard let pad else {
            return
        }
        let entry = item("\(pad.name)  \(pad.host)", action: #selector(connectClicked(_:)), enabled: true)
        entry.representedObject = pad
        menu.addItem(entry)
    }

    private func item(_ title: String, action: Selector?, enabled: Bool) -> NSMenuItem {
        let entry = NSMenuItem(title: title, action: action, keyEquivalent: "")
        entry.target = self
        entry.isEnabled = enabled
        return entry
    }

    @objc private func connectClicked(_ sender: NSMenuItem) {
        guard let pad = sender.representedObject as? FoundPad else {
            return
        }
        let key = PadSession.key(host: pad.host, port: pad.port)
        if sessions[key] != nil {
            return
        }
        errorText = nil
        let session = PadSession(pad: pad, owner: self)
        sessions[key] = session
        session.start()
        rebuildMenu()
    }

    @objc private func disconnectClicked(_ sender: NSMenuItem) {
        guard let key = sender.representedObject as? String else {
            return
        }
        sessions[key]?.stop(message: nil)
    }

    @objc private func rescanClicked() {
        errorText = nil
        finder.rescan()
    }

    @objc private func quitClicked() {
        NSApp.terminate(nil)
    }
}

final class PadSession: NSObject, SCStreamOutput, SCStreamDelegate {
    enum Phase {
        case connecting
        case live
    }

    let key: String
    let host: String
    let port: Int
    private(set) var padName: String
    private(set) var phase: Phase = .connecting
    private weak var owner: AppDelegate?
    private var displays: VirtualDisplayController
    private let context = CIContext(options: [
        .useSoftwareRenderer: false,
        .highQualityDownsample: true
    ])
    private let captureQueue: DispatchQueue
    private let sendQueue: DispatchQueue
    private let sendLock = NSLock()
    private let stateLock = NSLock()

    private var link: Connection?
    private var stream: SCStream?
    private var reader: Thread?
    private var padWidth = 1024
    private var padHeight = 600
    private var stopped = false
    private var pending: [(Int, Data)] = []
    private var sending = false
    private var buttonDown = false
    private var previousBytes: [UInt8] = []
    private var previousWidth = 0
    private var previousHeight = 0
    private var previousBytesPerRow = 0
    private var lastKeyframe = Date.distantPast
    private var scaledBuffer: CVPixelBuffer?
    private let fullQuality: CGFloat = 0.86
    private let patchQuality: CGFloat = 0.92

    static func key(host: String, port: Int) -> String {
        "\(host):\(port)"
    }

    init(pad: FoundPad, owner: AppDelegate) {
        key = PadSession.key(host: pad.host, port: pad.port)
        host = pad.host
        port = pad.port
        padName = pad.name
        displays = VirtualDisplayController()
        captureQueue = DispatchQueue(label: "pad.capture.\(pad.host).\(pad.port)")
        sendQueue = DispatchQueue(label: "pad.send.\(pad.host).\(pad.port)")
        self.owner = owner
        super.init()
    }

    func start() {
        let link: Connection
        do {
            link = try Connection(host: host, port: port)
        } catch {
            stop(message: error.localizedDescription)
            return
        }
        stateLock.lock()
        stopped = false
        self.link = link
        stateLock.unlock()
        phase = .connecting
        reader = Thread { [weak self] in
            self?.readLoop()
        }
        reader?.start()
        owner?.notePhase(self)
    }

    func stop(message: String?) {
        stateLock.lock()
        if stopped {
            stateLock.unlock()
            return
        }
        stopped = true
        let currentLink = link
        link = nil
        stateLock.unlock()
        stream?.stopCapture()
        stream = nil
        currentLink?.close()
        sendLock.lock()
        pending.removeAll()
        sending = false
        sendLock.unlock()
        previousBytes = []
        previousWidth = 0
        previousHeight = 0
        previousBytesPerRow = 0
        buttonDown = false
        owner?.noteStopped(self, message: message)
    }

    private func readLoop() {
        stateLock.lock()
        let link = self.link
        stateLock.unlock()
        guard let link else {
            return
        }
        do {
            let (type, payload) = try Wire.readFrame(from: link)
            guard type == Wire.hello, let hello = Wire.parseHello(payload) else {
                throw NSError(domain: "PadScreen", code: 6, userInfo: [NSLocalizedDescriptionKey: "平板没有完成握手"])
            }
            if hello.0 < 2 || hello.1 < 2 || hello.0 > 4096 || hello.1 > 4096 {
                throw NSError(domain: "PadScreen", code: 10, userInfo: [NSLocalizedDescriptionKey: "平板分辨率无效"])
            }
            padWidth = hello.0
            padHeight = hello.1
            if !hello.2.isEmpty {
                padName = hello.2
            }
            NSLog("PadScreen hello %dx%d %@", hello.0, hello.1, hello.2)
            DispatchQueue.main.async {
                self.openDisplay()
            }
            while !isStopped {
                let (frameType, frame) = try Wire.readFrame(from: link)
                if frameType == Wire.touch, let touch = Wire.parseTouch(frame) {
                    DispatchQueue.main.async {
                        self.postTouch(action: touch.0, x: touch.1, y: touch.2)
                    }
                }
            }
        } catch {
            stateLock.lock()
            let already = stopped
            stateLock.unlock()
            if !already {
                DispatchQueue.main.async {
                    self.stop(message: error.localizedDescription)
                }
            }
        }
    }

    private var isStopped: Bool {
        stateLock.lock()
        let value = stopped
        stateLock.unlock()
        return value
    }

    private func openDisplay() {
        if isStopped {
            return
        }
        displays = owner?.adoptDisplay(named: padName, current: displays) ?? displays
        var error: NSString?
        let ok = displays.ensureDisplayedNamed(padName, width: UInt32(padWidth), height: UInt32(padHeight), error: &error)
        if !ok {
            stop(message: (error as String?) ?? "无法创建虚拟显示器")
            return
        }
        let displayID = displays.displayID
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: false) { [weak self] content, contentError in
            DispatchQueue.main.async {
                guard let self, !self.isStopped else {
                    return
                }
                if let contentError {
                    self.stop(message: self.owner?.explainCaptureError(contentError) ?? contentError.localizedDescription)
                    return
                }
                guard let content, let display = content.displays.first(where: { $0.displayID == displayID }) else {
                    self.stop(message: "采集列表里没有这块虚拟显示器。请在系统设置中允许屏幕录制。")
                    return
                }
                self.startStream(display)
            }
        }
    }

    private func startStream(_ display: SCDisplay) {
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        let pixels = capturePixelSize(displayID: display.displayID)
        configuration.width = pixels.0
        configuration.height = pixels.1
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.showsCursor = true
        configuration.captureResolution = .best
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 15)
        configuration.queueDepth = 2
        previousBytes = []
        previousWidth = 0
        previousHeight = 0
        previousBytesPerRow = 0
        lastKeyframe = .distantPast
        scaledBuffer = nil
        let stream = SCStream(filter: filter, configuration: configuration, delegate: self)
        do {
            try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: captureQueue)
        } catch {
            stop(message: error.localizedDescription)
            return
        }
        self.stream = stream
        stream.startCapture { [weak self] error in
            DispatchQueue.main.async {
                guard let self, !self.isStopped else {
                    return
                }
                if let error {
                    self.stop(message: error.localizedDescription)
                } else {
                    self.phase = .live
                    self.owner?.notePhase(self)
                }
            }
        }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, CMSampleBufferIsValid(sampleBuffer), CMSampleBufferDataIsReady(sampleBuffer) else {
            return
        }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
           let raw = attachments.first?[.status] as? Int,
           raw != SCFrameStatus.complete.rawValue {
            return
        }
        sendLock.lock()
        let busy = sending
        if !busy {
            sending = true
        }
        sendLock.unlock()
        if busy {
            return
        }
        guard let pixelBuffer = sampleBuffer.imageBuffer else {
            releaseSend()
            return
        }
        let frames = makeFrames(from: pixelBuffer)
        if frames.isEmpty || isStopped {
            releaseSend()
            return
        }
        sendLock.lock()
        pending.append(contentsOf: frames)
        sendLock.unlock()
        sendQueue.async {
            self.drain()
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        DispatchQueue.main.async {
            if !self.isStopped {
                self.stop(message: error.localizedDescription)
            }
        }
    }

    private func releaseSend() {
        sendLock.lock()
        sending = false
        sendLock.unlock()
    }

    private func drain() {
        while !isStopped {
            sendLock.lock()
            if pending.isEmpty {
                sending = false
                sendLock.unlock()
                return
            }
            let frame = pending.removeFirst()
            sendLock.unlock()
            stateLock.lock()
            let link = self.link
            stateLock.unlock()
            guard let link else {
                releaseSend()
                return
            }
            do {
                try Wire.writeFrame(to: link, type: frame.0, payload: frame.1)
            } catch {
                sendLock.lock()
                pending.removeAll()
                sending = false
                sendLock.unlock()
                DispatchQueue.main.async {
                    if !self.isStopped {
                        self.stop(message: error.localizedDescription)
                    }
                }
                return
            }
        }
    }

    /// 虚拟屏是 2 倍像素。采集用真实像素，再缩到这块平板自己的点大小。
    private func capturePixelSize(displayID: CGDirectDisplayID) -> (Int, Int) {
        if let mode = CGDisplayCopyDisplayMode(displayID) {
            let width = mode.pixelWidth
            let height = mode.pixelHeight
            if width > 0 && height > 0 {
                return (width, height)
            }
        }
        return (padWidth * 2, padHeight * 2)
    }

    private func frameForPad(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        if width == padWidth && height == padHeight {
            return pixelBuffer
        }
        guard width > 0, height > 0, padWidth > 0, padHeight > 0 else {
            return nil
        }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        let image = CIImage(cvPixelBuffer: pixelBuffer)
        let scale = CGFloat(padHeight) / CGFloat(height)
        let aspect = (CGFloat(padWidth) / CGFloat(width)) / scale
        guard let filter = CIFilter(name: "CILanczosScaleTransform") else {
            return nil
        }
        filter.setValue(image, forKey: kCIInputImageKey)
        filter.setValue(NSNumber(value: Double(scale)), forKey: kCIInputScaleKey)
        filter.setValue(NSNumber(value: Double(aspect)), forKey: kCIInputAspectRatioKey)
        guard let filtered = filter.outputImage else {
            return nil
        }
        let shifted = filtered.transformed(by: CGAffineTransform(
            translationX: -filtered.extent.origin.x,
            y: -filtered.extent.origin.y
        ))
        let bounds = CGRect(x: 0, y: 0, width: padWidth, height: padHeight)
        if scaledBuffer == nil
            || CVPixelBufferGetWidth(scaledBuffer!) != padWidth
            || CVPixelBufferGetHeight(scaledBuffer!) != padHeight {
            let attrs: [String: Any] = [
                kCVPixelBufferCGImageCompatibilityKey as String: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:]
            ]
            var buffer: CVPixelBuffer?
            let status = CVPixelBufferCreate(
                kCFAllocatorDefault,
                padWidth,
                padHeight,
                kCVPixelFormatType_32BGRA,
                attrs as CFDictionary,
                &buffer
            )
            guard status == kCVReturnSuccess, let buffer else {
                return nil
            }
            scaledBuffer = buffer
        }
        guard let scaledBuffer else {
            return nil
        }
        context.render(
            shifted.cropped(to: bounds),
            to: scaledBuffer,
            bounds: bounds,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )
        return scaledBuffer
    }

    /// 整屏软解是 A20 的瓶颈。静止画面不发送；小范围变化只编码脏矩形。
    private func makeFrames(from pixelBuffer: CVPixelBuffer) -> [(Int, Data)] {
        guard let pixelBuffer = frameForPad(pixelBuffer) else {
            return []
        }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else {
            return []
        }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        if width <= 0 || height <= 0 || bytesPerRow < width * 4 {
            return []
        }
        let pixels = base.assumingMemoryBound(to: UInt8.self)
        let image = CIImage(cvPixelBuffer: pixelBuffer)
        let sizeChanged = previousWidth != width || previousHeight != height
            || previousBytesPerRow != bytesPerRow || previousBytes.count != height * bytesPerRow
        if sizeChanged || width != padWidth || height != padHeight {
            return commitFullFrame(image, pixels: pixels, width: width, height: height, bytesPerRow: bytesPerRow)
        }
        let regions = changedRegions(pixels: pixels, width: width, height: height, bytesPerRow: bytesPerRow)
        if regions == nil || (Date().timeIntervalSince(lastKeyframe) >= 20 && regions?.isEmpty == false) {
            return commitFullFrame(image, pixels: pixels, width: width, height: height, bytesPerRow: bytesPerRow)
        }
        guard let regions, !regions.isEmpty else {
            return []
        }
        var frames: [(Int, Data)] = []
        for region in regions {
            let rect = CGRect(
                x: region.x,
                y: height - region.y - region.h,
                width: region.w,
                height: region.h
            )
            guard let jpeg = encodeJPEG(image, rect: rect, quality: patchQuality) else {
                return commitFullFrame(image, pixels: pixels, width: width, height: height, bytesPerRow: bytesPerRow)
            }
            frames.append((Wire.patch, packPatch(region, jpeg: jpeg)))
        }
        remember(pixels, width: width, height: height, bytesPerRow: bytesPerRow)
        return frames
    }

    private func commitFullFrame(_ image: CIImage, pixels: UnsafePointer<UInt8>, width: Int, height: Int, bytesPerRow: Int) -> [(Int, Data)] {
        guard let jpeg = encodeJPEG(image, rect: image.extent, quality: fullQuality) else {
            return []
        }
        remember(pixels, width: width, height: height, bytesPerRow: bytesPerRow)
        lastKeyframe = Date()
        return [(Wire.jpeg, jpeg)]
    }

    private func changedRegions(pixels: UnsafePointer<UInt8>, width: Int, height: Int, bytesPerRow: Int) -> [PatchRegion]? {
        let tile = 32
        let cols = (width + tile - 1) / tile
        let rows = (height + tile - 1) / tile
        var changed = [Bool](repeating: false, count: cols * rows)
        var changedCount = 0
        let dirty = previousBytes.withUnsafeBytes { raw -> Bool in
            guard let old = raw.bindMemory(to: UInt8.self).baseAddress else {
                return true
            }
            for row in 0..<rows {
                for col in 0..<cols {
                    let x = col * tile
                    let y = row * tile
                    let w = min(tile, width - x)
                    let h = min(tile, height - y)
                    var tileDirty = false
                    for line in y..<(y + h) {
                        let offset = line * bytesPerRow + x * 4
                        if memcmp(pixels + offset, old + offset, w * 4) != 0 {
                            tileDirty = true
                            break
                        }
                    }
                    if tileDirty {
                        changed[row * cols + col] = true
                        changedCount += 1
                    }
                }
            }
            return false
        }
        if dirty {
            return nil
        }
        if changedCount == 0 {
            return []
        }
        if changedCount * tile * tile * 2 > width * height {
            return nil
        }
        var seen = [Bool](repeating: false, count: changed.count)
        var regions: [PatchRegion] = []
        for row in 0..<rows {
            for col in 0..<cols {
                let start = row * cols + col
                if !changed[start] || seen[start] {
                    continue
                }
                var stack = [(col, row)]
                seen[start] = true
                var minCol = col
                var maxCol = col
                var minRow = row
                var maxRow = row
                var index = 0
                while index < stack.count {
                    let current = stack[index]
                    index += 1
                    let neighbors = [(current.0 - 1, current.1), (current.0 + 1, current.1), (current.0, current.1 - 1), (current.0, current.1 + 1)]
                    for neighbor in neighbors {
                        if neighbor.0 < 0 || neighbor.1 < 0 || neighbor.0 >= cols || neighbor.1 >= rows {
                            continue
                        }
                        let neighborIndex = neighbor.1 * cols + neighbor.0
                        if !changed[neighborIndex] || seen[neighborIndex] {
                            continue
                        }
                        seen[neighborIndex] = true
                        stack.append(neighbor)
                        minCol = min(minCol, neighbor.0)
                        maxCol = max(maxCol, neighbor.0)
                        minRow = min(minRow, neighbor.1)
                        maxRow = max(maxRow, neighbor.1)
                    }
                }
                let x = minCol * tile
                let y = minRow * tile
                let w = min(width - x, (maxCol - minCol + 1) * tile)
                let h = min(height - y, (maxRow - minRow + 1) * tile)
                if w < 2 || h < 2 {
                    continue
                }
                regions.append(PatchRegion(x: x, y: y, w: w, h: h))
                if regions.count > 3 {
                    return nil
                }
            }
        }
        var area = 0
        for region in regions {
            area += region.w * region.h
        }
        if area * 2 > width * height {
            return nil
        }
        return regions
    }

    private func encodeJPEG(_ image: CIImage, rect: CGRect, quality: CGFloat) -> Data? {
        guard let cgImage = context.createCGImage(image, from: rect) else {
            return nil
        }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else {
            return nil
        }
        let options = [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary
        CGImageDestinationAddImage(destination, cgImage, options)
        guard CGImageDestinationFinalize(destination) else {
            return nil
        }
        return data as Data
    }

    private func packPatch(_ region: PatchRegion, jpeg: Data) -> Data {
        var payload = Data(count: 8 + jpeg.count)
        payload[0] = UInt8((region.x >> 8) & 0xff)
        payload[1] = UInt8(region.x & 0xff)
        payload[2] = UInt8((region.y >> 8) & 0xff)
        payload[3] = UInt8(region.y & 0xff)
        payload[4] = UInt8((region.w >> 8) & 0xff)
        payload[5] = UInt8(region.w & 0xff)
        payload[6] = UInt8((region.h >> 8) & 0xff)
        payload[7] = UInt8(region.h & 0xff)
        payload.replaceSubrange(8..<payload.count, with: jpeg)
        return payload
    }

    private func remember(_ pixels: UnsafePointer<UInt8>, width: Int, height: Int, bytesPerRow: Int) {
        let count = height * bytesPerRow
        if previousBytes.count != count {
            previousBytes = [UInt8](repeating: 0, count: count)
        }
        previousBytes.withUnsafeMutableBytes { raw in
            if let destination = raw.baseAddress {
                memcpy(destination, pixels, count)
            }
        }
        previousWidth = width
        previousHeight = height
        previousBytesPerRow = bytesPerRow
    }

    private func postTouch(action: Int, x: Int, y: Int) {
        let displayID = displays.displayID
        if displayID == 0 {
            return
        }
        _ = owner?.wantsAccessibilityPrompt()
        let bounds = CGDisplayBounds(displayID)
        let point = CGPoint(x: bounds.origin.x + CGFloat(x), y: bounds.origin.y + CGFloat(y))
        let source = CGEventSource(stateID: .hidSystemState)
        source?.localEventsSuppressionInterval = 0
        CGWarpMouseCursorPosition(point)
        let kind: CGEventType
        let button = CGMouseButton.left
        if action == Wire.touchDown {
            kind = .leftMouseDown
            buttonDown = true
        } else if action == Wire.touchUp {
            kind = .leftMouseUp
            buttonDown = false
        } else if buttonDown {
            kind = .leftMouseDragged
        } else {
            kind = .mouseMoved
        }
        guard let event = CGEvent(mouseEventSource: source, mouseType: kind, mouseCursorPosition: point, mouseButton: button) else {
            return
        }
        if kind == .leftMouseDragged || kind == .mouseMoved {
            event.type = kind
        }
        event.post(tap: .cghidEventTap)
    }
}

private struct PatchRegion {
    var x: Int
    var y: Int
    var w: Int
    var h: Int
}

@main
struct PadScreenMain {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        app.run()
    }
}
