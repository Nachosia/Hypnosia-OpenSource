import { randomInt } from "crypto";
import { Hono } from "hono";
import { getDb } from "./queries/connection";
import {
  modAccounts,
  modSessions,
  modPlaytimeDaily,
  modPlaytimeServersDaily,
  modLinkCodes,
  modNotifications,
  modFreezeLogs,
  siteStats,
} from "@db/schema";
import { eq, and, sql, isNull, desc, gt, isNotNull } from "drizzle-orm";
import {
  checkModAuth,
  checkRateLimit,
  checkReplayProtection,
  hashAccountKey,
  generateSessionToken,
  hashLinkCode,
  getMoscowDateString,
  getClientIp,
  verifyBatchSignature,
  checkBatchRateLimit,
  markBatchAccepted,
} from "./lib/mod-auth";
import {
  getLicenseAccountInfo,
  reportOfflineToLicenseServer,
} from "./lib/license-client";
import { encrypt } from "./lib/encryption";

const MAX_HEARTBEAT_GAP_SECONDS = 240; // 4 minutes
const SESSION_CODE_TTL_MINUTES = 10;

// в”Ђв”Ђв”Ђ Helper: find or create mod account from license server в”Ђв”Ђв”Ђ
async function findOrCreateModAccount(
  accountKey: string,
  hwidHash?: string
): Promise<{ id: number; accountId: number; hwidHash: string | null; playtimeBanned: string; playtimeFrozen: string } | null> {
  const db = getDb();
  const keyHash = hashAccountKey(accountKey);

  // Check local cache first
  const [existing] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.accountKeyHash, keyHash))
    .limit(1);

  if (existing) {
    // Update HWID + accountKey if changed
    const updates: Record<string, any> = { updatedAt: new Date() };
    if (hwidHash && existing.hwidHash !== hwidHash) updates.hwidHash = hwidHash;
    if (!existing.accountKey && accountKey) updates.accountKey = accountKey;
    if (Object.keys(updates).length > 1) {
      await db
        .update(modAccounts)
        .set(updates)
        .where(eq(modAccounts.id, existing.id));
    }
    return { id: existing.id, accountId: existing.accountId, hwidHash: existing.hwidHash, playtimeBanned: existing.playtimeBanned, playtimeFrozen: existing.playtimeFrozen };
  }

  // Fetch from license server
  let licenseInfo: import("./lib/license-client").LicenseAccountInfo | null = null;
  try {
    licenseInfo = await getLicenseAccountInfo(accountKey, hwidHash);
  } catch (e: any) {
    console.error("[ModAPI] License server unreachable:", e.message);
    throw new Error("LICENSE_SERVER_UNAVAILABLE");
  }

  if (!licenseInfo) {
    return null; // Unknown account key
  }

  // Create local cache
  const [result] = await db.insert(modAccounts).values({
    accountId: licenseInfo.accountId ?? licenseInfo.id,
    accountKeyEnc: encrypt(accountKey),
    accountKeyHash: keyHash,
    hwidHash: hwidHash || licenseInfo.hwidHash || null,
    displayName: licenseInfo.name || null,
    contact: licenseInfo.contact || null,
    licenseRoles: licenseInfo.roles || [],
    lastSyncedAt: new Date(),
  });

  const insertedId = Number(result.insertId);
  return { id: insertedId, accountId: licenseInfo.accountId ?? licenseInfo.id, hwidHash: hwidHash || licenseInfo.hwidHash || null, playtimeBanned: "false", playtimeFrozen: "false" };
}

// в”Ђв”Ђв”Ђ Helper: close active session for account в”Ђв”Ђв”Ђ
// Session is closed without counting playtime вЂ” playtime is tracked exclusively via batch (v2.2)
async function closeActiveSession(
  accountDbId: number,
  reason: "replaced" | "timeout" | "admin" | "normal"
): Promise<void> {
  const db = getDb();
  const [activeSession] = await db
    .select()
    .from(modSessions)
    .where(and(eq(modSessions.accountId, accountDbId), isNull(modSessions.endedAt)))
    .limit(1);

  if (!activeSession) return;

  const now = new Date();

  await db
    .update(modSessions)
    .set({
      endedAt: now,
      closeReason: reason,
    })
    .where(eq(modSessions.id, activeSession.id));
}

