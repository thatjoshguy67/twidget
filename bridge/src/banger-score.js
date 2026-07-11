export function bangerScore(post, followers) {
  if (!post || Number(post.views) <= 0) return 0;
  const views = Number(post.views);
  const reachReference = Math.max(1000, Math.max(1, Number(followers) || 0) * 10);
  const reach = Math.max(0.05, Math.min(2, Math.log(views + 1) / Math.log(reachReference + 1)));
  const approval = (Number(post.likes || 0) + 2) / (views + 100);
  const meaningful = Number(post.replies || 0) * 2 + Number(post.reposts || 0) * 3 + Number(post.quotes || 0) * 4;
  const interaction = (meaningful + 1) / (views + 200);
  return Math.cbrt(reach * Math.max(0.001, approval / 0.03) * Math.max(0.001, interaction / 0.01));
}
