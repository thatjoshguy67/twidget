package com.tjg.twidget

import android.content.Context
import org.json.JSONObject
import java.time.Instant

data class PostponeError(
    val message: String,
    val field: String? = null,
    val code: String? = null,
)

data class PostponeResult<T>(
    val value: T? = null,
    val errors: List<PostponeError> = emptyList(),
    val unsupported: Boolean = false,
) {
    val isSuccess: Boolean get() = value != null && errors.isEmpty() && !unsupported
}

data class PostponeProfile(
    val id: String,
    val username: String,
    val email: String?,
)

data class PostponeSocialAccount(
    val id: String,
    val username: String,
    val formattedUsername: String?,
    val name: String?,
    val platform: String,
    val avatarUrl: String?,
    val isConnected: Boolean,
    val isEnabled: Boolean,
)

data class PostponeLibraryItem(
    val id: String,
    val name: String,
    val url: String?,
    val thumbnailUrl: String?,
    val mimeType: String?,
    val size: Long?,
)

data class PostponeLibraryPage(
    val total: Int,
    val items: List<PostponeLibraryItem>,
)

data class PostponeMutation(
    val remotePostId: String?,
)

class PostponeClient(
    context: Context,
    private val endpoint: String = API_URL,
    private val apiKeyOverride: String? = null,
) {
    private val appContext = context.applicationContext

    fun verifyProfile(): PostponeResult<PostponeProfile> =
        executeAndParse(PostponeGraphQlCodec.profileRequest(), PostponeGraphQlCodec::parseProfile)

    fun listTwitterSocialAccounts(): PostponeResult<List<PostponeSocialAccount>> =
        executeAndParse(
            PostponeGraphQlCodec.socialAccountsRequest(),
            PostponeGraphQlCodec::parseSocialAccounts,
        )

    fun browseContentLibrary(
        search: String? = null,
        page: Int = 1,
        limit: Int = 25,
    ): PostponeResult<PostponeLibraryPage> {
        require(page > 0) { "Page must be positive" }
        require(limit in 1..100) { "Postpone supports between 1 and 100 media items per page" }
        return executeAndParse(
            PostponeGraphQlCodec.mediaRequest(search, page, limit),
            PostponeGraphQlCodec::parseMedia,
        )
    }

    fun scheduleTweet(post: ScheduledPost): PostponeResult<PostponeMutation> =
        runCatching {
            require(post.provider == ScheduleProvider.POSTPONE) { "Post must use the Postpone provider" }
            PostponeGraphQlCodec.tweetMutationRequest(
                post,
                update = false,
                maxTextLength = accountTextLimit(post),
            )
        }.fold(
            onSuccess = { executeAndParse(it) { raw -> PostponeGraphQlCodec.parseMutation(raw, "scheduleTweet") } },
            onFailure = { failure(it) },
        )

    fun updateScheduledTweet(post: ScheduledPost): PostponeResult<PostponeMutation> =
        runCatching {
            require(post.provider == ScheduleProvider.POSTPONE) { "Post must use the Postpone provider" }
            require(!post.remotePostId.isNullOrBlank()) { "A remote post ID is required for updates" }
            PostponeGraphQlCodec.tweetMutationRequest(
                post,
                update = true,
                maxTextLength = accountTextLimit(post),
            )
        }.fold(
            onSuccess = {
                executeAndParse(it) { raw -> PostponeGraphQlCodec.parseMutation(raw, "updateScheduledTweet") }
            },
            onFailure = { failure(it) },
        )

    private fun accountTextLimit(post: ScheduledPost): Int {
        val account = post.accountId?.takeIf(String::isNotBlank) ?: post.accountUsername
        return SchedulePolicy.textLimit(TwidgetStore.currentStats(appContext, account).isVerified)
    }

    /**
     * Postpone documents deletePlatformPost rather than a dedicated cancel
     * mutation. A soft delete moves the scheduled Twitter post to trash.
     */
    fun cancelScheduledTweet(remotePostId: String): PostponeResult<PostponeMutation> =
        deleteScheduledTweet(remotePostId, hardDelete = false)

    fun deleteScheduledTweet(
        remotePostId: String,
        hardDelete: Boolean = false,
    ): PostponeResult<PostponeMutation> {
        if (remotePostId.isBlank()) return failure(IllegalArgumentException("A remote post ID is required"))
        return executeAndParse(
            PostponeGraphQlCodec.deleteRequest(remotePostId, hardDelete),
        ) { raw ->
            PostponeGraphQlCodec.parseMutation(raw, "deletePlatformPost", remotePostId)
        }
    }

    private fun <T> executeAndParse(
        requestBody: String,
        parser: (String) -> PostponeResult<T>,
    ): PostponeResult<T> = try {
        parser(execute(requestBody))
    } catch (error: Exception) {
        failure(error)
    }

    private fun execute(requestBody: String): String {
        val key = apiKeyOverride?.trim().orEmpty().ifBlank { readApiKey() }
        if (key.isBlank()) error("Postpone API key is not configured")
        val validatedBody = JSONObject(requestBody).toString()
        val response = HttpTransport.post(
            endpoint,
            validatedBody,
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Authorization" to "Bearer $key",
            ),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
        if (response.code !in 200..299) {
            val detail = PostponeGraphQlCodec.firstErrorMessage(response.body)
                ?: response.body.take(400).takeIf(String::isNotBlank)
                ?: "empty response"
            throw PostponeHttpError(response.code, "Postpone HTTP ${response.code}: $detail")
        }
        JSONObject(response.body)
        return response.body
    }

    private fun readApiKey(): String {
        return SecureCredentialStore.read(appContext, SecureCredentialStore.POSTPONE_API_KEY)
    }

    private fun <T> failure(error: Throwable): PostponeResult<T> =
        PostponeResult(errors = listOf(PostponeError(error.message ?: "Postpone request failed")))

    class PostponeHttpError(val statusCode: Int, message: String) : IllegalStateException(message)

    companion object {
        const val API_URL = "https://api.postpone.app/gql"
    }
}