// в”Ђв”Ђв”Ђ Helper: normalize server IP for per-server tracking в”Ђв”Ђв”Ђ
function normalizeServerIp(raw: string): string {
  if (raw === "singleplayer") return raw;
  let ip = raw.split(":")[0].trim().toLowerCase();
  if (ip.startsWith("lobby.")) {
    ip = ip.slice(6);
  }
  return ip;
}

// в•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђ
export const modApi = new Hono();

// в”Ђв”Ђв”Ђ Replay + Rate Limit middleware for new endpoints в”Ђв”Ђв”Ђ
modApi.use("/session/*", async (c, next) => {
  if (!checkModAuth(c)) return c.json({ error: "UNAUTHORIZED" }, 401);

  const ip = getClientIp(c);
  const rate = checkRateLimit(ip);
  if (!rate.allowed) {
    c.header("Retry-After", String(rate.retryAfter));
    return c.json({ error: "RATE_LIMITED", retryAfter: rate.retryAfter }, 429);
  }

  // Optional replay protection (timestamp + nonce in body).
  // Skip for /session/batch: that handler verifies HMAC first, then consumes
  // the nonce itself. Consuming it here too would mark every batch as a replay.
  if (!c.req.path.endsWith("/session/batch")) {
    try {
      const body = await c.req.json();
      if (body.timestamp != null && body.nonce != null) {
        const replay = await checkReplayProtection(Number(body.timestamp), String(body.nonce));
        if (!replay.valid) {
          return c.json({ error: replay.error }, 400);
        }
      }
    } catch {
      // GET requests or invalid JSON вЂ” skip replay check
    }
  }

  return next();
});

modApi.use("/link/*", async (c, next) => {
  if (!checkModAuth(c)) return c.json({ error: "UNAUTHORIZED" }, 401);

  const ip = getClientIp(c);
  const rate = checkRateLimit(ip);
  if (!rate.allowed) {
    c.header("Retry-After", String(rate.retryAfter));
    return c.json({ error: "RATE_LIMITED", retryAfter: rate.retryAfter }, 429);
  }

  try {
    const body = await c.req.json();
    if (body.timestamp != null && body.nonce != null) {
      const replay = await checkReplayProtection(Number(body.timestamp), String(body.nonce));
      if (!replay.valid) {
        return c.json({ error: replay.error }, 400);
      }
    }
  } catch {
    // skip
  }

  return next();
});

// в•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђ
// NEW ENDPOINTS
// в•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђв•ђ

// в”Ђв”Ђв”Ђ POST /api/mod/session/start в”Ђв”Ђв”Ђ
modApi.post("/session/start", async (c) => {
  const body = await c.req.json<{
    accountKey: string;
    hwidHash?: string;
    minecraftVersion?: string;
    modVersion?: string;
  }>();

  const { accountKey, hwidHash, minecraftVersion, modVersion } = body;
  if (!accountKey) {
    return c.json({ error: "MISSING_ACCOUNT_KEY" }, 400);
  }

  const db = getDb();
  let account: { id: number; accountId: number } | null;
  try {
    account = await findOrCreateModAccount(accountKey, hwidHash);
  } catch (e: any) {
    if (e.message === "LICENSE_SERVER_UNAVAILABLE") {
      return c.json({ error: "LICENSE_SERVER_UNAVAILABLE" }, 503);
    }
    throw e;
  }
  if (!account) {
    return c.json({ error: "UNKNOWN_ACCOUNT_KEY" }, 404);
  }

  // Close any existing active session
  await closeActiveSession(account.id, "replaced");

  // Create new session
  const token = generateSessionToken();
  const now = new Date();

  await db.insert(modSessions).values({
    accountId: account.id,
    accountKey: accountKey,
    sessionToken: token,
    startedAt: now,
    lastHeartbeatAt: now,
    lastCountedAt: now,
    clientIp: getClientIp(c),
    minecraftVersion: minecraftVersion || null,
    modVersion: modVersion || null,
  });

  // Mark account online
  await db
    .update(modAccounts)
    .set({ isOnline: "true", lastSeenAt: now, updatedAt: now })
    .where(eq(modAccounts.id, account.id));

  return c.json({
    success: true,
    sessionToken: token,
    accountId: account.accountId,
  });
});

