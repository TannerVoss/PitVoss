import Foundation
import CoreBluetooth

final class BluetoothRPC: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    var event: (String,String) -> Void = { _,_ in }
    private var central: CBCentralManager!
    private var devices: [UUID:CBPeripheral] = [:]
    private var selected: CBPeripheral?
    private var scanRequested = false
    private var scanning = false
    private var scanToken = UUID()
    private var connectionToken = UUID()
    private var callbacks = 0
    private let serviceID = CBUUID(string:"5f6d4f53-5f52-5043-5f53-56435f49445f")
    private let dataID = CBUUID(string:"5f6d4f53-5f52-5043-5f64-6174615f5f5f")
    private let txID = CBUUID(string:"5f6d4f53-5f52-5043-5f74-785f63746c5f")
    private let rxID = CBUUID(string:"5f6d4f53-5f52-5043-5f72-785f63746c5f")
    private let debugService = CBUUID(string:"5f6d4f53-5f44-4247-5f53-56435f49445f")
    private let debugID = CBUUID(string:"306d4f53-5f44-4247-5f6c-6f675f5f5f30")
    private var dataChar: CBCharacteristic?, tx: CBCharacteristic?, rx: CBCharacteristic?
    private var ready = false
    private var writing = false, reading = false
    private var debugBuffer = ""
    private var payload = Data(), reply = Data()
    private var offset = 0, expected = 0, requestID = 900
    private var transaction = UUID()
    private var completion: ((Result<[String:Any],Error>) -> Void)?
    private var pendingProvision: (String,String)?
    private var provisioning = false
    private var saving = false
    private func setup(_ kind: String,_ text: String) { event("setup_"+kind,text) }
    func scan() {
        scanRequested = true
        if central == nil { central = CBCentralManager(delegate:self,queue:.main); return }
        startScanIfReady()
    }
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { if scanRequested { startScanIfReady() } }
        else if central.state != .unknown && central.state != .resetting {
            stopScan(); fail("Bluetooth unavailable. Enable Bluetooth and allow access in iPhone Settings.")
            event("error","Bluetooth unavailable. Enable Bluetooth and allow access in iPhone Settings.")
        }
    }
    private func startScanIfReady() {
        guard central.state == .poweredOn else { return }
        stopScan(); scanRequested = false; devices.removeAll(); callbacks = 0; scanning = true
        let token = UUID(); scanToken = token
        central.scanForPeripherals(withServices:nil,options:[CBCentralManagerScanOptionAllowDuplicatesKey:true])
        event("started","12")
        DispatchQueue.main.asyncAfter(deadline:.now()+12) { [weak self] in if self?.scanToken == token { self?.stopScan() } }
    }
    func stopScan() {
        scanRequested = false; central?.stopScan(); scanToken = UUID()
        if scanning { scanning = false; event("finished",json(["devices":devices.count,"callbacks":callbacks,"parseErrors":0])) }
    }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String:Any], rssi RSSI: NSNumber) {
        guard scanning else { return }; callbacks += 1; devices[peripheral.identifier] = peripheral
        event("device",json(["address":peripheral.identifier.uuidString,"name":advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "","rssi":RSSI,"connectable":advertisementData[CBAdvertisementDataIsConnectable] as? Bool ?? true,"services":(advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map { $0.uuidString }]))
    }
    func select(_ address: String) {
        guard let uuid = UUID(uuidString:address), let device = devices[uuid], central?.state == .poweredOn else { setup("error","Scan again and select a nearby grill. iOS uses device identifiers rather than MAC addresses."); return }
        disconnect(); stopScan(); selected = device; device.delegate = self
        setup("ble_link","connecting"); central.connect(device)
        let token = connectionToken
        DispatchQueue.main.asyncAfter(deadline:.now()+25) { [weak self] in
            guard let self = self, self.connectionToken == token, !self.ready else { return }
            self.fail("Bluetooth setup timed out"); self.disconnect()
        }
    }
    func disconnect() {
        connectionToken = UUID(); ready = false; pendingProvision = nil; debugBuffer = ""; dataChar = nil; tx = nil; rx = nil
        let old = selected; selected = nil
        fail("Bluetooth disconnected")
        if let old = old { central?.cancelPeripheralConnection(old) }
        setup("ble_link","disconnected")
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === selected else { return }; setup("ble_link","connected")
        peripheral.discoverServices([serviceID,debugService])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { if peripheral === selected { fail("Bluetooth connection failed"); disconnect() } }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) { if peripheral === selected { fail(saving ? "Save outcome unknown; controller may have rebooted. Check its address before retrying." : "Bluetooth connection closed"); disconnect() } }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === selected else { return }
        guard error == nil, let services = peripheral.services, services.contains(where:{$0.uuid == serviceID}) else { fail("Mongoose OS RPC service unavailable"); return }
        for service in services { peripheral.discoverCharacteristics(nil,for:service) }
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === selected, error == nil else { return }
        if service.uuid == debugService, let debug = service.characteristics?.first(where:{$0.uuid == debugID}), debug.properties.contains(.notify) { peripheral.setNotifyValue(true,for:debug) }
        guard service.uuid == serviceID else { return }
        for c in service.characteristics ?? [] { if c.uuid == dataID { dataChar = c }; if c.uuid == txID { tx = c }; if c.uuid == rxID { rx = c } }
        guard let d = dataChar, let t = tx, let r = rx, d.properties.contains(.read), d.properties.contains(.write), t.properties.contains(.write), r.properties.contains(.read) else { fail("Required RPC characteristics unavailable"); return }
        if r.properties.contains(.notify) || r.properties.contains(.indicate) { peripheral.setNotifyValue(true,for:r) }
        else { becameReady() }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === selected else { return }
        if characteristic.uuid == rxID { if error == nil { becameReady() } else { fail("RPC notification subscription failed") } }
    }
    private func becameReady() {
        guard !ready else { return }; ready = true
        if let credentials = pendingProvision { pendingProvision = nil; provision(credentials.0,credentials.1); return }
        testState(lookupAfter:true)
    }
    func testState(lookupAfter: Bool = false) {
        request("GetState",[:]) { [weak self] result in
            guard let self = self else { return }
            switch result { case .success(let obj): self.setup("ble_state",self.json(obj)); case .failure: self.setup("ble_state_error","BLE GetState unavailable") }
            if lookupAfter { self.lookup() }
        }
    }
    private func lookup() {
        request("GetCurrentIP",[:]) { [weak self] result in
            guard let self = self else { return }
            if case .success(let obj) = result {
                let text = self.json(obj)
                let regex = try! NSRegularExpression(pattern:"(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
                for match in regex.matches(in:text,range:NSRange(text.startIndex...,in:text)) {
                    if let range = Range(match.range,in:text), NetworkRPC.allowed(String(text[range])) { self.setup("ip",String(text[range])); return }
                }
            }
            self.setup("error","No local address returned. Enter the smoker’s IP manually.")
        }
    }
    func provisionFor(_ address: String,_ ssid: String,_ password: String) {
        guard !provisioning, completion == nil else { setup("wifi_error","Bluetooth busy; wait and retry"); return }
        if selected?.identifier.uuidString == address && ready { provision(ssid,password) }
        else { select(address); if selected != nil { pendingProvision = (ssid,password) } }
    }
    func provision(_ ssid: String,_ password: String) {
        guard !ssid.isEmpty, !password.isEmpty, !provisioning else { setup("wifi_error","Enter a network name and password; wait for any current setup."); return }
        provisioning = true; setup("wifi_status","Sending Wi-Fi settings over Bluetooth…")
        request("Config.Set",["config":["wifi":["sta":["enable":true,"ssid":ssid,"pass":password]]]]) { [weak self] result in
            guard let self = self else { return }
            guard case .success = result else { self.provisioning = false; self.setup("wifi_error","Settings not acknowledged. Details suppressed to protect credentials."); return }
            self.saving = true
            self.request("Config.Save",["reboot":true]) { [weak self] result in
                guard let self = self else { return }; self.provisioning = false; self.saving = false
                if case .success = result { self.setup("wifi_saved","Configuration saved. Wait for the controller to join Wi-Fi.") }
                else { self.setup("wifi_error","Save outcome unknown; controller may have rebooted. Check its address before retrying.") }
            }
        }
    }
    private func request(_ method: String,_ params: [String:Any],_ done: @escaping (Result<[String:Any],Error>)->Void) {
        guard ready, let selected = selected, let tx = tx, completion == nil else { done(.failure(PitError(message:"Bluetooth not ready or busy"))); return }
        requestID += 1; writing = true; reading = false; expected = 0; offset = 0; reply.removeAll(); completion = done
        payload = (try? JSONSerialization.data(withJSONObject:["id":requestID,"method":method,"params":params])) ?? Data()
        let token = UUID(); transaction = token
        let n = UInt32(payload.count)
        selected.writeValue(Data([UInt8((n>>24)&255),UInt8((n>>16)&255),UInt8((n>>8)&255),UInt8(n&255)]),for:tx,type:.withResponse)
        DispatchQueue.main.asyncAfter(deadline:.now()+20) { [weak self] in
            guard let self = self, self.transaction == token, self.completion != nil else { return }
            self.fail("BLE RPC timed out"); self.disconnect()
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === selected, completion != nil else { return }
        guard error == nil, let dataChar = dataChar else { fail("Bluetooth write failed"); return }
        if offset < payload.count {
            let end = min(payload.count,offset + peripheral.maximumWriteValueLength(for:.withResponse))
            let bytes = payload.subdata(in:offset..<end); offset = end
            peripheral.writeValue(bytes,for:dataChar,type:.withResponse)
        } else { writing = false; payload.removeAll(); poll() }
    }
    private func poll() {
        let token = transaction
        DispatchQueue.main.asyncAfter(deadline:.now()+0.35) { [weak self] in
            guard let self = self, self.transaction == token, self.completion != nil, !self.writing, !self.reading, self.expected == 0, let p = self.selected, let rx = self.rx else { return }; self.reading = true; p.readValue(for:rx)
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === selected else { return }
        if characteristic.uuid == debugID {
            guard let data = characteristic.value, let text = String(data:data,encoding:.utf8) else { return }
            debugBuffer += text
            if debugBuffer.count > 4096 { debugBuffer = String(debugBuffer.suffix(2048)) }
            while let end = debugBuffer.firstIndex(of:"\n") {
                let line = String(debugBuffer[..<end]).trimmingCharacters(in:.whitespacesAndNewlines)
                debugBuffer.removeSubrange(...end)
                if line.hasPrefix("FE0B") || line.hasPrefix("FE0C") { setup("ble_frame",String(line.prefix(512))) }
            }
            // Firmware may deliver complete frames without a newline.
            let frame = debugBuffer.trimmingCharacters(in:.whitespacesAndNewlines)
            if (frame.hasPrefix("FE0B") && frame.count == 88) || (frame.hasPrefix("FE0C") && frame.count == 54) { setup("ble_frame",frame); debugBuffer = "" }
            return
        }
        guard completion != nil else { return }
        guard error == nil, let value = characteristic.value else { fail("Bluetooth read failed"); return }
        if characteristic.uuid == rxID {
            reading = false
            guard !writing, expected == 0 else { return }
            guard value.count >= 4 else { poll(); return }
            expected = value.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
            if expected == 0 { poll(); return }
            guard expected <= 65536, let dataChar = dataChar else { fail("Invalid RPC reply length"); return }
            peripheral.readValue(for:dataChar)
        } else if characteristic.uuid == dataID, expected > 0 {
            guard !value.isEmpty else { fail("Empty RPC response"); return }
            reply.append(value.prefix(expected-reply.count))
            if reply.count < expected { peripheral.readValue(for:characteristic); return }
            let done = completion; completion = nil; transaction = UUID(); payload.removeAll()
            guard let obj = try? JSONSerialization.jsonObject(with:reply) as? [String:Any], obj["id"] as? Int == requestID else { reply.removeAll(); done?(.failure(PitError(message:"Invalid response identifier"))); return }
            reply.removeAll()
            if obj["result"] == nil || obj["error"] != nil { done?(.failure(PitError(message:"RPC not acknowledged"))) }
            else { done?(.success(obj)) }
        }
    }
    private func fail(_ text: String) { let done = completion; completion = nil; transaction = UUID(); writing = false; reading = false; payload.removeAll(); reply.removeAll(); done?(.failure(PitError(message:text))) }
    private func json(_ obj: Any) -> String { String(data:(try? JSONSerialization.data(withJSONObject:obj)) ?? Data(),encoding:.utf8) ?? "{}" }
}
