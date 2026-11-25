import { TopFollowersBudgetPause } from "./top-followers-budget.js";

const VERSION = "count100-v1";

// The callback is the same metered fetch used by production scans. At most
// three pages per arm are sampled; no profiles or credentials enter the log.
export async function benchmarkTopFollowersPageSize({ budget, fetchPage, now = Date.now }) {
  const owner = `${now()}-${Math.random()}`;
  const state = await budget.update((ledger, timestamp) => {
    const old = ledger.pageSizeBenchmark;
    if (old?.version === VERSION && (old.status === "complete" || old.retryAt > timestamp || old.leaseUntil > timestamp)) return old;
    const value = old?.version === VERSION ? old : {
      version: VERSION, baseline: [], count100: [], cursors: { baseline: "", count100: "" }, finished: {},
    };
    Object.assign(value, { owner, status: "running", leaseUntil: timestamp + 5 * 60_000 });
    ledger.pageSizeBenchmark = value;
    return value;
  });
  if (state.owner !== owner) return publicResult(state);
  try {
    for (const arm of ["baseline", "count100"]) {
      while (state[arm].length < 3 && !state.finished[arm]) {
        const page = await fetchPage(state.cursors[arm], arm === "baseline" ? null : 100);
        state[arm].push(page.users.length);
        state.cursors[arm] = page.nextCursor;
        if (!page.users.length || !page.nextCursor) state.finished[arm] = true;
        await checkpoint(budget, state);
      }
    }
    state.status = "complete";
    state.completedAt = now();
    state.retryAt = 0;
  } catch (error) {
    // Keep partial samples and try later when credits or the shared budget
    // permit. A failed call still consumes a request reservation.
    state.status = "waiting";
    state.reason = error instanceof TopFollowersBudgetPause ? error.message : `provider_status_${Number(error.status || 0)}`;
    state.retryAt = error.retryAt || now() + 60 * 60_000;
  }
  state.leaseUntil = 0;
  await checkpoint(budget, state);
  return publicResult(state);
}

async function checkpoint(budget, state) {
  await budget.update((ledger) => {
    if (ledger.pageSizeBenchmark?.owner !== state.owner) throw new Error("benchmark_lease_lost");
    ledger.pageSizeBenchmark = { ...state };
  });
}

function publicResult(state) {
  const baselineRows = (state.baseline || []).reduce((a, b) => a + b, 0);
  const count100Rows = (state.count100 || []).reduce((a, b) => a + b, 0);
  const baselinePages = state.baseline?.length || 0;
  const count100Pages = state.count100?.length || 0;
  return {
    status: state.status, baseline: state.baseline, count100: state.count100,
    baselineRows, count100Rows, baselinePages, count100Pages,
    // Compare delivered rows per request, never assume the requested size.
    observedRequestReduction: state.status === "complete" && baselineRows && count100Rows
      ? Math.max(0, 1 - (baselineRows / baselinePages) / (count100Rows / count100Pages)) : null,
    reason: state.status === "waiting" ? state.reason : undefined,
    retryAt: state.status === "waiting" ? state.retryAt : undefined,
  };
}
