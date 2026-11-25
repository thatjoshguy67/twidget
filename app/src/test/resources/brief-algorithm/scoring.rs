// Source: xai-org/x-algorithm 30647a85a20cbdd114169beb9973680c4dc4f401, xai-value-model/scoring.rs (Apache-2.0).
pub fn compute_weighted_score(
    weights: &ValueModelWeights,
    candidate: &CandidateScoringInputs,
) -> f64 {
    weighted_terms(weights, candidate)
        .iter()
        .map(|(_, t)| t)
        .sum()
}

pub fn weighted_terms(
    weights: &ValueModelWeights,
    candidate: &CandidateScoringInputs,
) -> [(&'static str, f64); 30] {
    let scores = &candidate.phoenix_scores;

    let vqv_weight = if candidate.vqv_eligible {
        weights.vqv
    } else {
        0.0
    };
    let quoted_vqv_weight = if candidate.quoted_vqv_eligible {
        weights.quoted_vqv
    } else {
        0.0
    };
    let post_unexplored_weight =
        if weights.post_unexplored_include_out_of_network || candidate.in_network == Some(true) {
            weights.post_unexplored
        } else {
            0.0
        };

    [
        ("favorite", apply(scores.favorite_score, weights.favorite)),
        (
            "reply",
            apply(scores.reply_score, weights.reply_weight_for(candidate)),
        ),
        ("retweet", apply(scores.retweet_score, weights.retweet)),
        (
            "photo_expand",
            apply(scores.photo_expand_score, weights.photo_expand),
        ),
        (
            "video_open",
            apply(scores.video_open_score, weights.video_open),
        ),
        ("click", apply(scores.click_score, weights.click)),
        (
            "open_link",
            apply(scores.open_link_score, weights.open_link),
        ),
        (
            "profile_click",
            apply(scores.profile_click_score, weights.profile_click),
        ),
        ("vqv", apply(scores.vqv_score, vqv_weight)),
        ("share", apply(scores.share_score, weights.share)),
        (
            "share_via_dm",
            apply(scores.share_via_dm_score, weights.share_via_dm),
        ),
        (
            "share_via_copy_link",
            apply(
                scores.share_via_copy_link_score,
                weights.share_via_copy_link,
            ),
        ),
        (
            "dwell",
            apply(scores.dwell_score, weights.dwell_weight_for(candidate)),
        ),
        ("quote", apply(scores.quote_score, weights.quote)),
        (
            "quoted_click",
            apply(scores.quoted_click_score, weights.quoted_click),
        ),
        (
            "quoted_vqv",
            apply(scores.quoted_vqv_score, quoted_vqv_weight),
        ),
        (
            "dwell_time",
            apply(scores.dwell_time, weights.cont_dwell_time),
        ),
        (
            "click_dwell_time",
            apply(scores.click_dwell_time, weights.cont_click_dwell_time),
        ),
        (
            "video_continuation",
            apply(
                product(scores.video_open_score, scores.home_video_continuation_secs),
                weights.video_continuation,
            ),
        ),
        (
            "user_video_continuation",
            apply(
                product(
                    scores.video_open_score,
                    candidate.user_video_continuation_secs,
                ),
                weights.user_video_continuation,
            ),
        ),
        (
            "profile_visit_secs",
            apply(
                product(scores.profile_click_score, scores.home_profile_visit_secs),
                weights.profile_visit_secs,
            ),
        ),
        (
            "follow_author",
            apply(scores.follow_author_score, weights.follow_author),
        ),
        (
            "not_interested",
            apply(scores.not_interested_score, weights.not_interested),
        ),
        (
            "block_author",
            apply(scores.block_author_score, weights.block_author),
        ),
        (
            "mute_author",
            apply(scores.mute_author_score, weights.mute_author),
        ),
        ("report", apply(scores.report_score, weights.report)),
        (
            "not_dwelled",
            apply(scores.not_dwelled_score, weights.not_dwelled),
        ),
        (
            "post_unexplored",
            apply(scores.post_unexplored_score, post_unexplored_weight),
        ),
        (
            "post_video",
            apply(scores.post_video_score, weights.post_video),
        ),
        (
            "grok_analyze",
            apply(scores.grok_analyze_score, weights.grok_analyze),
        ),
    ]
}

