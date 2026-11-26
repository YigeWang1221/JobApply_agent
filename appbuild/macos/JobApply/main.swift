// JobApply macOS 外壳：一个 WKWebView 窗口，负责启动 / 重启 / 关闭自带的 Java 后端，
// 并给网页一个小桥（选文件夹、重启后端、在 Finder 中显示）。见 plan/coding/03-features/C017-macos-app.md。
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
    private var shellAlive: Pipe?
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
        // 内存上限与省内存参数（plan/coding/04-refinement/C029-jvm-memory.md）
        p.arguments = ["-Xmx384m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=64m",
                       "-jar", resources.appendingPathComponent("jobapply.jar").path,
                       "--spring.profiles.active=macapp"]
        var env = ProcessInfo.processInfo.environment
        env["JOBAPPLY_RESOURCES"] = resources.path
        p.environment = env
        p.currentDirectoryURL = FileManager.default.homeDirectoryForCurrentUser
        p.standardOutput = log
        // 后端读这个管道；外壳不管怎么退出（包括强制退出），写端都会被系统关掉，后端读到结尾就自己退出
        let shellAlive = Pipe()
        p.standardInput = shellAlive
        self.shellAlive = shellAlive
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
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        window.title = "JobApply"
        window.titleVisibility = .hidden
        window.titlebarAppearsTransparent = true
        window.minSize = NSSize(width: 940, height: 640)
        window.backgroundColor = NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return isDark
                ? NSColor(calibratedRed: 0.122, green: 0.122, blue: 0.114, alpha: 1.0)
                : NSColor(calibratedRed: 0.980, green: 0.976, blue: 0.961, alpha: 1.0)
        }
        webView.setValue(false, forKey: "drawsBackground")
        window.contentView = webView
        if !window.setFrameUsingName("JobApplyMain") {
            window.center()
        }
        window.setFrameAutosaveName("JobApplyMain")
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        backend.onUnexpectedExit = { [weak self] code in
            self?.showError("后端意外退出（退出码 \(code)）。")
        }
        launchBackend()
    }

    // 启动后端并在就绪后加载界面；失败时窗口里显示原因和日志路径。
    func launchBackend() {
        showMessage("正在启动 JobApply…", detail: "本地服务准备就绪后将自动进入工作区", isError: false)
        do { try backend.start() } catch let e as ShellError { return showError(e.message) }
        catch { return showError("无法启动后端：\(error.localizedDescription)") }
        backend.waitReady { [weak self] ok in
            guard let self = self else { return }
            if ok { self.loadUI() }
            else if self.backend.process != nil { self.showError("后端在规定时间内没有就绪。") }
            // 进程已退出的情况由 onUnexpectedExit 显示。
        }
    }

    func restartBackend() {
        showMessage("正在重启后端…", detail: "正在应用新配置并重新连接本地服务", isError: false)
        DispatchQueue.global().async {
            self.backend.stop()
            DispatchQueue.main.async { self.launchBackend() }
        }
    }

    func showError(_ reason: String) {
        showMessage("JobApply 没能启动",
                    detail: "\(escape(reason))<div class=\"log\">日志：<code>\(escape(backend.logURL.path))</code></div>",
                    isError: true)
    }

    func showMessage(_ title: String, detail: String, isError: Bool = false) {
        let indicator = isError
            ? """
              <div class="badge err" aria-hidden="true">!</div>
              """
            : """
              <div class="badge ok" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21c-4.5-3.2-7-7.2-7-11.2A7 7 0 0 1 19 9.8c0 4-2.5 8-7 11.2z"/><path d="M12 10v7"/></svg>
              </div>
              <div class="spinner" aria-hidden="true"></div>
              """
        let actions = isError
            ? """
              <div class="actions">
                <button class="primary" onclick="window.webkit?.messageHandlers?.jobapply?.postMessage({type:'retryBackend'})">重试启动</button>
                <button onclick="window.webkit?.messageHandlers?.jobapply?.postMessage({type:'openLog'})">在 Finder 中打开日志</button>
              </div>
              """
            : ""
        let html = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><style>
        :root {
          color-scheme: light dark;
          --bg: #faf9f5; --surface: #ffffff; --ink: #1c1b18; --muted: #6e6b63;
          --line: rgba(28, 27, 24, 0.1); --accent: #2d6a4f; --accent-ink: #ffffff;
          --wash: rgba(45, 106, 79, 0.1); --crit: #c8433e; --crit-wash: rgba(200, 67, 62, 0.12);
          --shadow: 0 8px 28px rgba(28, 27, 24, 0.08);
        }
        @media (prefers-color-scheme: dark) {
          :root {
            --bg: #1f1f1d; --surface: #262624; --ink: #f2f1ec; --muted: #98968d;
            --line: rgba(255, 255, 255, 0.1); --accent: #52b788; --accent-ink: #0f2419;
            --wash: rgba(82, 183, 136, 0.16); --crit: #ef746e; --crit-wash: rgba(239, 116, 110, 0.15);
            --shadow: 0 10px 32px rgba(0, 0, 0, 0.36);
          }
        }
        * { box-sizing: border-box; }
        html, body { height: 100%; margin: 0; background: var(--bg); color: var(--ink);
          font-family: -apple-system, "PingFang SC", sans-serif; -webkit-user-select: none; }
        .drag { position: fixed; top: 0; left: 0; right: 0; height: 42px; }
        .wrap { min-height: 100%; display: grid; place-items: center; padding: 32px; }
        .card { width: min(460px, 100%); background: var(--surface); border: 1px solid var(--line);
          border-radius: 14px; padding: 28px 28px 24px; box-shadow: var(--shadow); text-align: center; }
        .badge { width: 42px; height: 42px; border-radius: 11px; margin: 0 auto 14px;
          display: inline-flex; align-items: center; justify-content: center; font-weight: 700; font-size: 18px; }
        .badge.ok { background: var(--wash); color: var(--accent); }
        .badge.err { background: var(--crit-wash); color: var(--crit); }
        .spinner { width: 18px; height: 18px; margin: 0 auto 14px; border-radius: 50%;
          border: 2px solid var(--line); border-top-color: var(--accent); animation: spin 0.8s linear infinite; }
        @keyframes spin { to { transform: rotate(360deg); } }
        h2 { margin: 0 0 8px; font-size: 17px; font-weight: 600; letter-spacing: -0.01em; }
        .detail { margin: 0; font-size: 13.5px; line-height: 1.6; color: var(--muted); }
        .log { margin-top: 12px; padding: 8px 10px; border-radius: 8px; background: var(--bg);
          border: 1px solid var(--line); font-size: 12px; text-align: left; word-break: break-all; }
        code { font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 11.5px; color: var(--ink); -webkit-user-select: text; }
        .actions { display: flex; justify-content: center; gap: 10px; margin-top: 18px; flex-wrap: wrap; }
        button { font: inherit; font-size: 13px; padding: 7px 14px; border-radius: 7px; cursor: pointer;
          border: 1px solid var(--line); background: var(--surface); color: var(--ink); }
        button.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); font-weight: 500; }
        </style></head><body>
        <div class="drag" onmousedown="window.webkit?.messageHandlers?.jobapply?.postMessage({type:'startDrag'})"></div>
        <main class="wrap"><section class="card">
          \(indicator)
          <h2>\(escape(title))</h2>
          <div class="detail">\(detail)</div>
          \(actions)
        </section></main>
        </body></html>
        """
        webView.loadHTMLString(html, baseURL: nil)
    }

    func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
    }

    // 网页小桥：pickFolder / restartBackend / reveal / startDrag / setTheme / retryBackend / openLog。
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
        case "retryBackend":
            launchBackend()
        case "openLog":
            NSWorkspace.shared.activateFileViewerSelecting([backend.logURL])
        case "reveal":
            if let path = msg["path"] as? String {
                NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
            }
        case "startDrag":
            if let event = NSApp.currentEvent {
                window.performDrag(with: event)
            }
        case "setTheme":
            let mode = msg["mode"] as? String ?? "system"
            switch mode {
            case "light":
                window.appearance = NSAppearance(named: .aqua)
            case "dark":
                window.appearance = NSAppearance(named: .darkAqua)
            default:
                window.appearance = nil
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

    // window.alert / confirm / prompt：WKWebView 不实现这些就当「取消」，删除确认等会静默失败。
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let alert = NSAlert()
        alert.messageText = message
        alert.addButton(withTitle: "好")
        alert.beginSheetModal(for: window) { _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert()
        alert.messageText = message
        alert.addButton(withTitle: "确定")
        alert.addButton(withTitle: "取消")
        alert.beginSheetModal(for: window) { resp in completionHandler(resp == .alertFirstButtonReturn) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String,
                 defaultText: String?, initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (String?) -> Void) {
        let alert = NSAlert()
        alert.messageText = prompt
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 280, height: 24))
        field.stringValue = defaultText ?? ""
        alert.accessoryView = field
        alert.addButton(withTitle: "确定")
        alert.addButton(withTitle: "取消")
        alert.window.initialFirstResponder = field
        alert.beginSheetModal(for: window) { resp in
            completionHandler(resp == .alertFirstButtonReturn ? field.stringValue : nil)
        }
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
        appMenu.addItem(withTitle: "关于 JobApply", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "设置…", action: #selector(openSettings), keyEquivalent: ",")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "隐藏 JobApply", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(withTitle: "隐藏其他", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
            .keyEquivalentModifierMask = [.command, .option]
        appMenu.addItem(withTitle: "全部显示", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: "")
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

        let viewItem = NSMenuItem(); main.addItem(viewItem)
        let view = NSMenu(title: "显示")
        view.addItem(withTitle: "重新加载", action: #selector(reload), keyEquivalent: "r")
        view.addItem(.separator())
        view.addItem(withTitle: "放大", action: #selector(zoomIn), keyEquivalent: "=")
        view.addItem(withTitle: "缩小", action: #selector(zoomOut), keyEquivalent: "-")
        view.addItem(withTitle: "实际大小", action: #selector(zoomReset), keyEquivalent: "0")
        view.addItem(.separator())
        let full = view.addItem(withTitle: "进入全屏", action: #selector(NSWindow.toggleFullScreen(_:)), keyEquivalent: "f")
        full.keyEquivalentModifierMask = [.command, .control]
        viewItem.submenu = view

        let navItem = NSMenuItem(); main.addItem(navItem)
        let nav = NSMenu(title: "前往")
        nav.addItem(withTitle: "返回", action: #selector(goBack), keyEquivalent: "[")
        navItem.submenu = nav

        let winItem = NSMenuItem(); main.addItem(winItem)
        let win = NSMenu(title: "窗口")
        win.addItem(withTitle: "关闭", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        win.addItem(withTitle: "最小化", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        winItem.submenu = win
        NSApp.mainMenu = main
    }

    @objc func openSettings() {
        webView.evaluateJavaScript("window.location.hash = '#/settings/models'")
    }

    @objc func goBack() {
        webView.evaluateJavaScript("window.__jobapplyShell && window.__jobapplyShell.back && window.__jobapplyShell.back()")
    }

    @objc func zoomIn() {
        webView.pageZoom = min(2.0, webView.pageZoom + 0.1)
    }

    @objc func zoomOut() {
        webView.pageZoom = max(0.6, webView.pageZoom - 0.1)
    }

    @objc func zoomReset() {
        webView.pageZoom = 1.0
    }

    @objc func reload() {
        if let url = webView.url, url.host == "127.0.0.1" || url.host == "localhost" {
            webView.load(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData))
        } else {
            loadUI()
        }
    }

    // 不用 WKWebView 磁盘缓存里的旧 index.html：装了新版也会先显示上一版界面（C024-fix 第 12 节）
    func loadUI() { webView.load(URLRequest(url: backendURL, cachePolicy: .reloadIgnoringLocalCacheData)) }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
