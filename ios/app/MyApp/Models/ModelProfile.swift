import Foundation

/// A workload profile standing in for a real GGUF model. This app does not
/// download or load actual model weights (see Settings/About) — selecting a
/// profile scales the synthetic compute workload to roughly match the
/// relative cost of running a model of that size, and the RAM estimate is
/// used to compute a fit tag against this device's physical memory, mirroring
/// ENTITY's RECOMMENDED/GOOD FIT/FITS/TIGHT/TOO BIG catalog tags.
struct ModelProfile: Codable, Hashable, Identifiable {
    var id: String { name }
    var name: String
    var parameterCountBillions: Double
    var quantization: String
    var estimatedRAMGB: Double
    /// Relative synthetic compute cost per token vs. the 1B baseline.
    var computeWeight: Double
    var isImported: Bool = false

    static let defaultProfile = catalog[0]

    static let catalog: [ModelProfile] = [
        ModelProfile(name: "Llama 3.2 1B", parameterCountBillions: 1.0, quantization: "Q4_K_M", estimatedRAMGB: 1.1, computeWeight: 1.0),
        ModelProfile(name: "Qwen 2.5 1.5B", parameterCountBillions: 1.5, quantization: "Q4_K_M", estimatedRAMGB: 1.5, computeWeight: 1.4),
        ModelProfile(name: "Phi-3.5 Mini 3.8B", parameterCountBillions: 3.8, quantization: "Q4_K_M", estimatedRAMGB: 2.6, computeWeight: 3.4),
        ModelProfile(name: "Llama 3.1 8B", parameterCountBillions: 8.0, quantization: "Q4_K_M", estimatedRAMGB: 5.2, computeWeight: 7.6),
        ModelProfile(name: "Mistral 7B", parameterCountBillions: 7.0, quantization: "Q4_K_M", estimatedRAMGB: 4.6, computeWeight: 6.7),
        ModelProfile(name: "Gemma 2 9B", parameterCountBillions: 9.0, quantization: "Q4_K_M", estimatedRAMGB: 6.1, computeWeight: 8.9),
    ]

    enum FitTag: String {
        case recommended = "RECOMMENDED"
        case goodFit = "GOOD FIT"
        case fits = "FITS"
        case tight = "TIGHT"
        case tooBig = "TOO BIG"

        var tint: String {
            switch self {
            case .recommended: return "green"
            case .goodFit: return "mint"
            case .fits: return "yellow"
            case .tight: return "orange"
            case .tooBig: return "red"
            }
        }
    }

    func fitTag(physicalMemoryGB: Double) -> FitTag {
        let ratio = estimatedRAMGB / physicalMemoryGB
        switch ratio {
        case ..<0.2: return .recommended
        case ..<0.35: return .goodFit
        case ..<0.5: return .fits
        case ..<0.7: return .tight
        default: return .tooBig
        }
    }

    /// Derives a profile from an imported file's byte size, assuming a
    /// Q4_K_M-style ~0.6 GB-per-billion-parameters footprint (the same rough
    /// ratio llama.cpp Q4 quantization produces), so an imported GGUF scales
    /// the synthetic workload roughly the way its real size implies.
    static func fromImportedFile(name: String, byteCount: Int64) -> ModelProfile {
        let gb = Double(byteCount) / 1_073_741_824
        let approxParamsB = max(0.1, gb / 0.6)
        return ModelProfile(
            name: name,
            parameterCountBillions: approxParamsB,
            quantization: "Unknown (imported)",
            estimatedRAMGB: gb * 1.15,
            computeWeight: approxParamsB,
            isImported: true
        )
    }
}
