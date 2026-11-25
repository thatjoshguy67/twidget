import crypto from "node:crypto";

// This is a client-reported default, never proof that an install owns an X account.
export const PRIMARY_PROFILE_RETENTION_DAYS = 90;
export const MAX_PRIMARY_INSTALLATIONS = 10000;

export function installationHash(value) {
  if (typeof value !== "string" || !/^[a-f0-9]{64}$/.test(value)) return null;
  return crypto.createHash("sha256").update(value).digest("hex");
}

export function primaryProfileSummary(records, now = Date.now()) {
  const accounts = new Map();
  for (const record of records) {
    if (record.lastSeenAt < now - PRIMARY_PROFILE_RETENTION_DAYS * 86400000) continue;
    const row = accounts.get(record.username) || { username: record.username, primaryInstalls: 0, active7d: 0, active30d: 0 };
    row.primaryInstalls += 1;
    if (record.lastSeenAt >= now - 7 * 86400000) row.active7d += 1;
    if (record.lastSeenAt >= now - 30 * 86400000) row.active30d += 1;
    accounts.set(record.username, row);
  }
  return [...accounts.values()].sort((a, b) => b.primaryInstalls - a.primaryInstalls || a.username.localeCompare(b.username));
}

export function mountPrimaryProfileRoutes(app, { repository, jsonBody, maxInstallations = MAX_PRIMARY_INSTALLATIONS, onChange = () => {} }) {
  app.post("/installation/primary-profile", jsonBody, async (req, res) => {
    const hash = installationHash(req.get("X-Twidget-Install-Key"));
    if (!hash) return res.status(400).json({ error: "invalid_installation_key" });
    // null clears a report, including when shared history is switched off.
    const username = req.body?.username;
    if (username !== null && (typeof username !== "string" || !/^[A-Za-z0-9_]{1,15}$/.test(username))) {
      return res.status(400).json({ error: "invalid_username" });
    }
    const result = await repository.recordPrimaryProfile(hash, username?.toLowerCase() ?? null, maxInstallations);
    if (result === "account_missing") return res.status(409).json({ error: "history_account_not_registered" });
    if (result === "capacity") return res.status(503).json({ error: "installation_capacity_reached" });
    onChange();
    res.status(204).end();
  });

  // The server's central /admin middleware supplies operator-only authentication.
  app.get("/admin/audience", async (_req, res) => {
    const [registered, primaryAccounts] = await Promise.all([repository.listAccounts(), repository.primaryProfileCounts()]);
    const reported = new Set(primaryAccounts.map(row => row.username));
    res.json({
      attribution: "client_reported_default_not_verified_ownership",
      retentionDays: PRIMARY_PROFILE_RETENTION_DAYS,
      registeredAccounts: registered.length,
      reportedInstallations: primaryAccounts.reduce((sum, row) => sum + row.primaryInstalls, 0),
      accountsWithReportedPrimary: primaryAccounts.length,
      // Older clients and nonparticipating installs cannot be classified.
      accountsWithoutPrimaryReport: registered.filter(username => !reported.has(username)),
      primaryAccounts,
    });
  });
}
