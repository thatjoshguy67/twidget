package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.core.HttpTransport
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class BufferError(val message: String)

data class BufferResult<T>(
    val value: T? = null,
    val errors: List<BufferError> = emptyList(),
) {
    val isSuccess: Boolean get() = value != null && errors.isEmpty()
}

data class BufferAccount(val id: String, val email: String, val name: String?)

data class BufferChannel(
    val id: String,
    val organizationId: String,
    val name: String,
    val displayName: String?,
    val service: String,
    val avatarUrl: String?,
    val isQueuePaused: Boolean,
)

data class BufferPost(
    val id: String,
    val channelId: String,
    val text: String,
    val status: String,
    val dueAt: Long?,
    val createdAt: Long?,
    val media: List<PublicUrlMedia> = emptyList(),
)

data class BufferPreSignedUpload(
    val url: String,
    val key: String,
    val bucket: String,
)

class BufferClient(
    context: Context,
    private val endpoint: String = API_URL,
    private val tokenOverride: String? = null,
) {
    private val appContext = context.applicationContext

    fun verifyAccount(): BufferResult<BufferAccount> = execute(ACCOUNT_QUERY) { data ->
        val account = data.getJSONObject("account")
        BufferAccount(account.getString("id"), account.getString("email"), account.optString("name").ifBlank { null })
    }

    fun listTwitterChannels(): BufferResult<List<BufferChannel>> {
        val organizations = execute(ORGANIZATIONS_QUERY) { data ->
            data.getJSONObject("account").getJSONArray("organizations").objects().map {
                it.getString("id")
            }
        }
        if (!organizations.isSuccess) return BufferResult(errors = organizations.errors)
        val channels = mutableListOf<BufferChannel>()
        organizations.value.orEmpty().forEach { organizationId ->
            val result = execute(
                CHANNELS_QUERY,
                JSONObject().put("organizationId", organizationId),
            ) { data ->
                data.getJSONArray("channels").objects().map { channel ->
                    BufferChannel(
                        id = channel.getString("id"),
                        organizationId = organizationId,
                        name = channel.getString("name"),
                        displayName = channel.optString("displayName").ifBlank { null },
                        service = channel.getString("service"),
                        avatarUrl = channel.optString("avatar").ifBlank { null },
                        isQueuePaused = channel.optBoolean("isQueuePaused"),
                    )
                }
            }
            if (!result.isSuccess) return BufferResult(errors = result.errors)
            channels += result.value.orEmpty().filter { it.service.equals("twitter", ignoreCase = true) }
        }
        return BufferResult(channels)
    }

    fun listPosts(
        organizationId: String,
        channelId: String,
        statuses: List<String> = listOf("scheduled", "draft", "sent", "error"),
        startDateMillis: Long? = null,
    ): BufferResult<List<BufferPost>> {
        val posts = mutableListOf<BufferPost>()
        var cursor: String? = null
        do {
            val variables = JSONObject()
                .put("organizationId", organizationId)
                .put("channelId", channelId)
                .put("statuses", JSONArray(statuses))
                .put("startDate", startDateMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                .put("after", cursor ?: JSONObject.NULL)
            val page = execute(POSTS_QUERY, variables) { data ->
                val result = data.getJSONObject("posts")
                val values = result.getJSONArray("edges").objects().map { edge ->
                    val post = edge.getJSONObject("node")
                    BufferPost(
                        id = post.getString("id"),
                        channelId = post.getString("channelId"),
                        text = post.optString("text"),
                        status = post.getString("status"),
                        dueAt = post.optIsoMillis("dueAt"),
                        createdAt = post.optIsoMillis("createdAt"),
                        media = post.optJSONArray("assets").toMediaList(),
                    )
                }
                values to result.getJSONObject("pageInfo").let { info ->
                    if (info.optBoolean("hasNextPage")) info.optString("endCursor").ifBlank { null } else null
                }
            }
            if (!page.isSuccess) return BufferResult(errors = page.errors)
            posts += page.value?.first.orEmpty()
            cursor = page.value?.second
        } while (cursor != null)
        return BufferResult(posts)
    }

    fun organizationIdForChannel(channelId: String): BufferResult<String> {
        val channels = listTwitterChannels()
        if (!channels.isSuccess) return BufferResult(errors = channels.errors)
        val channel = channels.value.orEmpty().firstOrNull { it.id == channelId }
            ?: return failure("The selected Buffer channel is no longer connected")
        return BufferResult(channel.organizationId)
    }

    // The upload-slot query is authorized per client on Buffer's gateway, so
    // this request mirrors the web dashboard's shape: its operation name, the
    // _o routing parameter, and the webapp client headers.
    fun preSignedUpload(
        organizationId: String,
        fileName: String,
        mimeType: String,
    ): BufferResult<BufferPreSignedUpload> = execute(
        PRESIGNED_UPLOAD_QUERY,
        JSONObject().put(
            "input",
            JSONObject()
                .put("organizationId", organizationId)
                .put("fileName", fileName)
                .put("mimeType", mimeType)
                .put("uploadType", "postAsset"),
        ),
        endpointOverride = "$endpoint/?_o=s3PreSignedURL",
        extraHeaders = WEBAPP_CLIENT_HEADERS,
    ) { data ->
        val payload = data.getJSONObject("s3PreSignedURL")
        BufferPreSignedUpload(
            url = payload.getString("url"),
            key = payload.getString("key"),
            bucket = payload.getString("bucket"),
        )
    }

    fun schedulePost(post: ScheduledPost): BufferResult<String> = mutatePost(post, false, false)

    fun saveDraft(post: ScheduledPost): BufferResult<String> = mutatePost(
        post,
        update = !post.remotePostId.isNullOrBlank(),
        saveToDraft = true,
    )

    fun updatePost(post: ScheduledPost): BufferResult<String> = mutatePost(post, true, false)

    fun deletePost(remotePostId: String): BufferResult<String> {
        if (remotePostId.isBlank()) return failure("A Buffer post ID is required")
        return execute(
            DELETE_MUTATION,
            JSONObject().put("input", JSONObject().put("id", remotePostId)),
        ) { data ->
            val payload = data.getJSONObject("deletePost")
            payload.optString("id").takeIf(String::isNotBlank)
                ?: error(payload.optString("message").ifBlank { "Buffer could not delete the post" })
        }
    }

    private fun mutatePost(post: ScheduledPost, update: Boolean, saveToDraft: Boolean): BufferResult<String> = runCatching {
        require(post.provider == ScheduleProvider.BUFFER) { "Post must use the Buffer provider" }
        require(post.accountUsername.isNotBlank()) { "Choose a Buffer X channel" }
        requireNotNull(post.scheduledAt) { "A scheduled time is required" }
        val input = BufferGraphQlCodec.postInput(post, saveToDraft)
        if (update) input.put("id", requireNotNull(post.remotePostId) { "A Buffer post ID is required" })
        val operation = if (update) EDIT_MUTATION else CREATE_MUTATION
        execute(operation, JSONObject().put("input", input)) { data ->
            val payload = data.getJSONObject(if (update) "editPost" else "createPost")
            payload.optJSONObject("post")?.optString("id")?.takeIf(String::isNotBlank)
                ?: error(payload.optString("message").ifBlank { "Buffer could not save the post" })
        }
    }.getOrElse { failure(it.message ?: "Buffer request failed") }

    private fun <T> execute(
        query: String,
        variables: JSONObject = JSONObject(),
        endpointOverride: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        parser: (JSONObject) -> T,
    ): BufferResult<T> = try {
        val token = tokenOverride?.takeIf(String::isNotBlank) ?: BufferOAuth.accessToken(appContext)
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val response = HttpTransport.post(
            endpointOverride ?: endpoint,
            body,
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Authorization" to "Bearer $token",
            ) + extraHeaders,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 25_000,
        )
        val root = runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
        if (response.code !in 200..299) {
            error(root.firstError() ?: "Buffer HTTP ${response.code}")
        }
        root.firstError()?.let { error(it) }
        BufferResult(parser(root.getJSONObject("data")))
    } catch (error: Exception) {
        failure(error.message ?: "Buffer request failed")
    }

    private fun <T> failure(message: String): BufferResult<T> = BufferResult(errors = listOf(BufferError(message)))

    companion object {
        const val API_URL = "https://api.buffer.com"

        private const val ACCOUNT_QUERY =
            "query Account { account { id email name } }"
        private const val ORGANIZATIONS_QUERY =
            "query Organizations { account { organizations { id } } }"
        private const val CHANNELS_QUERY =
            "query Channels(\$organizationId: OrganizationId!) { channels(input: { organizationId: \$organizationId }) { id name displayName service avatar isQueuePaused } }"
        private const val POSTS_QUERY =
            "query Posts(\$organizationId: OrganizationId!, \$channelId: ChannelId!, \$statuses: [PostStatus!], \$startDate: DateTime, \$after: String) { posts(first: 50, after: \$after, input: { organizationId: \$organizationId, filter: { channelIds: [\$channelId], status: \$statuses, startDate: \$startDate }, sort: [{ field: dueAt, direction: asc }] }) { edges { node { id channelId text status dueAt createdAt assets { __typename mimeType thumbnail source } } } pageInfo { hasNextPage endCursor } } }"
        private const val CREATE_MUTATION =
            "mutation Create(\$input: CreatePostInput!) { createPost(input: \$input) { ... on PostActionSuccess { post { id } } ... on InvalidInputError { message } ... on UnauthorizedError { message } ... on UnexpectedError { message } ... on RestProxyError { message } ... on LimitReachedError { message } } }"
        private const val EDIT_MUTATION =
            "mutation Edit(\$input: EditPostInput!) { editPost(input: \$input) { ... on PostActionSuccess { post { id } } ... on InvalidInputError { message } ... on UnauthorizedError { message } ... on UnexpectedError { message } ... on RestProxyError { message } ... on LimitReachedError { message } ... on NotFoundError { message } } }"
        private val WEBAPP_CLIENT_HEADERS = mapOf(
            "x-buffer-client-id" to "webapp-publishing",
            "x-buffer-client-name" to "webapp-publishing",
        )
        private const val PRESIGNED_UPLOAD_QUERY =
            "query s3PreSignedURL(\$input: S3PreSignedURLInput!) { s3PreSignedURL(input: \$input) { url key bucket } }"
        private const val DELETE_MUTATION =
            "mutation Delete(\$input: DeletePostInput!) { deletePost(input: \$input) { ... on DeletePostSuccess { id } ... on VoidMutationError { message } } }"
    }
}

