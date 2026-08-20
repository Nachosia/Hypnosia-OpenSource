import { getDb } from "./queries/connection";
import { modSessions, modAccounts, playerProfiles, modFreezeLogs, modPlaytimeDaily } from "@db/schema";
import { eq, and, isNull, lt, sql } from "drizzle-orm";
import { reportOfflineToLicenseServer, getAllLicenseAccounts, parseGradientColors } from "./lib/license-client";
import { normalizeRole, pickHighestLicenseRole } from "./lib/roles";

const MAX_HEARTBEAT_GAP_SECONDS = 240; // 4 minutes

// ─── Timeout Worker: close stale sessions, freeze if >10 min stale ───
export async function runTimeoutWorker(): Promise<{ closed: number; frozen: number }> {
  const db = getDb();
  const cutoff = new Date(Date.now() - MAX_HEARTBEAT_GAP_SECONDS * 1000);

  const staleSessions = await db
    .select()
    .from(modSessions)
    .where(and(isNull(modSessions.endedAt), lt(modSessions.lastHeartbeatAt, cutoff)));

  let closed = 0;
  let frozen = 0;
  for (const session of staleSessions) {
    const now = new Date();

    // Skip if grace period is active (crash recovery window)
    if (session.graceEndAt && new Date(session.graceEndAt) > now) {
      continue;
    }

    const lastCounted = session.lastCountedAt;
    const deltaSec = Math.floor((now.getTime() - lastCounted.getTime()) / 1000);

    // If stale > 10 minutes (600 sec) → freeze account (anti-cheat)
    if (deltaSec > 600) {
      await db
        .update(modAccounts)
        .set({
          playtimeFrozen: "true",
          playtimeFreezeReason: "SESSION_TIMEOUT: no heartbeat for >10 minutes",
          playtimeFrozenAt: now,
          playtimeFrozenSessionId: session.id,
          isOnline: "false",
          lastSeenAt: now,
          updatedAt: now,
        })
        .where(eq(modAccounts.id, session.accountId));

      await db.insert(modFreezeLogs).values({
        accountId: session.accountId,
        action: "freeze",
        reason: "SESSION_TIMEOUT: no heartbeat for >10 minutes",
        performedBy: "auto",
        sessionId: session.id,
      });

      // Close session without counting playtime
      await db
        .update(modSessions)
        .set({
          endedAt: now,
          closeReason: "timeout",
        })
        .where(eq(modSessions.id, session.id));

      if (session.accountKey) {
        reportOfflineToLicenseServer(session.accountKey, "").catch(() => {});
      }

      frozen++;
      continue;
    }

    // Normal timeout (4-10 minutes): close session without counting playtime
    // Playtime is tracked exclusively via batch (v2.2); if batch missed, it's either:
    // - graceful exit (emergency batch should have recorded it)
    // - or a problem (no way to recover accurately)
    await db
      .update(modSessions)
      .set({
        endedAt: now,
        closeReason: "timeout",
      })
      .where(eq(modSessions.id, session.id));

    await db
      .update(modAccounts)
      .set({ isOnline: "false", lastSeenAt: now, updatedAt: now })
      .where(eq(modAccounts.id, session.accountId));

    if (session.accountKey) {
      reportOfflineToLicenseServer(session.accountKey, "").catch(() => {});
    }

    closed++;
  }

  return { closed, frozen };
}

// ─── Cleanup old sessions (keep 90 days) ───
export async function runSessionCleanup(): Promise<{ deleted: number }> {
  const db = getDb();
  const cutoff = new Date(Date.now() - 90 * 24 * 60 * 60 * 1000);

  const result = await db
    .delete(modSessions)
    .where(lt(modSessions.createdAt, cutoff));

  return { deleted: result[0].affectedRows || 0 };
}

// ─── Cleanup old playtime records (keep 2 years) ───
export async function runPlaytimeCleanup(): Promise<{ deleted: number }> {
  const db = getDb();
  const cutoff = new Date(Date.now() - 730 * 24 * 60 * 60 * 1000);

  const result = await db
    .delete(modPlaytimeDaily)
    .where(lt(modPlaytimeDaily.createdAt, cutoff));

  return { deleted: result[0].affectedRows || 0 };
}

