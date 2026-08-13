import Foundation
import Observation
import Accelerate

struct BenchmarkConfiguration {
    var mode: BenchmarkMode = .ablation
    var modelProfile: ModelProfile = .defaultProfile
    var arms: [Arm] = [.naive, .threadsOnly, .auto]
    var runsPerArm: Int = 3
    var promptTokenCount: Int = 512
    var decodeTokenCount: Int = 128
    var cooldownSeconds: Int = 5

    // Thread sweep
    var sweepWorkerCounts: [Int] = [2, 4, 6, 8]

    // Sustained
    var sustainedArm: Arm = .auto
    var sustainedDurationSeconds: Int = 120
    var sustainedSampleIntervalSeconds: Double = 5

    static let workloadDescription = "Synthetic CPU-bound proxy workload (PP\(512)/TG\(128)-shaped) using Accelerate/vForce, not real LLM inference"
}

@MainActor
@Observable
final class LiveRunState: Identifiable {
    let id = UUID()
    var isRunning = false
    var isAborted = false
    var mode: BenchmarkMode = .ablation
    var totalArms = 0
    var armIndex = 0
    var currentArm: Arm?
    var currentRepetition = 0
    var phase = "idle" // idle | prompt | decode | cooldown | done
    var progress: Double = 0
    var cooldownRemaining = 0
    var liveBatteryLevel: Float = Telemetry.batteryLevel()
    var liveBatteryState: String = Telemetry.batteryStateDescription()
    var liveThermalState: String = Telemetry.thermalStateDescription()
    var liveAppCPUPercent: Double = 0
    var completedArms: [ArmResult] = []
    var sweepPoints: [SweepPoint] = []
    var currentSweepWorkerCount: Int?
    var sustainedSamples: [SustainedSample] = []
    var sustainedElapsed: Double = 0
    var sustainedDuration: Double = 0

    func reset(mode: BenchmarkMode, totalArms: Int) {
        isRunning = true
        isAborted = false
        self.mode = mode
        self.totalArms = totalArms
        armIndex = 0
        currentArm = nil
        currentRepetition = 0
        phase = "idle"
        progress = 0
        cooldownRemaining = 0
        completedArms = []
        sweepPoints = []
        sustainedSamples = []
        sustainedElapsed = 0
        sustainedDuration = 0
    }

    func refreshLiveTelemetry() {
        liveBatteryLevel = Telemetry.batteryLevel()
        liveBatteryState = Telemetry.batteryStateDescription()
        liveThermalState = Telemetry.thermalStateDescription()
    }

    func abort() {
        isAborted = true
    }
}

enum BenchmarkEngine {

    static func run(configuration: BenchmarkConfiguration, state: LiveRunState) async -> BenchmarkRun {
        Telemetry.enableBatteryMonitoring()
        let deviceInfo = Telemetry.currentDeviceInfo()
        let baseIterations = calibrateIterationsPerToken()
        let iterationsPerToken = max(500, Int(Double(baseIterations) * configuration.modelProfile.computeWeight))

        switch configuration.mode {
        case .ablation:
            return await runAblation(configuration: configuration, iterationsPerToken: iterationsPerToken, deviceInfo: deviceInfo, state: state)
        case .threadSweep:
            return await runThreadSweep(configuration: configuration, iterationsPerToken: iterationsPerToken, deviceInfo: deviceInfo, state: state)
        case .sustained:
            return await runSustained(configuration: configuration, iterationsPerToken: iterationsPerToken, deviceInfo: deviceInfo, state: state)
        }
    }

    // MARK: - Ablation mode

