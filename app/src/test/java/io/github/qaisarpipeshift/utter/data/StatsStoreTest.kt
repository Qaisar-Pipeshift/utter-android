package io.github.qaisarpipeshift.utter.data

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private var day: LocalDate = LocalDate.of(2026, 10, 20)
    private lateinit var store: StatsStore

    @Before
    fun setUp() {
        dir = folder.newFolder("stats")
        store = StatsStore(dir, clock = { day })
    }

    @Test
    fun `record accumulates in one day bucket`() = runBlocking {
        store.record("com.a", 10, 4_000)
        store.record("com.a", 5, 2_000)
        store.record("com.b", 3, 1_000)

        val days = store.days()

        assertEquals(1, days.size)
        assertEquals(3, days.first().takes)
        assertEquals(18, days.first().words)
        assertEquals(7_000L, days.first().speechMs)
        assertEquals(mapOf("com.a" to 2, "com.b" to 1), days.first().pkgCounts)
    }

    @Test
    fun `day rollover creates a new bucket`() = runBlocking {
        store.record("com.a", 10, 1_000)
        day = day.plusDays(1)
        store.record("com.a", 5, 500)

        val days = store.days()

        assertEquals(2, days.size)
        assertEquals(listOf(10, 5), days.map { it.words })
        assertEquals(15, store.summary().total.words)
        assertEquals(5, store.summary().today.words)
    }

    @Test
    fun `purges buckets older than thirty days`() = runBlocking {
        store.record("com.a", 1, 100)
        day = day.plusDays(30)
        store.record("com.a", 1, 100)

        val days = store.days()

        assertEquals(1, days.size)
        assertEquals(day, days.first().date)
    }

    @Test
    fun `corrupt file is treated as empty`() = runBlocking {
        File(dir, "stats.json").writeText("not json")

        store.record("com.a", 2, 200)

        assertEquals(1, store.days().size)
        assertEquals(2, store.days().first().words)
    }
}
