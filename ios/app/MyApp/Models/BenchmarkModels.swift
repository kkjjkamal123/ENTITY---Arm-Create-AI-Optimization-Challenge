import Foundation

enum Arm: String, Codable, CaseIterable, Identifiable {
    case naive = "Naive"
    case threadsOnly = "Threads Only"
    case auto = "Entity Auto"
    case efficiency = "Efficiency"

    var id: String { rawValue }

    /// Worker count relative to the device's active processor count.
    func workerCount(activeProcessors: Int) -> Int {
        switch self {
        case .naive: return activeProcessors
        case .threadsOnly, .auto, .efficiency: return max(2, activeProcessors / 2)
        }
    }

    /// iOS has no public core-pinning API, so QoS class is the closest lever
    /// available to bias the scheduler toward performance or efficiency cores.
    var qualityOfService: QualityOfService {
        switch self {
        case .naive: return .default
        case .threadsOnly: return .default
        case .auto: return .userInteractive
        case .efficiency: return .background
        }
    }

    var subtitle: String {
        switch self {
        case .naive: return "Default thread count, no scheduling hint"
        case .threadsOnly: return "Reduced thread count, default QoS"
        case .auto: return "Reduced thread count, high-priority QoS (biases P-cores)"
        case .efficiency: return "Reduced thread count, background QoS (biases E-cores)"
        }
    }
}

struct MetricSet: Codable, Hashable {
    var decodeTokensPerSecond: Double
    var promptTokensPerSecond: Double
    var timeToFirstTokenMs: Double
    var estimatedPowerWatts: Double
    var tokensPerWatt: Double
    var appCPUPercent: Double
    var thermalStateStart: String
    var thermalStatePeak: String
    var activeProcessorCount: Int
    var totalProcessorCount: Int
    var ramFootprintMB: Double

    static let zero = MetricSet(
        decodeTokensPerSecond: 0, promptTokensPerSecond: 0, timeToFirstTokenMs: 0,
        estimatedPowerWatts: 0, tokensPerWatt: 0, appCPUPercent: 0,
        thermalStateStart: "nominal", thermalStatePeak: "nominal",
        activeProcessorCount: 0, totalProcessorCount: 0, ramFootprintMB: 0
    )
}

struct ArmResult: Codable, Hashable, Identifiable {
    var id = UUID()
    var arm: Arm
    var metrics: MetricSet
    var stdDev: MetricSet?
    var sampleCount: Int
}

struct DeviceInfo: Codable, Hashable {
    var modelIdentifier: String
    var systemName: String
    var systemVersion: String
    var processorCount: Int
    var activeProcessorCount: Int
    var physicalMemoryGB: Double
    var batteryLevelAtStart: Float
    var batteryStateAtStart: String
    var lowPowerModeEnabled: Bool
    var availableMemoryMBAtStart: Double = 0
    var freeDiskSpaceGBAtStart: Double = 0
}

enum BenchmarkMode: String, Codable, CaseIterable, Identifiable {
    case ablation = "Ablation"
    case threadSweep = "Thread Sweep"
    case sustained = "Sustained"

    var id: String { rawValue }

    var subtitle: String {
        switch self {
        case .ablation: return "Naive vs threads-only vs auto vs efficiency"
        case .threadSweep: return "Decode throughput across worker counts"
        case .sustained: return "Long-running decode to watch thermal/perf drift"
        }
    }
}

struct SweepPoint: Codable, Hashable, Identifiable {
    var id = UUID()
    var workerCount: Int
    var decodeTokensPerSecond: Double
    var appCPUPercent: Double
    var thermalStateEnd: String
}

struct SustainedSample: Codable, Hashable, Identifiable {
    var id = UUID()
    var elapsedSeconds: Double
    var decodeTokensPerSecond: Double
    var thermalState: String
    var batteryLevel: Float
    var appCPUPercent: Double
}

struct BenchmarkRun: Codable, Hashable, Identifiable {
    var id = UUID()
    var date: Date
    var device: DeviceInfo
    var arms: [ArmResult]
    var numberOfRunsPerArm: Int
    var workloadDescription: String
    var appVersion: String
    var mode: BenchmarkMode = .ablation
    var modelProfileName: String = ModelProfile.defaultProfile.name
    var sweepPoints: [SweepPoint] = []
    var sustainedSamples: [SustainedSample] = []
    var sustainedArm: Arm? = nil

    var headlineArm: ArmResult? { arms.first(where: { $0.arm == .auto }) ?? arms.first }
    var baselineArm: ArmResult? { arms.first(where: { $0.arm == .naive }) }

    var decodeImprovementPercent: Double? {
        guard let baseline = baselineArm, let headline = headlineArm,
              baseline.metrics.decodeTokensPerSecond > 0 else { return nil }
        return (headline.metrics.decodeTokensPerSecond - baseline.metrics.decodeTokensPerSecond)
            / baseline.metrics.decodeTokensPerSecond * 100
    }
}
