# ENTITY v1.4.0 — 2026-07-04

**APK:** `ENTITY-v4-icon-chips-20260704-1259.apk`

A personalization release that also hardened the benchmark path. The headline feature is a theme-aware
app-icon switcher: ENTITY ships with two logos — a black-background and a white-background "E" — and this
version lets you choose between them, or set **Auto** so the launcher icon matches your phone's light or
dark theme automatically. It's implemented with launcher activity-aliases rather than a hack, with a
guard that guarantees exactly one icon is ever enabled so the app can never vanish from your home screen.
Alongside that, the title bar gained always-on temperature and free-RAM chips for at-a-glance device state
during a run. This release also absorbed three fixes an adversarial code review turned up on the new
benchmark: a power-sampler race that could crash a run, and a core-affinity leak that could quietly leave
the chat running on the slow efficiency cores after benchmarking — plus a same-session fix for an
icon-switch crash and an over-shrunk icon glyph.

## Added
- **Theme-aware app-icon switcher** (Settings → App icon) — *Auto* (black-bg icon in dark theme,
  white-bg in light), *Black background*, or *White background*, implemented with launcher
  activity-aliases so exactly one icon is ever enabled.
- **Header chips** — always-on temperature and free-RAM pills below the title, refreshed live during
  generation but throttled to ~1×/sec so they don't cost decode speed.

## Fixed
- **Benchmark could crash mid-run** — the power sampler was cancelled without waiting, so reading the
  samples list could race the sampler thread (`ConcurrentModificationException`). Now `cancelAndJoin`
  before reading.
- **Chat could get stuck on the slow cores after a benchmark** — `benchModel` restored the context
  tracker but not the CPU-affinity globals it overwrites, so a benchmark could leave the chat pinned
  across all 8 cores (including the A55s) until reload. Now saved/restored around the benchmark.
- **Icon switch crashed the app** — the launcher aliases live in the `com.example.llama` namespace but
  the code built the component name from the `com.entity.chat` applicationId, so enabling the alias
  threw and (because the choice was saved first) crash-looped on every launch. Fixed the namespace and
  wrapped the switch so a failure can never take down launch.
- **App icon glyph was tiny** — the source logos have wide transparent margins; the icons are now
  cropped to the "E" so it fills the tile instead of floating small inside it.
