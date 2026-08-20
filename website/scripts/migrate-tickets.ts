import { createConnection } from "mysql2/promise";
import { readFileSync } from "fs";
import { resolve } from "path";
import "dotenv/config";

async function main() {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    throw new Error("DATABASE_URL is not set. Configure it in the environment before running this migration.");
  }

  const db = await createConnection({
    uri: databaseUrl,
    multipleStatements: true,
  });

  const sql = readFileSync(resolve("db/migrations/006_tickets.sql"), "utf-8");
  await db.query(sql);
  console.log("Migration 006_tickets applied successfully");
  await db.end();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
