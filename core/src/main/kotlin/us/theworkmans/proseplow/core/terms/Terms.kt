package us.theworkmans.proseplow.core.terms

/**
 * The terms every user agrees to before the app can be used: the constants that decide which
 * terms this build asks for, and the rules of the flow that do not depend on a screen.
 *
 * The wording of the statements is in the app's string table, where every text the app shows
 * is; what is here is what that wording is made from ([BLOCKED_STATES], through [statesPhrase])
 * and what decides whether an agreement on record is still the one this build asks for
 * ([TERMS_VERSION]). It lives in the core, and nowhere else, because both the lock (which is
 * handed the version) and the screens read it, and neither may know the other.
 */
object Terms {
    /**
     * The version of the terms this build asks for. It is raised whenever the wording of a
     * statement, the wording of the two lines above the confirmation field, [BLOCKED_STATES] or
     * the default of how long messages are kept changes: an agreement on record with a lower
     * version is asked for again. A test of the app holds a digest of all of them to this
     * number, so that changing one without raising it fails the build.
     */
    const val TERMS_VERSION = 1

    /**
     * The US states named in the second statement: every state with an enacted law on chatbots
     * as of 23 November 2026, including laws not yet in force. Shown in alphabetical order
     * whatever order they are written in here ([statesPhrase]).
     */
    val BLOCKED_STATES: List<String> = listOf(
        "California",
        "Colorado",
        "Connecticut",
        "Georgia",
        "Hawaii",
        "Idaho",
        "Iowa",
        "Maine",
        "Nebraska",
        "New Hampshire",
        "New York",
        "Oregon",
        "Rhode Island",
        "Tennessee",
        "Utah",
        "Washington",
    )

    /**
     * The words the confirmation field must hold, once blanks at either end are dropped, every
     * run of blanks counts as one and capitals are ignored ([agrees]).
     */
    const val AGREEMENT_WORDS = "i agree"

    /**
     * For how long after the buttons of a statement are placed neither of them responds, on the
     * device clock: the second tap of a double tap must not land on a button that has just
     * moved under the finger.
     */
    const val UNRESPONSIVE_MILLIS = 1_000L

    /**
     * [states] as the second statement names them: in alphabetical order, one name alone, two
     * joined by " and ", three or more separated by ", " with " and " before the last. Null when
     * the list is empty, in which case the statement is left out altogether.
     */
    fun statesPhrase(states: List<String> = BLOCKED_STATES): String? {
        val sorted = states.sorted()
        return when (sorted.size) {
            0 -> null
            1 -> sorted[0]
            else -> sorted.dropLast(1).joinToString(", ") + " and " + sorted.last()
        }
    }

    /**
     * Whether [typed] is the agreement the confirmation asks for: [AGREEMENT_WORDS], give or take
     * blanks at either end, the length of a run of blanks between the words, and capitals.
     * Nothing else passes, a full stop included, because the point is that the words were typed.
     */
    fun agrees(typed: CharSequence): Boolean =
        typed.trim().split(BLANKS).joinToString(" ").lowercase() == AGREEMENT_WORDS

    /**
     * Whether the buttons placed at [placedAtElapsed] respond at [nowElapsed], both on the device
     * clock: only once [UNRESPONSIVE_MILLIS] have passed. A clock that reads earlier than the
     * placement (which no single boot can produce) does not let them respond.
     */
    fun buttonsRespond(placedAtElapsed: Long, nowElapsed: Long): Boolean = nowElapsed - placedAtElapsed >= UNRESPONSIVE_MILLIS

    private val BLANKS = Regex("\\s+")
}