// ─── Moscow date helper ───
function getMoscowDateString(): string {
  return new Date().toLocaleDateString("en-CA", {
    timeZone: "Europe/Moscow",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

// ─── Insert a brand-new license account into mod_accounts ───
// Shared by daily full sync and the 5-min online sync so new accounts show up
// on the site within minutes instead of waiting for the 05:00 MSK full sync.
async function insertNewLicenseAccount(
  db: ReturnType<typeof getDb>,
  lsAccount: Awaited<ReturnType<typeof getAllLicenseAccounts>>[number]
): Promise<void> {
  await db.insert(modAccounts).values({
    accountId: lsAccount.id,
    accountKey: lsAccount.accountKey,
    accountKeyHash: lsAccount.accountKey,
    hwidHash: lsAccount.hwidHash || null,
    displayName: (lsAccount.displayName && lsAccount.displayName.trim() !== "" && lsAccount.displayName !== "None")
      ? lsAccount.displayName
      : `Account #${lsAccount.id}`,
    contact: lsAccount.contact || null,
    licenseRoles: lsAccount.roles,
    isOnline: lsAccount.isOnline ? "true" : "false",
    lastSeenAt: new Date(lsAccount.lastSeenAt || lsAccount.createdAt),
    lastSyncedAt: new Date(),
  });
}

// ─── Daily full sync (all accounts) ───
export async function runDailyAccountSync(): Promise<{ inserted: number; updated: number; errors: number }> {
  const db = getDb();
  const accounts = await getAllLicenseAccounts();
  let inserted = 0;
  let updated = 0;
  let errors = 0;

  for (const lsAccount of accounts) {
    try {
      const existing = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.accountId, lsAccount.id))
        .limit(1);

      const highestRole = pickHighestLicenseRole(lsAccount.roles);
      const mappedRole = normalizeRole(highestRole);
      const nickG = parseGradientColors(lsAccount.nickGradients?.[highestRole ?? ""]);
      const roleG = parseGradientColors(lsAccount.roleGradients?.[highestRole ?? ""]);

      if (existing.length === 0) {
        await insertNewLicenseAccount(db, lsAccount);
        inserted++;
      } else {
        const ma = existing[0];
        const newDisplayName = (lsAccount.displayName && lsAccount.displayName.trim() !== "" && lsAccount.displayName !== "None")
          ? lsAccount.displayName
          : (ma.displayName && ma.displayName !== "None" ? ma.displayName : `Account #${lsAccount.id}`);

        await db.update(modAccounts).set({
          accountKey: lsAccount.accountKey,
          hwidHash: lsAccount.hwidHash || null,
          displayName: newDisplayName,
          contact: lsAccount.contact || ma.contact,
          licenseRoles: lsAccount.roles,
          isOnline: lsAccount.isOnline ? "true" : "false",
          lastSeenAt: new Date(lsAccount.lastSeenAt || lsAccount.createdAt),
          lastSyncedAt: new Date(),
        }).where(eq(modAccounts.id, ma.id));
        updated++;
      }

      // Sync player_profiles if discord-linked account exists
      const [linkedProfile] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, String(lsAccount.id)))
        .limit(1);

      if (linkedProfile) {
        await db.update(playerProfiles).set({
          displayName: lsAccount.displayName || linkedProfile.displayName,
          role: mappedRole,
          nickGradientFrom: nickG?.from ?? linkedProfile.nickGradientFrom,
          nickGradientTo: nickG?.to ?? linkedProfile.nickGradientTo,
          roleGradientFrom: roleG?.from ?? linkedProfile.roleGradientFrom,
          roleGradientTo: roleG?.to ?? linkedProfile.roleGradientTo,
        }).where(eq(playerProfiles.id, linkedProfile.id));
      }
    } catch (e) {
      console.error(`[DailySync] Failed for LS account ${lsAccount.id}:`, e);
      errors++;
    }
  }

  console.log(`[DailySync] Inserted: ${inserted}, Updated: ${updated}, Errors: ${errors}, Total: ${accounts.length}`);
  return { inserted, updated, errors };
}

