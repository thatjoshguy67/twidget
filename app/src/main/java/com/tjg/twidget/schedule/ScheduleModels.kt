package com.tjg.twidget.schedule

import java.util.UUID

enum class ScheduleProvider {
    LOCAL_REMINDER,
    BUFFER,
}

enum class ScheduleStatus {
    DRAFT,
    SCHEDULED,
    NEEDS_ACTION,
    PUBLISHED,
    FAILED,
    CANCELLED,
}

object ScheduleStateTransitions {
    fun canMove(from: ScheduleStatus, to: ScheduleStatus): Boolean {
        if (from == to) return true
        return when (from) {
            ScheduleStatus.DRAFT -> to in setOf(
                ScheduleStatus.SCHEDULED,
                ScheduleStatus.FAILED,
                ScheduleStatus.CANCELLED,
            )
            ScheduleStatus.SCHEDULED -> to in setOf(
                ScheduleStatus.DRAFT,
                ScheduleStatus.NEEDS_ACTION,
                ScheduleStatus.PUBLISHED,
                ScheduleStatus.FAILED,
                ScheduleStatus.CANCELLED,
            )
            ScheduleStatus.NEEDS_ACTION -> to in setOf(
                ScheduleStatus.DRAFT,
                ScheduleStatus.SCHEDULED,
                ScheduleStatus.PUBLISHED,
                ScheduleStatus.CANCELLED,
            )
            ScheduleStatus.FAILED -> to in setOf(
                ScheduleStatus.DRAFT,
                ScheduleStatus.SCHEDULED,
                ScheduleStatus.CANCELLED,
            )
            ScheduleStatus.PUBLISHED, ScheduleStatus.CANCELLED -> false
        }
    }
}

sealed class ScheduleMediaSource {
    abstract val mimeType: String?
}

data class LocalUriMedia(
    val uri: String,
    val displayName: String? = null,
    override val mimeType: String? = null,
) : ScheduleMediaSource()

data class PublicUrlMedia(
    val url: String,
    override val mimeType: String? = null,
) : ScheduleMediaSource()

data class ScheduleThreadItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val media: List<ScheduleMediaSource> = emptyList(),
)

data class ScheduledPost(
    val id: String = UUID.randomUUID().toString(),
    val provider: ScheduleProvider,
    val status: ScheduleStatus = ScheduleStatus.DRAFT,
    val accountId: String? = null,
    val accountUsername: String,
    val scheduledAt: Long?,
    val thread: List<ScheduleThreadItem>,
    val remotePostId: String? = null,
    val remoteSubmissionId: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val publishedAt: Long? = null,
    val pinned: Boolean = false,
    val deletedAt: Long? = null,
)

object ScheduleTrashPolicy {
    const val RETENTION_DAYS = 30
    const val RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L

    fun canMoveToTrash(status: ScheduleStatus): Boolean =
        status == ScheduleStatus.DRAFT || status == ScheduleStatus.NEEDS_ACTION
}

object ScheduleQueuePolicy {
    fun canPin(status: ScheduleStatus): Boolean = status in setOf(
        ScheduleStatus.DRAFT,
        ScheduleStatus.SCHEDULED,
        ScheduleStatus.FAILED,
    )

    fun includes(
        post: ScheduledPost,
        provider: ScheduleProvider,
        defaultUsername: String,
        bufferChannelId: String?,
    ): Boolean = when (provider) {
        ScheduleProvider.LOCAL_REMINDER ->
            post.provider == ScheduleProvider.LOCAL_REMINDER &&
                listOf(post.accountId, post.accountUsername).any {
                    ScheduleAccountMapping.normalize(it.orEmpty()) ==
                        ScheduleAccountMapping.normalize(defaultUsername)
                }
        ScheduleProvider.BUFFER ->
            post.provider == ScheduleProvider.BUFFER &&
                !bufferChannelId.isNullOrBlank() &&
                post.accountUsername == bufferChannelId
    }
}

object ScheduleNotificationPolicy {
    fun shouldNotifyBufferPublished(previous: ScheduledPost?, nextStatus: ScheduleStatus): Boolean =
        previous?.provider == ScheduleProvider.BUFFER &&
            previous.status == ScheduleStatus.SCHEDULED &&
            nextStatus == ScheduleStatus.PUBLISHED
}

enum class ScheduleValidationCode {
    EMPTY_THREAD,
    EMPTY_ITEM,
    TEXT_TOO_LONG,
    TOO_MANY_MEDIA,
    NOT_IN_FUTURE,
}

data class ScheduleValidationIssue(
    val code: ScheduleValidationCode,
    val itemIndex: Int? = null,
    val message: String,
)

object SchedulePolicy {
    const val STANDARD_TEXT_LENGTH = 280
    const val PREMIUM_TEXT_LENGTH = 25_000
    const val MAX_MEDIA_PER_ITEM = 4

    fun textLimit(isVerified: Boolean?): Int =
        if (isVerified == true) PREMIUM_TEXT_LENGTH else STANDARD_TEXT_LENGTH

    fun textLength(text: String): Int = text.codePointCount(0, text.length)

    fun validate(
        post: ScheduledPost,
        nowMillis: Long = System.currentTimeMillis(),
        maxTextLength: Int = STANDARD_TEXT_LENGTH,
    ): List<ScheduleValidationIssue> = validate(post.thread, post.scheduledAt, nowMillis, maxTextLength)

    fun validate(
        thread: List<ScheduleThreadItem>,
        scheduledAt: Long?,
        nowMillis: Long = System.currentTimeMillis(),
        maxTextLength: Int = STANDARD_TEXT_LENGTH,
    ): List<ScheduleValidationIssue> {
        val issues = mutableListOf<ScheduleValidationIssue>()
        if (thread.isEmpty()) {
            issues += ScheduleValidationIssue(
                ScheduleValidationCode.EMPTY_THREAD,
                message = "At least one thread item is required.",
            )
        }
        thread.forEachIndexed { index, item ->
            if (item.text.isBlank() && item.media.isEmpty()) {
                issues += ScheduleValidationIssue(
                    ScheduleValidationCode.EMPTY_ITEM,
                    index,
                    "A thread item must contain text or media.",
                )
            }
            if (textLength(item.text) > maxTextLength) {
                issues += ScheduleValidationIssue(
                    ScheduleValidationCode.TEXT_TOO_LONG,
                    index,
                    "Text must be $maxTextLength characters or fewer.",
                )
            }
            if (item.media.size > MAX_MEDIA_PER_ITEM) {
                issues += ScheduleValidationIssue(
                    ScheduleValidationCode.TOO_MANY_MEDIA,
                    index,
                    "An item can contain at most $MAX_MEDIA_PER_ITEM media attachments.",
                )
            }
        }
        if (scheduledAt == null || scheduledAt <= nowMillis) {
            issues += ScheduleValidationIssue(
                ScheduleValidationCode.NOT_IN_FUTURE,
                message = "The scheduled time must be in the future.",
            )
        }
        return issues
    }
}
