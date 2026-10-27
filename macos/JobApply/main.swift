// JobApply macOS 外壳：一个 WKWebView 窗口，负责启动 / 重启 / 关闭自带的 Java 后端，
// 并给网页一个小桥（选文件夹、重启后端、在 Finder 中显示）。见 plan/coding/C017-macos-app.md。
import AppKit
import WebKit

let backendURL = URL(string: "http://127.0.0.1:8088/")!
let backendPort: UInt16 = 8088

// 后端进程：启动、等就绪、优雅关闭。
final class Backend {
    let resources = Bundle.main.resourceURL!
    let logURL = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Logs/JobApply/backend.log")
    private(set) var process: Process?
    private var stopping = false
    // 后端意外退出时回调（主线程），参数是退出码。
    var onUnexpectedExit: ((Int32) -> Void)?

    func start() throws {
        if Backend.portInUse(backendPort) {
            throw ShellError("端口 \(backendPort) 已被占用（可能开发版后端正在运行）。请先关掉它再打开 JobApply。")
        }
        try FileManager.default.createDirectory(at: logURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: logURL.path) {
            FileManager.default.createFile(atPath: logURL.path, contents: nil)
        }
        let log = try FileHandle(forWritingTo: logURL)
        log.seekToEndOfFile()
        log.write("\n=== JobApply 启动 \(Date()) ===\n".data(using: .utf8)!)

        let p = Process()
        p.executableURL = resources.appendingPathComponent("runtime/bin/java")
        p.arguments = ["-jar", resources.appendingPathComponent("jobapply.jar").path,
                       "--spring.profiles.active=macapp"]
        var env = ProcessInfo.processInfo.environment
        env["JOBAPPLY_RESOURCES"] = resources.path
        p.environment = env
        p.currentDirectoryURL = FileManager.default.homeDirectoryForCurrentUser
        p.standardOutput = log
        p.standardError = log
        p.terminationHandler = { [weak self] proc in
            DispatchQueue.main.async {
                guard let self = self, self.process === proc else { return }
                self.process = nil
                if !self.stopping { self.onUnexpectedExit?(proc.terminationStatus) }
            }
        }
        stopping = false
        try p.run()
        process = p
    }

    // 轮询直到 8088 能打开；进程先退出或超时则失败。
    func waitReady(timeout: TimeInterval = 120, done: @escaping (Bool) -> Void) {
        let deadline = Date().addingTimeInterval(timeout)
        func poll() {
            guard process != nil else { return done(false) }
            var req = URLRequest(url: backendURL)
            req.timeoutInterval = 2
            URLSession.shared.dataTask(with: req) { _, resp, _ in
                DispatchQueue.main.async {
                    if resp is HTTPURLResponse { return done(true) }
                    if Date() > deadline { return done(false) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: poll)
                }
            }.resume()
        }
        poll()
    }

    // SIGTERM，最多等约 10 秒，再 SIGKILL。阻塞调用线程。
    func stop() {
        guard let p = process, p.isRunning else { process = nil; return }
        stopping = true
        p.terminate()
        let deadline = Date().addingTimeInterval(10)
        while p.isRunning && Date() < deadline { usleep(100_000) }
        if p.isRunning { kill(p.processIdentifier, SIGKILL); p.waitUntilExit() }
        process = nil
    }

    static func portInUse(_ port: UInt16) -> Bool {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let r = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return r == 0
    }
}

struct ShellError: Error { let message: String; init(_ m: String) { message = m } }

final class AppDelegate: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKUIDelegate, WKNavigationDelegate {
    let backend = Backend()
    var window: NSWindow!
    var webView: WKWebView!

