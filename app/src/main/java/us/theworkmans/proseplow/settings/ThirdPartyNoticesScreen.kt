package us.theworkmans.proseplow.settings

import android.content.res.Resources
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.theworkmans.proseplow.R

/** A paragraph is set in by this much for every blank it began with in the text. */
private const val INDENT_STEP_DP = 3

/** Deeper than this many blanks a paragraph is not set in any further: a phone is narrow. */
private const val INDENT_MAX_BLANKS = 8

/**
 * The whole text of the third-party notices.
 *
 * It is the raw resource `third_party_notices`, which only the build writes: the task
 * `generate<Variant>ThirdPartyNotices` copies `app/notices/NOTICES.txt` there after it has
 * compared the list of libraries with what the build really packages. It is a raw resource and
 * not a string of `strings.xml` because it is fifty kilobytes of other people's legal text that
 * must reach the reader word for word: a string resource would need every apostrophe and
 * quotation mark in it escaped, and is offered for translation.
 *
 * This reads a file and must not be called on the main thread.
 */
fun readThirdPartyNotices(resources: Resources): String =
    resources.openRawResource(R.raw.third_party_notices).bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * The third-party notices: every library, model and piece of artwork in the app that is someone
 * else's work, with its copyright lines, and the text of every licence once.
 *
 * The text is long, so it is a lazy list of its blocks and only what is on the screen is laid
 * out. Every paragraph wraps to the width of the screen; nothing scrolls sideways. All of it can
 * be selected and copied, like the About screen it is opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThirdPartyNoticesScreen(viewModel: ThirdPartyNoticesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.third_party_notices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        val inside = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
        when (val current = state) {
            ThirdPartyNoticesState.Loading -> Unit
            ThirdPartyNoticesState.Unavailable -> Text(
                stringResource(R.string.third_party_notices_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = inside.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            is ThirdPartyNoticesState.Shown -> SelectionContainer(modifier = inside) {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    // The three kinds of block are laid out differently; saying which is which lets the
                    // list reuse a paragraph's layout for the next paragraph while it scrolls.
                    items(current.blocks, contentType = { block -> block::class }) { block -> NoticesBlockRow(block) }
                }
            }
        }
    }
}

/** One block of the notices: a dividing line, a heading, or a paragraph set in as the text had it. */
@Composable
private fun NoticesBlockRow(block: NoticesBlock) {
    when (block) {
        NoticesBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        is NoticesBlock.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            // A screen reader can then go from one licence or one part to the next.
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        is NoticesBlock.Paragraph -> Text(
            block.text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = if (block.centred) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (block.centred) 0.dp else (minOf(block.indent, INDENT_MAX_BLANKS) * INDENT_STEP_DP).dp,
                    top = if (block.separated) 10.dp else 2.dp,
                ),
        )
    }
}
