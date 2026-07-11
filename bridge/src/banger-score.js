// A banger is a post that hit hard in absolute terms, so rank by weighted
// engagement volume. Engagement *rate* falls as reach grows, so it is only
// a bounded quality multiplier: it lets a well-received post edge out a
// hollow one of similar size, but can never lift a small high-rate post
// over a genuinely viral one.
export function bangerScore(post) {
  const views = Number(post?.views) || 0;
  if (views <= 0) return 0;
  const impact =
    Number(post.likes || 0) +
    Number(post.replies || 0) * 2 +
    Number(post.reposts || 0) * 3 +
    Number(post.quotes || 0) * 4;
  if (impact <= 0) return 0;
  const rate = impact / (views + 100);
  const quality = Math.min(1.5, Math.max(0.5, Math.sqrt(rate / 0.02)));
  return impact * quality;
}