// в”Ђв”Ђв”Ђ POST /api/mod/session/heartbeat в”Ђв”Ђв”Ђ
// Heartbeat only updates lastHeartbeatAt вЂ” playtime is tracked exclusively via batch (v2.2)
modApi.post("/session/heartbeat", async (c) => {
  const body = await c.req.json<{
    sessionToken: string;
  }>();

  const { sessionToken } = body;
  if (!sessionToken) {
    return c.json({ error: "MISSING_SESSION_TOKEN" }, 400);
  }

  const db = getDb();
  const [session] = await db
    .select()
    .from(modSessions)
    .where(eq(modSessions.sessionToken, sessionToken))
    .limit(1);

  if (!session) {
    return c.json({ error: "SESSION_NOT_FOUND" }, 404);
  }

  if (session.endedAt) {
    return c.json({ error: "SESSION_ALREADY_CLOSED" }, 410);
  }

  const now = new Date();

  // Update session heartbeat only вЂ” no playtime counting here (batch handles it)
  await db
    .update(modSessions)
    .set({
      lastHeartbeatAt: now,
    })
    .where(eq(modSessions.id, session.id));

  // Update account last seen
  await db
    .update(modAccounts)
    .set({ lastSeenAt: now, updatedAt: now })
    .where(eq(modAccounts.id, session.accountId));

  return c.json({ success: true });
});

// в”Ђв”Ђв”Ђ POST /api/mod/session/end в”Ђв”Ђв”Ђ
modApi.post("/session/end", async (c) => {
  const body = await c.req.json<{
    sessionToken: string;
    reason?: "normal" | "timeout" | "admin";
    isEmergency?: boolean;
  }>();

  const { sessionToken, reason = "normal", isEmergency } = body;
  if (!sessionToken) {
    return c.json({ error: "MISSING_SESSION_TOKEN" }, 400);
  }

  const db = getDb();
  const [session] = await db
    .select()
    .from(modSessions)
    .where(eq(modSessions.sessionToken, sessionToken))
    .limit(1);

  if (!session) {
    return c.json({ error: "SESSION_NOT_FOUND" }, 404);
  }

  if (session.endedAt) {
    return c.json({ success: true, alreadyClosed: true });
  }

  const now = new Date();

  // Grace period for crash/emergency: give 5 minutes to reconnect
  if (isEmergency === true) {
    const graceEndAt = new Date(now.getTime() + 5 * 60 * 1000);
    await db
      .update(modSessions)
      .set({
        graceEndAt,
        closeReason: "normal",
      })
      .where(eq(modSessions.id, session.id));

    await db
      .update(modAccounts)
      .set({ isOnline: "false", lastSeenAt: now, updatedAt: now })
      .where(eq(modAccounts.id, session.accountId));

    return c.json({ success: true, gracePeriod: true, graceEndAt });
  }

  // Close session вЂ” playtime is tracked exclusively via batch (v2.2)
  await db
    .update(modSessions)
    .set({
      endedAt: now,
      closeReason: reason,
    })
    .where(eq(modSessions.id, session.id));

  // Mark account offline
  await db
    .update(modAccounts)
    .set({ isOnline: "false", lastSeenAt: now, updatedAt: now })
    .where(eq(modAccounts.id, session.accountId));

  // Report offline to license server (fire and forget)
  if (session.accountKey) {
    reportOfflineToLicenseServer(session.accountKey, "").catch(() => {});
  }

  return c.json({ success: true, countedSeconds: 0, totalSeconds: session.durationSeconds });
});

