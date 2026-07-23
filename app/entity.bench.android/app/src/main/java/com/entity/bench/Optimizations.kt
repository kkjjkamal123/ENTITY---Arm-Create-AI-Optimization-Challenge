package com.entity.bench

// The optimization indicator on the home screen. Every lever ENTITY ships is listed;
// a lever "glows" (solid inversion) only when it is actually live on the phone in hand.
// Silicon levers gate on the CPU's ISA flags; runtime levers that ship in every build
// are always live. Nothing here claims a kernel the build does not contain - the i8mm
// row lights because ggml's loaded variant runs its MATMUL_INT8 GEMM for Q4_0/Q8_0
// weights, not because of any bespoke kernel of ours.
object Optimizations {

    class Caps(
        val flags: Set<String>,     // DeviceInfo.cpuFlags: dotprod, i8mm, sve, sve2, sme, fp16
        val multiCluster: Boolean,  // more than one max-frequency tier -> pinning has somewhere to pin
        val currentReporting: Boolean,
    )

    data class Opt(val label: String, val on: (Caps) -> Boolean)

    // Silicon first (these are what separate one phone from another), then runtime policy.
    val ALL: List<Opt> = listOf(
        Opt("i8mm gemm")        { "i8mm" in it.flags },
        Opt("dotprod gemm")     { "dotprod" in it.flags },
        Opt("sve2 kernels")     { "sve2" in it.flags },
        Opt("sme")              { "sme" in it.flags },
        Opt("sme2 kleidiai")    { "sme2" in it.flags },
        Opt("fp16 vector")      { "fp16" in it.flags },
        Opt("kleidiai q4_0")    { "dotprod" in it.flags },
        Opt("big.LITTLE pin")   { it.multiCluster },
        Opt("adaptive threads") { true },
        Opt("adaptive ctx")     { true },
        Opt("thermal guard")    { true },
        Opt("mmap weights")     { true },
        Opt("energy telem")     { it.currentReporting },
    )

    fun evaluate(caps: Caps): List<Pair<String, Boolean>> = ALL.map { it.label to it.on(caps) }
}
