import fs from "node:fs/promises";
import path from "node:path";
import { Pool } from "pg";
import { PostgresHistoryRepository } from "../src/infrastructure.js";

const databaseUrl = String(process.env.HISTORY_DATABASE_URL || "").trim();
if (!databaseUrl) throw new Error("HISTORY_DATABASE_URL is required");

const sourcePath = path.resolve(process.argv[2] || process.env.HISTORY_STORE_PATH || "data/twidget-history.json");
const source = JSON.parse(await fs.readFile(sourcePath, "utf8"));
const accountCount = Object.keys(source?.accounts || {}).length;
const sampleCount = Object.values(source?.accounts || {}).reduce(
  (total, samples) => total + (Array.isArray(samples) ? samples.length : 0),
  0,
);
const pool = new Pool({
  connectionString: databaseUrl,
  max: 2,
  connectionTimeoutMillis: 5000,
  statement_timeout: 30_000,
  application_name: "twidget-history-migration",
});
const repository = new PostgresHistoryRepository({ pool, maxHistoryDays: 400 });

try {
  await repository.initialize();
  await repository.importLegacyStore(source);
  console.log(`Imported ${sampleCount} samples across ${accountCount} accounts from ${sourcePath}`);
  console.log(`PostgreSQL now contains ${await repository.countAccounts()} history accounts`);
} finally {
  await repository.close();
}
