package com.example.llama

import com.arm.aichat.TurnStats

/**
 * Stores a [TurnStats] in the single `messages.stats` TEXT column.
 *
 * Six integers separated by commas, prefixed with a version number. JSON would need a parser
 * on a hot path that runs for every message of every restored conversation, and a column per
 * field would need a migration every time a field is added - this needs neither.
 *
 * [decode] returns null for anything it does not recognise rather than throwing. Stats are a
 * detail view; a row written by a future version, or corrupted, must never take down the
 * conversation it belongs to.
 */
object TurnStatsCodec {

    private const val VERSION = 1
    private const val FIELDS = 6

    fun encode(s: TurnStats): String = listOf(
        VERSION, s.promptTokens, s.generatedTokens, s.prefillMs, s.decodeMs,
        s.contextUsed, s.contextSize,
    ).joinToString(",")

    fun decode(raw: String?): TurnStats? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(',')
        if (parts.size != FIELDS + 1) return null
        val n = parts.map { it.trim().toLongOrNull() ?: return null }
        if (n[0] != VERSION.toLong()) return null
        return TurnStats(
            promptTokens = n[1].toInt(),
            generatedTokens = n[2].toInt(),
            prefillMs = n[3],
            decodeMs = n[4],
            contextUsed = n[5].toInt(),
            contextSize = n[6].toInt(),
        )
    }
}
