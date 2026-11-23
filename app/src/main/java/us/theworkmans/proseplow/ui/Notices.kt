package us.theworkmans.proseplow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Something the user has to be told, with what can be done about it: a tinted card with a title,
 * a sentence or two, an optional technical line, and its actions at the end. It is a polite live
 * region, so a screen reader says it when it appears.
 *
 * @param problem true for something that went wrong (the error colours); false for a note.
 * @param technical a detail for whoever has to find the cause, shown small; null to leave it out.
 * @param actions the buttons, most important last. They wrap when they do not fit on one line.
 *   Null for a notice that nothing can be done about here.
 */
@Composable
fun NoticeCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    problem: Boolean = true,
    technical: String? = null,
    actions: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            if (!technical.isNullOrEmpty()) {
                Text(technical, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            }
            if (actions != null) {
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
            } else {
                // The buttons would have given the card its lower margin.
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The heading of a group of settings. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}