    private static func runAblation(
        configuration: BenchmarkConfiguration,
        iterationsPerToken: Int,
        deviceInfo: DeviceInfo,
        state: LiveRunState
    ) async -> BenchmarkRun {
        await MainActor.run { state.reset(mode: .ablation, totalArms: configuration.arms.count) }

        var results: [ArmResult] = []

        for (index, arm) in configuration.arms.enumerated() {
            if await MainActor.run(body: { state.isAborted }) { break }

            await MainActor.run {
                state.armIndex = index
                state.currentArm = arm
                state.progress = 0
            }

            var samples: [MetricSet] = []
            for rep in 0..<configuration.runsPerArm {
                if await MainActor.run(body: { state.isAborted }) { break }
                await MainActor.run { state.currentRepetition = rep + 1 }
                let sample = await runSingleArmPass(
                    arm: arm,
                    configuration: configuration,
                    iterationsPerToken: iterationsPerToken,
                    deviceInfo: deviceInfo,
                    state: state
                )
                samples.append(sample)
            }

            guard !samples.isEmpty else { continue }
            let aggregated = aggregate(samples)
            let stdDev = samples.count > 1 ? standardDeviation(samples, mean: aggregated) : nil
            let result = ArmResult(arm: arm, metrics: aggregated, stdDev: stdDev, sampleCount: samples.count)
            results.append(result)
            await MainActor.run { state.completedArms.append(result) }

            let isLastArm = index == configuration.arms.count - 1
            if !isLastArm, await MainActor.run(body: { !state.isAborted }) {
                await cooldown(seconds: configuration.cooldownSeconds, state: state)
            }
        }

        await MainActor.run {
            state.phase = "done"
            state.isRunning = false
        }

        return BenchmarkRun(
            date: Date(),
            device: deviceInfo,
            arms: results,
            numberOfRunsPerArm: configuration.runsPerArm,
            workloadDescription: BenchmarkConfiguration.workloadDescription,
            appVersion: appVersionString(),
            mode: .ablation,
            modelProfileName: configuration.modelProfile.name
        )
    }

    // MARK: - Thread sweep mode

    private static func runThreadSweep(
        configuration: BenchmarkConfiguration,
        iterationsPerToken: Int,
        deviceInfo: DeviceInfo,
        state: LiveRunState
    ) async -> BenchmarkRun {
        let counts = configuration.sweepWorkerCounts
            .map { min($0, deviceInfo.activeProcessorCount) }
            .filter { $0 > 0 }
        await MainActor.run { state.reset(mode: .threadSweep, totalArms: counts.count) }

        var points: [SweepPoint] = []
        for (index, workerCount) in counts.enumerated() {
            if await MainActor.run(body: { state.isAborted }) { break }
            await MainActor.run {
                state.armIndex = index
                state.currentSweepWorkerCount = workerCount
                state.progress = 0
            }

            let thermalTracker = ThermalPeakTracker()
            await MainActor.run { thermalTracker.start() }
            let (decodeElapsed, _) = await runDecodePhase(
                tokenCount: configuration.decodeTokenCount,
                workerCount: workerCount,
                qos: .default,
                iterationsPerToken: iterationsPerToken,
                state: state
            )
            thermalTracker.stop()

            let decodeTps = decodeElapsed > 0 ? Double(configuration.decodeTokenCount) / decodeElapsed : 0
            let busyThreadSeconds = Double(workerCount) * decodeElapsed
            let appCPUPercent = decodeElapsed > 0
                ? min(100 * Double(deviceInfo.activeProcessorCount), busyThreadSeconds / decodeElapsed * 100)
                : 0

            let point = SweepPoint(
                workerCount: workerCount,
                decodeTokensPerSecond: decodeTps,
                appCPUPercent: appCPUPercent,
                thermalStateEnd: Telemetry.thermalStateDescription(thermalTracker.peakState)
            )
            points.append(point)
            await MainActor.run { state.sweepPoints.append(point) }

            let isLast = index == counts.count - 1
            if !isLast, await MainActor.run(body: { !state.isAborted }) {
                await cooldown(seconds: min(3, configuration.cooldownSeconds), state: state)
            }
        }

        await MainActor.run {
            state.phase = "done"
            state.isRunning = false
        }

        return BenchmarkRun(
            date: Date(),
            device: deviceInfo,
            arms: [],
            numberOfRunsPerArm: 1,
            workloadDescription: BenchmarkConfiguration.workloadDescription,
            appVersion: appVersionString(),
            mode: .threadSweep,
            modelProfileName: configuration.modelProfile.name,
            sweepPoints: points
        )
    }

    // MARK: - Sustained mode