// в”Ђв”Ђв”Ђ POST /api/mod/session/batch (HMAC-protected batch playtime v2.2) в”Ђв”Ђв”Ђ
modApi.post("/session/batch", async (c) => {
  const body = await c.req.json<{
    accountId: number;
    accountKey: string;
    hwidHash: string;
    sessionToken: string;
    activeMinutes: number;
    status: "online" | "offline";
    timestamp: number;
    nonce: string;
    signature: string;
    servers?: { serverIp: string; activeMinutes: number }[];
    isEmergency?: boolean;
  }>();

  const { accountId, accountKey, hwidHash, sessionToken, activeMinutes, status, timestamp, nonce, signature, servers, isEmergency } = body;

  // в”Ђв”Ђв”Ђ Field validation в”Ђв”Ђв”Ђ
  // sessionToken is optional вЂ” batch accepted on HMAC + accountKey + hwidHash alone
  if (
    accountId == null || !accountKey || !hwidHash ||
    activeMinutes == null || !status ||
    !timestamp || !nonce || !signature
  ) {
    return c.json({ error: "MISSING_FIELDS" }, 400);
  }

  // в”Ђв”Ђв”Ђ Range validation в”Ђв”Ђв”Ђ
  // Allow up to 11 to catch autofreeze; > 11 is physically impossible for 10-min interval
  if (activeMinutes < 0 || activeMinutes > 11) {
    return c.json({ error: "INVALID_RANGE" }, 400);
  }

  // в”Ђв”Ђв”Ђ servers[] validation в”Ђв”Ђв”Ђ
  if (servers && Array.isArray(servers)) {
    if (servers.length > 10) {
      return c.json({ error: "TOO_MANY_SERVERS" }, 400);
    }
    const serversTotal = servers.reduce((sum, s) => sum + (Number(s.activeMinutes) || 0), 0);
    if (serversTotal > activeMinutes) {
      return c.json({ error: "SERVERS_MINUTES_EXCEED_TOTAL" }, 400);
    }
  }

  // в”Ђв”Ђв”Ђ Per-account rate limit (9 min / 5 min emergency) в”Ђв”Ђв”Ђ
  const rate = await checkBatchRateLimit(accountKey, isEmergency === true);
  if (!rate.allowed) {
    c.header("Retry-After", String(rate.retryAfter));
    return c.json({ error: "BATCH_TOO_FREQUENT", retryAfter: rate.retryAfter }, 429);
  }

  // в”Ђв”Ђв”Ђ HMAC validation в”Ђв”Ђв”Ђ
  // v2.2 payload: accountKey:sessionToken:timestamp:activeMinutes:status:nonce
  // sessionToken may be absent (mod sends empty segment)
  const payload = sessionToken
    ? `${accountKey}:${sessionToken}:${timestamp}:${activeMinutes}:${status}:${nonce}`
    : `${accountKey}::${timestamp}:${activeMinutes}:${status}:${nonce}`;
  const hmac = verifyBatchSignature(payload, signature, timestamp);
  if (!hmac.valid) {
    return c.json({ error: hmac.error }, 403);
  }

  // в”Ђв”Ђв”Ђ Replay protection в”Ђв”Ђв”Ђ
  const replay = await checkReplayProtection(timestamp, nonce);
  if (!replay.valid) {
    return c.json({ error: replay.error }, 400);
  }

  const db = getDb();

  // в”Ђв”Ђв”Ђ Find account в”Ђв”Ђв”Ђ
  let account: { id: number; accountId: number; hwidHash: string | null; playtimeBanned: string; playtimeFrozen: string } | null;
  try {
    account = await findOrCreateModAccount(accountKey, hwidHash);
  } catch (e: any) {
    if (e.message === "LICENSE_SERVER_UNAVAILABLE") {
      return c.json({ error: "LICENSE_SERVER_UNAVAILABLE" }, 503);
    }
    throw e;
  }
  if (!account) {
    return c.json({ error: "UNKNOWN_ACCOUNT_KEY" }, 404);
  }

  // в”Ђв”Ђв”Ђ Check if banned or frozen в”Ђв”Ђв”Ђ
  if (account.playtimeBanned === "true") {
    return c.json({ error: "PLAYTIME_BANNED" }, 403);
  }
  if (account.playtimeFrozen === "true") {
    return c.json({ error: "PLAYTIME_FROZEN" }, 403);
  }

  // в”Ђв”Ђв”Ђ Validate accountId matches в”Ђв”Ђв”Ђ
  if (account.accountId !== accountId) {
    return c.json({ error: "ACCOUNT_ID_MISMATCH" }, 403);
  }

  // в”Ђв”Ђв”Ђ HWID check / bind в”Ђв”Ђв”Ђ
  if (account.hwidHash) {
    if (account.hwidHash !== hwidHash) {
      return c.json({ error: "HWID_MISMATCH" }, 403);
    }
  } else {
    // First login вЂ” bind HWID permanently
    await db
      .update(modAccounts)
      .set({ hwidHash, updatedAt: new Date() })
      .where(eq(modAccounts.id, account.id));
  }

  // в”Ђв”Ђв”Ђ Validate sessionToken (optional) в”Ђв”Ђв”Ђ
  let session: typeof modSessions.$inferSelect | undefined;
  if (sessionToken) {
    const [found] = await db
      .select()
      .from(modSessions)
      .where(eq(modSessions.sessionToken, sessionToken))
      .limit(1);

    if (!found) {
      return c.json({ error: "SESSION_NOT_FOUND" }, 404);
    }
    if (found.endedAt) {
      return c.json({ error: "SESSION_ALREADY_CLOSED" }, 410);
    }
    if (found.accountId !== account.id) {
      return c.json({ error: "SESSION_ACCOUNT_MISMATCH" }, 403);
    }
    const heartbeatCutoff = new Date(Date.now() - 240 * 1000);
    if (found.lastHeartbeatAt < heartbeatCutoff) {
      return c.json({ error: "SESSION_HEARTBEAT_EXPIRED" }, 410);
    }
    session = found;
  }

  // в”Ђв”Ђв”Ђ Autofreeze: activeMinutes > 11 is physically impossible for 10-min interval в”Ђв”Ђв”Ђ
  if (activeMinutes > 11) {
    const db = getDb();
    const acc = await db.select({ id: modAccounts.id }).from(modAccounts).where(eq(modAccounts.accountKey, accountKey)).limit(1);
    const accountDbId = acc[0]?.id;

    if (accountDbId) {
      await db
        .update(modAccounts)
        .set({
          playtimeFrozen: "true",
          playtimeFreezeReason: `activeMinutes=${activeMinutes} exceeds 11min limit`,
          playtimeFrozenAt: new Date(),
          updatedAt: new Date(),
        })
        .where(eq(modAccounts.id, accountDbId));

      await db.insert(modFreezeLogs).values({
        accountId: accountDbId,
        action: "freeze",
        reason: `activeMinutes=${activeMinutes} exceeds 11min limit`,
        performedBy: "auto",
        sessionId: session ? Number(session.id) : null,
      });
    }

    // End session if token provided
    if (sessionToken) {
      await db
        .update(modSessions)
        .set({ endedAt: new Date(), closeReason: "admin" })
        .where(eq(modSessions.sessionToken, sessionToken));
    }

    return c.json({ error: "ACCOUNT_FROZEN", reason: "activeMinutes exceeds physical limit" }, 403);
  }

  // в”Ђв”Ђв”Ђ Grace period for crash recovery в”Ђв”Ђв”Ђ
  if (isEmergency === true && status === "offline" && session) {
    const graceEndAt = new Date(Date.now() + 5 * 60 * 1000);
    await db
      .update(modSessions)
      .set({ graceEndAt })
      .where(eq(modSessions.id, session.id));
  }

  // в”Ђв”Ђв”Ђ Accept batch в”Ђв”Ђв”Ђ
  await markBatchAccepted(accountKey, isEmergency === true);

  const dateMsk = getMoscowDateString();
  const now = new Date();

  // Determine current server IP from servers array (the one with most minutes)
  let currentServerIp: string | null = null;
  if (servers && servers.length > 0) {
    const sorted = [...servers].sort((a, b) => (b.activeMinutes || 0) - (a.activeMinutes || 0));
    currentServerIp = normalizeServerIp(sorted[0].serverIp);
  } else if (status === "online") {
    currentServerIp = "unknown";
  }

  // Write activeMinutes to playtime tables.
  // daily + servers_daily are the single source of truth. monthly / alltime /
  // servers_alltime are derived caches, rebuilt from daily by the rollup worker
  // (see workers.ts). We intentionally do NOT increment them here вЂ” that was the
  // cause of rollup drift. Reads for a single account aggregate daily live.
  if (activeMinutes > 0) {
    await db
      .insert(modPlaytimeDaily)
      .values({ accountId: account.id, dateMsk, seconds: activeMinutes * 60, minutes: activeMinutes })
      .onDuplicateKeyUpdate({
        set: {
          seconds: sql`${modPlaytimeDaily.seconds} + ${activeMinutes * 60}`,
          minutes: sql`${modPlaytimeDaily.minutes} + ${activeMinutes}`,
        },
      });

    // Write per-server daily playtime (servers_alltime is rebuilt by the worker)
    if (servers && servers.length > 0) {
      for (const s of servers) {
        const serverIp = normalizeServerIp(s.serverIp);
        const srvMinutes = Math.max(0, Math.floor(Number(s.activeMinutes) || 0));
        if (srvMinutes <= 0) continue;

        await db
          .insert(modPlaytimeServersDaily)
          .values({ accountId: account.id, serverIp, dateMsk, minutes: srvMinutes })
          .onDuplicateKeyUpdate({
            set: {
              minutes: sql`${modPlaytimeServersDaily.minutes} + ${srvMinutes}`,
            },
          });
      }
    }
  }

  // Update session heartbeat if session exists
  if (session) {
    await db
      .update(modSessions)
      .set({ lastHeartbeatAt: now, lastCountedAt: now })
      .where(eq(modSessions.id, session.id));
  }

  // Update account online status + last seen + current server
  const accountUpdates: Record<string, any> = {
    isOnline: status === "online" ? "true" : "false",
    lastSeenAt: now,
    lastPlaytimeCountedAt: now,
    updatedAt: now,
  };
  if (currentServerIp !== null) {
    accountUpdates.currentServerIp = currentServerIp;
  }
  await db
    .update(modAccounts)
    .set(accountUpdates)
    .where(eq(modAccounts.id, account.id));

  return c.json({ success: true, acceptedActiveMinutes: activeMinutes });
});

