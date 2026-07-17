# ENTITY v2.4.0 — 2026-07-17

**APK:** `ENTITY-v12-kv-session-adaptive-threads-20260717-release.apk` (release)

Two inference-path changes: multi-turn conversations no longer re-decode their whole history on
restore, and the thread count is derived from the CPU topology instead of hardcoded. On the
reference 4+4 phone the derived count is still exactly 4, so every published benchmark number
carries over.

## Added

- **KV-cache session reuse**: the active conversation's KV state is saved (llama.cpp
  `llama_state_seq_*` API) to a per-conversation file in app-private storage and restored on
  conversation switch and app restart, instead of re-decoding the full history. The state file
  header records model, context size and system prompt; any mismatch or corruption falls back
  silently to the existing re-prime path. State files are deleted with their conversation, and a
  TTFT log line per path (restored vs re-primed) makes the gain measurable on-device.
- **Topology-adaptive thread count**: Auto's generation thread count is now the size of the top
  frequency cluster (cores with `cpuinfo_max_freq` within 10% of the fastest), clamped to [2, 6],
  instead of a hardcoded 4. A 4+4 big.LITTLE phone still derives 4; a flagship with more than
  four performance cores threads wider. Manual thread settings still override.
