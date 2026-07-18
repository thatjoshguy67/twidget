package com.tjg.twidget.schedule

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.FileProvider
import androidx.picker.app.SeslDatePickerDialog
import androidx.picker.app.SeslTimePickerDialog
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class ScheduleComposeActivity : FoldablePopOverActivity() {
    private val store by lazy { ScheduleStore(this) }
    private val coordinator by lazy { ScheduleCoordinator(this) }

    private lateinit var composeUi: ScheduleComposeUi
    private var draftButton: AppCompatButton? = null
    private var saveButton: AppCompatButton? = null
    private var editorPost: ScheduledPost? = null
    private var editorProvider = ScheduleProvider.LOCAL_REMINDER
    private var editorAccount = ""
    private var editorTime = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    private val editorItems = mutableListOf<EditorItem>()
    private var mediaTarget = 0
    private var pendingCameraFile: File? = null
    private var pendingCameraUri: Uri? = null
    private var busy = false
    private var notificationWarningAccepted = false
    private var exactWarningAccepted = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationWarningAccepted = true
            if (!granted) toast(R.string.schedule_notification_permission)
            submitSchedule()
        }

    private val localMediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(SchedulePolicy.MAX_MEDIA_PER_ITEM)
    ) { uris ->
        val target = editorItems.getOrNull(mediaTarget) ?: return@registerForActivityResult
        val room = SchedulePolicy.MAX_MEDIA_PER_ITEM - target.media.size
        val selected = uris.take(room.coerceAtLeast(0))
        if (selected.isEmpty()) return@registerForActivityResult
        val targetId = target.id
        setBusy(true)
        AppExecutors.execute(
            onRejected = { runOnUiThread { setBusy(false); toast(R.string.schedule_busy) } },
        ) {
            val imported = selected.mapNotNull(::importPickedMedia)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) {
                    imported.forEach { it.file.delete() }
                    return@runOnUiThread
                }
                val currentTarget = editorItems.firstOrNull { it.id == targetId }
                if (currentTarget == null) {
                    imported.forEach { it.file.delete() }
                    return@runOnUiThread
                }
                val currentRoom = SchedulePolicy.MAX_MEDIA_PER_ITEM - currentTarget.media.size
                val retained = imported.take(currentRoom.coerceAtLeast(0))
                currentTarget.media += retained.map(ImportedMedia::source)
                imported.drop(retained.size).forEach { it.file.delete() }
                if (imported.size < selected.size) toast(R.string.schedule_media_permission_failed)
                composeUi.refreshMediaForActiveItem()
            }
        }
    }

    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri
        val file = pendingCameraFile
        pendingCameraUri = null
        pendingCameraFile = null
        if (!saved || uri == null) {
            file?.delete()
            return@registerForActivityResult
        }
        val target = editorItems.getOrNull(mediaTarget) ?: return@registerForActivityResult
        if (target.media.size >= SchedulePolicy.MAX_MEDIA_PER_ITEM) {
            file?.delete()
            return@registerForActivityResult
        }
        target.media += LocalUriMedia(
            uri = uri.toString(),
            displayName = file?.name,
            mimeType = "image/jpeg",
        )
        composeUi.refreshMediaForActiveItem()
    }

    private val closeCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = requestClose()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_compose)
        val root = findViewById<ToolbarLayout>(R.id.schedule_compose_root)
        val bottomBar = findViewById<View>(R.id.schedule_compose_bottom_bar)
        root.setNavigationButtonOnClickListener { requestClose() }
        applyEdgeToEdgeInsets(root) { navigationBarInset ->
            bottomBar.updateBottomMarginForNavigationBar(0, navigationBarInset)
        }
        onBackPressedDispatcher.addCallback(this, closeCallback)

        editorPost = intent.getStringExtra(EXTRA_SCHEDULE_ID)?.let(store::get)
        editorProvider = editorPost?.provider ?: ScheduleSettingsStore.defaultProvider(this)
        editorAccount = resolveEditorAccount(editorPost)
        val requestedTime = intent.getLongExtra(EXTRA_SCHEDULED_AT, -1L).takeIf { it > 0L }
        editorTime = Calendar.getInstance().apply {
            timeInMillis = editorPost?.scheduledAt?.takeIf { it > 0L }
                ?: requestedTime
                ?: (System.currentTimeMillis() + 60 * 60 * 1000L)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        editorItems += editorPost?.thread?.map {
            EditorItem(it.id, it.text, it.media.toMutableList())
        }.orEmpty().ifEmpty { listOf(EditorItem()) }

        composeUi = ScheduleComposeUi(this, root)
        composeUi.bind()
    }

    override fun onDestroy() {
        if (isFinishing) cleanupUnstoredOwnedMedia(editorItems.flatMap { it.media })
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.schedule_compose, menu)
        val actionView = menu.findItem(R.id.schedule_compose_save)?.actionView
        draftButton = actionView?.findViewById(R.id.schedule_compose_draft_button)
        saveButton = actionView?.findViewById(R.id.schedule_compose_save_button)
        draftButton?.setOnClickListener { saveDraft() }
        saveButton?.setOnClickListener { submitSchedule() }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasContent = composeHasContent()
        val draftEnabled = !busy && hasContent
        val saveEnabled = draftEnabled && !composeHasInvalidLength()
        draftButton?.isEnabled = draftEnabled
        draftButton?.alpha = if (draftEnabled) 1f else 0.45f
        saveButton?.isEnabled = saveEnabled
        saveButton?.alpha = if (saveEnabled) 1f else 0.45f
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.schedule_compose_save) return super.onOptionsItemSelected(item)
        submitSchedule()
        return true
    }

    private fun requestClose() {
        if (!composeHasContent() || editorPost != null) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_discard_title)
            .setMessage(R.string.schedule_discard_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.schedule_discard) { _, _ -> finish() }
            .setPositiveButton(R.string.schedule_save_draft) { _, _ -> saveDraft() }
            .show()
    }

    internal fun onComposeAttachMedia(index: Int) {
        mediaTarget = index
        if (editorProvider == ScheduleProvider.LOCAL_REMINDER) {
            localMediaPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
            return
        }
        showPublicUrlDialog()
    }

    internal fun onComposeTakePhoto(index: Int) {
        mediaTarget = index
        val target = editorItems.getOrNull(index) ?: return
        if (target.media.size >= SchedulePolicy.MAX_MEDIA_PER_ITEM) return
        if (editorProvider != ScheduleProvider.LOCAL_REMINDER) {
            toast(R.string.schedule_camera_local_only)
            return
        }
        val directory = File(filesDir, "scheduled-media").apply { mkdirs() }
        val file = File(directory, "camera-${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.update_files", file)
        pendingCameraFile = file
        pendingCameraUri = uri
        runCatching { cameraCapture.launch(uri) }.onFailure {
            pendingCameraFile = null
            pendingCameraUri = null
            file.delete()
            toast(R.string.schedule_camera_unavailable)
        }
    }

    internal fun onComposePickTimeRequested() = pickDate()

    private fun importPickedMedia(uri: Uri): ImportedMedia? {
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?: displayName?.substringAfterLast('.', "")
                ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val directory = File(filesDir, "scheduled-media").apply { mkdirs() }
        val file = File(
            directory,
            buildString {
                append("picker-")
                append(UUID.randomUUID())
                extension?.let { append('.').append(it.lowercase(Locale.ROOT)) }
            },
        )
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to read selected media")
            val ownedUri = FileProvider.getUriForFile(this, "$packageName.update_files", file)
            ImportedMedia(
                source = LocalUriMedia(
                    uri = ownedUri.toString(),
                    displayName = displayName,
                    mimeType = mimeType,
                ),
                file = file,
            )
        }.getOrElse {
            file.delete()
            null
        }
    }

    internal fun onComposeDownloadMedia(itemIndex: Int, mediaIndex: Int? = null) {
        val item = editorItems.getOrNull(itemIndex) ?: return
        val media = mediaIndex?.let { item.media.getOrNull(it)?.let(::listOf) } ?: item.media
        if (media.isEmpty()) return
        setBusy(true)
        AppExecutors.execute(
            onRejected = { runOnUiThread { setBusy(false); toast(R.string.schedule_busy) } },
        ) {
            val outcome = ScheduleMediaExporter.downloadItem(
                this,
                ScheduleThreadItem(item.id, item.text, media),
            )
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = when (outcome.result) {
                    ScheduleMediaExportResult.SAVED -> if (outcome.detail.isNullOrBlank()) {
                        getString(R.string.schedule_media_saved, outcome.savedCount)
                    } else {
                        getString(R.string.schedule_media_saved_partial, outcome.savedCount, outcome.detail)
                    }
                    ScheduleMediaExportResult.NOTHING_TO_SAVE -> getString(R.string.schedule_media_nothing_to_save)
                    ScheduleMediaExportResult.FAILED -> outcome.detail ?: getString(R.string.schedule_media_save_failed)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun onComposeAddThreadRequested() {
        if (editorItems.size >= MAX_THREAD_ITEMS) return
        editorItems += EditorItem()
        composeUi.refreshFromEditor(focusLast = true)
    }

    internal fun onComposeRemoveThreadRequested(index: Int) {
        if (editorItems.size <= 1 || index !in editorItems.indices) return
        cleanupUnstoredOwnedMedia(editorItems.removeAt(index).media)
        composeUi.refreshFromEditor()
    }

    internal fun composeItemCount(): Int = editorItems.size
    internal fun composeItemText(index: Int): String = editorItems.getOrNull(index)?.text.orEmpty()
    internal fun composeUpdateItemText(index: Int, value: String) {
        editorItems.getOrNull(index)?.text = value
        invalidateOptionsMenu()
    }
    internal fun composeItemMedia(index: Int): List<ScheduleMediaSource> = editorItems.getOrNull(index)?.media.orEmpty()
    internal fun composeRemoveMedia(itemIndex: Int, mediaIndex: Int) {
        val media = editorItems.getOrNull(itemIndex)?.media ?: return
        if (mediaIndex !in media.indices) return
        val removed = media.removeAt(mediaIndex)
        cleanupUnstoredOwnedMedia(listOf(removed))
        invalidateOptionsMenu()
    }
    internal fun composeHasContent(): Boolean = editorItems.any { it.text.isNotBlank() || it.media.isNotEmpty() }
    internal fun composeCharacterLimit(): Int = SchedulePolicy.textLimit(
        TwidgetStore.currentStats(this, editorAccount).isVerified
    )
    internal fun composeHasInvalidLength(): Boolean = editorItems.any {
        SchedulePolicy.textLength(it.text) > composeCharacterLimit()
    }
    internal fun composeIsBusy(): Boolean = busy
    internal fun composeAvatarUsername(): String = requestedUsername().ifBlank { editorAccount }
    internal fun composeTimeSummaryText(): String =
        SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(editorTime.time)
            .replace("AM", "am").replace("PM", "pm")
    internal fun composeDp(value: Int): Int = dp(value)

    private fun showPublicUrlDialog() {
        val index = mediaTarget.coerceIn(editorItems.indices)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.schedule_public_url_hint)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_add_public_url)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_add) { _, _ ->
                val value = input.text.toString().trim()
                val uri = Uri.parse(value)
                if ((uri.scheme == "https" || uri.scheme == "http") &&
                    editorItems[index].media.size < SchedulePolicy.MAX_MEDIA_PER_ITEM
                ) {
                    editorItems[index].media += PublicUrlMedia(value)
                    composeUi.refreshMediaForActiveItem()
                } else toast(R.string.schedule_invalid_public_url)
            }
            .show()
    }

    private fun pickDate() {
        SeslDatePickerDialog(this, { _, year, month, day ->
            editorTime.set(year, month, day)
            pickTime()
        }, editorTime.get(Calendar.YEAR), editorTime.get(Calendar.MONTH), editorTime.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        SeslTimePickerDialog(this, { _, hour, minute ->
            editorTime.set(Calendar.HOUR_OF_DAY, hour)
            editorTime.set(Calendar.MINUTE, minute)
            composeUi.refreshTimeSummary()
        }, editorTime.get(Calendar.HOUR_OF_DAY), editorTime.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun buildEditedPost(): ScheduledPost {
        val old = editorPost
        val providerUsername = if (editorProvider == ScheduleProvider.BUFFER) {
            ScheduleSettingsStore.bufferChannelFor(this, editorAccount).orEmpty()
        } else editorAccount
        return ScheduledPost(
            id = old?.id ?: UUID.randomUUID().toString(),
            provider = editorProvider,
            status = old?.status ?: ScheduleStatus.DRAFT,
            accountId = editorAccount,
            accountUsername = providerUsername,
            scheduledAt = editorTime.timeInMillis,
            thread = editorItems.map { ScheduleThreadItem(it.id, it.text, it.media.toList()) },
            remotePostId = old?.remotePostId?.takeIf { old.provider == editorProvider },
            remoteSubmissionId = old?.remoteSubmissionId?.takeIf { old.provider == editorProvider },
            errorMessage = null,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            publishedAt = old?.publishedAt,
            pinned = old?.pinned ?: false,
        )
    }

    private fun saveDraft() {
        if (editorProvider == ScheduleProvider.BUFFER && editorAccount.isBlank()) {
            toast(R.string.schedule_buffer_account_required)
            return
        }
        val draft = buildEditedPost()
        if (draft.provider == ScheduleProvider.BUFFER) {
            runRemote { coordinator.saveDraftWithProvider(draft) }
        } else {
            editorPost = coordinator.saveDraft(draft)
            toast(R.string.schedule_draft_saved)
            finish()
        }
    }

    private fun submitSchedule() {
        if (composeHasInvalidLength()) {
            toast(R.string.schedule_character_limit_error)
            return
        }
        if (editorProvider == ScheduleProvider.BUFFER && editorAccount.isBlank()) {
            toast(R.string.schedule_buffer_account_required)
            return
        }
        if (editorProvider == ScheduleProvider.BUFFER &&
            ScheduleSettingsStore.bufferChannelFor(this, editorAccount).isNullOrBlank()
        ) {
            showErrors(listOf(getString(R.string.schedule_buffer_mapping_required, editorAccount)))
            return
        }
        val post = buildEditedPost()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationWarningAccepted
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (post.provider == ScheduleProvider.BUFFER) {
            runRemote { coordinator.schedule(post) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !(getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() && !exactWarningAccepted
        ) {
            showExactAlarmChoice()
            return
        }
        val previous = editorPost
        if (previous?.provider == ScheduleProvider.BUFFER && !previous.remotePostId.isNullOrBlank()) {
            runRemote {
                val cancelled = coordinator.cancel(previous.id)
                if (cancelled?.isSuccess != true) cancelled else coordinator.schedule(post)
            }
        } else showCoordinatorResult(coordinator.schedule(post))
    }

    private fun showExactAlarmChoice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_exact_alarm_title)
            .setMessage(R.string.schedule_exact_alarm_message)
            .setNegativeButton(R.string.schedule_use_approximate) { _, _ ->
                exactWarningAccepted = true
                toast(R.string.schedule_approximate_notice)
                submitSchedule()
            }
            .setPositiveButton(R.string.schedule_open_system_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
            .show()
    }

    private fun runRemote(work: () -> ScheduleCoordinatorResult?) {
        setBusy(true)
        AppExecutors.execute(
            onRejected = { runOnUiThread { setBusy(false); toast(R.string.schedule_busy) } },
        ) {
            val result = runCatching(work)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = ::showCoordinatorResult,
                    onFailure = { showErrors(listOf(it.message ?: getString(R.string.schedule_unknown_error))) },
                )
            }
        }
    }

    private fun showCoordinatorResult(result: ScheduleCoordinatorResult?) {
        when {
            result == null -> toast(R.string.schedule_not_found)
            result.isSuccess -> {
                toast(
                    if (result.fellBackToLocal && result.post.status == ScheduleStatus.DRAFT) {
                        R.string.schedule_draft_fell_back_local
                    } else if (result.fellBackToLocal) R.string.schedule_fell_back_local
                    else R.string.schedule_updated,
                )
                finish()
            }
            else -> showErrors(result.errors)
        }
    }

    private fun resolveEditorAccount(post: ScheduledPost?): String {
        post?.accountId?.takeIf(String::isNotBlank)?.let { return it }
        post?.accountUsername?.takeIf(String::isNotBlank)?.let { return it }
        return requestedUsername()
    }

    private fun requestedUsername(): String =
        TwidgetStore.settings(this).username.trim().trimStart('@')

    private fun setBusy(value: Boolean) {
        busy = value
        composeUi.setBusy(value)
        invalidateOptionsMenu()
    }

    private fun cleanupUnstoredOwnedMedia(sources: Collection<ScheduleMediaSource>) {
        val candidates = sources.filterIsInstance<LocalUriMedia>().map(LocalUriMedia::uri).toSet()
        if (candidates.isEmpty()) return
        val retained = runCatching {
            store.list().flatMap { post ->
                post.thread.flatMap { item ->
                    item.media.filterIsInstance<LocalUriMedia>().map(LocalUriMedia::uri)
                }
            }.toSet()
        }.getOrDefault(emptySet())
        candidates.filterNot(retained::contains).forEach { ScheduleOwnedMedia.delete(this, it) }
    }

    private fun showErrors(errors: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_error_title)
            .setMessage(errors.filter(String::isNotBlank).joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class EditorItem(
        val id: String = UUID.randomUUID().toString(),
        var text: String = "",
        val media: MutableList<ScheduleMediaSource> = mutableListOf(),
    )

    private data class ImportedMedia(
        val source: LocalUriMedia,
        val file: File,
    )

    companion object {
        const val EXTRA_USERNAME = "com.tjg.twidget.extra.COMPOSE_USERNAME"
        const val EXTRA_SCHEDULE_ID = "com.tjg.twidget.extra.COMPOSE_SCHEDULE_ID"
        const val EXTRA_SCHEDULED_AT = "com.tjg.twidget.extra.COMPOSE_SCHEDULED_AT"
        private const val MAX_THREAD_ITEMS = 20
    }
}
