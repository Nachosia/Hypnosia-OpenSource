import { drizzle } from "drizzle-orm/mysql2";
import { env } from "../lib/env";
import * as schema from "@db/schema";
import * as relations from "@db/relations";

const fullSchema = { ...schema, ...relations };

let instance: ReturnType<typeof drizzle<typeof fullSchema>>;

// Production MySQL listens on the standard port. A stale .env has repeatedly
// shipped port 3307 (nothing listens there) which takes the whole app down with
// ECONNREFUSED. Normalize a known-bad localhost port so a bad deploy can't kill
// the DB connection; log loudly so the .env still gets fixed.
function normalizeDatabaseUrl(raw: string): string {
  try {
    const url = new URL(raw);
    const isLocal = url.hostname === "localhost" || url.hostname === "127.0.0.1";
    if (isLocal && url.port === "3307") {
      url.port = "3306";
      console.warn(
        "[DB] DATABASE_URL pointed at localhost:3307 (no MySQL there). " +
        "Overriding to 3306. Fix DATABASE_URL in .env to silence this warning."
      );
      return url.toString();
    }
    return raw;
  } catch {
    // Not a parseable URL (or a connection-string variant) — leave as-is.
    return raw;
  }
}

export function getDb() {
  if (!instance) {
    instance = drizzle(normalizeDatabaseUrl(env.databaseUrl), {
      mode: "planetscale",
      schema: fullSchema,
    });
  }
  return instance;
}
