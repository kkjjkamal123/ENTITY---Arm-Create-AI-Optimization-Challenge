import SwiftUI

struct NewRunView: View {
    let onStart: (BenchmarkConfiguration) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var mode: BenchmarkMode = .ablation
    @State private var selectedProfile: ModelProfile = .defaultProfile
    @State private var showingModelCatalog = false

    // Ablation
    @State private var enabledArms: Set<Arm> = [.naive, .threadsOnly, .auto]
    @State private var includeEfficiencyArm = false
    @State private var runsPerArm = 3
    @State private var cooldownSeconds = 5

    // Thread sweep
    @State private var sweepCounts: Set<Int> = [2, 4, 6, 8]

    // Sustained
    @State private var sustainedArm: Arm = .auto
    @State private var sustainedMinutes = 2

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Mode", selection: $mode) {
                        ForEach(BenchmarkMode.allCases) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    Text(mode.subtitle)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Model Profile") {
                    Button {
                        showingModelCatalog = true
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(selectedProfile.name)
                                    .foregroundStyle(.primary)
                                Text("\(selectedProfile.parameterCountBillions, specifier: "%.1f")B · \(selectedProfile.quantization)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .foregroundStyle(.tertiary)
                        }
                    }
                }

                switch mode {
                case .ablation:
                    ablationSections
                case .threadSweep:
                    threadSweepSections
                case .sustained:
                    sustainedSections
                }

                Section {
                    Button {
                        start()
                    } label: {
                        Text("Start Benchmark")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(!canStart)
                }
            }
            .navigationTitle("New Benchmark")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $showingModelCatalog) {
                ModelCatalogView(selectedProfile: $selectedProfile)
            }
        }
    }

    private var ablationSections: some View {
        Group {
            Section {
                Text("Runs a synthetic CPU-bound proxy workload shaped like a PP512/TG128 pass across ablation arms. Not real LLM inference — see the Reference tab for ENTITY's actual Android/llama.cpp measurements.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Ablation Arms") {
                ForEach([Arm.naive, .threadsOnly, .auto], id: \.self) { arm in
                    Toggle(isOn: binding(for: arm)) {
                        VStack(alignment: .leading) {
                            Text(arm.rawValue)
                            Text(arm.subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Toggle(isOn: $includeEfficiencyArm) {
                    VStack(alignment: .leading) {
                        Text(Arm.efficiency.rawValue)
                        Text(Arm.efficiency.subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Section("Run Configuration") {
                Stepper("Runs per arm: \(runsPerArm)", value: $runsPerArm, in: 1...5)
                Stepper("Cooldown between arms: \(cooldownSeconds)s", value: $cooldownSeconds, in: 0...30, step: 5)
            }
        }
    }

    private var threadSweepSections: some View {
        Section("Worker Counts") {
            ForEach([2, 4, 6, 8], id: \.self) { count in
                Toggle("\(count) workers", isOn: sweepBinding(for: count))
            }
            Text("Runs a decode-only pass at each worker count you enable, in ascending order, to chart throughput scaling.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var sustainedSections: some View {
        Group {
            Section("Configuration") {
                Picker("Arm", selection: $sustainedArm) {
                    ForEach([Arm.naive, .threadsOnly, .auto, .efficiency]) { arm in
                        Text(arm.rawValue).tag(arm)
                    }
                }
                Picker("Duration", selection: $sustainedMinutes) {
                    Text("2 min").tag(2)
                    Text("5 min").tag(5)
                    Text("10 min").tag(10)
                }
                .pickerStyle(.segmented)
            }
            Section {
                Text("Runs \(sustainedArm.rawValue) continuously for \(sustainedMinutes) minutes, sampling throughput/thermal/battery every 5s to reveal thermal throttling drift over time.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var canStart: Bool {
        switch mode {
        case .ablation: return !enabledArms.isEmpty || includeEfficiencyArm
        case .threadSweep: return !sweepCounts.isEmpty
        case .sustained: return true
        }
    }

    private func start() {
        var configuration = BenchmarkConfiguration()
        configuration.mode = mode
        configuration.modelProfile = selectedProfile

        switch mode {
        case .ablation:
            var arms = [Arm.naive, .threadsOnly, .auto].filter { enabledArms.contains($0) }
            if includeEfficiencyArm { arms.append(.efficiency) }
            configuration.arms = arms
            configuration.runsPerArm = runsPerArm
            configuration.cooldownSeconds = cooldownSeconds
        case .threadSweep:
            configuration.sweepWorkerCounts = sweepCounts.sorted()
        case .sustained:
            configuration.sustainedArm = sustainedArm
            configuration.sustainedDurationSeconds = sustainedMinutes * 60
        }

        onStart(configuration)
    }

    private func binding(for arm: Arm) -> Binding<Bool> {
        Binding(
            get: { enabledArms.contains(arm) },
            set: { isOn in
                if isOn { enabledArms.insert(arm) } else { enabledArms.remove(arm) }
            }
        )
    }

    private func sweepBinding(for count: Int) -> Binding<Bool> {
        Binding(
            get: { sweepCounts.contains(count) },
            set: { isOn in
                if isOn { sweepCounts.insert(count) } else { sweepCounts.remove(count) }
            }
        )
    }
}

#Preview {
    NewRunView { _ in }
}
