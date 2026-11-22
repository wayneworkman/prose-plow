package us.theworkmans.proseplow.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.model.ModelCatalog

/**
 * What the app calls each model on screen. The catalogue in the core carries English text for
 * its own tests and tools; everything the user reads comes from the string resources, found here
 * by model id. The resources are named after the id (`model_name_` or `model_description_`, then
 * the id with everything but letters and digits turned into underscores). A model the catalogue
 * gains needs an entry in both functions, which a unit test checks.
 */
object ModelText {

    /** The model's name, or null for an id this version does not know (a chat stored by another build). */
    @StringRes
    fun nameRes(modelId: String): Int? = when (modelId) {
        ModelCatalog.QWEN35_0_8B.id -> R.string.model_name_qwen3_5_0_8b
        ModelCatalog.PEACEBELL_V1_291M.id -> R.string.model_name_peacebell_v1_291m
        ModelCatalog.BORIS_2_INSTRUCT.id -> R.string.model_name_boris_2_instruct
        ModelCatalog.BORIS2_PEACEBELL_THINK.id -> R.string.model_name_boris2_peacebell_think
        else -> null
    }

    /** One sentence about what the model is good for, or null for an id this version does not know. */
    @StringRes
    fun descriptionRes(modelId: String): Int? = when (modelId) {
        ModelCatalog.QWEN35_0_8B.id -> R.string.model_description_qwen3_5_0_8b
        ModelCatalog.PEACEBELL_V1_291M.id -> R.string.model_description_peacebell_v1_291m
        ModelCatalog.BORIS_2_INSTRUCT.id -> R.string.model_description_boris_2_instruct
        ModelCatalog.BORIS2_PEACEBELL_THINK.id -> R.string.model_description_boris2_peacebell_think
        else -> null
    }
}

/** The name of the model [modelId] as shown everywhere; its id when this version does not know it. */
@Composable
fun modelName(modelId: String): String = ModelText.nameRes(modelId)?.let { stringResource(it) } ?: modelId

/** What the model [modelId] is good for, or null when this version does not know it. */
@Composable
fun modelDescription(modelId: String): String? = ModelText.descriptionRes(modelId)?.let { stringResource(it) }
