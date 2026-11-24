package us.theworkmans.proseplow.terms

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.terms.ButtonPlacement
import us.theworkmans.proseplow.core.terms.ButtonPlacer
import us.theworkmans.proseplow.core.terms.Terms
import us.theworkmans.proseplow.core.terms.TermsFlow
import us.theworkmans.proseplow.core.terms.TermsProgress
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.TermsAgreement
import kotlin.random.Random

/** The space kept free between the buttons of a statement and the edges of the lower half. */
private val BUTTON_MARGIN = 16.dp

/** The least empty space between the two buttons of a statement. */
private val BUTTON_GAP = 24.dp

/**
 * The terms flow while the lock asks for it ([us.theworkmans.proseplow.security.LockState.TermsRequired]):
 * the statements one at a time, then the confirmation. [flow] is where the person stands, kept
 * for the life of the process; the lock records the agreement ([AppLock.acceptTerms]).
 *
 * "I do not agree", on any screen of the flow, starts the flow afresh, tells the lock (which
 * records nothing and lets the process end once nothing of the app is on screen) and then
 * [onLeave], which leaves the app at once, without a question.
 */
@Composable
fun TermsGate(appLock: AppLock<*>, flow: TermsFlow, onLeave: () -> Unit) {
    val progress by flow.progress.collectAsStateWithLifecycle()
    val ui by appLock.termsUi.collectAsStateWithLifecycle()
    TermsFlowContent(
        progress = progress,
        statements = termsStatements(),
        busy = ui.busy,
        error = ui.error,
        onAgree = flow::agree,
        onBack = { flow.back() },
        onType = flow::type,
        onConfirm = appLock::acceptTerms,
        onDecline = {
            flow.restart()
            appLock.declineTerms()
            onLeave()
        },
    )
}

/**
 * The screens of the terms flow for [progress]: a statement of [statements], or the confirmation
 * once every one of them was agreed to. Everything it does is handed out; it holds nothing
 * itself but where the two buttons of a statement are, which a new statement or a new size of
 * the lower half draws again.
 *
 * The system's back action does what "Back" does; on the first statement it is left to the
 * system, which leaves the app.
 */
@Composable
fun TermsFlowContent(
    progress: TermsProgress,
    statements: List<String>,
    busy: Boolean,
    error: String?,
    onAgree: () -> Unit,
    onBack: () -> Unit,
    onType: (String) -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    require(statements.size == progress.statementCount) { "One step for each statement" }
    BackHandler(enabled = progress.step > 0, onBack = onBack)
    if (progress.confirming) {
        ConfirmationScreen(statements, progress.typed, progress.canConfirm, busy, error, onType, onConfirm, onBack, onDecline)
    } else {
        StatementScreen(progress.step, statements, onAgree, onBack, onDecline)
    }
}

/**
 * One statement. The upper half holds where the person stands, how to answer and the statement,
 * and scrolls when they do not fit; the lower half holds the two buttons and nothing else
 * ([PlacedButtons]). A screen reader's focus moves to the statement when it appears, and its
 * number is announced politely.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatementScreen(step: Int, statements: List<String>, onAgree: () -> Unit, onBack: () -> Unit, onDecline: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terms_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (step > 0) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            // Each statement is a screen of its own: its scroll position and focus start afresh.
            key(step) {
                val focus = remember { FocusRequester() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.terms_progress, step + 1, statements.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        stringResource(R.string.terms_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        statements[step],
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .focusRequester(focus)
                            .focusable(),
                    )
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
                HorizontalDivider()
                PlacedButtons(onAgree = onAgree, onDecline = onDecline, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/**
 * Where the two buttons of one statement are, and since when ([placedAtElapsed], on the device
 * clock; [Long.MAX_VALUE] before they are first placed, which lets nothing respond). Kept for as
 * long as the statement is on screen; drawn again whenever the area or the size of the buttons
 * changes, which [generation] counts.
 */
private class PlacementMemory {
    private var key: List<Int>? = null
    private var placement: ButtonPlacement? = null

    var placedAtElapsed: Long = Long.MAX_VALUE
        private set

    val generation: MutableIntState = mutableIntStateOf(0)

    /** The places for this area and these buttons: the same as before while nothing changed, new ones otherwise. */
    fun placementFor(width: Int, height: Int, buttonWidth: Int, buttonHeight: Int, margin: Int, gap: Int): ButtonPlacement {
        val wanted = listOf(width, height, buttonWidth, buttonHeight, margin, gap)
        placement?.let { if (key == wanted) return it }
        val fresh = ButtonPlacer.place(width, height, buttonWidth, buttonHeight, margin, gap, Random.Default)
        key = wanted
        placement = fresh
        placedAtElapsed = SystemClock.elapsedRealtime()
        generation.intValue++
        return fresh
    }
}

