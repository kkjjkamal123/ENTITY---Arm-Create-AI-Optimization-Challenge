package com.entity.bench

import java.util.Locale

// CSV and plain-text renderings of a saved result. Everything is rebuilt from the
// BenchResult on disk, so a result can be exported any time after the run - the row
// keys and meta names match the v1.0 exporter, existing analysis scripts keep working.
object BenchExport {

    fun csv(r: BenchResult): String =
        if (r.type == BenchResult.TYPE_SUSTAINED) sustainedCsv(r) else ablationCsv(r)

    private fun ablationCsv(r: BenchResult): String = buildString {
        val w = CsvWriter(this)
        appendLine("config,run_index,metric,value,unit")
        w.row("meta", "", "app", "ENTITY Bench", "")
        w.row("meta", "", "benchmark_type", r.type, "")
        w.row("meta", "", "app_version", r.appVersion, "")
        w.row("meta", "", "app_version_code", r.appVersionCode.toString(), "")
        w.row("meta", "", "benchmark_completed_at_epoch_ms", r.ts.toString(), "ms")
        w.row("meta", "", "exported_at_epoch_ms", System.currentTimeMillis().toString(), "ms")
        w.row("meta", "", "model", r.model, "")
        w.row("meta", "", "device", "${r.deviceManufacturer} ${r.deviceModel}", "")
        w.row("meta", "", "device_manufacturer", r.deviceManufacturer, "")
        w.row("meta", "", "device_model", r.deviceModel, "")
        w.row("meta", "", "device_fingerprint", r.deviceFingerprint, "")
        w.row("meta", "", "android", r.androidRelease, "")
        w.row("meta", "", "android_sdk", r.androidSdk.toString(), "")
        w.row("meta", "", "supported_abis", r.abis.joinToString(" "), "")
        w.row("meta", "", "charging", r.charging.toString(), "")
        w.row("meta", "", "benchmark_start_temp", num(r.startTempC), "C")
        w.row("meta", "", "benchmark_start_thermal_status", r.startThermalStatus.toString(), "Android PowerManager status")
        w.row("meta", "", "pp", BenchRunner.PP.toString(), "tokens")
        w.row("meta", "", "tg", BenchRunner.TG.toString(), "tokens")
        w.row("meta", "", "runs_per_config", r.runsPerArm.toString(), "")
        w.row("meta", "", "warmup", "PP 64 / TG 16 / discarded", "")
        w.row("meta", "", "configuration_order", r.arms.joinToString("_then_") { it.key }, "")
        for (a in r.arms) w.row("meta", "", "threads_${a.key}", a.threads.toString(), "")
        for (a in r.arms) w.row("meta", "", "affinity_${a.key}", affinityLabel(a, r), "")
        w.row("meta", "", "cooldown_minimum", (BenchRunner.MIN_PAUSE_MS / 1000).toString(), "s")
        w.row("meta", "", "cooldown_maximum", (BenchRunner.MAX_COOLDOWN_MS / 1000).toString(), "s")
        w.row("meta", "", "cooldown_target_margin", num(BenchRunner.COOL_MARGIN_C), "C above benchmark-start temperature")
        w.row("meta", "", "perf_cores", r.fastCores.joinToString(" "), "cpu index")
        w.row("meta", "", "little_cores", r.littleCores.joinToString(" "), "cpu index")
        w.row("meta", "", "cpu_max_clocks", r.maxFreqsMhz.joinToString(" "), "MHz per cpu index")

        for (a in r.arms) {
            passRows(w, a)
            aggRows(w, a, r)
        }
    }