internal object PostponeGraphQlCodec {
    private const val MUTATION_FIELDS =
        "success errors { field message } post { id }"

    fun profileRequest(): String = request(
        "query Profile { profile { id username email } }",
        jsonObject(),
        "Profile",
    )

    fun socialAccountsRequest(): String = request(
        """
        query TwitterAccounts(${'$'}platform: String) {
          socialAccounts(platform: ${'$'}platform) {
            id username formattedUsername name platform avatarUrl isConnected isEnabled
          }
        }
        """.trimIndent(),
        jsonObject("platform" to json("twitter")),
        "TwitterAccounts",
    )

    fun mediaRequest(search: String?, page: Int, limit: Int): String = request(
        """
        query Media(${'$'}search: String, ${'$'}page: Int, ${'$'}limit: Int) {
          media(search: ${'$'}search, page: ${'$'}page, limit: ${'$'}limit, orderBy: "-id") {
            total
            objects { id name url thumbnailUrl mimeType size }
          }
        }
        """.trimIndent(),
        jsonObject(
            "search" to json(search?.trim()?.takeIf(String::isNotBlank)),
            "page" to json(page),
            "limit" to json(limit),
        ),
        "Media",
    )

    fun tweetMutationRequest(
        post: ScheduledPost,
        update: Boolean,
        maxTextLength: Int = SchedulePolicy.STANDARD_TEXT_LENGTH,
    ): String {
        val scheduledAt = requireNotNull(post.scheduledAt) { "A scheduled time is required" }
        val issues = SchedulePolicy.validate(post, maxTextLength = maxTextLength)
        require(issues.isEmpty()) { issues.joinToString(" ") { it.message } }
        val inputValues = linkedMapOf<String, JsonValue>(
            "username" to json(post.accountUsername.trim().trimStart('@')),
            "postAt" to json(Instant.ofEpochMilli(scheduledAt).toString()),
            "thread" to JsonValue.ArrayValue(post.thread.mapIndexed(::tweetInput)),
        )
        if (update) inputValues["id"] = json(requireNotNull(post.remotePostId))
        val operation = if (update) "UpdateScheduledTweet" else "ScheduleTweet"
        val field = if (update) "updateScheduledTweet" else "scheduleTweet"
        return request(
            "mutation $operation(${'$'}input: ScheduleTweetInput!) { $field(input: ${'$'}input) { $MUTATION_FIELDS } }",
            jsonObject("input" to JsonValue.ObjectValue(inputValues)),
            operation,
        )
    }

    fun deleteRequest(remotePostId: String, hardDelete: Boolean): String = request(
        """
        mutation DeletePlatformPost(
          ${'$'}platform: SocialPlatform!,
          ${'$'}postId: ID!,
          ${'$'}hardDelete: Boolean
        ) {
          deletePlatformPost(
            platform: ${'$'}platform,
            postId: ${'$'}postId,
            hardDelete: ${'$'}hardDelete
          ) {
            success
            errors { field code message }
          }
        }
        """.trimIndent(),
        jsonObject(
            "platform" to json("TWITTER"),
            "postId" to json(remotePostId),
            "hardDelete" to json(hardDelete),
        ),
        "DeletePlatformPost",
    )

    fun parseProfile(raw: String): PostponeResult<PostponeProfile> {
        val root = parseRoot(raw)
        root.errors.takeIf { it.isNotEmpty() }?.let { return PostponeResult(errors = it) }
        val profile = root.data?.optionalObject("profile")
            ?: return missing("Postpone returned no profile")
        return runCatching {
            PostponeProfile(
                id = profile.string("id"),
                username = profile.string("username"),
                email = profile.optionalString("email"),
            )
        }.fold(
            onSuccess = { PostponeResult(value = it) },
            onFailure = { missing(it.message ?: "Invalid profile response") },
        )
    }

