import SwiftUI

struct LiveRunView: View {
    @Bindable var state: LiveRunState
    let onFinished: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                if state.phase == "done" {
                    doneView
                } else {
                    runningView
                }
            }
            .padding()
            .navigationTitle("Live Run")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .destructive) {
                        state.abort()
                        onFinished()
                    } label: {
                        Text(state.phase == "done" ? "Close" : "Abort")
                    }
                }
            }
            .interactiveDismissDisabled()
        }
    }

    private var runningView: some View {
        VStack(spacing: 20) {
            header

            if state.phase == "cooldown" {
                VStack(spacing: 8) {
                    Image(systemName: "thermometer.snowflake")
                        .font(.system(size: 40))
                    Text("Cooling down: \(state.cooldownRemaining)s")
                        .font(.title3)
                }
            } else if state.mode == .sustained {
                VStack(spacing: 8) {
                    Text("Elapsed \(formatted(state.sustainedElapsed)) / \(formatted(state.sustainedDuration))")
                        .font(.headline)
                    ProgressView(value: state.progress)
                        .progressViewStyle(.linear)
                    if let last = state.sustainedSamples.last {
                        Text("\(last.decodeTokensPerSecond, specifier: "%.1f") tok/s (last window)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                VStack(spacing: 8) {
                    Text(phaseLabel)
                        .font(.headline)
                    ProgressView(value: state.progress)
                        .progressViewStyle(.linear)
                    if state.mode == .ablation {
                        Text("Repetition \(state.currentRepetition)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Divider()

            LiveTelemetryGrid(state: state)

            Spacer()

            resultsSoFar
        }
    }

    private var header: some View {
        VStack(spacing: 4) {
            switch state.mode {
            case .ablation:
                Text("Arm \(state.armIndex + 1) of \(state.totalArms)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(state.currentArm?.rawValue ?? "—")
                    .font(.largeTitle.bold())
                if let arm = state.currentArm {
                    Text(arm.subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            case .threadSweep:
                Text("Point \(state.armIndex + 1) of \(state.totalArms)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("\(state.currentSweepWorkerCount ?? 0) workers")
                    .font(.largeTitle.bold())
            case .sustained:
                Text("Sustained Run")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(state.currentArm?.rawValue ?? "—")
                    .font(.largeTitle.bold())
            }
        }
    }

    private var phaseLabel: String {
        switch state.phase {
        case "prompt": return "Prompt phase (prefill)"
        case "decode": return "Decode phase (generation)"
        default: return "Preparing…"
        }
    }

    private func formatted(_ seconds: Double) -> String {
        let m = Int(seconds) / 60
        let s = Int(seconds) % 60
        return String(format: "%d:%02d", m, s)
    }

    private var resultsSoFar: some View {
        Group {
            switch state.mode {
            case .ablation where !state.completedArms.isEmpty:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Completed").font(.caption.bold()).foregroundStyle(.secondary)
                    ForEach(state.completedArms) { result in
                        HStack {
                            Text(result.arm.rawValue)
                            Spacer()
                            Text("\(result.metrics.decodeTokensPerSecond, specifier: "%.1f") tok/s").monospacedDigit()
                        }
                        .font(.callout)
                    }
                }
            case .threadSweep where !state.sweepPoints.isEmpty:
                VStack(alignment: .leading, spacing: 6) {
                    Text("Completed").font(.caption.bold()).foregroundStyle(.secondary)
                    ForEach(state.sweepPoints) { point in
                        HStack {
                            Text("\(point.workerCount) workers")
                            Spacer()
                            Text("\(point.decodeTokensPerSecond, specifier: "%.1f") tok/s").monospacedDigit()
                        }
                        .font(.callout)
                    }
                }
            default:
                EmptyView()
            }
        }
    }

    private var doneView: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 56))
                .foregroundStyle(.green)
            Text("Benchmark Complete")
                .font(.title2.bold())
            switch state.mode {
            case .ablation:
                ForEach(state.completedArms) { result in
                    HStack {
                        Text(result.arm.rawValue)
                        Spacer()
                        Text("\(result.metrics.decodeTokensPerSecond, specifier: "%.1f") tok/s").monospacedDigit()
                    }
                }
                .padding(.horizontal)
            case .threadSweep:
                ForEach(state.sweepPoints) { point in
                    HStack {
                        Text("\(point.workerCount) workers")
                        Spacer()
                        Text("\(point.decodeTokensPerSecond, specifier: "%.1f") tok/s").monospacedDigit()
                    }
                }
                .padding(.horizontal)
            case .sustained:
                if let last = state.sustainedSamples.last {
                    Text("\(last.decodeTokensPerSecond, specifier: "%.1f") tok/s at \(formatted(last.elapsedSeconds))")
                }
            }
            Spacer()
        }
    }
}

private struct LiveTelemetryGrid: View {
    @Bindable var state: LiveRunState

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            TelemetryTile(title: "Battery", value: batteryText, systemImage: "battery.75")
            TelemetryTile(title: "Thermal State", value: state.liveThermalState.capitalized, systemImage: "thermometer.medium")
            TelemetryTile(title: "App CPU (est.)", value: "\(Int(state.liveAppCPUPercent))%", systemImage: "cpu")
            TelemetryTile(title: "Battery State", value: state.liveBatteryState.capitalized, systemImage: "bolt.fill")
        }
    }

    private var batteryText: String {
        state.liveBatteryLevel < 0 ? "N/A" : "\(Int(state.liveBatteryLevel * 100))%"
    }
}

private struct TelemetryTile: View {
    let title: String
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: systemImage)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title3.bold().monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
    }
}
