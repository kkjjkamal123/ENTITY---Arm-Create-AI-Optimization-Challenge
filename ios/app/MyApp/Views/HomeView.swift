import SwiftUI

struct HomeView: View {
    @Environment(BenchmarkStore.self) private var store
    @State private var showingNewRun = false
    @State private var liveState: LiveRunState?
    @State private var runTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            List {
                if let latest = store.latest {
                    Section("Latest Result") {
                        NavigationLink(value: latest) {
                            LatestResultCard(run: latest)
                        }
                    }
                } else {
                    ContentUnavailableView(
                        "No Benchmarks Yet",
                        systemImage: "gauge.with.dots.needle.bottom.50percent",
                        description: Text("Run a benchmark to see decode/prompt throughput, TTFT, estimated power draw, and thermal behavior on this device.")
                    )
                }

                if store.runs.count > 1 {
                    Section("History") {
                        ForEach(store.runs.dropFirst()) { run in
                            NavigationLink(value: run) {
                                HistoryRow(run: run)
                            }
                        }
                        .onDelete { offsets in
                            for index in offsets {
                                store.delete(Array(store.runs.dropFirst())[index])
                            }
                        }
                    }
                }
            }
            .navigationTitle("ENTITY iOS Bench")
            .navigationDestination(for: BenchmarkRun.self) { run in
                FullResultView(run: run)
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingNewRun = true
                    } label: {
                        Label("New Benchmark", systemImage: "plus.circle.fill")
                    }
                }
            }
            .sheet(isPresented: $showingNewRun) {
                NewRunView { configuration in
                    showingNewRun = false
                    start(configuration: configuration)
                }
            }
            .fullScreenCover(item: $liveState) { state in
                LiveRunView(state: state) {
                    liveState = nil
                }
            }
        }
    }

    private func start(configuration: BenchmarkConfiguration) {
        let state = LiveRunState()
        liveState = state
        runTask = Task {
            let run = await BenchmarkEngine.run(configuration: configuration, state: state)
            guard !state.isAborted else { return }
            store.add(run)
        }
    }
}

private struct LatestResultCard: View {
    let run: BenchmarkRun

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(run.date, style: .date)
                    .font(.headline)
                Spacer()
                Text(run.mode.rawValue)
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
            }
            Text(run.modelProfileName)
                .font(.caption)
                .foregroundStyle(.secondary)
            switch run.mode {
            case .ablation:
                if let headline = run.headlineArm {
                    Text("\(headline.arm.rawValue): \(headline.metrics.decodeTokensPerSecond, specifier: "%.1f") tok/s decode")
                        .font(.title3.bold())
                }
                if let improvement = run.decodeImprovementPercent {
                    Text("\(improvement >= 0 ? "+" : "")\(improvement, specifier: "%.0f")% decode vs naive")
                        .font(.callout)
                        .foregroundStyle(improvement >= 0 ? .green : .red)
                }
            case .threadSweep:
                if let best = run.sweepPoints.max(by: { $0.decodeTokensPerSecond < $1.decodeTokensPerSecond }) {
                    Text("Best: \(best.workerCount) workers · \(best.decodeTokensPerSecond, specifier: "%.1f") tok/s")
                        .font(.title3.bold())
                }
            case .sustained:
                if let last = run.sustainedSamples.last {
                    Text("\(last.decodeTokensPerSecond, specifier: "%.1f") tok/s after \(Int(last.elapsedSeconds))s")
                        .font(.title3.bold())
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct HistoryRow: View {
    let run: BenchmarkRun

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(run.date, style: .date) + Text(" · ") + Text(run.date, style: .time)
                Text("\(run.mode.rawValue) · \(run.modelProfileName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if let headline = run.headlineArm {
                Text("\(headline.metrics.decodeTokensPerSecond, specifier: "%.1f") tok/s")
                    .font(.callout.monospacedDigit())
            } else if let best = run.sweepPoints.max(by: { $0.decodeTokensPerSecond < $1.decodeTokensPerSecond }) {
                Text("\(best.decodeTokensPerSecond, specifier: "%.1f") tok/s")
                    .font(.callout.monospacedDigit())
            } else if let last = run.sustainedSamples.last {
                Text("\(last.decodeTokensPerSecond, specifier: "%.1f") tok/s")
                    .font(.callout.monospacedDigit())
            }
        }
    }
}

#Preview {
    HomeView()
        .environment(BenchmarkStore())
}
