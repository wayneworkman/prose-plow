package us.theworkmans.proseplow.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The background writer on virtual time: what it writes, in which order, and when. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingWriterTest {

    /** Everything that was written, as "key=content", in order. */
    private val written = ArrayList<String>()
    private val failures = ArrayList<String>()

    private fun TestScope.writer(lazyMillis: Long = 1_000): Pair<CoalescingWriter, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return CoalescingWriter(scope, lazyMillis) { key, failure -> failures += "$key: ${failure.message}" } to scope
    }

    private fun CoalescingWriter.put(key: String, content: String, urgent: Boolean = true): Boolean =
        submit(key, urgent) { written += "$key=$content" }

    @Test
    fun urgentWritesRunInTheBackgroundInTheOrderTheyWereAskedFor() = runTest {
        val (writer, _) = writer()
        assertTrue(writer.isIdle)
        writer.put("a", "1")
        writer.put("b", "1")
        assertEquals("nothing is written on the caller's thread", emptyList<String>(), written)
        assertFalse(writer.isIdle)

        runCurrent()

        assertEquals(listOf("a=1", "b=1"), written)
        assertTrue(writer.isIdle)
    }

    @Test
    fun aNewerWriteForAKeyReplacesTheOneThatHasNotRunAndKeepsItsPlace() = runTest {
        val (writer, _) = writer()
        writer.put("a", "1")
        writer.put("b", "1")
        writer.put("a", "2")
        writer.put("a", "3")

        runCurrent()

        assertEquals("one write per key, the newest content, first come first served", listOf("a=3", "b=1"), written)
    }

    @Test
    fun aKeyCanBeWrittenAgainAfterItWasWritten() = runTest {
        val (writer, _) = writer()
        writer.put("a", "1")
        runCurrent()
        writer.put("a", "2")
        runCurrent()
        assertEquals(listOf("a=1", "a=2"), written)
    }

    @Test
    fun aLazyWriteWaitsForNewerOnesAndIsWrittenOnceWhenTheIntervalIsOver() = runTest {
        val (writer, _) = writer(lazyMillis = 1_000)
        writer.put("a", "1", urgent = false)
        advanceTimeBy(400)
        writer.put("a", "2", urgent = false)
        advanceTimeBy(599)
        runCurrent()
        assertEquals(emptyList<String>(), written)

        advanceTimeBy(1)
        runCurrent()

        assertEquals("the interval runs from the first lazy write, so the file is never further behind", listOf("a=2"), written)
        assertTrue(writer.isIdle)

        // The next lazy write starts a new interval.
        writer.put("a", "3", urgent = false)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf("a=2"), written)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a=2", "a=3"), written)
    }

    @Test
    fun anUrgentWriteTakesTheLazyOnesPlaceAtOnce() = runTest {
        val (writer, _) = writer()
        writer.put("a", "typing", urgent = false)
        writer.put("a", "sent")
        runCurrent()
        assertEquals(listOf("a=sent"), written)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("the lazy write it replaced never runs", listOf("a=sent"), written)
    }

    @Test
    fun aLazyWriteForAKeyThatIsAlreadyQueuedGoesWithIt() = runTest {
        val (writer, _) = writer()
        writer.put("a", "1")
        writer.put("a", "2", urgent = false)
        runCurrent()
        assertEquals(listOf("a=2"), written)
    }

    @Test
    fun flushWritesEverythingLazyNow() = runTest {
        val (writer, _) = writer()
        writer.put("a", "1", urgent = false)
        writer.put("b", "1", urgent = false)

        writer.flush()
        runCurrent()

        assertEquals(listOf("a=1", "b=1"), written)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("and nothing is written twice", listOf("a=1", "b=1"), written)
    }

    @Test
    fun closingWritesEverythingThatWasAskedForAndRefusesTheRest() = runTest {
        val (writer, _) = writer()
        writer.put("a", "1")
        writer.put("b", "draft", urgent = false)
        var closed = false
        launch {
            writer.closeAndJoin()
            closed = true
        }
        runCurrent()

        assertTrue(closed)
        assertEquals(listOf("a=1", "b=draft"), written)
        assertFalse("a closed writer accepts nothing", writer.put("c", "late"))
        assertFalse(writer.put("c", "late", urgent = false))
        writer.flush()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("a=1", "b=draft"), written)
    }

    @Test
    fun closingAnIdleWriterReturnsAtOnce() = runTest {
        val (writer, _) = writer()
        writer.closeAndJoin()
        assertTrue(writer.isIdle)
    }

    @Test
    fun aWriteThatFailsIsReportedAndTheOthersGoOn() = runTest {
        val (writer, _) = writer()
        var diskFull = true
        val save = { if (diskFull) throw IOException("No space left on device") else written += "a=1" }
        writer.submit("a") { save() }
        writer.put("b", "1")
        runCurrent()

        assertEquals(listOf("a: No space left on device"), failures)
        assertEquals(listOf("b=1"), written)

        // The key's next write is a new attempt.
        diskFull = false
        writer.submit("a") { save() }
        runCurrent()
        assertEquals(listOf("b=1", "a=1"), written)
        assertEquals(1, failures.size)
    }

    @Test
    fun cancellingTheScopeAbandonsWhatWasNotWritten() = runTest {
        val (writer, scope) = writer()
        writer.put("a", "1")
        writer.put("b", "lazy", urgent = false)
        scope.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList<String>(), written)
    }

    /**
     * What the session does when its time to close is up while a write is stuck in the disk: it
     * cancels the scope. The write that is running cannot be interrupted and finishes; nothing
     * that was queued behind it is started any more (the data key is gone by then), and the
     * scope's job, which the reset waits for, completes only when that write has returned.
     */
    @Test
    fun cancellingTheScopeWhileAWriteRunsLetsItFinishAndStartsNoOther() = runTest {
        val (writer, scope) = writer()
        val job = scope.coroutineContext.job
        var completedInsideTheWrite: Boolean? = null
        writer.submit("a") {
            written += "a started"
            scope.cancel() // Seen from inside the write that hangs: this happens while it is running.
            completedInsideTheWrite = job.isCompleted
            written += "a finished"
        }
        writer.put("b", "1")
        writer.put("c", "lazy", urgent = false)

        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("a started", "a finished"), written)
        assertEquals("not while the write was still running", false, completedInsideTheWrite)
        assertTrue("but once it had returned", job.isCompleted)
        assertEquals(emptyList<String>(), failures)
    }
}