    private static func runSustained(
        configuration: BenchmarkConfiguration,
        iterationsPerToken: Int,
        deviceInfo: DeviceInfo,
        state: LiveRunState
    ) async -> BenchmarkRun {
        await MainActor.run {
            state.reset(mode: .sustained, totalArms: 1)
            state.sustainedDuration = Double(configuration.sustainedDurationSeconds)
            state.currentArm = configuration.sustainedArm
            state.phase = "decode"
        }

        let arm = configuration.sustainedArm
        let workerCount = arm.workerCount(activeProcessors: deviceInfo.activeProcessorCount)
        let dispatchQoS = mapQoS(arm.qualityOfService)
        let queue = DispatchQueue(label: "benchmark.sustained", qos: dispatchQoS, attributes: .concurrent)
        let perWorkerIterations = max(1, iterationsPerToken / workerCount)

        var samples: [SustainedSample] = []
        let overallStart = DispatchTime.now()
        var windowTokenCount = 0
        var lastSampleTime = overallStart

        func elapsedSince(_ start: DispatchTime) -> Double {
            Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000_000
        }

        while elapsedSince(overallStart) < Double(configuration.sustainedDurationSeconds) {
            if await MainActor.run(body: { state.isAborted }) { break }

            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let group = DispatchGroup()
                for _ in 0..<workerCount {
                    group.enter()
                    queue.async {
                        _ = computeWorkUnit(iterations: perWorkerIterations)
                        group.leave()
                    }
                }
                group.notify(queue: .main) { continuation.resume() }
            }
            windowTokenCount += 1

            let sinceLastSample = elapsedSince(lastSampleTime)
            if sinceLastSample >= configuration.sustainedSampleIntervalSeconds {
                let totalElapsed = elapsedSince(overallStart)
                let tps = sinceLastSample > 0 ? Double(windowTokenCount) / sinceLastSample : 0
                let sample = SustainedSample(
                    elapsedSeconds: totalElapsed,
                    decodeTokensPerSecond: tps,
                    thermalState: Telemetry.thermalStateDescription(),
                    batteryLevel: Telemetry.batteryLevel(),
                    appCPUPercent: min(100 * Double(deviceInfo.activeProcessorCount), Double(workerCount) * 100)
                )
                samples.append(sample)
                windowTokenCount = 0
                lastSampleTime = DispatchTime.now()
                await MainActor.run {
                    state.sustainedSamples.append(sample)
                    state.sustainedElapsed = totalElapsed
                    state.progress = min(1, totalElapsed / Double(configuration.sustainedDurationSeconds))
                    state.refreshLiveTelemetry()
                }
            }
        }

        await MainActor.run {
            state.phase = "done"
            state.isRunning = false
        }

