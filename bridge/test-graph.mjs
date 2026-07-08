import { reconstructFollowerHistory } from "./src/graph.js";

const MONTH = 30 * 24 * 60 * 60 * 1000;
const now = Date.now();
const accountCreatedAt = now - 12 * MONTH;

// Simulate an account gaining ~20 followers/month for a year, where a third
// of followers are accounts created near their follow date (the pins) and the
// rest are older accounts (only lower bounds).
const followerCreatedAts = [];
for (let month = 0; month < 12; month += 1) {
  for (let i = 0; i < 20; i += 1) {
    const followTime = accountCreatedAt + month * MONTH + i * (MONTH / 20);
    const isNewAccount = i % 3 === 0;
    followerCreatedAts.push(isNewAccount ? followTime - 5 * 24 * 60 * 60 * 1000 : accountCreatedAt - 24 * MONTH * Math.random());
  }
}

const samples = reconstructFollowerHistory({ followerCreatedAts, accountCreatedAt, currentCount: 240, now });
console.log("samples:", samples.length);
for (const s of samples) {
  const monthsAgo = Math.round((now - s.ts) / MONTH);
  const trueCount = Math.min(240, Math.max(0, Math.round((s.ts - accountCreatedAt) / MONTH) * 20));
  console.log(`${new Date(s.ts).toISOString().slice(0, 10)}  est=${String(s.followers).padStart(4)}  true≈${trueCount}`);
}

// Degenerate cases
console.log("empty followers:", reconstructFollowerHistory({ followerCreatedAts: [], accountCreatedAt, currentCount: 5, now }).length, "samples (interp created→now)");
console.log("zero count:", reconstructFollowerHistory({ followerCreatedAts: [], accountCreatedAt, currentCount: 0, now }).length);
console.log("no created:", reconstructFollowerHistory({ followerCreatedAts: [], accountCreatedAt: 0, currentCount: 5, now }).length);
