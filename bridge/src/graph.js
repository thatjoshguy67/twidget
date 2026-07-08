// Follower-graph history reconstruction.
//
// X returns an account's followers in reverse-chronological follow order, and
// every follower's own account-creation date is a hard lower bound on when
// that follow happened (nobody follows before they exist). Walking the list
// oldest-follow-first, the running maximum of follower creation dates pins
// (time, follower-count) anchor points wherever a newer-than-anything-before
// account appears; linear interpolation between anchors approximates the
// account's followers-over-time curve.
//
// The result is an ESTIMATE: unfollows are invisible (the curve is monotone)
// and positions between pins are interpolated. Every sample produced here
// must stay flagged `est` end to end.

// followerCreatedAts: creation timestamps (ms) in chronological FOLLOW order
// (oldest follow first). Returns monthly { ts, followers } points from the
// account's creation month up to (not including) today.
//
// baseCount: number of follows that happened BEFORE the enumerated window.
// Pagination can end early, leaving only the newest follows — anchoring those
// at count index+1 would map the recent tail onto the start of the account's
// life (the "8 followers yesterday" bug). The stretch between account
// creation and the first pin stays plain interpolation.
export function reconstructFollowerHistory({ followerCreatedAts, accountCreatedAt, currentCount, now, baseCount = 0 }) {
  if (!accountCreatedAt || !currentCount || currentCount < 1) return [];

  const anchors = [{ time: accountCreatedAt, count: 0 }];
  let newestSeen = accountCreatedAt;
  followerCreatedAts.forEach((created, index) => {
    if (created > newestSeen && created <= now) {
      newestSeen = created;
      anchors.push({ time: created, count: baseCount + index + 1 });
    }
  });
  anchors.push({ time: now, count: currentCount });
  for (let index = 1; index < anchors.length; index += 1) {
    anchors[index].count = Math.max(anchors[index].count, anchors[index - 1].count);
  }

  const samples = [];
  const start = new Date(accountCreatedAt);
  let cursor = new Date(start.getFullYear(), start.getMonth() + 1, 1).getTime();
  while (cursor < now) {
    samples.push({ ts: cursor, followers: Math.round(interpolate(anchors, cursor)) });
    const date = new Date(cursor);
    cursor = new Date(date.getFullYear(), date.getMonth() + 1, 1).getTime();
  }
  return samples;
}

function interpolate(anchors, time) {
  if (time <= anchors[0].time) return anchors[0].count;
  for (let index = 1; index < anchors.length; index += 1) {
    if (time <= anchors[index].time) {
      const previous = anchors[index - 1];
      const next = anchors[index];
      const span = Math.max(next.time - previous.time, 1);
      return previous.count + ((next.count - previous.count) * (time - previous.time)) / span;
    }
  }
  return anchors[anchors.length - 1].count;
}
