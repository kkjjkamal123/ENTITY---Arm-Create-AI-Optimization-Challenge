import Foundation

/// Historical measurements published by the ENTITY project (Android, Arm CPU,
/// llama.cpp). Source: benchmarks/termux_master_results.txt in
/// kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge.
///
/// These used a different model, backend, and workload than this app's
/// synthetic iOS benchmark, so values are shown for reference only and must
/// not be compared directly against the iOS numbers.
struct ReferenceRow: Identifiable, Hashable {
    let id = UUID()
    let device: String
    let model: String
    let config: String
    let promptTokensPerSecond: Double
    let genTokensPerSecond: Double
    let powerWatts: Double
    let tokensPerWatt: Double
}

enum ReferenceData {
    static let sourceDescription = "ENTITY (Android, Dimensity 7300, llama.cpp) — termux_master_results.txt"

    static let rows: [ReferenceRow] = [
        ReferenceRow(device: "CMF Phone 1", model: "1B", config: "NAIVE", promptTokensPerSecond: 91.10, genTokensPerSecond: 13.65, powerWatts: 4.087, tokensPerWatt: 1.98),
        ReferenceRow(device: "CMF Phone 1", model: "1B", config: "OPTIMIZED", promptTokensPerSecond: 123.15, genTokensPerSecond: 18.35, powerWatts: 3.936, tokensPerWatt: 4.04),
        ReferenceRow(device: "CMF Phone 1", model: "3B", config: "NAIVE", promptTokensPerSecond: 19.90, genTokensPerSecond: 3.75, powerWatts: 4.288, tokensPerWatt: 0.16),
        ReferenceRow(device: "CMF Phone 1", model: "3B", config: "OPTIMIZED", promptTokensPerSecond: 21.95, genTokensPerSecond: 5.95, powerWatts: 3.626, tokensPerWatt: 1.21),
    ]

    static let idleBaselineWatts = 0.931
    static let caveat = "Different model, flags, workload, and runtime from this app's benchmark — do not compare absolute values directly. Shown to illustrate the naive-vs-optimized shape ENTITY found on Android."
}
