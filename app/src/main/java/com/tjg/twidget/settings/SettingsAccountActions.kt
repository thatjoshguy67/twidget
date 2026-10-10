package com.tjg.twidget.settings

internal enum class AccountPopupAction {
    SET_DEFAULT,
    IMPORT_ANALYTICS,
    DELETE,
}

internal fun accountPopupActions(isDefault: Boolean): List<AccountPopupAction> = buildList {
    if (!isDefault) add(AccountPopupAction.SET_DEFAULT)
    add(AccountPopupAction.IMPORT_ANALYTICS)
    add(AccountPopupAction.DELETE)
}
