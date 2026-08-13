import SwiftUI

enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

enum PaletteMode: String, CaseIterable, Identifiable {
    case monochrome, colour
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var accentColor: Color {
        switch self {
        case .monochrome: return .primary
        case .colour: return .blue
        }
    }
}

struct SettingsView: View {
    @AppStorage("themeMode") private var themeMode: ThemeMode = .system
    @AppStorage("paletteMode") private var paletteMode: PaletteMode = .monochrome
    @Environment(BenchmarkStore.self) private var store

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Picker("Theme", selection: $themeMode) {
                        ForEach(ThemeMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    Picker("Palette", selection: $paletteMode) {
                        ForEach(PaletteMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                }

                Section("Data") {
                    LabeledContent("Saved results", value: "\(store.runs.count)")
                }

                Section("About") {
                    Text("This benchmark app is inspired by ENTITY (kkjjkamal123/ENTITY---Arm-Create-AI-Optimization-Challenge), an Android app that optimizes llama.cpp inference for Arm CPUs. It reproduces ENTITY's ablation structure, thread-sweep mode, sustained-run mode, and model-fit catalog using a real Accelerate/vForce-backed synthetic workload plus iOS's real thermal, battery, memory, and disk telemetry — since iOS does not expose CPU core pinning, instantaneous power draw, or a real llama.cpp backend the way ENTITY's Android/Termux environment does. No model weights are ever downloaded or executed; model profiles only scale the synthetic workload's cost.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }
}

#Preview {
    SettingsView()
        .environment(BenchmarkStore())
}
