import SwiftUI
import UniformTypeIdentifiers
import Charts

struct FullResultView: View {
    let run: BenchmarkRun

    @Environment(BenchmarkStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var showingDeleteConfirmation = false

    var body: some View {
        List {
            Section("Run") {
                LabeledContent("Mode", value: run.mode.rawValue)
                LabeledContent("Model profile", value: run.modelProfileName)
                if let baseline = run.baselineArm, let headline = run.headlineArm, headline.arm != baseline.arm {
                    LabeledContent("Baseline (\(baseline.arm.rawValue))", value: String(format: "%.1f tok/s", baseline.metrics.decodeTokensPerSecond))
                    LabeledContent("Headline (\(headline.arm.rawValue))", value: String(format: "%.1f tok/s", headline.metrics.decodeTokensPerSecond))
                    if let improvement = run.decodeImprovementPercent {
                        LabeledContent("Decode Improvement") {
                            Text("\(improvement >= 0 ? "+" : "")\(improvement, specifier: "%.0f")%")
                                .foregroundStyle(improvement >= 0 ? .green : .red)
                                .bold()
                        }
                    }
                }
                LabeledContent("Workload", value: run.workloadDescription)
                if run.mode == .ablation {
                    LabeledContent("Runs per arm", value: "\(run.numberOfRunsPerArm)")
                }
            }

            Section("Device") {
                LabeledContent("Model", value: run.device.modelIdentifier)
                LabeledContent("System", value: "\(run.device.systemName) \(run.device.systemVersion)")
                LabeledContent("Cores (active/total)", value: "\(run.device.activeProcessorCount)/\(run.device.processorCount)")
                LabeledContent("Physical RAM", value: String(format: "%.1f GB", run.device.physicalMemoryGB))
                LabeledContent("Available memory at start", value: String(format: "%.0f MB", run.device.availableMemoryMBAtStart))
                LabeledContent("Free disk at start", value: String(format: "%.1f GB", run.device.freeDiskSpaceGBAtStart))
                LabeledContent("Battery at start", value: run.device.batteryLevelAtStart < 0 ? "N/A" : "\(Int(run.device.batteryLevelAtStart * 100))% (\(run.device.batteryStateAtStart))")
                LabeledContent("Low Power Mode", value: run.device.lowPowerModeEnabled ? "On" : "Off")
                LabeledContent("App version", value: run.appVersion)
            }

            switch run.mode {
            case .ablation:
                ForEach(run.arms) { result in
                    Section(result.arm.rawValue) {
                        MetricRow(label: "Decode throughput", value: result.metrics.decodeTokensPerSecond, stdDev: result.stdDev?.decodeTokensPerSecond, unit: "tok/s")
                        MetricRow(label: "Prompt throughput", value: result.metrics.promptTokensPerSecond, stdDev: result.stdDev?.promptTokensPerSecond, unit: "tok/s")
                        MetricRow(label: "Time to first token", value: result.metrics.timeToFirstTokenMs, stdDev: result.stdDev?.timeToFirstTokenMs, unit: "ms")
                        MetricRow(label: "Est. power draw", value: result.metrics.estimatedPowerWatts, stdDev: result.stdDev?.estimatedPowerWatts, unit: "W")
                        MetricRow(label: "Efficiency", value: result.metrics.tokensPerWatt, stdDev: result.stdDev?.tokensPerWatt, unit: "tok/W")
                        MetricRow(label: "App CPU (est.)", value: result.metrics.appCPUPercent, stdDev: result.stdDev?.appCPUPercent, unit: "%")
                        LabeledContent("Thermal (start → peak)", value: "\(result.metrics.thermalStateStart.capitalized) → \(result.metrics.thermalStatePeak.capitalized)")
                        MetricRow(label: "App RAM footprint", value: result.metrics.ramFootprintMB, stdDev: result.stdDev?.ramFootprintMB, unit: "MB")
                        LabeledContent("Samples", value: "\(result.sampleCount)")
                    }
                }
            case .threadSweep:
                if !run.sweepPoints.isEmpty {
                    Section("Throughput vs Worker Count") {
                        Chart(run.sweepPoints) { point in
                            BarMark(
                                x: .value("Workers", point.workerCount),
                                y: .value("Decode tok/s", point.decodeTokensPerSecond)
                            )
                        }
                        .frame(height: 200)
                    }
                    Section("Sweep Points") {
                        ForEach(run.sweepPoints) { point in
                            LabeledContent("\(point.workerCount) workers") {
                                Text("\(point.decodeTokensPerSecond, specifier: "%.1f") tok/s · CPU \(Int(point.appCPUPercent))% · \(point.thermalStateEnd.capitalized)")
                            }
                        }
                    }
                }
            case .sustained:
                if !run.sustainedSamples.isEmpty {
                    if let arm = run.sustainedArm {
                        Section("Configuration") {
                            LabeledContent("Arm", value: arm.rawValue)
                        }
                    }
                    Section("Throughput Over Time") {
                        Chart(run.sustainedSamples) { sample in
                            LineMark(
                                x: .value("Elapsed (s)", sample.elapsedSeconds),
                                y: .value("Decode tok/s", sample.decodeTokensPerSecond)
                            )
                        }
                        .frame(height: 200)
                    }
                    Section("Thermal State Over Time") {
                        Chart(run.sustainedSamples) { sample in
                            LineMark(
                                x: .value("Elapsed (s)", sample.elapsedSeconds),
                                y: .value("Severity", Telemetry.thermalSeverity(from: sample.thermalState))
                            )
                        }
                        .frame(height: 140)
                        Text("0=nominal, 1=fair, 2=serious, 3=critical")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Section("Samples") {
                        ForEach(run.sustainedSamples) { sample in
                            LabeledContent("\(Int(sample.elapsedSeconds))s") {
                                Text("\(sample.decodeTokensPerSecond, specifier: "%.1f") tok/s · \(sample.thermalState.capitalized) · Batt \(sample.batteryLevel < 0 ? "N/A" : "\(Int(sample.batteryLevel * 100))%")")
                            }
                        }
                    }
                }
            }

            Section {
                Text("Power draw is estimated from a per-thread heuristic, not measured — iOS exposes no public instantaneous power API (unlike the sysfs battery-current reads ENTITY's Android app uses). App CPU % is derived from busy thread-seconds over wall time. Treat both as directional, not absolute.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Result")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    ShareLink(item: store.csv(for: run), preview: SharePreview("Benchmark Result CSV")) {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        UIPasteboard.general.string = store.csv(for: run)
                    } label: {
                        Label("Copy CSV", systemImage: "doc.on.doc")
                    }
                    Button(role: .destructive) {
                        showingDeleteConfirmation = true
                    } label: {
                        Label("Delete Result", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("Delete this result?", isPresented: $showingDeleteConfirmation, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                store.delete(run)
                dismiss()
            }
        }
    }
}

private struct MetricRow: View {
    let label: String
    let value: Double
    let stdDev: Double?
    let unit: String

    var body: some View {
        LabeledContent(label) {
            if let stdDev, stdDev > 0 {
                Text("\(value, specifier: "%.2f") ± \(stdDev, specifier: "%.2f") \(unit)")
            } else {
                Text("\(value, specifier: "%.2f") \(unit)")
            }
        }
    }
}
