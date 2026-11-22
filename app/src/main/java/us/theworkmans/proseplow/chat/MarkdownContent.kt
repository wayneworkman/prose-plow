package us.theworkmans.proseplow.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.ui.rememberCopyToClipboard

/**
 * Renders an answer as Markdown.
 *
 * Nothing is ever fetched for it. A model can write an image or a link to any address; no image
 * loader is wired in at all ([NoOpImageTransformerImpl] returns no painter) and an image is shown
 * as its alternative text, both as a block and in the middle of a line. A link is only followed
 * when the user taps it, through whatever `LocalUriHandler` the screen provides, which asks
 * first.
 *
 * The previous rendering always stays until the next one is ready. Without that the answer would
 * be empty for a moment whenever it is parsed again (also at the moment it stops streaming), the
 * message would collapse, and a list that was scrolled to its end would find itself at its top.
 *
 * @param streaming while true the text is parsed again off the main thread as it grows; an
 *   answer that is first shown finished is parsed at once, so that it never appears empty.
 */
@Composable
fun MarkdownContent(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val imagePlaceholder = stringResource(R.string.image_placeholder)
    val imagePlaceholderNoAlt = stringResource(R.string.image_placeholder_no_alt)
    val annotator = remember(imagePlaceholder, imagePlaceholderNoAlt) {
        imageAsTextAnnotator { alt -> if (alt.isBlank()) imagePlaceholderNoAlt else imagePlaceholder.format(alt) }
    }
    val state = rememberMarkdownState(text, retainState = true, immediate = !streaming)
    Markdown(
        markdownState = state,
        modifier = modifier,
        imageTransformer = NoOpImageTransformerImpl(),
        annotator = annotator,
        components = AnswerComponents,
    )
}

private val AnswerComponents: MarkdownComponents = markdownComponents(
    codeFence = { model ->
        MarkdownCodeFence(model.content, model.node, block = { code, language, _ -> CodeBlock(code, language) })
    },
    codeBlock = { model ->
        MarkdownCodeBlock(model.content, model.node, block = { code, language, _ -> CodeBlock(code, language) })
    },
    image = { model -> ImageAsText(imageAltText(model.content, model.node)) },
    inlineImage = { model -> ImageAsText(imageAltText(model.content, model.node)) },
)

private fun imageAsTextAnnotator(placeholder: (alt: String) -> String): MarkdownAnnotator =
    markdownAnnotator(annotate = { content, child ->
        if (child.type == MarkdownElementTypes.IMAGE) {
            append(placeholder(imageAltText(content, child)))
            true
        } else {
            false
        }
    })

/** The `[alt]` text of an image node (searched among its descendants), without the brackets. */
private fun imageAltText(content: String, node: ASTNode): String {
    fun find(n: ASTNode): ASTNode? =
        if (n.type == MarkdownElementTypes.LINK_TEXT) n else n.children.firstNotNullOfOrNull(::find)
    return find(node)?.getTextInNode(content)?.toString()?.removePrefix("[")?.removeSuffix("]")?.trim().orEmpty()
}

@Composable
private fun ImageAsText(alt: String) {
    Text(
        text = if (alt.isBlank()) stringResource(R.string.image_placeholder_no_alt) else stringResource(R.string.image_placeholder, alt),
        style = MaterialTheme.typography.bodyMedium,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A block of code: monospace, never wrapped (it scrolls sideways instead), with the language the
 * model named and a button that copies the code alone, without the fence around it.
 */
@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val copy = rememberCopyToClipboard()
    val copiedMessage = stringResource(R.string.code_copied)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = language.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { copy(code, copiedMessage) }) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.copy_code),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}
