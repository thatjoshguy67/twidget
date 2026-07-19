package com.samsung.android.sdk.routines.v3.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.widget.RefreshWorker

/**
 * Minimal wire-compatible Samsung Routines SDK v3 provider.
 *
 * Samsung does not publish the SDK artifact used by Modes and Routines. Keep
 * this compatibility surface isolated under the SDK's expected provider class
 * name so it can be replaced by the genuine library without touching Twidget's
 * refresh implementation.
 */
class RoutineSdkContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val request = extras ?: return null
        if (request.getString(KEY_TYPE) != TYPE_ACTION) return null
        if (request.getString(KEY_TAG) != TAG_REFRESH_STATS) return null

        val identity = Binder.clearCallingIdentity()
        return try {
            when (method) {
                METHOD_IS_SUPPORT -> result(
                    if (TwidgetStore.isOnboarded(context)) RESULT_SUCCESS else RESULT_NOT_SUPPORTED,
                )
                METHOD_IS_VALID -> result(
                    if (TwidgetStore.isOnboarded(context)) RESULT_SUCCESS else RESULT_NOT_AVAILABLE,
                )
                METHOD_GET_CURRENT_PARAM -> Bundle().apply {
                    putString(KEY_PARAMETER_VALUES, request.getString(KEY_PARAMETER_VALUES, EMPTY_PARAMETERS))
                }
                METHOD_GET_LABEL_PARAM -> Bundle().apply {
                    putString(KEY_LABEL_PARAMS, "")
                }
                METHOD_PERFORM_ACTION -> {
                    val instanceId = request.getLong(KEY_INSTANCE_ID, 0L)
                    runCatching { RefreshWorker.requestRefresh(context) }
                        .fold(
                            onSuccess = { actionResult(instanceId, RESULT_SUCCESS) },
                            onFailure = { error ->
                                Log.w(LOG_TAG, "Unable to enqueue routine refresh", error)
                                actionResult(instanceId, RESULT_NOT_AVAILABLE)
                            },
                        )
                }
                METHOD_RECOVER_ACTION -> actionResult(
                    request.getLong(KEY_INSTANCE_ID, 0L),
                    RESULT_NOT_SUPPORTED,
                )
                else -> null
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = -1
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = -1

    private fun result(code: Int): Bundle = Bundle().apply {
        putInt(KEY_RESULT_INT, code)
    }

    private fun actionResult(instanceId: Long, code: Int): Bundle = result(code).apply {
        putLong(KEY_INSTANCE_ID, instanceId)
    }

    private companion object {
        const val LOG_TAG = "TwidgetRoutines"
        const val TAG_REFRESH_STATS = "twidget_refresh_stats"

        const val TYPE_ACTION = "action"
        const val KEY_TYPE = "type"
        const val KEY_TAG = "tag"
        const val KEY_INSTANCE_ID = "instanceId"
        const val KEY_LABEL_PARAMS = "labelParams"
        const val KEY_PARAMETER_VALUES = "parameterValues"
        const val KEY_RESULT_INT = "resultInt"

        const val METHOD_GET_CURRENT_PARAM = "getCurrentParam"
        const val METHOD_GET_LABEL_PARAM = "getLabelParam"
        const val METHOD_IS_VALID = "isValid"
        const val METHOD_IS_SUPPORT = "isSupport"
        const val METHOD_PERFORM_ACTION = "performAction"
        const val METHOD_RECOVER_ACTION = "recoverAction"

        const val RESULT_SUCCESS = 1
        const val RESULT_NOT_AVAILABLE = -2
        const val RESULT_NOT_SUPPORTED = -3
        const val EMPTY_PARAMETERS = "{}"
    }
}