// в”Ђв”Ђв”Ђ POST /api/mod/config/count-sync (License Server в†’ site) в”Ђв”Ђв”Ђ
modApi.post("/config/count-sync", async (c) => {
  const body = await c.req.json<{
    totalConfigs: number;
  }>();

  const apiKey = c.req.header("X-API-Key");
  const expectedKey = process.env.MOD_API_KEY || process.env.API_KEY;
  // Fail closed: never authorize against a hardcoded default key.
  if (!expectedKey || apiKey !== expectedKey) {
    return c.json({ error: "UNAUTHORIZED" }, 401);
  }

  const { totalConfigs } = body;
  if (typeof totalConfigs !== "number" || totalConfigs < 0) {
    return c.json({ error: "INVALID_VALUE" }, 400);
  }

  const db = getDb();
  await db
    .insert(siteStats)
    .values({ statKey: "total_configs", statValue: Math.floor(totalConfigs) })
    .onDuplicateKeyUpdate({
      set: {
        statValue: Math.floor(totalConfigs),
        updatedAt: new Date(),
      },
    });

  return c.json({ success: true, totalConfigs: Math.floor(totalConfigs) });
});

// в”Ђв”Ђв”Ђ POST /api/mod/link/create в”Ђв”Ђв”Ђ
modApi.post("/link/create", async (c) => {
  const body = await c.req.json<{
    accountKey: string;
    hwidHash?: string;
  }>();

  const { accountKey, hwidHash } = body;
  if (!accountKey) {
    return c.json({ error: "MISSING_ACCOUNT_KEY" }, 400);
  }

  const db = getDb();
  let account: { id: number; accountId: number } | null;
  try {
    account = await findOrCreateModAccount(accountKey, hwidHash);
  } catch (e: any) {
    if (e.message === "LICENSE_SERVER_UNAVAILABLE") {
      return c.json({ error: "LICENSE_SERVER_UNAVAILABLE" }, 503);
    }
    throw e;
  }
  if (!account) {
    return c.json({ error: "UNKNOWN_ACCOUNT_KEY" }, 404);
  }

  // Generate 6-char alphanumeric code
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let rawCode = "";
  for (let i = 0; i < 6; i++) {
    rawCode += chars.charAt(randomInt(chars.length));
  }

  const codeHash = hashLinkCode(rawCode);
  const expiresAt = new Date(Date.now() + SESSION_CODE_TTL_MINUTES * 60 * 1000);

  // Delete old unused codes for this account
  await db
    .delete(modLinkCodes)
    .where(
      and(
        eq(modLinkCodes.accountId, account.id),
        isNull(modLinkCodes.usedAt)
      )
    );

  await db.insert(modLinkCodes).values({
    accountId: account.id,
    accountKey: accountKey,
    codeHash,
    expiresAt,
    createdIp: getClientIp(c),
  });

  return c.json({
    success: true,
    code: rawCode,
    expiresIn: SESSION_CODE_TTL_MINUTES * 60,
  });
});