    fun parseSocialAccounts(raw: String): PostponeResult<List<PostponeSocialAccount>> {
        val root = parseRoot(raw)
        root.errors.takeIf { it.isNotEmpty() }?.let { return PostponeResult(errors = it) }
        val values = root.data?.values?.get("socialAccounts") as? JsonValue.ArrayValue
            ?: return missing("Postpone returned no social accounts")
        val accounts = values.values.mapNotNull { entry ->
            runCatching {
                val item = entry.asObject()
                PostponeSocialAccount(
                    id = item.string("id"),
                    username = item.string("username"),
                    formattedUsername = item.optionalString("formattedUsername"),
                    name = item.optionalString("name"),
                    platform = item.string("platform"),
                    avatarUrl = item.optionalString("avatarUrl"),
                    isConnected = item.boolean("isConnected"),
                    isEnabled = item.boolean("isEnabled"),
                )
            }.getOrNull()
        }.filter { it.platform.equals("twitter", ignoreCase = true) }
        return PostponeResult(value = accounts)
    }

    fun parseMedia(raw: String): PostponeResult<PostponeLibraryPage> {
        val root = parseRoot(raw)
        root.errors.takeIf { it.isNotEmpty() }?.let { return PostponeResult(errors = it) }
        val media = root.data?.optionalObject("media")
            ?: return missing("Postpone returned no content library")
        return runCatching {
            val items = media.array("objects").values.mapNotNull { entry ->
                runCatching {
                    val item = entry.asObject()
                    PostponeLibraryItem(
                        id = item.string("id"),
                        name = item.string("name"),
                        url = item.optionalString("url"),
                        thumbnailUrl = item.optionalString("thumbnailUrl"),
                        mimeType = item.optionalString("mimeType"),
                        size = item.optionalLong("size"),
                    )
                }.getOrNull()
            }
            PostponeLibraryPage(media.long("total").toInt(), items)
        }.fold(
            onSuccess = { PostponeResult(value = it) },
            onFailure = { missing(it.message ?: "Invalid content library response") },
        )
    }

    fun parseMutation(
        raw: String,
        field: String,
        knownPostId: String? = null,
    ): PostponeResult<PostponeMutation> {
        val root = parseRoot(raw)
        root.errors.takeIf { it.isNotEmpty() }?.let { return PostponeResult(errors = it) }
        val mutation = root.data?.optionalObject(field)
            ?: return missing("Postpone returned no $field result")
        val fieldErrors = parseErrors(mutation.values["errors"])
        val success = (mutation.values["success"] as? JsonValue.BooleanValue)?.value ?: false
        if (!success || fieldErrors.isNotEmpty()) {
            return PostponeResult(
                errors = fieldErrors.ifEmpty { listOf(PostponeError("$field was not successful")) },
            )
        }
        val remoteId = mutation.optionalObject("post")?.optionalString("id") ?: knownPostId
        if (remoteId.isNullOrBlank()) return missing("$field returned no post ID")
        return PostponeResult(value = PostponeMutation(remoteId))
    }

    fun firstErrorMessage(raw: String): String? =
        runCatching { parseRoot(raw).errors.firstOrNull()?.message }.getOrNull()

    private fun tweetInput(index: Int, item: ScheduleThreadItem): JsonValue {
        val local = item.media.filterIsInstance<LocalUriMedia>()
        require(local.isEmpty()) {
            "Postpone cannot upload content:// media through scheduleTweet; use a public URL or Content Library item"
        }
        val publicUrls = item.media.filterIsInstance<PublicUrlMedia>().map { it.url }
        val library = item.media.filterIsInstance<PostponeLibraryMedia>()
        require(library.size <= 1) {
            "Postpone's documented TweetInputType accepts one Content Library mediaName"
        }
        require(publicUrls.isEmpty() || library.isEmpty()) {
            "Do not mix mediaUrls with a Content Library mediaName in one tweet"
        }
        val values = linkedMapOf<String, JsonValue>(
            "text" to json(item.text),
            "order" to json(index),
        )
        if (publicUrls.isNotEmpty()) {
            values["mediaUrls"] = JsonValue.ArrayValue(publicUrls.map(::json))
        }
        library.firstOrNull()?.let { values["mediaName"] = json(it.name) }
        return JsonValue.ObjectValue(values)
    }

    private fun request(query: String, variables: JsonValue.ObjectValue, operationName: String): String =
        JsonText.stringify(
            jsonObject(
                "query" to json(query),
                "variables" to variables,
                "operationName" to json(operationName),
            )
        )

    private data class ParsedRoot(
        val data: JsonValue.ObjectValue?,
        val errors: List<PostponeError>,
    )

    private fun parseRoot(raw: String): ParsedRoot {
        val root = JsonText.parse(raw).asObject()
        return ParsedRoot(
            data = root.optionalObject("data"),
            errors = parseErrors(root.values["errors"]),
        )
    }

    private fun parseErrors(value: JsonValue?): List<PostponeError> {
        val array = value as? JsonValue.ArrayValue ?: return emptyList()
        return array.values.mapNotNull { entry ->
            runCatching {
                val error = entry.asObject()
                PostponeError(
                    message = error.string("message"),
                    field = error.optionalString("field"),
                    code = error.optionalString("code"),
                )
            }.getOrNull()
        }
    }

    private fun <T> missing(message: String): PostponeResult<T> =
        PostponeResult(errors = listOf(PostponeError(message)))
}