internal object BufferGraphQlCodec {
    fun postInput(post: ScheduledPost, saveToDraft: Boolean): JSONObject {
        val first = post.thread.firstOrNull() ?: error("At least one thread item is required")
        val input = JSONObject()
            .put("text", first.text)
            .put("channelId", post.accountUsername)
            .put("schedulingType", "automatic")
            .put("mode", "customScheduled")
            .put("dueAt", Instant.ofEpochMilli(requireNotNull(post.scheduledAt)).toString())
            .put("saveToDraft", saveToDraft)
        if (post.thread.size == 1) {
            first.assetsJson().takeIf { it.length() > 0 }?.let { input.put("assets", it) }
        } else {
            val thread = JSONArray()
            post.thread.forEach { item ->
                JSONObject().put("text", item.text).also { value ->
                    item.assetsJson().takeIf { it.length() > 0 }?.let { value.put("assets", it) }
                }.let(thread::put)
            }
            input.put("metadata", JSONObject().put("twitter", JSONObject().put("thread", thread)))
        }
        return input
    }

    private fun ScheduleThreadItem.assetsJson(): JSONArray = JSONArray().apply {
        media.forEach { source ->
            val url = (source as? PublicUrlMedia)?.url
                ?: error("Buffer media must use a public URL")
            val kind = if (source.mimeType?.startsWith("video/", ignoreCase = true) == true) "video" else "image"
            put(JSONObject().put(kind, JSONObject().put("url", url)))
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

private fun JSONArray?.toMediaList(): List<PublicUrlMedia> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        val asset = optJSONObject(index) ?: return@mapNotNull null
        val url = asset.optString("source").ifBlank { asset.optString("thumbnail") }
        if (url.isBlank()) return@mapNotNull null
        PublicUrlMedia(url = url, mimeType = asset.optString("mimeType").ifBlank { null })
    }
}

private fun JSONObject.optIsoMillis(name: String): Long? = optString(name)
    .takeIf(String::isNotBlank)
    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun JSONObject.firstError(): String? {
    val errors = optJSONArray("errors") ?: return null
    return (0 until errors.length()).asSequence()
        .mapNotNull { errors.optJSONObject(it)?.optString("message")?.takeIf(String::isNotBlank) }
        .firstOrNull()
}