// ─── Online-only sync (every 5 min) ───
export async function runOnlineAccountSync(): Promise<{ inserted: number; updated: number; errors: number }> {
  const db = getDb();
  const accounts = await getAllLicenseAccounts();
  let inserted = 0;
  let updated = 0;
  let errors = 0;

  for (const lsAccount of accounts) {
    try {
      const existing = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.accountId, lsAccount.id))
        .limit(1);

      if (existing.length === 0) {
        // New account appeared on the License Server since the last full sync.
        // Create it now so it shows up on the site within minutes.
        await insertNewLicenseAccount(db, lsAccount);
        inserted++;
        continue;
      }

      const ma = existing[0];
      const newDisplayName = (lsAccount.displayName && lsAccount.displayName.trim() !== "" && lsAccount.displayName !== "None")
        ? lsAccount.displayName
        : (ma.displayName && ma.displayName !== "None" ? ma.displayName : `Account #${lsAccount.id}`);

      // Only update if something changed (online status, lastSeen, displayName)
      const needsUpdate =
        ma.isOnline !== (lsAccount.isOnline ? "true" : "false") ||
        newDisplayName !== ma.displayName;

      if (needsUpdate) {
        await db.update(modAccounts).set({
          displayName: newDisplayName,
          isOnline: lsAccount.isOnline ? "true" : "false",
          lastSeenAt: new Date(lsAccount.lastSeenAt || lsAccount.createdAt),
          lastSyncedAt: new Date(),
        }).where(eq(modAccounts.id, ma.id));
        updated++;
      }

      // For linked accounts: sync gradients/roles only if they changed
      if (ma.discordId) {
        const highestRole = pickHighestLicenseRole(lsAccount.roles);
        const mappedRole = normalizeRole(highestRole);
        const nickG = parseGradientColors(lsAccount.nickGradients?.[highestRole ?? ""]);
        const roleG = parseGradientColors(lsAccount.roleGradients?.[highestRole ?? ""]);

        const [linkedProfile] = await db
          .select()
          .from(playerProfiles)
          .where(eq(playerProfiles.discordId, ma.discordId))
          .limit(1);

        if (linkedProfile) {
          const roleChanged = linkedProfile.role !== mappedRole;
          const gradientChanged =
            (nickG?.from && linkedProfile.nickGradientFrom !== nickG.from) ||
            (roleG?.from && linkedProfile.roleGradientFrom !== roleG.from);

          if (roleChanged || gradientChanged) {
            await db.update(playerProfiles).set({
              displayName: lsAccount.displayName || linkedProfile.displayName,
              role: mappedRole,
              nickGradientFrom: nickG?.from ?? linkedProfile.nickGradientFrom,
              nickGradientTo: nickG?.to ?? linkedProfile.nickGradientTo,
              roleGradientFrom: roleG?.from ?? linkedProfile.roleGradientFrom,
              roleGradientTo: roleG?.to ?? linkedProfile.roleGradientTo,
            }).where(eq(playerProfiles.id, linkedProfile.id));
          }
        }
      }
    } catch (e) {
      console.error(`[OnlineSync] Failed for LS account ${lsAccount.id}:`, e);
      errors++;
    }
  }

  if (inserted > 0 || updated > 0) {
    console.log(`[OnlineSync] Inserted: ${inserted}, Updated: ${updated}, Errors: ${errors}`);
  }
  return { inserted, updated, errors };
}

// ─── Rebuild monthly + alltime rollups from daily data ───
// daily is the single source of truth (minutes only). monthly, alltime and the
// per-server alltime totals are all derived from the daily tables here so they
// can never drift apart.
export async function runRollupRebuild(): Promise<{ monthlyRows: number; alltimeRows: number; serversAlltimeRows: number }> {
  const db = getDb();

  // Rebuild monthly rollup using raw SQL.
  // Column/table names are backticked: year_month + minutes can trip the parser otherwise.
  const monthlyRaw = await db.execute(sql.raw(`
    INSERT INTO mod_playtime_monthly (\`account_id\`, \`year_month\`, \`minutes\`)
    SELECT \`account_id\`, DATE_FORMAT(\`date_msk\`, '%Y-%m'), SUM(\`minutes\`)
    FROM mod_playtime_daily
    WHERE \`minutes\` > 0
    GROUP BY \`account_id\`, DATE_FORMAT(\`date_msk\`, '%Y-%m')
    ON DUPLICATE KEY UPDATE \`minutes\` = VALUES(\`minutes\`)
  `));

  // Rebuild alltime rollup using raw SQL
  const alltimeRaw = await db.execute(sql.raw(`
    INSERT INTO mod_playtime_alltime (\`account_id\`, \`minutes\`)
    SELECT \`account_id\`, SUM(\`minutes\`)
    FROM mod_playtime_daily
    WHERE \`minutes\` > 0
    GROUP BY \`account_id\`
    ON DUPLICATE KEY UPDATE \`minutes\` = VALUES(\`minutes\`)
  `));

  // Rebuild per-server alltime totals from per-server daily data, so the
  // server bars on the profile can never exceed the account total time.
  const serversAlltimeRaw = await db.execute(sql.raw(`
    INSERT INTO mod_playtime_servers_alltime (\`account_id\`, \`server_ip\`, \`total_minutes\`)
    SELECT \`account_id\`, \`server_ip\`, SUM(\`minutes\`)
    FROM mod_playtime_servers_daily
    WHERE \`minutes\` > 0
    GROUP BY \`account_id\`, \`server_ip\`
    ON DUPLICATE KEY UPDATE \`total_minutes\` = VALUES(\`total_minutes\`)
  `));

  return {
    monthlyRows: (monthlyRaw as any)?.[0]?.affectedRows ?? 0,
    alltimeRows: (alltimeRaw as any)?.[0]?.affectedRows ?? 0,
    serversAlltimeRows: (serversAlltimeRaw as any)?.[0]?.affectedRows ?? 0,
  };
}

// ═════════════════════════════════════════════════════════════════
// Scheduler
// ═════════════════════════════════════════════════════════════════
let intervals: NodeJS.Timeout[] = [];

