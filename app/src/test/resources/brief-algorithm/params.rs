// xai-org/x-algorithm 30647a85a20cbdd114169beb9973680c4dc4f401 (Apache-2.0), home-mixer/params/param.rs excerpts.
param!(
    FavoriteWeight,
    f64,
    "rust_home_mixer_favorite_weight",
    0.708
);
param!(ReplyWeight, f64, "rust_home_mixer_reply_weight", 5.0);
param!(RetweetWeight, f64, "rust_home_mixer_retweet_weight", 1.0);
param!(QuoteWeight, f64, "rust_home_mixer_quote_weight", 5.0);
param!(ShareWeight, f64, "rust_home_mixer_share_weight", 2.118);
param!(
    ShareViaDmWeight,
    f64,
    "rust_home_mixer_share_via_dm_weight",
    5.0
);
param!(
    ShareViaCopyLinkWeight,
    f64,
    "rust_home_mixer_share_via_copy_link_weight",
    0.0
);
param!(ClickWeight, f64, "rust_home_mixer_click_weight", 0.274);
param!(OpenLinkWeight, f64, "rust_home_mixer_open_link_weight", 0.2);
param!(
    PhotoExpandWeight,
    f64,
    "rust_home_mixer_photo_expand_weight",
    0.05
);
param!(
    VideoOpenWeight,
    f64,
    "rust_home_mixer_video_open_weight",
    0.0
);
param!(VqvWeight, f64, "rust_home_mixer_vqv_weight", 0.0);
param!(
    ProfileClickWeight,
    f64,
    "rust_home_mixer_profile_click_weight",
    0.0
);
param!(
    FollowAuthorWeight,
    f64,
    "rust_home_mixer_follow_author_weight",
    4.0
);
param!(DwellWeight, f64, "rust_home_mixer_dwell_weight", 0.05);
param!(
    ContDwellTimeWeight,
    f64,
    "rust_home_mixer_cont_dwell_time_weight",
    0.0075
);
param!(
    ContClickDwellTimeWeight,
    f64,
    "rust_home_mixer_cont_click_dwell_time_weight",
    0.385
);
param!(
    NotInterestedWeight,
    f64,
    "rust_home_mixer_not_interested_weight",
    -53.07
);
param!(
    BlockAuthorWeight,
    f64,
    "rust_home_mixer_block_author_weight",
    -31.2
);
param!(
    MuteAuthorWeight,
    f64,
    "rust_home_mixer_mute_author_weight",
    -58.8
);
param!(ReportWeight, f64, "rust_home_mixer_report_weight", -234.0);
param!(
    NotDwelledWeight,
    f64,
    "rust_home_mixer_not_dwelled_weight",
    -0.02
);
