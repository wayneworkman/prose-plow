package us.theworkmans.proseplow.ui

import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCard
import us.theworkmans.proseplow.core.model.ModelCatalog

/**
 * Which links from a model's answer may be handed to another app: web addresses and mail, and
 * nothing that could make another app do something else (run a script, start a component, read
 * a file).
 */
class LinksTest {

    /** A context that only counts what it is asked to start. */
    private class RecordingContext : ContextWrapper(null) {
        var started = 0

        override fun startActivity(intent: Intent?) {
            started++
        }
    }

    @Test
    fun aLinkOfAKindTheAppDoesNotPassOnNeverReachesAnotherApp() {
        val context = RecordingContext()
        val refused = listOf(
            "javascript:alert(1)",
            "intent:#Intent;action=android.intent.action.CALL;end",
            "file:///data/data/x/files/lock.json",
            "content://com.example.provider/1",
            "tel:+15555550100",
            "example.org",
            "",
        )
        for (link in refused) assertFalse(link, Links.open(context, link))
        assertEquals("nothing was handed to another app", 0, context.started)
    }

    @Test
    fun everyModelsCardIsAnAddressTheOpenerHandsOn() {
        for (model in ModelCatalog.all) {
            val url = checkNotNull(ModelCard.url(model))
            assertTrue(url, url.startsWith("https://huggingface.co/"))
            assertTrue(url, Links.canOpen(url))
        }
    }

    @Test
    fun webAndMailLinksCanBeOpened() {
        for (link in listOf("http://example.org", "https://example.org/a?b=c#d", "mailto:someone@example.org", "https://[::1]/", "https:relative")) {
            assertTrue(link, Links.canOpen(link))
        }
    }

    @Test
    fun theSchemeIsRecognisedWhateverItsCase() {
        for (link in listOf("HTTP://example.org", "Https://example.org", "MAILTO:someone@example.org", "hTtPs://example.org")) {
            assertTrue(link, Links.canOpen(link))
        }
    }

    @Test
    fun everyOtherSchemeIsRefused() {
        val refused = listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "intent:#Intent;action=android.intent.action.CALL;end",
            "file:///data/data/x/files/lock.json",
            "content://com.example.provider/1",
            "data:text/html,<script>alert(1)</script>",
            "tel:+15555550100",
            "sms:+15555550100",
            "market://details?id=com.example",
            "android-app://com.example",
            "ftp://example.org/file",
            "geo:0,0",
            "about:blank",
            "blob:https://example.org/1",
            "view-source:https://example.org",
            "ws://example.org",
        )
        for (link in refused) assertFalse(link, Links.canOpen(link))
    }

    @Test
    fun whatHasNoSchemeIsNotALinkToOpen() {
        for (link in listOf("", "example.org", "www.example.org/path", "/a/path", "#fragment", "//example.org", "?query", " ")) {
            assertFalse("\"$link\"", Links.canOpen(link))
        }
    }

    @Test
    fun aSchemeIsOnlyWhatStandsBeforeTheFirstColon() {
        // A permitted scheme later in the text, or dressed up, opens nothing.
        val refused = listOf(
            "javascript:https://example.org",
            "intent:https://example.org#Intent;end",
            " https://example.org",
            "https ://example.org",
            "\thttps://example.org",
            "\nhttps://example.org",
            "httpss://example.org",
            "xhttp://example.org",
            "http\u0000://example.org",
            ":https://example.org",
            "https\u200B://example.org",
        )
        for (link in refused) assertFalse("\"$link\"", Links.canOpen(link))
        // The Turkish dotless i must not turn another word into "mailto" or "file" into something else.
        assertFalse(Links.canOpen("ma\u0131lto:someone@example.org"))
    }
}
