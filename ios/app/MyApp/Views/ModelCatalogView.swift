import SwiftUI
import UniformTypeIdentifiers

struct ModelCatalogView: View {
    @Binding var selectedProfile: ModelProfile
    @Environment(\.dismiss) private var dismiss
    @State private var importedProfile: ModelProfile?
    @State private var showingImporter = false
    @State private var importError: String?

    private let physicalMemoryGB = Telemetry.currentDeviceInfo().physicalMemoryGB

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("This app does not download or load real model weights — it has no bundled llama.cpp/inference backend. Selecting a profile scales the synthetic Accelerate-backed workload to roughly match that model size's relative compute cost, and the RAM estimate is checked against this device's memory the same way ENTITY tags its Android model catalog.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Catalog") {
                    ForEach(ModelProfile.catalog) { profile in
                        modelRow(profile)
                    }
                }

                if let importedProfile {
                    Section("Imported") {
                        modelRow(importedProfile)
                    }
                }

                Section {
                    Button {
                        showingImporter = true
                    } label: {
                        Label("Import Local GGUF…", systemImage: "square.and.arrow.down")
                    }
                    if let importError {
                        Text(importError)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                } footer: {
                    Text("Reads the real file size of the .gguf you pick and derives an approximate parameter count from it (~0.6 GB per billion params at Q4). The file itself is never loaded or run.")
                }
            }
            .navigationTitle("Model Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.data, .item], allowsMultipleSelection: false) { result in
                handleImport(result)
            }
        }
    }

    private func modelRow(_ profile: ModelProfile) -> some View {
        Button {
            selectedProfile = profile
            dismiss()
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(profile.name)
                            .font(.headline)
                            .foregroundStyle(.primary)
                        if profile.name == selectedProfile.name {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        }
                    }
                    Text("\(profile.parameterCountBillions, specifier: "%.1f")B · \(profile.quantization) · ~\(profile.estimatedRAMGB, specifier: "%.1f") GB RAM")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                fitBadge(profile.fitTag(physicalMemoryGB: physicalMemoryGB))
            }
        }
    }

    private func fitBadge(_ tag: ModelProfile.FitTag) -> some View {
        Text(tag.rawValue)
            .font(.caption2.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color(for: tag).opacity(0.2), in: Capsule())
            .foregroundStyle(color(for: tag))
    }

    private func color(for tag: ModelProfile.FitTag) -> Color {
        switch tag {
        case .recommended: return .green
        case .goodFit: return .mint
        case .fits: return .yellow
        case .tight: return .orange
        case .tooBig: return .red
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            importError = error.localizedDescription
        case .success(let urls):
            guard let url = urls.first else { return }
            guard url.startAccessingSecurityScopedResource() != false || FileManager.default.fileExists(atPath: url.path) else {
                importError = "Couldn't access the selected file."
                return
            }
            defer { url.stopAccessingSecurityScopedResource() }
            do {
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                let byteCount = (attributes[.size] as? NSNumber)?.int64Value ?? 0
                guard byteCount > 0 else {
                    importError = "Couldn't read the file's size."
                    return
                }
                let profile = ModelProfile.fromImportedFile(name: url.lastPathComponent, byteCount: byteCount)
                importedProfile = profile
                selectedProfile = profile
                importError = nil
                dismiss()
            } catch {
                importError = error.localizedDescription
            }
        }
    }
}

#Preview {
    ModelCatalogView(selectedProfile: .constant(.defaultProfile))
}
