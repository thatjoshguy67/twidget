import fs from "node:fs/promises";
import path from "node:path";

const DAY = 86_400_000;

export class TopFollowersBudgetPause extends Error {
  constructor(decision) {
    super(decision.reason);
    this.name = "TopFollowersBudgetPause";
    this.retryAt = decision.retryAt;
  }
}

// A single durable ledger is shared by every account, replica and trigger.
// Reserve before calling the provider; failed calls are never refunded, so
// our accounting errs on the side of less spend if billing is uncertain.
export class TopFollowersBudget {
  constructor({ pool = null, filePath, dailyLimit = 1250, monthlyLimit = 37500, trackedDailyLimit = dailyLimit, now = Date.now, provider = "twitterapis" }) {
    this.pool = pool;
    this.table = provider === "fxtwitter" ? "twidget_fx_followers_budget" : "twidget_top_followers_budget";
    this.filePath = filePath;
    this.dailyLimit = dailyLimit;
    this.monthlyLimit = monthlyLimit;
    this.trackedDailyLimit = Math.min(dailyLimit, trackedDailyLimit);
    this.now = now;
    this.pending = Promise.resolve();
  }

  async initialize() {
    if (this.pool) {
      await this.pool.query(`CREATE TABLE IF NOT EXISTS ${this.table} (
        id integer PRIMARY KEY CHECK (id = 1), state jsonb NOT NULL
      )`);
      await this.pool.query(`INSERT INTO ${this.table} (id, state) VALUES (1, '{}'::jsonb) ON CONFLICT DO NOTHING`);
    } else {
      try { JSON.parse(await fs.readFile(this.filePath, "utf8")); }
      catch (error) { if (error.code !== "ENOENT") throw error; }
    }
  }

  async reserve(username = "", { primary = false } = {}) {
    const key = String(username).toLowerCase();
    if (key && !/^[a-z0-9_]{1,15}$/.test(key)) throw new Error("invalid_budget_username");
    const decision = await this.update((state, now) => {
      const result = this.decision(state, now, primary);
      if (result.allowed) {
        // Count earlier, unclassified reservations conservatively against the
        // tracked bucket. Never reset spend when this policy is introduced.
        state.dayTrackedRequests = Number(state.dayTrackedRequests ?? state.dayRequests ?? 0);
        state.dayPrimaryRequests = Number(state.dayPrimaryRequests || 0);
        if (primary) state.dayPrimaryRequests += 1;
        else state.dayTrackedRequests += 1;
        state.dayRequests = Number(state.dayRequests || 0) + 1;
        state.monthRequests = Number(state.monthRequests || 0) + 1;
        if (key) {
          for (const window of ["dayAccounts", "monthAccounts"]) {
            const accounts = state[window] || {};
            state[window] = { ...accounts, [key]: Number(Object.hasOwn(accounts, key) ? accounts[key] : 0) + 1 };
          }
        }
      }
      return result;
    });
    if (!decision.allowed) throw new TopFollowersBudgetPause(decision);
    return decision;
  }

  // Inspect attribution without changing persisted counters or cooldowns.
  async usage() {
    let state;
    if (this.pool) {
      const result = await this.pool.query(`SELECT state FROM ${this.table} WHERE id = 1`);
      if (!result.rows[0]) throw new Error("top_followers_budget_missing");
      state = structuredClone(result.rows[0].state);
    } else {
      await this.pending;
      try { state = JSON.parse(await fs.readFile(this.filePath, "utf8")); }
      catch (error) { if (error.code !== "ENOENT") throw error; state = {}; }
    }
    const decision = budgetDecision(state, this.now(), this.dailyLimit, this.monthlyLimit);
    const window = (period, limit) => {
      const byAccount = state[`${period}Accounts`] || {};
      const requests = Number(state[`${period}Requests`] || 0);
      return { period: state[period], requests, limit, byAccount,
        unattributed: Math.max(0, requests - Object.values(byAccount).reduce((sum, value) => sum + Number(value), 0)) };
    };
    return { allowed: decision.allowed, reason: decision.reason, retryAt: decision.retryAt,
      day: window("day", this.dailyLimit), month: window("month", this.monthlyLimit),
      tracked: { requests: Number(state.dayTrackedRequests ?? state.dayRequests ?? 0), limit: this.trackedDailyLimit },
      primary: { requests: Number(state.dayPrimaryRequests || 0) } };
  }

