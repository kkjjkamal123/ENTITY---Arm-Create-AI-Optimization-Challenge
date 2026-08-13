import SwiftUI

@main
struct MyApp: App {
    @State private var store = BenchmarkStore()
    @AppStorage("themeMode") private var themeMode: ThemeMode = .system
    @AppStorage("paletteMode") private var paletteMode: PaletteMode = .monochrome

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(store)
                .preferredColorScheme(themeMode.colorScheme)
                .tint(paletteMode.accentColor)
        }
    }
}

struct ContentView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
            ReferenceComparisonView()
                .tabItem { Label("Reference", systemImage: "chart.bar.doc.horizontal") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
    }
}

#Preview {
    ContentView()
        .environment(BenchmarkStore())
}
