package us.theworkmans.proseplow.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelFamily

/**
 * What the app calls each model on screen. The catalogue in the core carries English text for
 * its own tests and tools; everything the user reads comes from the string resources, found here
 * by model id. The resources are named after the id (`model_name_` or `model_description_`, then
 * the id with everything but letters and digits turned into underscores). A model the catalogue
 * gains needs an entry in both functions, which a unit test checks.
 *
 * A few lines of the screens are worded for the kind of model rather than the model: a model
 * that continues text is not asked a question and does not answer. Those are chosen here by the
 * model's family, with every family named, so that a new family cannot be added without a
 * decision about its wording.
 */
object ModelText {

    /** The model's name, or null for an id this version does not know (a chat stored by another build). */
    @StringRes
    fun nameRes(modelId: String): Int? = when (modelId) {
        ModelCatalog.QWEN35_0_8B.id -> R.string.model_name_qwen3_5_0_8b
        ModelCatalog.PEACEBELL_V1_291M.id -> R.string.model_name_peacebell_v1_291m
        ModelCatalog.BORIS_2_INSTRUCT.id -> R.string.model_name_boris_2_instruct
        ModelCatalog.SHARD_2.id -> R.string.model_name_shard_2
        else -> null
    }

    /** One sentence about what the model is good for, or null for an id this version does not know. */
    @StringRes
    fun descriptionRes(modelId: String): Int? = when (modelId) {
        ModelCatalog.QWEN35_0_8B.id -> R.string.model_description_qwen3_5_0_8b
        ModelCatalog.PEACEBELL_V1_291M.id -> R.string.model_description_peacebell_v1_291m
        ModelCatalog.BORIS_2_INSTRUCT.id -> R.string.model_description_boris_2_instruct
        ModelCatalog.SHARD_2.id -> R.string.model_description_shard_2
        else -> null
    }

    /** The family of the model [modelId], or null for no model or one this version does not know. */
    private fun family(modelId: String?): ModelFamily? = modelId?.let { ModelCatalog.find(it) }?.family

    /** The title of a chat that has no message yet. */
    @StringRes
    fun emptyTitleRes(modelId: String?): Int = when (family(modelId)) {
        ModelFamily.COMPLETION -> R.string.empty_title_completion
        ModelFamily.QWEN35_CHATML, ModelFamily.PEACEBELL_CHATML, ModelFamily.PLAIN_CHATML, null -> R.string.empty_title
    }

    /** The line under that title, which takes the model's name: what the model will do with what is written. */
    @StringRes
    fun emptyHintRes(modelId: String?): Int = when (family(modelId)) {
        ModelFamily.COMPLETION -> R.string.empty_hint_completion
        ModelFamily.QWEN35_CHATML, ModelFamily.PEACEBELL_CHATML, ModelFamily.PLAIN_CHATML, null -> R.string.empty_hint
    }

    /** The hint in the empty field the user writes in. */
    @StringRes
    fun messageHintRes(modelId: String?): Int = when (family(modelId)) {
        ModelFamily.COMPLETION -> R.string.message_hint_completion
        ModelFamily.QWEN35_CHATML, ModelFamily.PEACEBELL_CHATML, ModelFamily.PLAIN_CHATML, null -> R.string.message_hint
    }

    /**
     * What Copy puts on the clipboard for a reply of the model [modelId] whose stored text is
     * [text]. A chat model's reply without the blanks around it. A model that continues text
     * wrote every character of it as part of the text, leading whitespace included (the user's
     * text and the continuation are one text), so its reply is copied exactly as written; only
     * the display drops the leading whitespace, which Markdown would otherwise take for a block
     * of code.
     */
    fun copiedReply(modelId: String?, text: String): String = when (family(modelId)) {
        ModelFamily.COMPLETION -> text
        ModelFamily.QWEN35_CHATML, ModelFamily.PEACEBELL_CHATML, ModelFamily.PLAIN_CHATML, null -> text.trim()
    }

    /** Why the model [modelId] has no system prompt to set, as a line that takes the model's name. */
    @StringRes
    fun noSystemPromptRes(modelId: String?): Int = when (family(modelId)) {
        ModelFamily.COMPLETION -> R.string.system_prompt_unsupported_completion
        ModelFamily.QWEN35_CHATML, ModelFamily.PEACEBELL_CHATML, ModelFamily.PLAIN_CHATML, null -> R.string.system_prompt_unsupported
    }

    /**
     * True when [detail], the technical detail of a failure to load a model, is the engine's
     * fixed way of saying that the phone had too little memory available for it. That failure
     * has plain words of its own, and its detail is not shown: it would say the same less well.
     */
    fun isTooLittleMemory(detail: String?): Boolean = detail == LlamaEngine.TOO_LITTLE_MEMORY

    /** The plain words for a model that could not be loaded, by the [detail] of the failure. */
    @StringRes
    fun loadFailedDetailRes(detail: String?): Int =
        if (isTooLittleMemory(detail)) R.string.error_model_too_little_memory_detail else R.string.error_model_load_failed_detail
}

/** The name of the model [modelId] as shown everywhere; its id when this version does not know it. */
@Composable
fun modelName(modelId: String): String = ModelText.nameRes(modelId)?.let { stringResource(it) } ?: modelId

/** What the model [modelId] is good for, or null when this version does not know it. */
@Composable
fun modelDescription(modelId: String): String? = ModelText.descriptionRes(modelId)?.let { stringResource(it) }
