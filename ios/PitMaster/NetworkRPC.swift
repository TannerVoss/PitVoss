import Foundation
import Darwin

struct PitError: LocalizedError { let message: String; var errorDescription: String? { message } }
final class NetworkRPC: NSObject, URLSessionTaskDelegate {
    private let lock = NSLock()
    private var storedMode = "automatic"
    var mode: String {
        get { lock.lock(); defer { lock.unlock() }; return storedMode }
        set { lock.lock(); storedMode = newValue; lock.unlock() }
    }
    private func cachedMethod(_ ip: String) -> String { lock.lock(); defer { lock.unlock() }; return stateMethods[ip] ?? "GetState" }
    private func remember(_ ip: String,_ method: String) { lock.lock(); stateMethods[ip] = method; lock.unlock() }
    private var stateMethods: [String: String] = [:]
    private var storedSession: URLSession?
    private var session: URLSession {
        lock.lock(); defer { lock.unlock() }
        if let existing = storedSession { return existing }
        let created = URLSession(configuration:.ephemeral,delegate:self,delegateQueue:nil)
        storedSession = created; return created
    }
    static func allowed(_ host: String) -> Bool {
        let p = host.split(separator: ".", omittingEmptySubsequences: false)
        let n = p.compactMap { Int($0) }
        guard n.count == 4, zip(p,n).allSatisfy({ String($0.1) == $0.0 && (0...255).contains($0.1) }) else { return false }
        return n[0] == 10 || (n[0] == 172 && (16...31).contains(n[1])) || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func send(_ ip: String, _ method: String, _ params: [String: Any], _ id: Int) async throws -> String {
        guard mode != "bluetooth", Self.allowed(ip), let url = URL(string: "http://\(ip)/rpc") else { throw PitError(message: "Use a private IPv4 address and enable Wi-Fi mode") }
        var request = URLRequest(url: url, timeoutInterval: 4)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["id":id,"method":method,"params":params])
        let (bytes,response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else { throw PitError(message: "Controller HTTP error") }
        var data = Data()
        for try await byte in bytes { guard data.count < 262144 else { throw PitError(message: "Response too large") }; data.append(byte) }
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw PitError(message: "Invalid RPC response") }
        if let replyID = obj["id"] as? Int, replyID != id { throw PitError(message: "Unexpected response identifier") }
        if let error = obj["error"] as? [String: Any] {
            let code = error["code"] as? Int ?? -1
            if [404,-32601].contains(code) { throw PitError(message: "missing-method") }
            let message = error["message"] as? String ?? "RPC error"
            if code != 0 || (method != "GetState" && ["failed","expected","bad request","wrong password"].contains(where: { message.lowercased().contains($0) })) { throw PitError(message: "Controller rejected RPC") }
        }
        return String(decoding: data, as: UTF8.self)
    }
    func state(_ ip: String, _ id: Int) async throws -> String {
        let first = cachedMethod(ip)
        do { let body = try await send(ip,first,[:],id); remember(ip,first); return body }
        catch let error as PitError where error.message == "missing-method" {
            let other = first == "GetState" ? "PB.GetState" : "GetState"
            let body = try await send(ip,other,[:],id); remember(ip,other); return body
        }
    }
    func control(_ ip: String, _ action: String, _ value: Int, _ fahrenheit: Bool, _ id: Int, _ model: [String: Any], _ board: [String: Any]) async throws -> String {
        let commands = board["commands"] as? [String:String] ?? [:]
        let temperatures = model["temperatureCommands"] as? [String:[String:String]] ?? [:]
        let frame = action == "setTemp" ? temperatures[fahrenheit ? "F" : "C"]?[String(value)] : commands[action]
        let method: String; let params: [String:Any]
        switch action {
        case "setTemp": method = "SetTemperature"; params = ["target":"grill","amount":value]
        case "powerOn", "powerOff": method = "SetPowerState"; params = ["desiredState":action == "powerOn" ? "On" : "Off"]
        case "lightOn", "lightOff":
            guard let frame = frame else { throw PitError(message: "No command definition") }
            let chars = Array(frame); var parts: [String] = []
            for i in stride(from: 0, to: chars.count-1, by: 2) { guard let n = Int(String(chars[i...i+1]),radix:16) else { throw PitError(message: "Invalid command") }; parts.append(String(format:"%03d",n)) }
            method = "SendGenericCommand"; params = ["command":parts.joined(separator:" ")]
        default: throw PitError(message: "Unsupported control")
        }
        if cachedMethod(ip) != "PB.GetState" {
            do { return try await send(ip,method,params,id) }
            catch let error as PitError where error.message == "missing-method" {}
        }
        guard let frame = frame else { throw PitError(message: "No command for selected model") }
        return try await send(ip,"PB.SendMCUCommand",["command":frame],id)
    }
    static func wifiAddress() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0 else { return nil }; defer { freeifaddrs(list) }
        var next = list
        while let item = next {
            defer { next = item.pointee.ifa_next }
            guard String(cString:item.pointee.ifa_name) == "en0", let addr = item.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var buffer = [CChar](repeating:0,count:Int(NI_MAXHOST))
            if getnameinfo(addr,socklen_t(addr.pointee.sa_len),&buffer,socklen_t(buffer.count),nil,0,NI_NUMERICHOST) == 0 { return String(cString:buffer) }
        }
        return nil
    }
}