/**
 * The lower half of a statement screen: "I agree" and "I do not agree", alike in size, shape and
 * colours, at random places ([ButtonPlacer]). Both have the size of the larger one, as wide as
 * its label wants and no wider than the area allows (the label wraps then), and are placed again
 * whenever that size or the size of the area changes (the device is turned, the window resized,
 * the font size changed), without animation. When the area cannot hold both under the rules
 * they are stacked and it scrolls.
 *
 * For [Terms.UNRESPONSIVE_MILLIS] after every placement neither responds: both are shown as
 * disabled, and a tap that arrives anyway is ignored by the time it is made, on the device clock.
 */
@Composable
private fun PlacedButtons(onAgree: () -> Unit, onDecline: () -> Unit, modifier: Modifier) {
    val memory = remember { PlacementMemory() }
    val placed = memory.generation.intValue
    var responsive by remember { mutableStateOf(false) }
    LaunchedEffect(placed) {
        responsive = false
        if (placed == 0) return@LaunchedEffect
        delay((Terms.UNRESPONSIVE_MILLIS - (SystemClock.elapsedRealtime() - memory.placedAtElapsed)).coerceAtLeast(0))
        responsive = true
    }
    val whenResponding: (() -> Unit) -> Unit = { action ->
        if (Terms.buttonsRespond(memory.placedAtElapsed, SystemClock.elapsedRealtime())) action()
    }
    val density = LocalDensity.current
    val margin = with(density) { BUTTON_MARGIN.roundToPx() }
    val gap = with(density) { BUTTON_GAP.roundToPx() }
    BoxWithConstraints(modifier = modifier) {
        val areaHeight = constraints.maxHeight
        Layout(
            content = {
                TermsChoice(stringResource(R.string.terms_agree), responsive) { whenResponding(onAgree) }
                TermsChoice(stringResource(R.string.terms_disagree), responsive) { whenResponding(onDecline) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val room = (width - 2 * margin).coerceAtLeast(0)
            val buttonWidth = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(room)
            val buttonHeight = measurables.maxOf { it.maxIntrinsicHeight(buttonWidth) }
            val placeables = measurables.map { it.measure(Constraints.fixed(buttonWidth, buttonHeight)) }
            val placement = memory.placementFor(width, areaHeight, buttonWidth, buttonHeight, margin, gap)
            layout(width, placement.height) {
                placeables[0].place(placement.agree.x, placement.agree.y)
                placeables[1].place(placement.disagree.x, placement.disagree.y)
            }
        }
    }
}

/** One of the two buttons of a statement: both are made here, so that neither can be emphasised. */
@Composable
private fun TermsChoice(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(label, textAlign = TextAlign.Center)
    }
}

/**
 * The last screen of the flow, reached only through "I agree" on every statement: every
 * statement again, the two lines of the agreement, the field in which the words I agree are
 * typed, and "Agree and continue", which is enabled only while the field holds them
 * ([Terms.agrees]); the keyboard's action key does the same and nothing while it is disabled.
 * Its buttons do not move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmationScreen(
    statements: List<String>,
    typed: String,
    canConfirm: Boolean,
    busy: Boolean,
    error: String?,
    onType: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onDecline: () -> Unit,
) {
    val confirm = { if (canConfirm && !busy) onConfirm() }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.terms_confirm_title), modifier = Modifier.semantics { heading() }) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatementList(statements)
            Text(stringResource(R.string.terms_build_line), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.terms_confirm_line), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = typed,
                onValueChange = onType,
                label = { Text(stringResource(R.string.terms_field_label)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                error?.let { Text(stringResource(R.string.error_detail, it), color = MaterialTheme.colorScheme.error) }
            }
            Button(onClick = confirm, enabled = canConfirm && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.terms_continue))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.back)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDecline, enabled = !busy) { Text(stringResource(R.string.terms_disagree)) }
            }
        }
    }
}

/** Every statement, numbered from 1, as plain text. */
@Composable
private fun StatementList(statements: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        statements.forEachIndexed { index, statement ->
            Text(stringResource(R.string.terms_numbered, index + 1, statement), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The terms that were agreed to, for reading only: opened from About ("Terms") and from the
 * chat screen's "Information". It shows the version agreed to and the date and time "Agree and
 * continue" was pressed, in the device's time zone and format, then every statement, numbered,
 * and the line about this build and the source code. The text can be selected and copied;
 * nothing can be changed, and there is no button to agree or not.
 *
 * [agreement] is the one on record; without one (which a lock that asks for terms never opens
 * the app with) only the statements are shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsRecordScreen(agreement: TermsAgreement?, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terms_record_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (agreement != null) {
                    Text(stringResource(R.string.terms_record_version, agreement.version), style = MaterialTheme.typography.titleMedium)
                    val confirmedAt = remember(agreement.confirmedAtMillis) {
                        DateUtils.formatDateTime(
                            context,
                            agreement.confirmedAtMillis,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
                        )
                    }
                    Text(stringResource(R.string.terms_record_when, confirmedAt), style = MaterialTheme.typography.bodyMedium)
                }
                StatementList(termsStatements())
                Text(stringResource(R.string.terms_build_line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
