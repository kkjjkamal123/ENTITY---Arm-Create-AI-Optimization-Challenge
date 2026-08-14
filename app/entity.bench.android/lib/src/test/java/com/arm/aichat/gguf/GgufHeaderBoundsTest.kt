package com.arm.aichat.gguf

import com.arm.aichat.internal.gguf.GgufMetadataReaderImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * What the GGUF reader does with a header it cannot trust.
 *
 * Every count and length in a GGUF header is a 64-bit field, and the parser drives its
 * loops with Ints. The conversions between the two used to be bare `toInt()` calls, which
 * is not a harmless narrowing: `2^32` truncates to `0`. A corrupt header did not fail - it
 * reported zero metadata pairs, or a zero-element array, having consumed none of the bytes
 * those items actually occupy. Parsing then continued from the wrong offset and read the
 * rest of the file, tensor table included, as garbage, with no exception anywhere to say
 * what had happened.
 *
 * These fixtures are hand-built headers rather than real files, because the interesting
 * cases are ones no real file contains. Each asserts the same thing: a header the reader
 * cannot make sense of produces an IOException naming the field, not a confusing error
 * three layers downstream.
 */
class GgufHeaderBoundsTest {

    private val reader = GgufMetadataReaderImpl(skipKeys = setOf("skip.me"), arraySummariseThreshold = -1)

    private fun read(bytes: ByteArray) = runBlocking {
        reader.readStructuredMetadata(bytes.inputStream())
    }

    // ---- little-endian writers, matching the format the reader expects -----------

    private class Gguf {
        val out = ByteArrayOutputStream()
        fun magic() = apply { out.write("GGUF".toByteArray()) }
        fun u32(v: Int) = apply {
            for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF)
        }
        fun u64(v: Long) = apply {
            for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }
        fun str(s: String) = apply {
            val b = s.toByteArray(Charsets.UTF_8)
            u64(b.size.toLong()); out.write(b)
        }
        /** A string whose declared length is a lie. */
        fun strWithLength(s: String, declared: Long) = apply {
            u64(declared); out.write(s.toByteArray(Charsets.UTF_8))
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** version 3, then tensorCount and kvCount as given. */
    private fun header(tensorCount: Long, kvCount: Long) =
        Gguf().magic().u32(3).u64(tensorCount).u64(kvCount)

    private companion object {
        const val TYPE_UINT32 = 4
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
    }

    // ---- the three narrowings ----------------------------------------------------

    @Test
    fun `a key-value count that truncates to zero is rejected, not silently believed`() {
        // 2^32 narrows to exactly 0. The old reader returned an empty metadata map having
        // consumed none of the real KV bytes - the single most confusing possible outcome,
        // because everything downstream then parsed from the wrong offset.
        val f = header(tensorCount = 1, kvCount = 1L shl 32)
            .str("general.architecture").u32(TYPE_STRING).str("llama")
            .bytes()

        val e = assertThrows(IOException::class.java) { read(f) }
        assertTrue("the message should name the field: ${e.message}",
            e.message!!.contains("metadata pair count"))
    }

    @Test
    fun `an array length that truncates to zero is rejected`() {
        val f = header(tensorCount = 1, kvCount = 1)
            .str("tokenizer.ggml.tokens").u32(TYPE_ARRAY).u32(TYPE_UINT32).u64(1L shl 32)
            .bytes()

        val e = assertThrows(IOException::class.java) { read(f) }
        assertTrue("the message should name the field: ${e.message}",
            e.message!!.contains("array element count"))
    }

    @Test
    fun `a negative length on a skipped string is rejected rather than skipping nothing`() {
        // skipFully's `while (remaining > 0)` reads a negative length as "skip 0 bytes",
        // so the stream pointer stayed on the string's own text and every subsequent key,
        // value and tensor record was parsed from the wrong offset - silently.
        val f = header(tensorCount = 1, kvCount = 1)
            .str("skip.me").u32(TYPE_STRING).strWithLength("some long template body", -1L)
            .bytes()

        val e = assertThrows(IOException::class.java) { read(f) }
        assertTrue("the message should name the field: ${e.message}",
            e.message!!.contains("skipped string length"))
    }

    // ---- the honest path still works --------------------------------------------

    @Test
    fun `a well-formed header still parses, including a skipped key`() {
        // general.file_type is present because ArchitectureInfo is dropped entirely when
        // the architecture name is the only thing known about it.
        val f = header(tensorCount = 0, kvCount = 4)
            .str("general.architecture").u32(TYPE_STRING).str("llama")
            .str("general.file_type").u32(TYPE_UINT32).u32(2)
            .str("skip.me").u32(TYPE_STRING).str("a chat template nobody needs here")
            .str("general.name").u32(TYPE_STRING).str("Llama 3.2 1B Instruct")
            .bytes()

        val meta = read(f)
        assertEquals(4L, meta.kvCount)
        assertEquals("llama", meta.architecture?.architecture)
        assertEquals(
            "the skipped key must be skipped by exactly its own length, leaving the key " +
                "after it readable",
            "Llama 3.2 1B Instruct", meta.basic.nameLabel,
        )
    }
}