    private fun sustainedCsv(r: BenchResult): String = buildString {
        val w = CsvWriter(this)
        appendLine("config,run_index,metric,value,unit")
        w.row("meta", "", "app", "ENTITY Bench", "")
        w.row("meta", "", "benchmark_type", r.type, "")
        w.row("meta", "", "app_version", r.appVersion, "")
        w.row("meta", "", "test", "sustained_no_cooldown", "")
        w.row("meta", "", "benchmark_completed_at_epoch_ms", r.ts.toString(), "ms")
        w.row("meta", "", "model", r.model, "")
        w.row("meta", "", "device", "${r.deviceManufacturer} ${r.deviceModel}", "")
        w.row("meta", "", "device_fingerprint", r.deviceFingerprint, "")
        w.row("meta", "", "duration", r.durationMin.toString(), "min")
        w.row("meta", "", "inter_pass_gap", (BenchRunner.SUSTAINED_GAP_MS / 1000).toString(), "s")
        w.row("meta", "", "pp", BenchRunner.PP.toString(), "tokens")
        w.row("meta", "", "tg", BenchRunner.TG.toString(), "tokens")
        w.row("meta", "", "perf_cores", r.fastCores.joinToString(" "), "cpu index")
        w.row("meta", "", "little_cores", r.littleCores.joinToString(" "), "cpu index")
        for (a in r.arms) {
            w.row("meta", "", "threads_${a.key}", a.threads.toString(), "")
            w.row("meta", "", "affinity_${a.key}", affinityLabel(a, r), "")
            w.row("meta", "", "passes_${a.key}", a.passes.size.toString(), "")
            a.passes.forEachIndexed { i, p ->
                val idx = (i + 1).toString()
                w.row(a.key, idx, "tg", num(p.tg), "tok/s")
                w.row(a.key, idx, "power", num(p.watts), "W")
                w.row(a.key, idx, "start_temp", num(p.startTempC), "C")
                w.row(a.key, idx, "peak_battery_temp", num(p.peakBatteryTempC), "C")
                w.row(a.key, idx, "peak_thermal_status", p.peakThermalStatus.toString(), "Android PowerManager status")
                w.row(a.key, idx, "mean_perf_core_clock", num(p.meanFastCoreFreqMhz(r.fastCores)), "MHz")
                w.row(a.key, idx, "mean_little_core_clock", num(p.meanLittleCoreFreqMhz(r.littleCores)), "MHz")
            }
        }
    }

    private fun passRows(w: CsvWriter, a: Arm) {
        a.passes.forEachIndexed { i, p ->
            val idx = (i + 1).toString()
            w.row(a.key, idx, "pp", num(p.pp), "tok/s")
            w.row(a.key, idx, "tg", num(p.tg), "tok/s")
            w.row(a.key, idx, "power", num(p.watts), "W")
            w.row(a.key, idx, "tok_per_w", num(p.tokPerW), "tok/W")
            w.row(a.key, idx, "ttft_pp${BenchRunner.PP}_derived", num(p.ttftMs), "ms")
            w.row(a.key, idx, "start_temp", num(p.startTempC), "C")
            w.row(a.key, idx, "average_process_cpu", num(p.averageProcessCpuPercent), "% one core")
            w.row(a.key, idx, "minimum_free_ram", num(p.minimumFreeGb), "GiB")
            w.row(a.key, idx, "peak_battery_temp", num(p.peakBatteryTempC), "C")
            w.row(a.key, idx, "peak_thermal_status", p.peakThermalStatus.toString(), "Android PowerManager status")
            p.telemetry.forEachIndexed { sampleIndex, sample ->
                val sampleRun = "$idx:${sampleIndex + 1}"
                w.row(a.key, sampleRun, "sample_elapsed", sample.elapsedMs.toString(), "ms")
                w.row(a.key, sampleRun, "sample_power", num(sample.watts), "W")
                w.row(a.key, sampleRun, "sample_process_cpu", num(sample.processCpuPercent), "% one core")
                w.row(a.key, sampleRun, "sample_free_ram", num(sample.freeGb), "GiB")
                w.row(a.key, sampleRun, "sample_battery_temp", num(sample.batteryTempC), "C")
                w.row(a.key, sampleRun, "sample_thermal_status", sample.thermalStatus.toString(), "Android PowerManager status")
                sample.cpuFreqMhz.forEachIndexed { cpu, mhz ->
                    if (mhz > 0) w.row(a.key, sampleRun, "sample_cpu${cpu}_freq", mhz.toString(), "MHz")
                }
            }
        }
    }