// в”Ђв”Ђв”Ђ POST /api/mod/notifications/poll в”Ђв”Ђв”Ђ
modApi.post("/notifications/poll", async (c) => {
  if (!checkModAuth(c)) return c.json({ error: "UNAUTHORIZED" }, 401);

  const body = await c.req.json<{
    accountKey: string;
  }>();

  const { accountKey } = body;
  if (!accountKey) {
    return c.json({ error: "MISSING_ACCOUNT_KEY" }, 400);
  }

  const db = getDb();
  const keyHash = hashAccountKey(accountKey);

  // Find local account
  const [account] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.accountKeyHash, keyHash))
    .limit(1);

  if (!account) {
    return c.json({ error: "UNKNOWN_ACCOUNT" }, 404);
  }

  // Get active notifications: broadcast (accountId IS NULL) or personal
  const now = new Date();
  const notifications = await db
    .select()
    .from(modNotifications)
    .where(
      and(
        isNull(modNotifications.readAt),
        gt(modNotifications.expiresAt, now),
        sql`${modNotifications.accountId} IS NULL OR ${modNotifications.accountId} = ${account.id}`
      )
    )
    .orderBy(desc(modNotifications.createdAt))
    .limit(10);

  // Mark as read
  if (notifications.length > 0) {
    const ids = notifications.map((n) => n.id);
    await db
      .update(modNotifications)
      .set({ readAt: now })
      .where(sql`${modNotifications.id} IN (${ids.join(",")})`);
  }

  return c.json({
    success: true,
    notifications: notifications.map((n) => ({
      id: n.id,
      message: n.message,
      createdAt: n.createdAt,
    })),
  });
});

