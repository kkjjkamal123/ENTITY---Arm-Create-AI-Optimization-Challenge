import Foundation
import Darwin
#if canImport(UIKit)
import UIKit
#endif

enum Telemetry {

    static func thermalStateDescription(_ state: ProcessInfo.ThermalState = ProcessInfo.processInfo.thermalState) -> String {
        switch state {
        case .nominal: return "nominal"
        case .fair: return "fair"
        case .serious: return "serious"
        case .critical: return "critical"
        @unknown default: return "unknown"
        }
    }

    static func thermalSeverity(_ state: ProcessInfo.ThermalState) -> Int {
        switch state {
        case .nominal: return 0
        case .fair: return 1
        case .serious: return 2
        case .critical: return 3
        @unknown default: return 0
        }
    }

    static func thermalSeverity(from description: String) -> Int {
        switch description {
        case "fair": return 1
        case "serious": return 2
        case "critical": return 3
        default: return 0
        }
    }

    static func batteryStateDescription() -> String {
        #if canImport(UIKit)
        switch UIDevice.current.batteryState {
        case .charging: return "charging"
        case .full: return "full"
        case .unplugged: return "unplugged"
        case .unknown: return "unknown"
        @unknown default: return "unknown"
        }
        #else
        return "unavailable"
        #endif
    }

    static func batteryLevel() -> Float {
        #if canImport(UIKit)
        return UIDevice.current.batteryLevel
        #else
        return -1
        #endif
    }

    static func enableBatteryMonitoring() {
        #if canImport(UIKit)
        UIDevice.current.isBatteryMonitoringEnabled = true
        #endif
    }

    static func modelIdentifier() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let mirror = Mirror(reflecting: systemInfo.machine)
        let identifier = mirror.children.reduce(into: "") { result, element in
            guard let value = element.value as? Int8, value != 0 else { return }
            result += String(UnicodeScalar(UInt8(value)))
        }
        return identifier
    }

    /// Resident memory footprint of this process, via the Mach task API.
    /// This is a real measurement (not simulated) but reflects the app's own
    /// footprint, not a full-device figure — iOS does not expose per-process
    /// memory for other apps or a system-wide "RAM floor" to sandboxed apps.
    static func residentMemoryMB() -> Double {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) { infoPtr -> kern_return_t in
            infoPtr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { intPtr in
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), intPtr, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        return Double(info.resident_size) / (1024 * 1024)
    }

    /// Approximate memory this app could allocate before the system would
    /// likely start reclaiming/terminating it. Public since iOS 13; the
    /// closest sanctioned analogue to Android's ActivityManager memory info,
    /// used the same way ENTITY treats its "RAM floor" model-fit check.
    static func availableMemoryMB() -> Double {
        Double(os_proc_available_memory()) / (1024 * 1024)
    }

    static func freeDiskSpaceGB() -> Double {
        guard let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first,
              let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let capacity = values.volumeAvailableCapacityForImportantUsage else { return 0 }
        return Double(capacity) / 1_073_741_824
    }

    static func currentDeviceInfo() -> DeviceInfo {
        enableBatteryMonitoring()
        let process = ProcessInfo.processInfo
        #if canImport(UIKit)
        let systemName = UIDevice.current.systemName
        let systemVersion = UIDevice.current.systemVersion
        #else
        let systemName = "iOS"
        let systemVersion = process.operatingSystemVersionString
        #endif
        return DeviceInfo(
            modelIdentifier: modelIdentifier(),
            systemName: systemName,
            systemVersion: systemVersion,
            processorCount: process.processorCount,
            activeProcessorCount: process.activeProcessorCount,
            physicalMemoryGB: Double(process.physicalMemory) / 1_073_741_824,
            batteryLevelAtStart: batteryLevel(),
            batteryStateAtStart: batteryStateDescription(),
            lowPowerModeEnabled: process.isLowPowerModeEnabled,
            availableMemoryMBAtStart: availableMemoryMB(),
            freeDiskSpaceGBAtStart: freeDiskSpaceGB()
        )
    }
}

/// Samples thermal state on an interval for the duration of a run and
/// remembers the worst state observed, since a run's peak matters more than
/// its instantaneous state at read time.
final class ThermalPeakTracker: @unchecked Sendable {
    private let lock = NSLock()
    private var worst: ProcessInfo.ThermalState
    private var timer: Timer?

    init() {
        worst = ProcessInfo.processInfo.thermalState
    }

    func start() {
        stop()
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.sample()
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func sample() {
        let current = ProcessInfo.processInfo.thermalState
        lock.lock()
        if Telemetry.thermalSeverity(current) > Telemetry.thermalSeverity(worst) {
            worst = current
        }
        lock.unlock()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    var peakState: ProcessInfo.ThermalState {
        lock.lock()
        defer { lock.unlock() }
        return worst
    }
}
