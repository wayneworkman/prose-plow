package us.theworkmans.proseplow.core.model

/**
 * Where a model's card is: the page of its repository on Hugging Face, which says what the
 * model is, how it was trained and under which licence it is published.
 *
 * The address is built here and nowhere else, from the catalogue's
 * [ModelSpec.sourceRepository]. It is the one address the app itself offers to open, so what
 * can come out of this is held narrowly: `https://huggingface.co/<owner>/<name>`, where owner
 * and name are made of letters, digits, `.`, `_` and `-` and begin with a letter or a digit.
 * A repository that is written any other way yields no address at all, rather than one that
 * leads somewhere else: no other host, no further path, no query, no fragment and no user
 * name can be smuggled in through it.
 */
object ModelCard {
    /** The one host a model card is on. */
    const val HOST: String = "huggingface.co"

    private const val PART = "[A-Za-z0-9][A-Za-z0-9._-]{0,95}"
    private val REPOSITORY = Regex("$PART/$PART")

    /** True when [text] is a repository as Hugging Face names them: an owner, one slash, a name. */
    fun isRepository(text: String): Boolean = REPOSITORY.matches(text) && ".." !in text

    /** The card's address as it is shown, without the scheme (`huggingface.co/owner/name`); null when the model's repository is not one. */
    fun address(model: ModelSpec): String? = model.sourceRepository.takeIf(::isRepository)?.let { "$HOST/$it" }

    /** The card's address as it is opened; null when [address] is. */
    fun url(model: ModelSpec): String? = address(model)?.let { "https://$it" }
}
