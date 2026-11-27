package com.tjg.twidget.main

import androidx.lifecycle.ViewModel
import com.tjg.twidget.data.ProfileStats

/** An unconfirmed lookup is never written to the user's account list. Survives rotation. */
internal class OnboardingAccountSession : ViewModel() {
    var candidate: ProfileStats? = null
        set(value) { field = value; confirmed = false }
    var confirmed = false
        private set

    fun confirm(persist: (ProfileStats) -> Unit): ProfileStats? {
        val profile = candidate ?: return null
        if (!confirmed) {
            persist(profile)
            confirmed = true
        }
        return profile
    }
}