    private fun aggRows(w: CsvWriter, a: Arm, r: BenchResult) {
        fun agg(metric: String, unit: String, sel: (Pass) -> Double) {
            val st = stat(a.passes.map(sel))
            w.row(a.key, "median", metric, num(st.median), unit)
            w.row(a.key, "stddev", metric, num(st.sd), unit)
        }
        agg("pp", "tok/s") { it.pp }
        agg("tg", "tok/s") { it.tg }
        agg("power", "W") { it.watts }
        agg("tok_per_w", "tok/W") { it.tokPerW }
        agg("ttft_pp${BenchRunner.PP}_derived", "ms") { it.ttftMs }
        agg("start_temp", "C") { it.startTempC }
        agg("average_process_cpu", "% one core") { it.averageProcessCpuPercent }
        agg("minimum_free_ram", "GiB") { it.minimumFreeGb }
        agg("peak_battery_temp", "C") { it.peakBatteryTempC }
        agg("mean_perf_core_clock", "MHz") { it.meanFastCoreFreqMhz(r.fastCores) }
        agg("mean_little_core_clock", "MHz") { it.meanLittleCoreFreqMhz(r.littleCores) }
    }

    // The native side pins to the N fastest cores where N = thread count, so a pinned
    // arm whose thread count covers every online core runs on all of them.
    private fun affinityLabel(a: Arm, r: BenchResult): String = when {
        !a.pinned -> "none_scheduler_placed"
        a.slowCluster -> "pinned_efficiency_cores"
        a.threads >= r.maxFreqsMhz.size && r.maxFreqsMhz.isNotEmpty() -> "mask_all_cores_effectively_unpinned"
        else -> "pinned_fast_cores"
    }

    private class CsvWriter(val sb: StringBuilder) {
        fun row(config: String, runIndex: String, metric: String, value: String, unit: String) {
            sb.appendLine(listOf(config, runIndex, metric, value, unit).joinToString(",") { esc(it) })
        }
        private fun esc(s: String) =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
    }

    private fun num(x: Double) = String.format(Locale.US, "%.3f", x)

    // ---- plain-text summary for the clipboard ----

    fun copyText(r: BenchResult): String = when (r.type) {
        BenchResult.TYPE_SUSTAINED -> sustainedText(r)
        BenchResult.TYPE_SWEEP -> sweepText(r)
        else -> ablationText(r)
    }

    private fun sweepText(r: BenchResult): String = buildString {
        val arms = r.sweepArms
        val best = r.bestSweepArm()
        appendLine("ENTITY Bench ${r.appVersion} - ${r.model}")
        appendLine("${r.deviceManufacturer} ${r.deviceModel} - Android ${r.androidRelease}")
        appendLine("Thread sweep, ${r.runsPerArm} runs per configuration, PP ${BenchRunner.PP} / TG ${BenchRunner.TG}")
        appendLine("CPU max clocks: ${r.maxFreqsMhz.joinToString(" ")} MHz")
        appendLine("Auto derives ${r.autoThreads} threads on this device")
        appendLine()
        appendLine("threads  placement  decode t/s  prompt t/s  tok/W")
        for (a in arms) {
            val tg = stat(a.passes.map { it.tg })
            val pp = stat(a.passes.map { it.pp })
            val eff = stat(a.passes.map { it.tokPerW })
            val mark = if (a.key == best?.key) "  <- best decode" else ""
            appendLine(
                "%-8d %-10s %-11s %-11s %s%s".format(
                    a.threads,
                    if (a.pinned) "pinned" else "no pin",
                    fmt(tg.median), fmt(pp.median),
                    if (r.powerValid) fmt(eff.median) else "-",
                    mark,
                )
            )
        }
        if (!r.powerValid) appendLine("\nPower not recorded: the phone was charging.")
    }

