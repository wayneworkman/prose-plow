package us.theworkmans.proseplow.terms

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.terms.Terms

/**
 * The statements of the terms, in their order, as the app shows them. Their wording is in the
 * string table, marked as not to be translated; the second statement names the states of
 * [Terms.BLOCKED_STATES] and is left out when there are none, so the count of statements is one
 * lower then.
 */
object TermsText {
    /** Every statement, in order. A test holds this list to the string table and to the digest of the terms. */
    private val statementIds = listOf(
        R.string.terms_statement_1,
        R.string.terms_statement_2,
        R.string.terms_statement_3,
        R.string.terms_statement_4,
        R.string.terms_statement_5,
        R.string.terms_statement_6,
        R.string.terms_statement_7,
        R.string.terms_statement_8,
        R.string.terms_statement_9,
        R.string.terms_statement_10,
    )

    /** The statement that names the states. */
    private val statesStatement = R.string.terms_statement_2

    /** How many statements are shown: every one, less the one that names the states when there are none. */
    val statementCount: Int get() = statementIds.size - if (Terms.statesPhrase() == null) 1 else 0

    /** The statements as they are shown, in order; exactly [statementCount] of them. */
    fun statements(resources: Resources): List<String> {
        val states = Terms.statesPhrase()
        return statementIds.mapNotNull { id ->
            if (id == statesStatement) states?.let { resources.getString(id, it) } else resources.getString(id)
        }
    }
}

/** [TermsText.statements] for the resources of the composition. */
@Composable
fun termsStatements(): List<String> {
    val resources = LocalResources.current
    return remember(resources) { TermsText.statements(resources) }
}
