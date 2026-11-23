package us.theworkmans.proseplow.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.model.ModelCard
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.ui.AppToasts
import us.theworkmans.proseplow.ui.Links

/**
 * The way to a model's card on Hugging Face: a row that shows the address in full and says
 * that it opens in the browser. A tap hands the address to the system, which opens it with the
 * phone's own browser; nothing is loaded inside this app, which has no network permission and
 * no browser of its own. When no app on the phone opens web addresses, a short message says so.
 *
 * A link in an answer is only handed on after the user has seen it and confirmed, because a
 * model wrote it and what such a link says and where it leads can differ. This row asks
 * nothing: the address is the app's own, fixed in the catalogue, built in one place
 * ([ModelCard]) and shown on the row that opens it. Leaving for the browser is leaving the
 * app like any other way out of it, and the lock treats it as that.
 *
 * Shows nothing for a model whose repository is not written as one (no address is better than
 * a wrong one).
 */
@Composable
fun ModelCardLink(model: ModelSpec, modifier: Modifier = Modifier) {
    val address = ModelCard.address(model) ?: return
    val url = ModelCard.url(model) ?: return
    val context = LocalContext.current
    val noApp = stringResource(R.string.link_no_app)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.model_card_open), role = Role.Button) {
                if (!Links.open(context, url)) AppToasts.show(context, noApp)
            }
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, alignment = Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.model_card), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.model_card_address, address),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
