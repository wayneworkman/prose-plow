package us.theworkmans.proseplow.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the text of the third-party notices becomes what the screen shows: the project's own list
 * line by line, the licence texts with the lines of each paragraph joined again, and never a
 * word added, dropped, changed or moved. Checked on small texts for each rule, and on the text
 * the app really ships for what the rules have to achieve there.
 */
class ThirdPartyNoticesTextTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile

    /** The text the build puts into the app. */
    private val shipped: String by lazy { File(module, "notices/NOTICES.txt").readText() }

    private val rule = "=".repeat(78)
    private val thin = "-".repeat(78)

    private fun paragraph(text: String, indent: Int = 0, centred: Boolean = false, separated: Boolean = true) =
        NoticesBlock.Paragraph(text, indent, centred, separated)

    /** The words of a text in their order, without its dividing lines. */
    private fun words(text: String): List<String> =
        text.lines().filterNot { line -> line.trim().let { it.length >= 4 && (it.all { c -> c == '=' } || it.all { c -> c == '-' }) } }
            .flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() }

    private fun words(blocks: List<NoticesBlock>): List<String> = blocks.flatMap { block ->
        when (block) {
            NoticesBlock.Rule -> emptyList()
            is NoticesBlock.Heading -> block.text.split(Regex("\\s+"))
            is NoticesBlock.Paragraph -> block.text.split(Regex("\\s+"))
        }
    }

    /** The blocks of the shipped text that come after the heading of the licence texts. */
    private fun licenceBlocks(): List<NoticesBlock> {
        val blocks = ThirdPartyNoticesText.blocks(shipped)
        val heading = blocks.indexOf(NoticesBlock.Heading(ThirdPartyNoticesText.LICENCE_TEXTS))
        assertTrue("the text has the heading of the licence texts", heading >= 0)
        return blocks.drop(heading + 1)
    }

    @Test
    fun theProjectsOwnListIsShownLineByLineHoweverLongALineIs() {
        val long = "The following components are used under a licence, and this sentence is far longer than a page is wide, on purpose."
        val text = listOf(
            long,
            "",
            "  - A library",
            "    Copyright 2020 Someone, with a notice that is long enough to reach the end of a page of eighty",
            "    com.example: library 1.0",
            "      A notice, quoted",
        ).joinToString("\n")
        assertEquals(
            listOf(
                paragraph(long),
                paragraph("- A library", indent = 2),
                paragraph("Copyright 2020 Someone, with a notice that is long enough to reach the end of a page of eighty", indent = 4, separated = false),
                paragraph("com.example: library 1.0", indent = 4, separated = false),
                paragraph("A notice, quoted", indent = 6, separated = false),
            ),
            ThirdPartyNoticesText.blocks(text),
        )
    }

    @Test
    fun aLineAboveADividingLineIsAHeadingAndTwoDividingLinesInARowAreOne() {
        val text = listOf(rule, "PART 1. COMPONENTS", rule, "", thin, "1.1  A LICENCE", thin, "", "A paragraph.").joinToString("\n")
        assertEquals(
            listOf(
                NoticesBlock.Rule,
                NoticesBlock.Heading("PART 1. COMPONENTS"),
                NoticesBlock.Rule,
                NoticesBlock.Heading("1.1  A LICENCE"),
                NoticesBlock.Rule,
                paragraph("A paragraph."),
            ),
            ThirdPartyNoticesText.blocks(text),
        )
    }

    @Test
    fun theLastLineOfAParagraphIsNotAHeadingBecauseADividingLineFollowsIt() {
        val text = listOf("First line of two,", "second line.", rule, "", "After it.").joinToString("\n")
        assertEquals(
            listOf(paragraph("First line of two,"), paragraph("second line.", separated = false), NoticesBlock.Rule, paragraph("After it.")),
            ThirdPartyNoticesText.blocks(text),
        )
    }

    private fun licenceText(vararg lines: String): List<NoticesBlock> =
        ThirdPartyNoticesText.blocks((listOf(rule, ThirdPartyNoticesText.LICENCE_TEXTS, rule, "") + lines).joinToString("\n")).drop(3)

    @Test
    fun theLinesOfALicenceParagraphAreJoinedAgain() {
        assertEquals(
            listOf(
                paragraph(
                    "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and " +
                        "associated documentation files (the \"Software\"), to deal in the Software without restriction.",
                ),
                paragraph("The above copyright notice shall be included in all copies."),
            ),
            licenceText(
                "Permission is hereby granted, free of charge, to any person obtaining a copy",
                "of this software and associated documentation files (the \"Software\"), to deal",
                "in the Software without restriction.",
                "",
                "The above copyright notice shall be included in all copies.",
            ),
        )
    }

    @Test
    fun aLineThatWasCutBeforeAWordWithACapitalIsJoinedWhenThePageWasFull() {
        // The first line has 59 characters with its blanks, as the shortest such line in the Apache licence has.
        assertEquals(
            listOf(paragraph("(a) You must give any other recipients of the Work or Derivative Works a copy of this License; and", indent = 6)),
            licenceText(
                "      (a) You must give any other recipients of the Work or",
                "          Derivative Works a copy of this License; and",
            ),
        )
    }

    @Test
    fun aShortLineThatEndsOnPurposeStaysALineOfItsOwn() {
        assertEquals(
            listOf(
                paragraph("Copyright (C) 2012 The Android Open Source Project"),
                paragraph("All rights reserved.", separated = false),
                paragraph("University of Illinois/NCSA"),
                paragraph("Open Source License", separated = false),
            ),
            licenceText(
                "Copyright (C) 2012 The Android Open Source Project",
                "All rights reserved.",
                "",
                "University of Illinois/NCSA",
                "Open Source License",
            ),
        )
    }

    @Test
    fun aShortLineIsCarriedOnByALineThatBeginsWithASmallLetterButNotByAnAddress() {
        assertEquals(
            listOf(
                paragraph("* Neither the name of Google Inc. nor the names of its contributors may be used to endorse products."),
                paragraph("Developed by the team at"),
                paragraph("http://llvm.org", separated = false),
            ),
            licenceText(
                "* Neither the name of Google Inc. nor the names of its",
                "  contributors may be used to endorse products.",
                "",
                "Developed by the team at",
                "http://llvm.org",
            ),
        )
    }

    @Test
    fun everyListEntryOfALicenceBeginsAParagraphOfItsOwn() {
        assertEquals(
            listOf(
                paragraph("Redistribution and use in source and binary forms are permitted provided that the following conditions are met:"),
                paragraph("* Redistributions of source code must retain the above copyright notice, this list of conditions.", indent = 1, separated = false),
                paragraph("* Redistributions in binary form must reproduce the above copyright notice in the documentation.", indent = 1, separated = false),
                paragraph("1) It will be in a separate directory tree with its own licence file, or", separated = false),
                paragraph("2) It will contain specific terms at the top of every file.", separated = false),
                paragraph("(b) You must cause any modified files to carry prominent notices.", indent = 6, separated = false),
                paragraph("2. Grant of Copyright License.", indent = 3, separated = false),
            ),
            licenceText(
                "Redistribution and use in source and binary forms are permitted provided that",
                "the following conditions are met:",
                " * Redistributions of source code must retain the above copyright notice,",
                "   this list of conditions.",
                " * Redistributions in binary form must reproduce the above copyright notice",
                "   in the documentation.",
                "1) It will be in a separate directory tree with its own licence file, or",
                "2) It will contain specific terms at the top of every file.",
                "      (b) You must cause any modified files to carry prominent notices.",
                "   2. Grant of Copyright License.",
            ),
        )
    }

    @Test
    fun theCentredTitleLinesOfALicenceStayThreeLines() {
        assertEquals(
            listOf(
                paragraph("Apache License", indent = 33, centred = true),
                paragraph("Version 2.0, January 2004", indent = 27, centred = true, separated = false),
                paragraph("http://www.apache.org/licenses/", indent = 24, centred = true, separated = false),
                paragraph("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", indent = 3),
            ),
            licenceText(
                "                                 Apache License",
                "                           Version 2.0, January 2004",
                "                        http://www.apache.org/licenses/",
                "",
                "   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            ),
        )
    }

    @Test
    fun aTextWithWindowsLineEndsIsReadTheSame() {
        val text = listOf(rule, "A HEADING", rule, "", "A line.", "Another.").joinToString("\r\n")
        assertEquals(ThirdPartyNoticesText.blocks(text.replace("\r\n", "\n")), ThirdPartyNoticesText.blocks(text))
        assertEquals(listOf(NoticesBlock.Rule, NoticesBlock.Heading("A HEADING"), NoticesBlock.Rule), ThirdPartyNoticesText.blocks(text).take(3))
    }

    @Test
    fun anEmptyTextHasNoBlocks() {
        assertEquals(emptyList<NoticesBlock>(), ThirdPartyNoticesText.blocks(""))
        assertEquals(emptyList<NoticesBlock>(), ThirdPartyNoticesText.blocks("\n\n  \n"))
    }

    // --- The text the app ships ---

    @Test
    fun noWordOfTheShippedTextIsAddedDroppedChangedOrMovedOnItsWayToTheScreen() {
        assertEquals(words(shipped), words(ThirdPartyNoticesText.blocks(shipped)))
    }

    @Test
    fun theShippedTextHasTheHeadingOfTheLicenceTextsOnceAndTheListInFrontOfIt() {
        val blocks = ThirdPartyNoticesText.blocks(shipped)
        assertEquals(1, blocks.count { it == NoticesBlock.Heading(ThirdPartyNoticesText.LICENCE_TEXTS) })
        assertEquals(1, shipped.lines().count { it.trim() == ThirdPartyNoticesText.LICENCE_TEXTS })

        // In front of that heading every line of the file is one paragraph or one heading: nothing is joined there.
        val own = shipped.substringBefore("\n" + ThirdPartyNoticesText.LICENCE_TEXTS + "\n")
        val lines = own.lines().map { it.trim() }.filter { line -> line.isNotEmpty() && !(line.all { it == '=' } || line.all { it == '-' }) }
        val shown = blocks.take(blocks.indexOf(NoticesBlock.Heading(ThirdPartyNoticesText.LICENCE_TEXTS))).mapNotNull { block ->
            when (block) {
                NoticesBlock.Rule -> null
                is NoticesBlock.Heading -> block.text
                is NoticesBlock.Paragraph -> block.text
            }
        }
        assertEquals(lines, shown)
    }

    /**
     * A paragraph of a licence that begins with a small letter would be the second half of a
     * sentence that was not joined to its first: the sign that a licence text is wrapped in a
     * way the rules do not know. The one thing that may begin so is an address.
     */
    @Test
    fun noParagraphOfAShippedLicenceBeginsInTheMiddleOfASentence() {
        val halves = licenceBlocks().filterIsInstance<NoticesBlock.Paragraph>()
            .filter { it.text[0] in 'a'..'z' && !it.text.startsWith("http") }
            .map { it.text.take(60) }
        assertEquals(emptyList<String>(), halves)
    }

    @Test
    fun theShippedLicencesComeOutAsTheirParagraphs() {
        val paragraphs = licenceBlocks().filterIsInstance<NoticesBlock.Paragraph>().map { it.text }

        // The MIT permission notice is three paragraphs, whole.
        assertTrue(
            paragraphs.contains(
                "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated " +
                    "documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation " +
                    "the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and " +
                    "to permit persons to whom the Software is furnished to do so, subject to the following conditions:",
            ),
        )
        assertTrue(paragraphs.contains("The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software."))

        // A clause of the Apache licence is one paragraph from its letter to its end.
        val clause = paragraphs.filter { it.startsWith("(d) If the Work includes a \"NOTICE\" text file") }
        assertEquals("once in the Apache licence and once in the LLVM project's copy of it", 2, clause.size)
        for (text in clause) assertTrue(text.endsWith("cannot be construed as modifying the License."))

        // Copyright lines stay lines of their own, and so do the entries of a list.
        for (line in listOf("Copyright (C) 2012 The Android Open Source Project", "Copyright (c) 2016, the R8 project authors.", "All rights reserved.")) {
            assertTrue(line, paragraphs.contains(line))
        }
        assertTrue(
            paragraphs.contains(
                "* Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products derived " +
                    "from this software without specific prior written permission.",
            ),
        )
        assertEquals(listOf("Apache License", "Apache License"), paragraphs.filter { it == "Apache License" })

        // The three title lines of the Apache licence, in both copies of it, are what is centred, and nothing else is.
        val centred = licenceBlocks().filterIsInstance<NoticesBlock.Paragraph>().filter { it.centred }.map { it.text }
        assertEquals(
            listOf("Apache License", "Version 2.0, January 2004", "http://www.apache.org/licenses/").let { it + it },
            centred,
        )
    }

    /**
     * Every heading stands apart in the same way: under a line. Where the text itself has none
     * above a heading (a licence's heading straight under the entry before it), the screen is
     * told to draw one. Only headings are ever given one, and the blocks stay as they were.
     */
    @Test
    fun everyHeadingHasALineAboveItTheTextsOwnOrTheScreens() {
        val blocks = ThirdPartyNoticesText.blocks(shipped)
        val before = blocks.toList()
        val headings = blocks.indices.filter { blocks[it] is NoticesBlock.Heading }
        assertTrue(headings.size > 3)

        val drawn = blocks.indices.filter { ThirdPartyNoticesText.headingLacksRuleAbove(blocks, it) }
        assertTrue("only headings", drawn.all { it in headings })
        for (index in headings) {
            val ruledInTheText = index == 0 || blocks[index - 1] == NoticesBlock.Rule
            assertEquals((blocks[index] as NoticesBlock.Heading).text, !ruledInTheText, index in drawn)
        }
        assertTrue("the shipped text has both kinds", drawn.isNotEmpty() && drawn.size < headings.size)
        assertTrue(
            "the one that was seen on the phone is among them",
            drawn.any { (blocks[it] as NoticesBlock.Heading).text.contains("MIT LICENSE") },
        )
        assertEquals("nothing of the text is touched by asking", before, blocks)

        // A heading at the very top needs nothing above it.
        val top = listOf(NoticesBlock.Heading("At the top"), NoticesBlock.Paragraph("Text", indent = 0, centred = false, separated = false), NoticesBlock.Heading("Under text"))
        assertEquals(listOf(false, false, true), top.indices.map { ThirdPartyNoticesText.headingLacksRuleAbove(top, it) })
    }
}
