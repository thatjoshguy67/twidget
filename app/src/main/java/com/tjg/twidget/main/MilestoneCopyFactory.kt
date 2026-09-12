package com.tjg.twidget.main

import android.content.Context
import com.tjg.twidget.R
import java.time.LocalDate

internal object MilestoneCopyFactory {
    fun message(
        context: Context,
        account: String,
        state: MilestonePerformanceState,
        progress: Int,
        target: String,
        noun: String,
    ): MilestoneMessage {
        val band = when {
            progress >= 100 -> 4
            progress >= 85 -> 3
            progress >= 50 -> 2
            else -> 1
        }
        if (progress >= 100) {
            return MilestoneMessage(
                context.getString(R.string.milestone_title_reached),
                context.getString(R.string.milestone_goal_reached_body, target, noun),
            )
        }
        val candidates = when (state) {
            MilestonePerformanceState.ACCELERATING -> listOf(
                MilestoneMessage(
                    context.getString(R.string.milestone_title_growing_quickly),
                    context.getString(R.string.milestone_goal_accelerating, target, noun),
                ),
                MilestoneMessage(
                    context.getString(R.string.milestone_title_picking_up_pace),
                    context.getString(R.string.milestone_goal_accelerating_alt, target, noun),
                ),
            )
            MilestonePerformanceState.DECELERATING -> listOf(
                MilestoneMessage(
                    context.getString(R.string.milestone_title_slipping_off),
                    context.getString(R.string.milestone_goal_decelerating, target, noun),
                ),
                MilestoneMessage(
                    context.getString(R.string.milestone_title_regain_momentum),
                    context.getString(R.string.milestone_goal_decelerating_alt, target, noun),
                ),
            )
            MilestonePerformanceState.NEUTRAL -> when {
                progress >= 85 -> listOf(
                    MilestoneMessage(
                        context.getString(R.string.milestone_title_almost),
                        context.getString(R.string.milestone_goal_almost, target, noun),
                    ),
                )
                progress >= 50 -> listOf(
                    MilestoneMessage(
                        context.getString(R.string.milestone_title_halfway),
                        context.getString(R.string.milestone_goal_halfway, target, noun),
                    ),
                    MilestoneMessage(
                        context.getString(R.string.milestone_title_keep_going),
                        context.getString(R.string.milestone_goal_climbing, target, noun),
                    ),
                )
                else -> listOf(
                    MilestoneMessage(
                        context.getString(R.string.milestone_title_getting_started),
                        context.getString(R.string.milestone_goal_body, target, noun),
                    ),
                    MilestoneMessage(
                        context.getString(R.string.milestone_title_on_your_way),
                        context.getString(R.string.milestone_goal_climbing, target, noun),
                    ),
                )
            }
        }
        return candidates[
            MilestonePolicy.deterministicMessageIndex(
                account = account,
                epochDay = LocalDate.now().toEpochDay(),
                state = state,
                progressBand = band,
                optionCount = candidates.size,
            ),
        ]
    }
}