  decision(state, now, primary = true) {
    const decision = budgetDecision(state, now, this.dailyLimit, this.monthlyLimit);
    if (decision.allowed && !primary && Number(state.dayTrackedRequests ?? state.dayRequests ?? 0) >= this.trackedDailyLimit) {
      const date = new Date(now);
      return { allowed: false, reason: "top_followers_tracked_daily_budget_reached",
        retryAt: Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + 1) };
    }
    return decision;
  }

  async status({ primary = true } = {}) {
    return this.update((state, now) => this.decision(state, now, primary));
  }

  async pause(reason) {
    const delay = reason === "provider_key_rejected" ? DAY : reason === "provider_credit_empty" ? 3_600_000 : 900_000;
    return this.update((state, now) => {
      const until = now + delay;
      if (until > Number(state.pauseUntil || 0)) {
        state.pauseUntil = until;
        state.pauseReason = reason;
      }
      return state.pauseUntil;
    });
  }

  // An operator can request one immediate probe after restoring credits. This
  // clears only the credit-error cooldown, never daily or monthly counters.
  async retryBenchmark(retryId) {
    if (!retryId) return;
    return this.update(state => {
      if (state.benchmarkRetryId === retryId) return;
      state.benchmarkRetryId = retryId;
      if (state.pauseReason === "provider_credit_empty") state.pauseUntil = 0;
      if (state.pageSizeBenchmark?.status === "waiting") state.pageSizeBenchmark.retryAt = 0;
    });
  }

  async update(change) {
    if (this.pool) {
      const client = await this.pool.connect();
      try {
        await client.query("BEGIN");
        const result = await client.query(`SELECT state FROM ${this.table} WHERE id = 1 FOR UPDATE`);
        if (!result.rows[0]) throw new Error("top_followers_budget_missing");
        const state = result.rows[0].state;
        const value = change(state, this.now());
        await client.query(`UPDATE ${this.table} SET state = $1::jsonb WHERE id = 1`, [JSON.stringify(state)]);
        await client.query("COMMIT");
        return value;
      } catch (error) {
        await client.query("ROLLBACK");
        throw error; // A ledger outage must never allow unmetered calls.
      } finally { client.release(); }
    }
    const work = this.pending.then(async () => {
      let state;
      try { state = JSON.parse(await fs.readFile(this.filePath, "utf8")); }
      catch (error) { if (error.code !== "ENOENT") throw error; state = {}; }
      const value = change(state, this.now());
      await fs.mkdir(path.dirname(this.filePath), { recursive: true });
      const temporary = `${this.filePath}.tmp`;
      const file = await fs.open(temporary, "w", 0o600);
      try { await file.writeFile(JSON.stringify(state)); await file.sync(); }
      finally { await file.close(); }
      await fs.rename(temporary, this.filePath);
      return value;
    });
    this.pending = work.catch(() => {});
    return work;
  }
}

function budgetDecision(state, now, dailyLimit, monthlyLimit) {
  const date = new Date(now);
  const day = date.toISOString().slice(0, 10);
  const month = day.slice(0, 7);
  if (state.day !== day) { state.day = day; state.dayRequests = 0; state.dayAccounts = {}; state.dayTrackedRequests = 0; state.dayPrimaryRequests = 0; }
  if (state.month !== month) { state.month = month; state.monthRequests = 0; state.monthAccounts = {}; }
  if (Number(state.pauseUntil || 0) > now) return { allowed: false, reason: state.pauseReason, retryAt: state.pauseUntil };
  if (state.monthRequests >= monthlyLimit) return {
    allowed: false, reason: "top_followers_monthly_budget_reached",
    retryAt: Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1),
  };
  if (state.dayRequests >= dailyLimit) return {
    allowed: false, reason: "top_followers_daily_budget_reached",
    retryAt: Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate() + 1),
  };
  return { allowed: true, dayRequests: state.dayRequests, monthRequests: state.monthRequests };
}
