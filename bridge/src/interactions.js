// CSV rows are daily account activity. Post counters are lifetime values and
// may only be attributed to creation day when no CSV observation exists.
export const interactionFields = ["likes", "reposts", "replies", "shares", "quotes", "bookmarks", "views"];
const counter = value => value == null ? null : Number.isSafeInteger(value) && value >= 0 ? value : undefined;

export function normalizeInteractionData(input) {
  if (!input || !Array.isArray(input.daily) || !Array.isArray(input.posts) || input.daily.length > 366 || input.posts.length > 2000) throw new Error("invalid_interactions");
  const daily = input.daily.map(row => {
    if (typeof row?.date !== "string" || !/^\d{4}-\d\d-\d\d$/.test(row.date) || !Number.isFinite(Date.parse(`${row.date}T00:00:00Z`)) || new Date(`${row.date}T00:00:00Z`).toISOString().slice(0, 10) !== row.date) throw new Error("invalid_interactions");
    const result = { date: row.date };
    for (const field of interactionFields) {
      const value = counter(row[field]);
      if (value === undefined) throw new Error("invalid_interactions");
      result[field] = value;
    }
    return result;
  });
  const posts = input.posts.map(row => {
    if (!/^[0-9]{1,30}$/.test(row?.id || "") || !Number.isSafeInteger(row.createdAt) || row.createdAt <= 0 || !Number.isSafeInteger(row.capturedAt) || row.capturedAt <= 0 || typeof row.isReply !== "boolean") throw new Error("invalid_interactions");
    const result = { id: row.id, createdAt: row.createdAt, capturedAt: row.capturedAt, isReply: row.isReply };
    for (const field of interactionFields) {
      const value = counter(row[field]);
      if (value === undefined) throw new Error("invalid_interactions");
      result[field] = value;
    }
    return result;
  });
  return { daily, posts };
}

export function mergeInteractionData(existing, incoming) {
  const daily = new Map((existing?.daily || []).map(row => [row.date, row]));
  for (const row of incoming.daily) {
    const old = daily.get(row.date);
    const next = { ...row };
    for (const field of interactionFields) next[field] = row[field] ?? old?.[field] ?? null;
    daily.set(row.date, next);
  }
  const posts = new Map((existing?.posts || []).map(row => [row.id, row]));
  for (const row of incoming.posts) {
    const old = posts.get(row.id);
    const next = old && old.capturedAt > row.capturedAt ? { ...old } : { ...row };
    for (const field of interactionFields) {
      const values = [row[field], old?.[field]].filter(value => value != null);
      next[field] = values.length ? Math.max(...values) : null;
    }
    posts.set(row.id, next);
  }
  return { daily: [...daily.values()].sort((a, b) => a.date.localeCompare(b.date)).slice(-366), posts: [...posts.values()].sort((a, b) => a.createdAt - b.createdAt).slice(-2000) };
}

// Convert raw FxTwitter rows before its legacy analytics normalizer replaces
// absent counters with zero. Fields not supplied by the provider stay null.
export function fxInteractionSnapshot(status, capturedAt) {
  const id = String(status?.id || '');
  const seconds = Number(status?.created_timestamp);
  const createdAt = seconds > 0 ? seconds * 1000 : Date.parse(status?.created_at || '');
  if (!/^[0-9]{1,30}$/.test(id) || !Number.isSafeInteger(createdAt) || createdAt <= 0) return null;
  const conversation = String(status.conversation_id || '');
  const result = { id, createdAt, capturedAt, isReply: Boolean(status.replying_to || status.in_reply_to_status_id || status.in_reply_to_status_id_str || (conversation && conversation !== id)) };
  for (const field of interactionFields) {
    const raw = status[field];
    const value = typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : raw;
    result[field] = counter(value) ?? null;
  }
  return result;
}