        return BenchmarkRun(
            date: Date(),
            device: deviceInfo,
            arms: [],
            numberOfRunsPerArm: 1,
            workloadDescription: BenchmarkConfiguration.workloadDescription,
            appVersion: appVersionString(),
            mode: .sustained,
            modelProfileName: configuration.modelProfile.name,
            sustainedSamples: samples,
            sustainedArm: arm
        )
    }

    // MARK: - Single pass (ablation)

    private static func runSingleArmPass(
        arm: Arm,
        configuration: BenchmarkConfiguration,
        iterationsPerToken: Int,
        deviceInfo: DeviceInfo,
        state: LiveRunState
    ) async -> MetricSet {
        let thermalTracker = ThermalPeakTracker()
        await MainActor.run { thermalTracker.start() }
        let thermalStart = Telemetry.thermalStateDescription()
        let workerCount = arm.workerCount(activeProcessors: deviceInfo.activeProcessorCount)
        let qos = arm.qualityOfService

        await MainActor.run { state.phase = "prompt"; state.progress = 0 }
        let promptElapsed = await runPhase(
            tokenCount: configuration.promptTokenCount,
            workerCount: workerCount,
            qos: qos,
            iterationsPerToken: iterationsPerToken,
            state: state
        )

        await MainActor.run { state.phase = "decode"; state.progress = 0 }
        let (decodeElapsed, firstTokenElapsed) = await runDecodePhase(
            tokenCount: configuration.decodeTokenCount,
            workerCount: workerCount,
            qos: qos,
            iterationsPerToken: iterationsPerToken,
            state: state
        )

        thermalTracker.stop()
        let thermalPeak = Telemetry.thermalStateDescription(thermalTracker.peakState)

        let promptTps = promptElapsed > 0 ? Double(configuration.promptTokenCount) / promptElapsed : 0
        let decodeTps = decodeElapsed > 0 ? Double(configuration.decodeTokenCount) / decodeElapsed : 0
        let ttftMs = (promptElapsed + firstTokenElapsed) * 1000

        let perThreadWatts: Double
        switch arm {
        case .naive, .threadsOnly: perThreadWatts = 0.6
        case .auto: perThreadWatts = 0.9
        case .efficiency: perThreadWatts = 0.25
        }
        let idleBaselineWatts = 0.4
        let powerWatts = idleBaselineWatts + Double(workerCount) * perThreadWatts
        let tokensPerWatt = powerWatts > 0 ? decodeTps / powerWatts : 0

        let busyThreadSeconds = Double(workerCount) * (promptElapsed + decodeElapsed)
        let wallSeconds = promptElapsed + decodeElapsed
        let appCPUPercent = wallSeconds > 0
            ? min(100 * Double(deviceInfo.activeProcessorCount), busyThreadSeconds / wallSeconds * 100)
            : 0

        await MainActor.run { state.liveAppCPUPercent = appCPUPercent }

        return MetricSet(
            decodeTokensPerSecond: decodeTps,
            promptTokensPerSecond: promptTps,
            timeToFirstTokenMs: ttftMs,
            estimatedPowerWatts: powerWatts,
            tokensPerWatt: tokensPerWatt,
            appCPUPercent: appCPUPercent,
            thermalStateStart: thermalStart,
            thermalStatePeak: thermalPeak,
            activeProcessorCount: deviceInfo.activeProcessorCount,
            totalProcessorCount: deviceInfo.processorCount,
            ramFootprintMB: Telemetry.residentMemoryMB()
        )
    }

    // MARK: - Phases

    /// Prompt/prefill phase: tokens are split across workers and processed in parallel.
    private static func runPhase(
        tokenCount: Int,
        workerCount: Int,
        qos: QualityOfService,
        iterationsPerToken: Int,
        state: LiveRunState
    ) async -> TimeInterval {
        let dispatchQoS = mapQoS(qos)
        return await withCheckedContinuation { continuation in
            let queue = DispatchQueue(label: "benchmark.prompt", qos: dispatchQoS, attributes: .concurrent)
            let group = DispatchGroup()
            let start = DispatchTime.now()
            var remaining = tokenCount
            let perWorker = max(1, tokenCount / workerCount)
            for w in 0..<workerCount {
                let count = (w == workerCount - 1) ? remaining : min(perWorker, remaining)
                remaining -= count
                guard count > 0 else { continue }
                group.enter()
                queue.async {
                    for _ in 0..<count {
                        _ = computeWorkUnit(iterations: iterationsPerToken)
                    }
                    group.leave()
                }
            }
            group.notify(queue: .main) {
                let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000_000
                continuation.resume(returning: elapsed)
            }
        }
    }

    /// Decode phase: tokens are generated one at a time (autoregressive), but
    /// each token's compute is spread across the worker count concurrently,
    /// mirroring how a real decode step parallelizes matrix work within a
    /// single token rather than across tokens.
    private static func runDecodePhase(
        tokenCount: Int,
        workerCount: Int,
        qos: QualityOfService,
        iterationsPerToken: Int,
        state: LiveRunState
    ) async -> (totalElapsed: TimeInterval, firstTokenElapsed: TimeInterval) {
        let dispatchQoS = mapQoS(qos)
        let queue = DispatchQueue(label: "benchmark.decode", qos: dispatchQoS, attributes: .concurrent)
        var totalElapsed: TimeInterval = 0
        var firstTokenElapsed: TimeInterval = 0
        let perWorkerIterations = max(1, iterationsPerToken / workerCount)

        for tokenIndex in 0..<tokenCount {
            if await MainActor.run(body: { state.isAborted }) { break }
            let elapsed: TimeInterval = await withCheckedContinuation { continuation in
                let group = DispatchGroup()
                let start = DispatchTime.now()
                for _ in 0..<workerCount {
                    group.enter()
                    queue.async {
                        _ = computeWorkUnit(iterations: perWorkerIterations)
                        group.leave()
                    }
                }
                group.notify(queue: .main) {
                    let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000_000
                    continuation.resume(returning: elapsed)
                }
            }
            totalElapsed += elapsed
            if tokenIndex == 0 { firstTokenElapsed = elapsed }
            if tokenIndex % 8 == 0 {
                await MainActor.run {
                    state.progress = Double(tokenIndex) / Double(tokenCount)
                    state.refreshLiveTelemetry()
                }
            }
        }
        await MainActor.run { state.progress = 1 }
        return (totalElapsed, firstTokenElapsed)
    }

    private static func mapQoS(_ qos: QualityOfService) -> DispatchQoS {
        switch qos {
        case .userInteractive: return .userInteractive
        case .userInitiated: return .userInitiated
        case .utility: return .utility
        case .background: return .background
        default: return .default
        }
    }

    private static func cooldown(seconds: Int, state: LiveRunState) async {
        await MainActor.run { state.phase = "cooldown" }
        for remaining in stride(from: seconds, through: 1, by: -1) {
            if await MainActor.run(body: { state.isAborted }) { break }
            await MainActor.run {
                state.cooldownRemaining = remaining
                state.refreshLiveTelemetry()
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
        await MainActor.run { state.cooldownRemaining = 0 }
    }

    private static func appVersionString() -> String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    // MARK: - Synthetic workload primitive (Accelerate/vForce backed)

    /// Vectorized floating point busy work standing in for one token's worth
    /// of matrix/dequant compute, using Accelerate's vForce `vvsqrt` — a real
    /// hand-optimized Arm NEON kernel, the closest Apple-platform analogue to
    /// the KleidiAI-accelerated kernels ENTITY's Android backend uses.
    private static func computeWorkUnit(iterations: Int) -> Double {
        let lanes = 128
        var buffer = [Double](repeating: 1.0001, count: lanes)
        var output = [Double](repeating: 0, count: lanes)
        var laneCount = Int32(lanes)
        var acc = 1.0001
        let blocks = max(1, iterations / lanes)
        for _ in 0..<blocks {
            for i in 0..<lanes { buffer[i] = buffer[i] * 1.0000001 + acc * 1e-9 }
            vvsqrt(&output, buffer, &laneCount)
            acc = output[Int(acc.magnitude.truncatingRemainder(dividingBy: Double(lanes)))]
            if !acc.isFinite || acc > 1e6 || acc < 1 { acc = 1.0001 }
            swap(&buffer, &output)
        }
        return acc
    }

    private static func calibrateIterationsPerToken(targetMs: Double = 10) -> Int {
        let probeIterations = 300_000
        let start = DispatchTime.now()
        _ = computeWorkUnit(iterations: probeIterations)
        let elapsedMs = Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000
        guard elapsedMs > 0 else { return probeIterations }
        let perIterationMs = elapsedMs / Double(probeIterations)
        return max(2000, Int(targetMs / perIterationMs))
    }

    // MARK: - Aggregation

    private static func aggregate(_ samples: [MetricSet]) -> MetricSet {
        func median(_ values: [Double]) -> Double {
            let sorted = values.sorted()
            let mid = sorted.count / 2
            return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid]
        }
        return MetricSet(
            decodeTokensPerSecond: median(samples.map(\.decodeTokensPerSecond)),
            promptTokensPerSecond: median(samples.map(\.promptTokensPerSecond)),
            timeToFirstTokenMs: median(samples.map(\.timeToFirstTokenMs)),
            estimatedPowerWatts: median(samples.map(\.estimatedPowerWatts)),
            tokensPerWatt: median(samples.map(\.tokensPerWatt)),
            appCPUPercent: median(samples.map(\.appCPUPercent)),
            thermalStateStart: samples.first?.thermalStateStart ?? "nominal",
            thermalStatePeak: samples.max(by: { Telemetry.thermalSeverity(thermalState(from: $0.thermalStatePeak)) < Telemetry.thermalSeverity(thermalState(from: $1.thermalStatePeak)) })?.thermalStatePeak ?? "nominal",
            activeProcessorCount: samples.first?.activeProcessorCount ?? 0,
            totalProcessorCount: samples.first?.totalProcessorCount ?? 0,
            ramFootprintMB: median(samples.map(\.ramFootprintMB))
        )
    }

    private static func standardDeviation(_ samples: [MetricSet], mean: MetricSet) -> MetricSet {
        func popStdDev(_ values: [Double], mean: Double) -> Double {
            guard values.count > 1 else { return 0 }
            let variance = values.reduce(0) { $0 + pow($1 - mean, 2) } / Double(values.count)
            return variance.squareRoot()
        }
        return MetricSet(
            decodeTokensPerSecond: popStdDev(samples.map(\.decodeTokensPerSecond), mean: mean.decodeTokensPerSecond),
            promptTokensPerSecond: popStdDev(samples.map(\.promptTokensPerSecond), mean: mean.promptTokensPerSecond),
            timeToFirstTokenMs: popStdDev(samples.map(\.timeToFirstTokenMs), mean: mean.timeToFirstTokenMs),
            estimatedPowerWatts: popStdDev(samples.map(\.estimatedPowerWatts), mean: mean.estimatedPowerWatts),
            tokensPerWatt: popStdDev(samples.map(\.tokensPerWatt), mean: mean.tokensPerWatt),
            appCPUPercent: popStdDev(samples.map(\.appCPUPercent), mean: mean.appCPUPercent),
            thermalStateStart: mean.thermalStateStart,
            thermalStatePeak: mean.thermalStatePeak,
            activeProcessorCount: mean.activeProcessorCount,
            totalProcessorCount: mean.totalProcessorCount,
            ramFootprintMB: popStdDev(samples.map(\.ramFootprintMB), mean: mean.ramFootprintMB)
        )
    }

    private static func thermalState(from description: String) -> ProcessInfo.ThermalState {
        switch description {
        case "fair": return .fair
        case "serious": return .serious
        case "critical": return .critical
        default: return .nominal
        }
    }
}
