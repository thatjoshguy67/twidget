import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import test from "node:test";

async function availablePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.listen(0, "127.0.0.1", resolve).once("error", reject));
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

test("bridge security and health defaults", async (t) => {
  const port = await availablePort();
  const temp = await mkdtemp(path.join(os.tmpdir(), "twidget-bridge-test-"));
  const child = spawn(process.execPath, ["src/server.js"], {
    cwd: path.resolve(import.meta.dirname, ".."),
    env: {
      ...process.env,
      PORT: String(port),
      BRIDGE_API_TOKEN: "test-token",
      HISTORY_ADMIN_TOKEN: "admin-token",
      HISTORY_STORE_PATH: path.join(temp, "history.json"),
      WAYBACK_BACKFILL: "0",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await new Promise((resolve) => child.once("exit", resolve));
    }
    await rm(temp, { recursive: true, force: true });
  });

  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("Bridge did not start")), 5000);
    child.once("error", reject);
    child.stdout.on("data", (chunk) => {
      if (String(chunk).includes("Twidget bridge listening")) {
        clearTimeout(timeout);
        resolve();
      }
    });
  });

  const base = `http://127.0.0.1:${port}`;
  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  assert.equal(health.headers.get("access-control-allow-origin"), null);
  assert.equal(health.headers.get("x-content-type-options"), "nosniff");
  const healthBody = await health.json();
  assert.equal(healthBody.authMode, "bearer");
  assert.equal(healthBody.history.analyticsImport, true);

  const unauthorized = await fetch(`${base}/official/user/example`);
  assert.equal(unauthorized.status, 401);
  assert.deepEqual(await unauthorized.json(), { error: "unauthorized" });

  const protectedRoute = await fetch(`${base}/official/user/example`, {
    headers: { Authorization: "Bearer test-token" },
  });
  assert.equal(protectedRoute.status, 501);

  const removedLegacyBackfill = await fetch(`${base}/history/example/backfill`, {
    method: "POST",
    headers: {
      Authorization: "Bearer test-token",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ samples: [] }),
  });
  assert.equal(removedLegacyBackfill.status, 404);

  const unauthorizedDelete = await fetch(`${base}/admin/history/example`, { method: "DELETE" });
  assert.equal(unauthorizedDelete.status, 401);

  const deleted = await fetch(`${base}/admin/history/example`, {
    method: "DELETE",
    headers: { Authorization: "Bearer admin-token" },
  });
  assert.equal(deleted.status, 204);
});