// в”Ђв”Ђв”Ђ POST /api/admin/sync-account в”Ђв”Ђв”Ђ
modApi.post("/admin/sync-account", async (c) => {
  if (!checkModAuth(c)) return c.json({ error: "UNAUTHORIZED" }, 401);

  const body = await c.req.json<{
    accountKey: string;
  }>();

  const { accountKey } = body;
  if (!accountKey) {
    return c.json({ error: "MISSING_ACCOUNT_KEY" }, 400);
  }

  const db = getDb();
  const keyHash = hashAccountKey(accountKey);

  // Fetch from license server
  let licenseInfo: { id: number; name?: string; contact?: string; roles?: string[]; hwidHash?: string } | null = null;
  try {
    licenseInfo = await getLicenseAccountInfo(accountKey);
  } catch (e: any) {
    return c.json({ error: "LICENSE_SERVER_UNAVAILABLE" }, 503);
  }

  if (!licenseInfo) {
    return c.json({ error: "UNKNOWN_ACCOUNT_KEY" }, 404);
  }

  // Upsert local cache
  const [existing] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.accountKeyHash, keyHash))
    .limit(1);

  if (existing) {
    await db
      .update(modAccounts)
      .set({
        displayName: licenseInfo.name || existing.displayName,
        contact: licenseInfo.contact || existing.contact,
        licenseRoles: licenseInfo.roles || existing.licenseRoles,
        hwidHash: licenseInfo.hwidHash || existing.hwidHash,
        lastSyncedAt: new Date(),
      })
      .where(eq(modAccounts.id, existing.id));
  } else {
    await db.insert(modAccounts).values({
      accountId: licenseInfo.accountId ?? licenseInfo.id,
      accountKeyEnc: encrypt(accountKey),
      accountKeyHash: keyHash,
      hwidHash: licenseInfo.hwidHash || null,
      displayName: licenseInfo.name || null,
      contact: licenseInfo.contact || null,
      licenseRoles: licenseInfo.roles || [],
      lastSyncedAt: new Date(),
    });
  }

  return c.json({
    success: true,
    accountId: licenseInfo.accountId ?? licenseInfo.id,
    roles: licenseInfo.roles,
    displayName: licenseInfo.name,
  });
});

// в”Ђв”Ђв”Ђ POST /api/admin/notifications/send в”Ђв”Ђв”Ђ
modApi.post("/admin/notifications/send", async (c) => {
  if (!checkModAuth(c)) return c.json({ error: "UNAUTHORIZED" }, 401);

  const body = await c.req.json<{
    message: string;
    accountId?: number;
    ttlMinutes?: number;
  }>();

  const { message, accountId, ttlMinutes = 60 } = body;
  if (!message || message.length > 500) {
    return c.json({ error: "INVALID_MESSAGE" }, 400);
  }

  const db = getDb();
  const expiresAt = new Date(Date.now() + ttlMinutes * 60 * 1000);

  await db.insert(modNotifications).values({
    accountId: accountId || null,
    message,
    expiresAt,
  });

  return c.json({ success: true, sentTo: accountId || "all" });
});
