import UIKit
import WebKit
import UniformTypeIdentifiers

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey:Any]?) -> Bool {
        return true
    }
}
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene:windowScene)
        window.rootViewController = DashboardController()
        self.window = window
        window.makeKeyAndVisible()
    }
}
final class DashboardController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate, UIDocumentPickerDelegate {
    private var web: WKWebView!
    private let network = NetworkRPC()
    private let ble = BluetoothRPC()
    private var modelID = "PB2180LK", boardID = "PBG"
    private var discovering = false, controlling = false
    private var active = true
    private var catalog: [String:Any] = [:]
    private var safetyAcknowledged = false
    override func viewDidLoad() {
        super.viewDidLoad(); view.backgroundColor = .black
        let config = WKWebViewConfiguration(); config.userContentController.add(self,name:"pit")
        web = WKWebView(frame:.zero,configuration:config); web.navigationDelegate = self; web.uiDelegate = self
        web.isOpaque = false; web.backgroundColor = .black; web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web); NSLayoutConstraint.activate([web.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),web.bottomAnchor.constraint(equalTo:view.safeAreaLayoutGuide.bottomAnchor),web.leadingAnchor.constraint(equalTo:view.leadingAnchor),web.trailingAnchor.constraint(equalTo:view.trailingAnchor)])
        if let url = Bundle.main.url(forResource:"model-catalog",withExtension:"json",subdirectory:"Web"), let data = try? Data(contentsOf:url) { catalog = (try? JSONSerialization.jsonObject(with:data) as? [String:Any]) ?? [:] }
        ble.event = { [weak self] kind,text in self?.emit("receiveBle",[kind,text]) }
        NotificationCenter.default.addObserver(self,selector:#selector(background),name:UIApplication.didEnterBackgroundNotification,object:nil)
        NotificationCenter.default.addObserver(self,selector:#selector(foreground),name:UIApplication.willEnterForegroundNotification,object:nil)
        let url = Bundle.main.url(forResource:"index",withExtension:"html",subdirectory:"Web")!
        web.loadFileURL(url,allowingReadAccessTo:url.deletingLastPathComponent())
    }
    @objc private func background() { active = false; ble.stopScan(); ble.disconnect() }
    @objc private func foreground() { active = true; emit("monitoringResult",[false,"iOS live alerts require the app to stay open. Reconnect Bluetooth if needed."]) }
    private func emit(_ function: String,_ args: [Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject:args), let json = String(data:data,encoding:.utf8) else { return }
        DispatchQueue.main.async { [weak self] in self?.web.evaluateJavaScript("window.\(function) && window.\(function)(...\(json))",completionHandler:nil) }
    }
    private func toast(_ text: String) { emit("toast",[text,true]) }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, message.frameInfo.request.url?.isFileURL == true, let body = message.body as? [String:Any], let method = body["method"] as? String, let args = body["args"] as? [Any] else { return }
        func s(_ i: Int) -> String { i < args.count ? args[i] as? String ?? "" : "" }
        func n(_ i: Int) -> Int { i < args.count ? args[i] as? Int ?? 0 : 0 }
        func b(_ i: Int) -> Bool { i < args.count ? args[i] as? Bool ?? false : false }
        switch method {
        case "setTransportMode": if ["automatic","wifi","bluetooth"].contains(s(0)) { network.mode = s(0); if s(0) == "wifi" { ble.disconnect() } }
        case "configureGrillProfile": modelID = String(s(0).prefix(100)); boardID = s(1)
        case "scanBle": ble.scan()
        case "selectBleGrill": ble.select(s(0))
        case "disconnectSelectedGrill": ble.disconnect()
        case "testBleState": ble.testState()
        case "provisionWifi": ble.provision(s(0),s(1))
        case "provisionWifiForGrill": ble.provisionFor(s(0),s(1),s(2))
        case "scanWifiNetworks": emit("receiveWifiNetworks",["[]","iPhone apps cannot list nearby Wi-Fi networks. Enter your 2.4 GHz network name manually."])
        case "configureMonitoring": emit("monitoringResult",[false,"iOS pauses live monitoring in the background. Keep the app open for live alerts."])
        case "acknowledgeSafety": safetyAcknowledged = true
        case "copyBleLog": UIPasteboard.general.string = String(s(0).suffix(300000)); toast("Log copied")
        case "exportBleLog": share(s(0),"Pit-Master-diagnostic.txt")
        case "exportLocalBackup": share(s(0),"Pit-Master-Controller-backup.json")
        case "shareFile": share(s(0),s(1))
        case "chooseLocalBackup": let picker = UIDocumentPickerViewController(forOpeningContentTypes:[.json],asCopy:true); picker.delegate = self; present(picker,animated:true)
        case "clearLocalData":
            safetyAcknowledged = false; ble.disconnect()
            WKWebsiteDataStore.default().removeData(ofTypes:WKWebsiteDataStore.allWebsiteDataTypes(),modifiedSince:.distantPast) { [weak self] in self?.web.reload() }
        case "discoverWifiGrill": discover()
        case "rpc":
            let ip = s(0), id = n(3)
            guard s(1) == "GetState" else { emit("receiveRpc",[id,"","Unsupported RPC"]); return }
            Task { do { let text = try await network.state(ip,id); emit("receiveRpc",[id,text,""]) } catch { emit("receiveRpc",[id,"",error.localizedDescription]) } }
        case "control", "setTemperature":
            let ip = s(0), action = method == "setTemperature" ? "setTemp" : s(1), value = method == "setTemperature" ? n(1) : n(2), unit = method == "setTemperature" ? b(2) : true, id = n(3)
            guard active, !controlling, safetyAcknowledged, network.mode != "bluetooth" else { emit("receiveControl",[id,action,"","Accept safety information, keep the app open, and wait for any current command."]); return }
            let model = (catalog["models"] as? [String:[String:Any]])?[modelID] ?? [:]
            let board = (catalog["boards"] as? [String:[String:Any]])?[boardID] ?? [:]
            controlling = true
            Task {
                defer { controlling = false }
                do {
                    if action == "setTemp" {
                        guard (model[unit ? "setpointsF":"setpointsC"] as? [Int] ?? []).contains(value) else { throw PitError(message:"Unsupported temperature for selected model") }
                        let raw = try await network.state(ip,id)
                        let outer = try JSONSerialization.jsonObject(with:Data(raw.utf8)) as? [String:Any] ?? [:]
                        var state = outer["result"] as? [String:Any] ?? outer
                        if let error = outer["error"] as? [String:Any], let message = error["message"] as? String, let data = message.data(using:.utf8), let parsed = try? JSONSerialization.jsonObject(with:data) as? [String:Any] { state = parsed }
                        if let status = state["sc_11"] as? String, let temperatures = state["sc_12"] as? String {
                            func byte(_ frame: String,_ index: Int) -> Int? { let chars = Array(frame); guard index >= 0, chars.count >= index*2+2 else { return nil }; return Int(String(chars[(index*2)...(index*2+1)]),radix:16) }
                            guard let pi = board["powerIndex"] as? Int, let ui = board["unitIndex"] as? Int, let power = byte(status,pi), let fahrenheit = byte(temperatures,ui) else { throw PitError(message:"Waiting for fresh power and unit readings") }
                            state = ["moduleIsOn":power == 1,"isFahrenheit":fahrenheit == 1]
                        }
                        let powered = (state["moduleIsOn"] as? Bool == true) || (state["moduleIsOn"] as? Int == 1)
                        guard powered, let actualUnit = state["isFahrenheit"] as? Bool, actualUnit == unit else { throw PitError(message:"Grill power or temperature unit changed. Check the grill and retry.") }
                    }
                    guard active, network.mode != "bluetooth" else { throw PitError(message:"App left foreground or transport changed") }
                    let text = try await network.control(ip,action,value,unit,id,model,board)
                    emit("receiveControl",[id,action,text,""])
                } catch { emit("receiveControl",[id,action,"",error.localizedDescription]) }
            }
        case "prime": emit("receivePrime",[n(3),s(1),"","Remote priming is disabled in this release. Use the grill panel."])
        default: break
        }
    }
    private func discover() {
        guard !discovering, network.mode != "bluetooth", let address = NetworkRPC.wifiAddress(), NetworkRPC.allowed(address) else { emit("receiveBle",["setup_error","Connect to local Wi-Fi or enter the grill’s IP manually."]); return }
        discovering = true
        let prefix = address.split(separator:".").prefix(3).joined(separator:".")
        emit("receiveBle",["setup_status","Searching the local /24 Wi-Fi segment…"])
        Task {
            defer { discovering = false }
            // Bounded batches; stop when a controller is found or app leaves foreground.
            for start in stride(from:1,through:254,by:16) {
                guard active, network.mode != "bluetooth" else { return }
                let found = await withTaskGroup(of:String?.self,returning:String?.self) { group in
                    for host in start...min(254,start+15) {
                        let candidate = prefix+".\(host)"
                        if candidate == address { continue }
                        group.addTask { [network] in
                            guard let text = try? await network.state(candidate,1), ["grillTemp","smokerActTemp","grillSetTemp","sc_11","sc_12"].contains(where:{text.contains($0)}) else { return nil }; return candidate
                        }
                    }
                    for await result in group { if let result = result { group.cancelAll(); return result } }; return nil
                }
                if let found = found { emit("receiveBle",["setup_ip",found]); return }
            }
            emit("receiveBle",["setup_error","No controller found in the local /24 segment. Enter its IP manually or use Bluetooth discovery."])
        }
    }
    private func share(_ text: String,_ name: String) {
        guard text.utf8.count <= 8388608 else { toast("Export exceeds 8 MB"); return }
        let safeName = URL(fileURLWithPath:name).lastPathComponent
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do {
            try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
            let url = folder.appendingPathComponent(safeName.isEmpty ? "export.txt":safeName)
            try Data(text.utf8).write(to:url,options:.atomic)
            let sheet = UIActivityViewController(activityItems:[url],applicationActivities:nil)
            sheet.popoverPresentationController?.sourceView = view; sheet.popoverPresentationController?.sourceRect = CGRect(x:view.bounds.midX,y:view.bounds.midY,width:1,height:1)
            sheet.completionWithItemsHandler = { _,_,_,_ in try? FileManager.default.removeItem(at:folder) }
            present(sheet,animated:true)
        } catch { toast("Export could not be created") }
    }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }; let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let size = try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 8388609
            guard size <= 8388608 else { throw PitError(message:"Backup too large") }
            let data = try Data(contentsOf:url); _ = try JSONSerialization.jsonObject(with:data)
            emit("restoreAppBackup",[String(decoding:data,as:UTF8.self)])
        } catch { toast("Could not read this JSON backup") }
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy)->Void) {
        decisionHandler(navigationAction.request.url?.isFileURL == true ? .allow : .cancel)
    }
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ()->Void) {
        let alert = UIAlertController(title:"Pit Master Controller",message:message,preferredStyle:.alert); alert.addAction(UIAlertAction(title:"OK",style:.default) { _ in completionHandler() }); present(alert,animated:true)
    }
    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool)->Void) {
        let alert = UIAlertController(title:"Pit Master Controller",message:message,preferredStyle:.alert); alert.addAction(UIAlertAction(title:"Cancel",style:.cancel) { _ in completionHandler(false) }); alert.addAction(UIAlertAction(title:"Continue",style:.default) { _ in completionHandler(true) }); present(alert,animated:true)
    }
}