    private fun ablationText(r: BenchResult): String {
        val arms = r.ablationArms
        fun stats(sel: (Pass) -> Double) = arms.map { a -> stat(a.passes.map(sel)) }
        val pp = stats { it.pp }
        val tg = stats { it.tg }
        val ttft = stats { it.ttftMs }
        val watts = stats { it.watts }
        val eff = stats { it.tokPerW }
        val temp = stats { it.startTempC }
        val cpu = stats { it.averageProcessCpuPercent }
        val free = stats { it.minimumFreeGb }
        val n = r.runsPerArm
        fun line(name: String, s: List<Stat>) =
            "$name: naive ${statText(s[0])}  threads-only ${statText(s[1])}  auto ${statText(s[2])}"
        return buildString {
            appendLine("ENTITY Bench ${r.appVersion} - ${r.model}")
            appendLine("${r.deviceManufacturer} ${r.deviceModel} - Android ${r.androidRelease}")
            appendLine("Decode ${signed(r.decodeDeltaPct())} vs naive" +
                if (r.powerValid && eff[0].median > 0)
                    " - ${"%.1f".format(eff[2].median / eff[0].median)}x more efficient" else "")
            appendLine("Runs/arm: $n (median${if (n > 1) " ±σ" else ""})")
            appendLine("Arms: naive=${r.naive?.threads} threads all cores · threads-only=${r.threadsOnly?.threads} threads no pin · auto=${r.optimized?.threads} perf cores pinned")
            appendLine(line("Prompt t/s", pp))
            appendLine(line("Decode t/s", tg))
            appendLine(line("TTFT* ms  ", ttft))
            if (r.powerValid) {
                appendLine(line("Power W   ", watts))
                appendLine(line("tok/W     ", eff))
            }
            appendLine(line("Start C   ", temp))
            appendLine(line("App CPU % ", cpu))
            appendLine(line("Free RAM min GB", free))
            r.efficiency?.let { e ->
                appendLine("Efficiency arm (${e.threads} threads, slow cluster): decode ${statText(stat(e.passes.map { it.tg }))}" +
                    if (r.powerValid) "  tok/W ${statText(stat(e.passes.map { it.tokPerW }))}" else "")
            }
            val naiveTg = tg[0].median
            val threadsTg = tg[1].median
            val optTg = tg[2].median
            if (naiveTg > 0 && threadsTg > 0) {
                appendLine("Attribution: ${r.threadsOnly?.threads} threads alone " +
                    "${signed((threadsTg / naiveTg - 1) * 100)} over naive; pinning adds " +
                    "${signed((optTg / threadsTg - 1) * 100)} on top.")
            }
            appendLine("*derived: PP${BenchRunner.PP} prompt eval + one decode step")
        }.trim()
    }

    private fun sustainedText(r: BenchResult): String = buildString {
        val to = r.threadsOnly
        val opt = r.optimized
        appendLine("ENTITY Bench ${r.appVersion} sustained ${r.durationMin} min (no cooldown) - ${r.model}")
        appendLine("${r.deviceManufacturer} ${r.deviceModel} - Android ${r.androidRelease}")
        appendLine("threads-only tg: " + (to?.passes ?: emptyList()).joinToString("  ") { fmt(it.tg) })
        appendLine("auto         tg: " + (opt?.passes ?: emptyList()).joinToString("  ") { fmt(it.tg) })
        appendLine("threads-only thermal: " + (to?.passes ?: emptyList()).joinToString("  ") { thermalLabel(it.peakThermalStatus) })
        appendLine("auto         thermal: " + (opt?.passes ?: emptyList()).joinToString("  ") { thermalLabel(it.peakThermalStatus) })
    }.trim()

    // ---- shared formatting ----

    fun fmt(x: Double) = if (x >= 100) "%.0f".format(x) else "%.1f".format(x)

    fun fmtSd(x: Double) = when {
        x >= 10 -> "%.0f".format(x)
        x >= 1 -> "%.1f".format(x)
        else -> "%.2f".format(x)
    }

    fun signed(p: Double) = (if (p >= 0) "+" else "") + "%.0f%%".format(p)

    fun pct(from: Double, to: Double) = if (from <= 0) "-" else signed((to / from - 1) * 100)

    fun statText(s: Stat) = when {
        s.n <= 0 -> "-"
        s.n == 1 -> fmt(s.median)
        else -> "${fmt(s.median)} ±${fmtSd(s.sd)}"
    }

    fun cellStat(s: Stat) = when {
        s.n <= 0 -> "-"
        s.n == 1 -> fmt(s.median)
        else -> "${fmt(s.median)}\n±${fmtSd(s.sd)}"
    }

    fun thermalLabel(status: Int) = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
