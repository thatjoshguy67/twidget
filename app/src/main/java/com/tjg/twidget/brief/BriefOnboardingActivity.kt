package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

class BriefOnboardingActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private lateinit var generation: Deferred<BriefAiResult>
    private var apiKeyPromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brief_onboarding)
        username = intent.getStringExtra(TwidgetBriefActivity.EXTRA_USERNAME).orEmpty()
            .trim().trimStart('@')
            .ifBlank { TwidgetStore.settings(this).username.trim().trimStart('@') }
        val defaultAccount = TwidgetStore.settings(this).username.trim().trimStart('@')
        if (username.isBlank() || !username.equals(defaultAccount, ignoreCase = true)) {
            finish()
            return
        }

        applyEdgeToEdgeInsets(findViewById(R.id.brief_onboarding_root))
        bindChrome()
        generation = BriefLaunchGeneration.start(this, username, restartIfComplete = true)
        watchGenerationForApiKeyRequirement()
    }

    override fun allowsPopOverPresentation(): Boolean = false

    private fun bindChrome() {
        val tint = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
        findViewById<ImageButton>(R.id.brief_onboarding_back).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_back))
            imageTintList = tint
            setOnClickListener { finish() }
        }
        val stats = TwidgetStore.currentStats(this, username)
        val name = stats.fullName.ifBlank { "@$username" }
        findViewById<TextView>(R.id.brief_onboarding_title).text =
            getString(R.string.brief_onboarding_title, name)
        findViewById<TextView>(R.id.brief_onboarding_continue).setOnClickListener {
            BriefSettingsStore.setOnboardingComplete(this, true)
            val waitForGeneration = briefContinueDestination(generation.isCompleted) ==
                BriefContinueDestination.SPINNER
            startActivity(
                TwidgetBriefActivity.contentIntent(
                    this,
                    username,
                    waitForLaunchGeneration = waitForGeneration,
                    fromOnboarding = true,
                    apiKeyPromptAlreadyShown = apiKeyPromptShown,
                ),
            )
            finish()
        }
    }

    private fun watchGenerationForApiKeyRequirement() {
        lifecycleScope.launch {
            val result = runCatching { generation.await() }.getOrNull() ?: return@launch
            if (isFinishing || isDestroyed || apiKeyPromptShown || !briefRequiresApiKey(
                    result.localStatus,
                    BriefSettingsStore.cloudApiKey(this@BriefOnboardingActivity),
                )
            ) {
                return@launch
            }
            apiKeyPromptShown = true
            BriefApiKeyDialog.show(this@BriefOnboardingActivity, required = true) {
                BriefSettingsStore.setProvider(this@BriefOnboardingActivity, BriefProviderMode.AUTO)
                BriefStore.resetAi(this@BriefOnboardingActivity, username)
                generation = BriefLaunchGeneration.start(
                    this@BriefOnboardingActivity,
                    username,
                    restartIfComplete = true,
                )
            }
        }
    }

    companion object {
        fun intent(context: Context, username: String) =
            Intent(context, BriefOnboardingActivity::class.java)
                .putExtra(TwidgetBriefActivity.EXTRA_USERNAME, username)
    }
}
