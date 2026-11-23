package us.theworkmans.proseplow.settings

/**
 * One piece of the third-party notices as the screen shows it. The text file is written for an
 * eighty-column page; a phone shows about fifty characters in a line, so the screen is given the
 * text's structure and lays it out for its own width.
 */
sealed interface NoticesBlock {

    /** A dividing line: a row of `=` or of `-` in the text. */
    data object Rule : NoticesBlock

    /** A heading: the line that stands directly above a dividing line. */
    data class Heading(val text: String) : NoticesBlock

    /**
     * A paragraph as one line without breaks, which the screen wraps to its width.
     *
     * @property indent how many blanks the paragraph began with in the text. A list entry, a
     *   quoted notice and a clause of a licence are told from what surrounds them by nothing else.
     * @property centred whether the text had the line in the middle of the page, as the title
     *   lines of a licence are.
     * @property separated whether a blank line, a heading or a dividing line stood before it.
     *   The screen keeps lines that followed each other in the text close together.
     */
    data class Paragraph(val text: String, val indent: Int, val centred: Boolean, val separated: Boolean) : NoticesBlock
}

/**
 * Turns the text of the third-party notices (`app/notices/NOTICES.txt`, which the build puts into
 * the app as a raw resource) into the blocks the screen shows.
 *
 * The text has two parts, and they are read differently:
 *
 * - Up to the heading [LICENCE_TEXTS] it is this project's own list of components, written with
 *   **one paragraph on one line**. Every line becomes a paragraph of its own, however long it is.
 * - After that heading come the licence texts, word for word and with the line breaks their
 *   authors gave them: they are wrapped by hand at seventy to eighty columns. Shown like that on
 *   a phone, every line would break a second time, a few words before its end. So the lines of
 *   a paragraph are joined again ([continues] decides which line carries on the one before it).
 *
 * Joining changes blanks and line breaks and nothing else: no word is added, dropped, changed
 * or moved, and the test of this class holds every licence text to that. The resource itself
 * keeps the texts exactly as they are.
 */
object ThirdPartyNoticesText {

    /** The heading after which the text is licence texts, to be shown with their lines joined. */
    const val LICENCE_TEXTS = "PART 2. LICENCE TEXTS"

    /** A line that begins with at least this many blanks was centred on the page by its author. */
    private const val CENTRED_FROM = 20

    /**
     * A line of a licence text that reaches this column was cut there because the page ended,
     * and the next line carries it on. In the texts the app shows, the longest line that ends
     * on purpose and is followed by another has 50 characters ("Copyright (C) 2012 The Android
     * Open Source Project"), and the shortest line that was cut before a word with a capital
     * has 59.
     */
    private const val FULL_LINE = 56

    /** The beginning of a list entry: a bullet, a dash, `(a)`, `1.` or `1)`, then a blank. */
    private val listEntry = Regex("""^(\*|-|\(\w{1,3}\)|\d{1,2}[.)])\s""")

    /**
     * True when the block at [index] of [blocks] is a heading that the text did not rule off
     * from what stands above it: a heading in the middle of the list, straight under the last
     * line of the entry before it. The headings at the top of the text have a line above them;
     * the screen gives these the same line and space, so that every heading looks alike. That
     * is a matter of how the blocks are drawn: the blocks, and so the text, stay as they are.
     */
    fun headingLacksRuleAbove(blocks: List<NoticesBlock>, index: Int): Boolean =
        blocks[index] is NoticesBlock.Heading && index > 0 && blocks[index - 1] != NoticesBlock.Rule

    /** The blocks of [text], in its order. */
    fun blocks(text: String): List<NoticesBlock> {
        val lines = text.replace("\r\n", "\n").split('\n').map { it.trimEnd() }
        val blocks = ArrayList<NoticesBlock>()
        var licenceTexts = false
        var separated = true
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.isEmpty() -> {
                    separated = true
                    index++
                }
                isRule(line) -> {
                    // Two dividing lines with nothing between them are one on the screen.
                    if (blocks.lastOrNull() != NoticesBlock.Rule) blocks += NoticesBlock.Rule
                    separated = true
                    index++
                }
                isHeading(lines, index) -> {
                    if (line.trim() == LICENCE_TEXTS) licenceTexts = true
                    blocks += NoticesBlock.Heading(line.trim())
                    separated = true
                    index++
                }
                else -> {
                    val indent = line.length - line.trimStart().length
                    val centred = indent >= CENTRED_FROM
                    val paragraph = StringBuilder(line.trim())
                    index++
                    if (licenceTexts && !centred) {
                        while (index < lines.size && continues(lines, index)) {
                            paragraph.append(' ').append(lines[index].trim())
                            index++
                        }
                    }
                    blocks += NoticesBlock.Paragraph(paragraph.toString(), indent, centred, separated)
                    separated = false
                }
            }
        }
        return blocks
    }

    /** A row of `=` or of `-`, at least four of them and nothing else. */
    private fun isRule(line: String): Boolean = line.length >= 4 && (line.all { it == '=' } || line.all { it == '-' })

    /**
     * A heading is a line of text directly above a dividing line, with a blank line, a dividing
     * line or nothing above itself: the last line of a paragraph that happens to be followed by
     * a dividing line is not one.
     */
    private fun isHeading(lines: List<String>, index: Int): Boolean {
        val below = lines.getOrNull(index + 1) ?: return false
        val above = lines.getOrNull(index - 1).orEmpty()
        return isRule(below) && (above.isEmpty() || isRule(above))
    }

    /**
     * Whether line [index] of a licence text carries on the line before it, which was cut because
     * the page ended, or begins something of its own.
     *
     * It begins something of its own when it is blank, a dividing line, a heading or the start of
     * a list entry. Otherwise it carries on when it begins with a small letter (a sentence does
     * not, and neither does a line of a title or of a copyright notice; an address does, and is
     * left alone), or when the line before it is a [FULL_LINE].
     */
    private fun continues(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (line.isEmpty() || isRule(line) || isHeading(lines, index)) return false
        val words = line.trim()
        if (listEntry.containsMatchIn(words)) return false
        val carriesOnASentence = words[0] in 'a'..'z' && !words.startsWith("http")
        return carriesOnASentence || lines[index - 1].length >= FULL_LINE
    }
}
