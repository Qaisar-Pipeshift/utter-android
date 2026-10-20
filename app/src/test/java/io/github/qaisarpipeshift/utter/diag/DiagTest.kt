package io.github.qaisarpipeshift.utter.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagTest {

    @Test
    fun `capacity evicts the oldest event`() {
        val diag = Diag(clock = { 0L })
        for (i in 0..100) diag.event("e$i")

        val snapshot = diag.snapshot()

        assertEquals(100, snapshot.size)
        assertEquals("e1", snapshot.first().kind)
        assertEquals("e100", snapshot.last().kind)
    }

    @Test
    fun `default capacity is one hundred`() {
        val diag = Diag(clock = { 0L })
        repeat(150) { diag.event("e$it") }

        assertEquals(100, diag.snapshot().size)
    }

    @Test
    fun `detail is truncated to 120 characters`() {
        val diag = Diag(clock = { 0L })
        diag.event("size", "x".repeat(200))

        assertEquals(120, diag.snapshot().single().detail.length)
    }

    @Test
    fun `detail shorter than the limit is kept`() {
        val diag = Diag(clock = { 0L })
        diag.event("http", "200")

        assertEquals("200", diag.snapshot().single().detail)
    }

    @Test
    fun `snapshot is oldest to newest`() {
        var now = 5L
        val diag = Diag(clock = { now++ })
        diag.event("first")
        diag.event("second")

        assertEquals(listOf("first", "second"), diag.snapshot().map { it.kind })
        assertEquals(listOf(5L, 6L), diag.snapshot().map { it.tMs })
    }

    @Test
    fun `dump uses ISO time and omits an empty detail`() {
        var now = 0L
        val diag = Diag(clock = { now })
        diag.event("start")
        now = 1000L
        diag.event("http", "200")

        assertEquals(
            "1970-01-01T00:00:00Z start\n1970-01-01T00:00:01Z http 200",
            diag.dump(),
        )
    }

    @Test
    fun `empty buffer dumps an empty string`() {
        assertEquals("", Diag(clock = { 0L }).dump())
    }
}
