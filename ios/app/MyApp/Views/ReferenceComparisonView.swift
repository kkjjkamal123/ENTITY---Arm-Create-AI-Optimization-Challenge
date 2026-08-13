import SwiftUI

struct ReferenceComparisonView: View {
    @Environment(BenchmarkStore.self) private var store

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(ReferenceData.caveat)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text(ReferenceData.sourceDescription)
                }

                Section("ENTITY Android — llama.cpp") {
                    ForEach(ReferenceData.rows) { row in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text("\(row.model) · \(row.config)")
                                    .font(.headline)
                                Spacer()
                                Text(row.device)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            HStack {
                                statTile("Prompt", String(format: "%.1f tok/s", row.promptTokensPerSecond))
                                statTile("Decode", String(format: "%.1f tok/s", row.genTokensPerSecond))
                                statTile("Power", String(format: "%.2f W", row.powerWatts))
                                statTile("Tok/W", String(format: "%.2f", row.tokensPerWatt))
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    LabeledContent("Idle baseline power", value: String(format: "%.3f W", ReferenceData.idleBaselineWatts))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let latest = store.latest {
                    Section("Your Latest iOS Result") {
                        ForEach(latest.arms) { result in
                            HStack {
                                Text(result.arm.rawValue)
                                Spacer()
                                statTile("Prompt", String(format: "%.1f tok/s", result.metrics.promptTokensPerSecond))
                                statTile("Decode", String(format: "%.1f tok/s", result.metrics.decodeTokensPerSecond))
                                statTile("Tok/W", String(format: "%.2f", result.metrics.tokensPerWatt))
                            }
                        }
                        Text("iOS numbers come from a synthetic proxy workload on this device, not the same model/backend as the Android reference above. Comparisons across platforms are illustrative, not scientific.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Reference")
        }
    }

    private func statTile(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.caption.monospacedDigit())
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

#Preview {
    ReferenceComparisonView()
        .environment(BenchmarkStore())
}
