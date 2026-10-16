package com.tjg.twidget.brief

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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

        applyEdgeToEdgeInsets(findViewById(R.id.brief_onboarding_root)) { bottomInset ->
            findViewById<android.view.View>(R.id.brief_onboarding_scroll)
                .updateBottomMarginForNavigationBar(0, bottomInset)
        }
        bindChrome()
        if (savedInstanceState == null) {
            findViewById<android.view.View>(R.id.brief_onboarding_root).post {
                if (!isFinishing && !isDestroyed) {
                    com.tjg.twidget.ui.TwidgetHaptics.quickRise(findViewById(R.id.brief_onboarding_root))
                }
            }
        }
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
        val title = findViewById<NowBriefSubtitleMotionView>(R.id.brief_onboarding_title)
        val subtitle = findViewById<TextView>(R.id.brief_onboarding_subtitle)
        val continueContainer = findViewById<android.view.View>(R.id.brief_onboarding_continue_container)
        subtitle.alpha = 0f
        continueContainer.alpha = 0f
        title.text = getString(R.string.brief_onboarding_title, name)
        var subtitleStarted = false
        val startSubtitleThenButton = {
            if (!subtitleStarted && !isFinishing && !isDestroyed) {
                subtitleStarted = true
                startSubtitleReveal(subtitle) {
                    continueContainer.postDelayed(
                        { continueContainer.animate().alpha(1f).setDuration(500L).start() },
                        BUTTON_AFTER_SUBTITLE_DELAY_MS,
                    )
                }
            }
        }
        title.post {
            if (isFinishing || isDestroyed) return@post
            title.prepareNowBriefOnboardingTitleMotion()
            title.startNowBriefTitleMotion(
                startDelayMs = 0L,
                onEnd = startSubtitleThenButton,
                onBeforeEnd = startSubtitleThenButton,
                beforeEndLeadMs = SUBTITLE_TITLE_OVERLAP_MS,
            )
            findViewById<android.view.View>(R.id.brief_onboarding_root).postDelayed(
                { startSubtitleThenButton() },
                TITLE_SEQUENCE_FALLBACK_DELAY_MS,
            )
        }
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

    private fun startSubtitleReveal(subtitle: TextView, onEnd: () -> Unit) {
        val fullText = subtitle.text.toString()
        val wordEnds = buildList {
            add(0)
            fullText.forEachIndexed { index, character ->
                if (character == ' ') add(index)
            }
            add(fullText.length)
        }.distinct()
        subtitle.minHeight = subtitle.height
        subtitle.text = ""
        subtitle.alpha = 1f
        ValueAnimator.ofInt(0, wordEnds.lastIndex).apply {
            duration = 350L
            addUpdateListener { animation ->
                val revealedEnd = wordEnds[animation.animatedValue as Int]
                subtitle.text = fullText.substring(0, revealedEnd)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        subtitle.text = fullText
                        onEnd()
                    }
                }
            })
            start()
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
        private const val SUBTITLE_TITLE_OVERLAP_MS = 800L
        private const val BUTTON_AFTER_SUBTITLE_DELAY_MS = 420L
        private const val TITLE_SEQUENCE_FALLBACK_DELAY_MS = 3_000L

        fun intent(context: Context, username: String) =
            Intent(context, BriefOnboardingActivity::class.java)
                .putExtra(TwidgetBriefActivity.EXTRA_USERNAME, username)
    }
}
