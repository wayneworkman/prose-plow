package us.theworkmans.proseplow.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The ViewModel of the third-party notices: the text is read once, not on the thread that made
 * the ViewModel, and the screen is told when it is there or that it could not be read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThirdPartyNoticesViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private val text = listOf("=".repeat(78), "THIRD-PARTY NOTICES", "=".repeat(78), "", "A library", "    Copyright 2020 Someone").joinToString("\n")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theTextIsReadOnceInTheBackgroundAndShownAsItsBlocks() {
        var reads = 0
        val viewModel = ThirdPartyNoticesViewModel(read = { reads++; text }, ioDispatcher = dispatcher)

        // Nothing was read while the ViewModel was made: the screen opens first.
        assertEquals(0, reads)
        assertEquals(ThirdPartyNoticesState.Loading, viewModel.state.value)

        scheduler.advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals(ThirdPartyNoticesState.Shown(ThirdPartyNoticesText.blocks(text)), viewModel.state.value)
        assertEquals(
            listOf(
                NoticesBlock.Rule,
                NoticesBlock.Heading("THIRD-PARTY NOTICES"),
                NoticesBlock.Rule,
                NoticesBlock.Paragraph("A library", indent = 0, centred = false, separated = true),
                NoticesBlock.Paragraph("Copyright 2020 Someone", indent = 4, centred = false, separated = false),
            ),
            (viewModel.state.value as ThirdPartyNoticesState.Shown).blocks,
        )

        // And never again.
        scheduler.advanceUntilIdle()
        assertEquals(1, reads)
    }

    @Test
    fun aTextThatCannotBeReadIsSaidToBeUnavailable() {
        val viewModel = ThirdPartyNoticesViewModel(read = { throw IOException("the resource is damaged") }, ioDispatcher = dispatcher)
        assertEquals(ThirdPartyNoticesState.Loading, viewModel.state.value)
        scheduler.advanceUntilIdle()
        assertEquals(ThirdPartyNoticesState.Unavailable, viewModel.state.value)
    }

    @Test
    fun aResourceThatIsNotThereIsSaidToBeUnavailableToo() {
        // What Android throws for a missing resource is not an IOException.
        val viewModel = ThirdPartyNoticesViewModel(read = { throw IllegalStateException("no such resource") }, ioDispatcher = dispatcher)
        scheduler.advanceUntilIdle()
        assertEquals(ThirdPartyNoticesState.Unavailable, viewModel.state.value)
    }
}