    func applicationDidFinishLaunching(_ note: Notification) {
        buildMenu()
        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "jobapply")
        webView = WKWebView(frame: .zero, configuration: config)
        webView.uiDelegate = self
        webView.navigationDelegate = self
        if #available(macOS 13.3, *) { webView.isInspectable = true }

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 820),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "JobApply"
        window.contentView = webView
        window.setFrameAutosaveName("JobApplyMain")
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        backend.onUnexpectedExit = { [weak self] code in
            self?.showError("后端意外退出（退出码 \(code)）。")
        }
        launchBackend()
    }

    // 启动后端并在就绪后加载界面；失败时窗口里显示原因和日志路径。
    func launchBackend() {
        showMessage("正在启动 JobApply…", detail: "")
        do { try backend.start() } catch let e as ShellError { return showError(e.message) }
        catch { return showError("无法启动后端：\(error.localizedDescription)") }
        backend.waitReady { [weak self] ok in
            guard let self = self else { return }
            if ok { self.webView.load(URLRequest(url: backendURL)) }
            else if self.backend.process != nil { self.showError("后端在规定时间内没有就绪。") }
            // 进程已退出的情况由 onUnexpectedExit 显示。
        }
    }

    func restartBackend() {
        showMessage("正在重启后端…", detail: "")
        DispatchQueue.global().async {
            self.backend.stop()
            DispatchQueue.main.async { self.launchBackend() }
        }
    }

    func showError(_ reason: String) {
        showMessage("JobApply 没能启动", detail: "\(reason)<br><br>日志：<code>\(escape(backend.logURL.path))</code>")
    }

    func showMessage(_ title: String, detail: String) {
        let html = """
        <html><body style="font-family:-apple-system;padding:48px;color:#222">
        <h2>\(escape(title))</h2><p style="line-height:1.6">\(detail)</p></body></html>
        """
        webView.loadHTMLString(html, baseURL: nil)
    }

    func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
    }

    // 网页小桥：pickFolder / restartBackend / reveal。
    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let msg = message.body as? [String: Any], let type = msg["type"] as? String else { return }
        switch type {
        case "pickFolder":
            let id = msg["id"].map { "\($0)" } ?? ""
            let panel = NSOpenPanel()
            panel.canChooseDirectories = true
            panel.canChooseFiles = false
            panel.canCreateDirectories = true
            panel.allowsMultipleSelection = false
            panel.beginSheetModal(for: window) { [weak self] resp in
                let path = resp == .OK ? panel.url?.path : nil
                self?.resolve(id: id, path: path)
            }
        case "restartBackend":
            restartBackend()
        case "reveal":
            if let path = msg["path"] as? String {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            }
        default:
            break
        }
    }

    func resolve(id: String, path: String?) {
        let json = { (v: Any) -> String in
            let d = try! JSONSerialization.data(withJSONObject: [v], options: [.fragmentsAllowed])
            return String(String(data: d, encoding: .utf8)!.dropFirst().dropLast())
        }
        let arg = path.map { json($0) } ?? "null"
        webView.evaluateJavaScript("window.__jobapplyShell && window.__jobapplyShell.resolve(\(json(id)), \(arg))")
    }

    // <input type=file>
    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = parameters.allowsDirectories
        panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.beginSheetModal(for: window) { resp in completionHandler(resp == .OK ? panel.urls : nil) }
    }

    // target=_blank / window.open → 系统默认浏览器。
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url { NSWorkspace.shared.open(url) }
        return nil
    }

    // 界面之外的链接也交给系统浏览器，窗口里只留本机后端。
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, action.navigationType == .linkActivated,
           url.host != "127.0.0.1" && url.host != "localhost" {
            NSWorkspace.shared.open(url)
            return decisionHandler(.cancel)
        }
        decisionHandler(.allow)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ app: NSApplication) -> Bool { true }

    func applicationWillTerminate(_ note: Notification) { backend.stop() }

    func buildMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem(); main.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "隐藏 JobApply", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "退出 JobApply", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu

        let editItem = NSMenuItem(); main.addItem(editItem)
        let edit = NSMenu(title: "编辑")
        edit.addItem(withTitle: "撤销", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "重做", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "拷贝", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit

        let winItem = NSMenuItem(); main.addItem(winItem)
        let win = NSMenu(title: "窗口")
        win.addItem(withTitle: "关闭", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        win.addItem(withTitle: "最小化", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        win.addItem(withTitle: "重新加载", action: #selector(reload), keyEquivalent: "r")
        winItem.submenu = win
        NSApp.mainMenu = main
    }

    @objc func reload() { webView.load(URLRequest(url: backendURL)) }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