export async function runOfflineWorker(): Promise<{ setOffline: number }> {
  const db = getDb();
  const cutoff = new Date(Date.now() - 11 * 60_000); // 11 minutes

  const result = await db
    .update(modAccounts)
    .set({ isOnline: "false", updatedAt: new Date() })
    .where(
      and(
        eq(modAccounts.isOnline, "true"),
        lt(modAccounts.lastSeenAt, cutoff)
      )
    );

  return { setOffline: result[0].affectedRows || 0 };
}

export function startWorkers(): void {
  // Timeout worker — every 1 minute
  intervals.push(
    setInterval(async () => {
      try {
        const result = await runTimeoutWorker();
        if (result.closed > 0) {
          console.log(`[Worker] Closed ${result.closed} stale sessions`);
        }
        if (result.frozen > 0) {
          console.log(`[Worker] Frozen ${result.frozen} accounts (session timeout >10min)`);
        }
      } catch (e) {
        console.error("[Worker] Timeout worker error:", e);
      }
    }, 60_000)
  );

  // Offline worker — every 1 minute (11 min timeout → offline)
  intervals.push(
    setInterval(async () => {
      try {
        const result = await runOfflineWorker();
        if (result.setOffline > 0) {
          console.log(`[Worker] Set ${result.setOffline} accounts offline (11min timeout)`);
        }
      } catch (e) {
        console.error("[Worker] Offline worker error:", e);
      }
    }, 60_000)
  );

  // Online-only sync — every 5 minutes
  intervals.push(
    setInterval(async () => {
      try {
        const result = await runOnlineAccountSync();
        if (result.inserted > 0 || result.updated > 0) {
          console.log(`[Worker] Online sync: +${result.inserted} new, ~${result.updated} updated`);
        }
      } catch (e) {
        console.error("[Worker] Online sync error:", e);
      }
    }, 5 * 60_000)
  );

  // Rollup cache rebuild — every 5 minutes.
  // monthly / alltime / servers_alltime are pure caches for the leaderboards,
  // rebuilt (overwritten) from the daily source of truth so they can never drift.
  // Profile reads aggregate daily live, so they are always exact regardless.
  intervals.push(
    setInterval(async () => {
      try {
        await runRollupRebuild();
      } catch (e) {
        console.error("[Worker] Rollup cache rebuild error:", e);
      }
    }, 5 * 60_000)
  );
  // Build the cache once on startup so leaderboards are correct right after deploy.
  runRollupRebuild().catch((e) => console.error("[Worker] Initial rollup rebuild error:", e));

  // Daily full sync at 05:00 MSK
  scheduleDailyAt(5, 0, "daily-sync", async () => {
    try {
      const result = await runDailyAccountSync();
      console.log(`[Worker] Daily full sync: +${result.inserted} new, ~${result.updated} updated, ${result.errors} errors`);
    } catch (e) {
      console.error("[Worker] Daily sync error:", e);
    }
  });

  // Run daily sync once immediately on startup (to catch up after deploy)
  runDailyAccountSync().catch(console.error);

  // Daily cleanup at 05:00 MSK
  scheduleDailyAt(5, 5, "cleanup", async () => {
    try {
      const s = await runSessionCleanup();
      const p = await runPlaytimeCleanup();
      console.log(`[Worker] Daily cleanup: ${s.deleted} sessions, ${p.deleted} playtime records`);
    } catch (e) {
      console.error("[Worker] Daily cleanup error:", e);
    }
  });

  // Daily rollup rebuild at 05:10 MSK (safety net: rebuilds rollups from daily data)
  scheduleDailyAt(5, 10, "rollup-rebuild", async () => {
    try {
      const result = await runRollupRebuild();
      console.log(`[Worker] Rollup rebuild: ${result.monthlyRows} monthly, ${result.alltimeRows} alltime, ${result.serversAlltimeRows} servers-alltime`);
    } catch (e) {
      console.error("[Worker] Rollup rebuild error:", e);
    }
  });

  console.log("[Workers] Started");
}

export function stopWorkers(): void {
  for (const iv of intervals) {
    clearInterval(iv);
  }
  intervals = [];
}

// ─── Schedule a task to run at specific MSK time ───
function scheduleDailyAt(hour: number, minute: number, label: string, task: () => Promise<void>): void {
  let lastRun: string | null = null;

  const check = () => {
    const now = new Date();
    const mskTime = new Date(
      now.toLocaleString("en-US", { timeZone: "Europe/Moscow" })
    );
    const key = `${mskTime.getFullYear()}-${mskTime.getMonth()}-${mskTime.getDate()}_${hour}:${minute}`;

    if (mskTime.getHours() === hour && mskTime.getMinutes() === minute && lastRun !== key) {
      lastRun = key;
      task().catch(console.error);
    }
  };

  // Check every minute
  intervals.push(setInterval(check, 60_000));
}
