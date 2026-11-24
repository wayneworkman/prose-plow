package us.theworkmans.proseplow.core.terms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where a person stands in the terms flow: [step] is the statement on screen (0 for the first),
 * and equals [statementCount] on the confirmation screen; [typed] is what the confirmation field
 * holds. The only way forward is [agree] on each statement in order, so the confirmation screen
 * cannot be reached any other way, and [back] takes the agreement to the statement before away
 * again. Nothing here is a record: only the confirmation, which the lock stores, is.
 */
data class TermsProgress(val statementCount: Int, val step: Int = 0, val typed: String = "") {
    init {
        require(statementCount >= 1) { "There is at least one statement" }
        require(step in 0..statementCount) { "The step is a statement or the confirmation" }
    }

    /** The confirmation screen is showing: every statement was agreed to, in order. */
    val confirming: Boolean get() = step == statementCount

    /** "Agree and continue" may be pressed: the confirmation is showing and its field holds the agreement ([Terms.agrees]). */
    val canConfirm: Boolean get() = confirming && Terms.agrees(typed)

    /** "I agree" on the statement on screen: the next statement, or after the last the confirmation, with an empty field. On the confirmation, nothing. */
    fun agree(): TermsProgress = if (confirming) this else copy(step = step + 1, typed = "")

    /**
     * "Back": the statement before, shown as if for the first time, whose agreement has to be
     * given again; from the confirmation, the last statement, and what was typed is gone. Null on
     * the first statement, where going back leaves the flow.
     */
    fun back(): TermsProgress? = if (step == 0) null else copy(step = step - 1, typed = "")

    /** What the confirmation field holds now. Ignored on a statement, which has no field. */
    fun type(text: String): TermsProgress = if (confirming) copy(typed = text) else this
}

/**
 * The progress through the terms flow for the life of the process, and only for it: it is kept
 * in memory, so that turning the device or leaving the app and coming back keeps the place and
 * the typed text, and a new process starts at the first statement with an empty field. It is
 * started afresh ([restart]) after "I do not agree" and whenever the app is not in the flow, so
 * that the next time the flow is shown it begins at the first statement.
 */
class TermsFlow(private val statementCount: Int) {
    private val _progress = MutableStateFlow(TermsProgress(statementCount))
    val progress: StateFlow<TermsProgress> = _progress.asStateFlow()

    /** See [TermsProgress.agree]. */
    fun agree() = _progress.update { it.agree() }

    /** See [TermsProgress.back]; false when the first statement was on screen and nothing changed. */
    fun back(): Boolean {
        val before = _progress.value.back() ?: return false
        _progress.value = before
        return true
    }

    /** See [TermsProgress.type]. */
    fun type(text: String) = _progress.update { it.type(text) }

    /** Back to the first statement, with an empty field. */
    fun restart() {
        _progress.value = TermsProgress(statementCount)
    }
}
