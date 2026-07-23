#!/usr/bin/env python3
"""Check ENTITY's thread-width rules against real SoCs, without owning the phone.

The rules in ai_chat.cpp read /sys/devices/system/cpu/cpuN/cpu_capacity, which the
kernel computes from the device tree's `capacity-dmips-mhz` times the core's max
clock, normalised so the strongest core is 1024. Those device trees are public, so
the same rules can be evaluated for any SoC whose DT is upstream - no device needed.

This does NOT replace measurement. It tells you what thread widths a phone WOULD be
given, which catches rules that silently collapse on topologies you do not own (the
v3.5.0 prefill bug was exactly that). It cannot tell you whether those widths are
FAST - only a run on the hardware can.

    python3 dt_thread_rules.py            # built-in SoC list
    python3 dt_thread_rules.py qcom/sm8650 exynos/exynos2200
"""
import json
import re
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/torvalds/linux/master/arch/arm64/boot/dts"

# Upstream DTs that actually carry capacity-dmips-mhz. Many vendor SoCs (sm8450,
# sm8750, gs201) are absent or omit it - that is a limit of this method, not a bug.
DEFAULT = [
    "qcom/sm8550",            # Snapdragon 8 Gen 2  - Galaxy S23, in the dataset
    "qcom/sm8650",            # Snapdragon 8 Gen 3
    "exynos/exynos2200",      # Exynos 2200         - no Exynos in the dataset at all
    "exynos/google/gs101",    # Google Tensor G1
    "mediatek/mt8192",
    "mediatek/mt8195",
    "amlogic/meson-g12b",
]

# Max clocks for parts whose upstream DT has no CPU operating-points table.
# Published figures, not measured - flagged as such in the output.
FALLBACK_MHZ = {
    "exynos2200": {"cortex-a510": 1820, "cortex-a710": 2520, "cortex-x2": 2800},
    "mt8192":     {"cortex-a55": 2000, "cortex-a76": 2600},
    "mt8195":     {"cortex-a55": 2000, "cortex-a78": 2600},
    "meson-g12b": {"cortex-a53": 1900, "cortex-a73": 1800},
}

N_THREADS_MIN, N_THREADS_MAX = 2, 6


def _block(s, start):
    """Text of the brace-delimited block whose opening brace is at or after `start`."""
    i = s.index("{", start)
    depth, j = 0, i
    while j < len(s):
        if s[j] == "{":
            depth += 1
        elif s[j] == "}":
            depth -= 1
            if depth == 0:
                return s[i + 1:j]
        j += 1
    return ""


def parse_dt(text):
    cpus = []
    for m in re.finditer(r"cpu(\d+)\s*:\s*cpu@[0-9a-fA-F]+\s*\{", text):
        body = _block(text, m.end() - 1)
        comp = re.search(r'compatible\s*=\s*"([^"]+)"', body)
        cap = re.search(r"capacity-dmips-mhz\s*=\s*<\s*(\d+)\s*>", body)
        opp = re.search(r"operating-points-v2\s*=\s*<&([\w-]+)", body)
        cpus.append({
            "idx": int(m.group(1)),
            "core": comp.group(1).replace("arm,", "") if comp else "?",
            "dmips": int(cap.group(1)) if cap else None,
            "opp": opp.group(1) if opp else None,
        })
    tables = {}
    for m in re.finditer(r"([\w-]+)\s*:\s*opp-table[^{]*\{", text):
        body = _block(text, m.end() - 1)
        hz = [int(x.replace(" ", ""), 0)
              for x in re.findall(r"opp-hz\s*=\s*/bits/\s*64\s*<\s*([0-9x a-fA-F]+)\s*>", body)]
        if hz:
            tables[m.group(1)] = max(hz)
    for c in cpus:
        c["max_hz"] = tables.get(c["opp"])
    return sorted(cpus, key=lambda c: c["idx"])


def widths(caps, freqs):
    """The two shipped rules. Mirrors ai_chat.cpp exactly."""
    top = max(freqs)
    n_gen = max(N_THREADS_MIN, min(N_THREADS_MAX,
                sum(1 for f in freqs if f >= top - top // 10)))
    lo = min(caps)
    perf = sum(1 for c in caps if c > lo) or len(caps)
    n_pp = max(n_gen, min(N_THREADS_MAX, perf))
    # Unity's "at least 2x the slowest core" rule, kept only to show why it was rejected.
    unity_n = sum(1 for c in caps if c >= 2 * lo) or len(caps)
    unity = max(n_gen, min(N_THREADS_MAX, unity_n))
    nxt = min((c for c in caps if c > lo), default=lo)
    margin = (nxt / (2 * lo) - 1) * 100 if lo else 0.0
    return n_gen, n_pp, unity, margin


def main(socs):
    print(f"{'SoC':<12}{'topology':<28}{'n_gen':>6}{'n_pp':>6}{'unity2x':>9}{'margin':>9}  freq source")
    print("-" * 100)
    for path in socs:
        name = path.split("/")[-1]
        try:
            with urllib.request.urlopen(f"{BASE}/{path}.dtsi", timeout=30) as r:
                text = r.read().decode("utf-8", "replace")
        except Exception as e:                                    # noqa: BLE001
            print(f"{name:<12}fetch failed: {e}")
            continue
        cpus = parse_dt(text)
        if not cpus or not any(c["dmips"] for c in cpus):
            print(f"{name:<12}no capacity-dmips-mhz upstream - cannot evaluate")
            continue
        fb = FALLBACK_MHZ.get(name, {})
        freqs, caps, src = [], [], "DT opp-table"
        for c in cpus:
            mhz = c["max_hz"] // 1_000_000 if c["max_hz"] else fb.get(c["core"])
            if mhz is None or not c["dmips"]:
                freqs = []
                break
            if not c["max_hz"]:
                src = "published (not DT)"
            freqs.append(mhz)
            caps.append(c["dmips"] * mhz)
        if not freqs:
            print(f"{name:<12}no clock for some cores - cannot evaluate")
            continue
        peak = max(caps)
        caps = [round(c * 1024 / peak) for c in caps]   # what sysfs cpu_capacity reports
        n_gen, n_pp, unity, margin = widths(caps, freqs)

        tiers = {}
        for c in cpus:
            tiers[c["core"]] = tiers.get(c["core"], 0) + 1
        topo = "+".join(f"{n}x{k.replace('cortex-', '')}" for k, n in tiers.items())

        notes = []
        if n_gen == N_THREADS_MIN:
            notes.append("n_gen sits at the MIN floor - decode width is unvalidated here")
        if unity != n_pp:
            notes.append(f"Unity 2x would give {unity}")
        if margin < 15:
            notes.append(f"Unity 2x margin only {margin:.1f}% - would be fragile")
        print(f"{name:<12}{topo:<28}{n_gen:>6}{n_pp:>6}{unity:>9}{margin:>8.1f}%  {src}")
        print(f"{'':12}cpu_capacity {caps}")
        for n in notes:
            print(f"{'':12}! {n}")
    print("\nn_gen = decode threads (cores within 10% of the fastest CLOCK, clamped [2,6])")
    print("n_pp  = prefill threads (cores above the slowest CAPACITY tier, >= n_gen, capped 6)")
    print("These are the widths the phone would be GIVEN. Whether they are FAST needs a run.")


if __name__ == "__main__":
    main(sys.argv[1:] or DEFAULT)
