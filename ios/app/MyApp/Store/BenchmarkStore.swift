import Foundation
import Observation

@MainActor
@Observable
final class BenchmarkStore {
    private(set) var runs: [BenchmarkRun] = []

    private let fileURL: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("benchmark_runs.json")
    }()

    var latest: BenchmarkRun? { runs.first }

    init() {
        load()
    }

    func add(_ run: BenchmarkRun) {
        runs.insert(run, at: 0)
        save()
    }

    func delete(_ run: BenchmarkRun) {
        runs.removeAll { $0.id == run.id }
        save()
    }

    func csv(for run: BenchmarkRun) -> String {
        var lines = ["arm,repetitions,decode_tok_s,decode_stddev,prompt_tok_s,prompt_stddev,ttft_ms,power_w_est,tok_per_watt,app_cpu_pct,thermal_start,thermal_peak,ram_mb"]
        for result in run.arms {
            let m = result.metrics
            let sd = result.stdDev
            lines.append([
                result.arm.rawValue,
                String(result.sampleCount),
                String(format: "%.2f", m.decodeTokensPerSecond),
                String(format: "%.2f", sd?.decodeTokensPerSecond ?? 0),
                String(format: "%.2f", m.promptTokensPerSecond),
                String(format: "%.2f", sd?.promptTokensPerSecond ?? 0),
                String(format: "%.1f", m.timeToFirstTokenMs),
                String(format: "%.2f", m.estimatedPowerWatts),
                String(format: "%.2f", m.tokensPerWatt),
                String(format: "%.1f", m.appCPUPercent),
                m.thermalStateStart,
                m.thermalStatePeak,
                String(format: "%.1f", m.ramFootprintMB)
            ].joined(separator: ","))
        }
        return lines.joined(separator: "\n")
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        runs = (try? decoder.decode([BenchmarkRun].self, from: data)) ?? []
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(runs) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
