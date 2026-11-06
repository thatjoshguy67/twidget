// Lifetime audience response: likes lead, reposts support, and replies/quotes
// contribute modestly. The bonus is capped at 25% of likes so discussion alone
// cannot dominate. These are Twidget product weights, not X prediction weights.
// Keep in sync with BangerScore in the Android client.
export function bangerScore(post) {
  const likes = count(post?.likes);
  const bonus = count(post?.reposts) * 2 + count(post?.replies) * 0.25 + count(post?.quotes) * 0.5;
  return likes + Math.min(likes * 0.25, bonus);
}

// Reach only breaks an exact audience-response tie; missing views are eligible.
export function compareBangers(candidate, incumbent) {
  return bangerScore(candidate) - bangerScore(incumbent) || count(candidate?.views) - count(incumbent?.views);
}

function count(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0;
}
