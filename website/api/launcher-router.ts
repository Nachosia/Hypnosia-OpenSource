import { Hono } from "hono";
import { setCookie, getCookie } from "hono/cookie";
import { createHmac, randomBytes } from "crypto";
import { z } from "zod";
import { env } from "./lib/env";
import { getDb } from "./queries/connection";
import { upsertUser, findUserByUnionId } from "./queries/users";
import { launcherDevices, modAccounts, playerProfiles, storeItems, userInventory, users } from "@db/schema";
import { eq, desc, and, ne } from "drizzle-orm";
import { LinkMinecraftException, linkMinecraftByDiscordId, linkLicenseAccountToDiscord } from "./lib/launcher-minecraft";
import { getLicenseAccountInfo, findOrCreateLicenseAccountByHwid, recoverLicenseAccountByKey, type LicenseAccountInfo } from "./lib/license-client";
import { hashAccountKey, checkLauncherRateLimit } from "./lib/mod-auth";
import { encrypt } from "./lib/encryption";

const LAUNCHER_REDIRECT_URI = "http://127.0.0.1:3000/api/launcher/oauth/callback";

const hwidSchema = z.string().min(8).max(128);
const codeSchema = z.string().regex(/^[A-Z0-9]{6}$/);
const accountKeySchema = z.string().regex(/^[A-Za-z0-9]{32}$/);

function hashHwid(hwid: string): string {
  // Identity — the launcher already sends the 64-char SHA-256 hash that the
  // mod and the license server share. Previously this applied HMAC-SHA256 with
  // appSecret on top, producing a different hash than the mod's own HWID and
  // splitting mod accounts into two invisible groups.
  return hwid;
}

async function getOrCreateHwidOnlyDevice(hwid: string) {
  const db = getDb();
  const hwidHash = hashHwid(hwid);
  await db
    .insert(launcherDevices)
    .values({ hwidHash, discordId: null })
    .onDuplicateKeyUpdate({
      set: { lastUsedAt: new Date() },
    });
  return hwidHash;
}

// Clear the server-side logout flag for a device. Called by every explicit
// login endpoint (login-hwid, oauth callback, recover-by-key) so that /me
// stops returning authenticated:false.
async function clearDeviceLogoutFlag(hwidHash: string) {
  const db = getDb();
  await db
    .update(launcherDevices)
    .set({ loggedOutAt: null })
    .where(eq(launcherDevices.hwidHash, hwidHash));
}

function createLauncherState(hwid: string): { state: string; nonce: string } {
  const nonce = randomBytes(16).toString("hex");
  const ts = Date.now();
  const payload = `${nonce}:${hwid}:${ts}`;
  const sig = createHmac("sha256", env.appSecret).update(payload).digest("hex");
  const stateObj = { nonce, hwid, ts, sig };
  const state = Buffer.from(JSON.stringify(stateObj)).toString("base64url");
  return { state, nonce };
}

function verifyLauncherState(state: string): { valid: boolean; nonce?: string; hwid?: string; error?: string } {
  try {
    const obj = JSON.parse(Buffer.from(state, "base64url").toString());
    const { nonce, hwid, ts, sig } = obj;
    if (!nonce || !hwid || !ts || !sig) {
      return { valid: false, error: "MALFORMED_STATE" };
    }
    const payload = `${nonce}:${hwid}:${ts}`;
    const expectedSig = createHmac("sha256", env.appSecret).update(payload).digest("hex");
    if (sig !== expectedSig) {
      return { valid: false, error: "INVALID_STATE_SIGNATURE" };
    }
    if (Date.now() - ts > 5 * 60 * 1000) {
      return { valid: false, error: "STATE_EXPIRED" };
    }
    return { valid: true, nonce, hwid };
  } catch {
    return { valid: false, error: "MALFORMED_STATE" };
  }
}

async function exchangeAuthCode(code: string, redirectUri: string) {
  const body = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    client_id: env.appId,
    redirect_uri: redirectUri,
    client_secret: env.appSecret,
  });

  const resp = await fetch(`${env.discordAuthUrl}/api/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });

  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`Token exchange failed (${resp.status}): ${text}`);
  }

  return resp.json() as Promise<{
    access_token: string;
    token_type: string;
    expires_in: number;
    refresh_token?: string;
    scope: string;
  }>;
}

async function fetchDiscordUser(accessToken: string) {
  const resp = await fetch(`${env.discordApiUrl}/users/@me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(`Discord profile fetch failed (${resp.status}): ${text}`);
  }
  return resp.json() as Promise<{
    id: string;
    username: string;
    email?: string;
    avatar?: string | null;
  }>;
}

function successHtml(): string {
  return `<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Hypnosia Launcher — авторизация</title>
  <style>
    body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #0b0c15; color: #e2e8f0; font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
    .card { text-align: center; padding: 2rem; border-radius: 1.5rem; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08); max-width: 420px; }
    h1 { font-size: 1.5rem; margin-bottom: 0.75rem; }
    p { color: #94a3b8; line-height: 1.5; }
    .icon { font-size: 3rem; margin-bottom: 1rem; }
  </style>
</head>
<body>
  <div class="card">
    <div class="icon">✅</div>
    <h1>Авторизация завершена</h1>
    <p>Можете вернуться в Hypnosia Launcher. Окно закроется автоматически.</p>
  </div>
  <script>
    setTimeout(() => window.close(), 3500);
  </script>
</body>
</html>`;
}

function errorHtml(message: string | undefined): string {
  return `<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Hypnosia Launcher — ошибка</title>
  <style>
    body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #0b0c15; color: #e2e8f0; font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
    .card { text-align: center; padding: 2rem; border-radius: 1.5rem; background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.08); max-width: 420px; }
    h1 { font-size: 1.5rem; margin-bottom: 0.75rem; }
    p { color: #f87171; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <h1>Ошибка авторизации</h1>
    <p>${(message || "").replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'} as Record<string, string>)[c])}</p>
  </div>
</body>
</html>`;
}

// ─── Rate limiting (in-memory) ───
const rateLimitStore = new Map<string, { attempts: number; resetAt: number }>();

function checkRateLimit(ip: string, maxAttempts = 10, windowMs = 60000): boolean {
  const now = Date.now();
  const entry = rateLimitStore.get(ip);
  if (!entry || now > entry.resetAt) {
    rateLimitStore.set(ip, { attempts: 1, resetAt: now + windowMs });
    return true;
  }
  if (entry.attempts >= maxAttempts) return false;
  entry.attempts++;
  return true;
}

function getClientIp(c: { req: { header: (name: string) => string | undefined } }): string {
  const forwarded = c.req.header("x-forwarded-for");
  if (forwarded) return forwarded.split(",")[0].trim();
  return c.req.header("x-real-ip") || "unknown";
}


async function syncLicenseAccountToDb(
  licenseInfo: {
    accountId?: number;
    id?: number;
    accountKey?: string;
    name?: string;
    roles?: string[];
  },
  hwidHash: string
) {
  const db = getDb();
  const accountId = licenseInfo.accountId ?? licenseInfo.id;
  if (!accountId) {
    throw new Error("LICENSE_ACCOUNT_INCOMPLETE");
  }

  const accountKey = licenseInfo.accountKey;
  if (!accountKey) {
    throw new Error("LICENSE_ACCOUNT_NO_KEY");
  }

  const keyHash = hashAccountKey(accountKey);

  // Prefer matching by key hash, fallback to license account id.
  const [existingByKey] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.accountKeyHash, keyHash))
    .limit(1);

  const [existingById] = existingByKey
    ? [null]
    : await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.accountId, accountId))
        .limit(1);

  const existing = existingByKey || existingById;
  const displayName = licenseInfo.name || `User ${accountId}`;

  if (existing) {
    await db
      .update(modAccounts)
      .set({
        hwidHash,
        accountKey,
        accountKeyEnc: encrypt(accountKey),
        accountKeyHash: keyHash,
        accountId,
        displayName: licenseInfo.name || existing.displayName,
        licenseRoles: licenseInfo.roles || existing.licenseRoles || [],
        lastSyncedAt: new Date(),
        updatedAt: new Date(),
      })
      .where(eq(modAccounts.id, existing.id));
  } else {
    await db
      .insert(modAccounts)
      .values({
        accountId,
        accountKey,
        accountKeyEnc: encrypt(accountKey),
        accountKeyHash: keyHash,
        hwidHash,
        displayName,
        licenseRoles: licenseInfo.roles || [],
        lastSyncedAt: new Date(),
      })
      .onDuplicateKeyUpdate({
        set: {
          hwidHash,
          accountKey,
          accountKeyEnc: encrypt(accountKey),
          accountKeyHash: keyHash,
          displayName,
          licenseRoles: licenseInfo.roles || [],
          lastSyncedAt: new Date(),
          updatedAt: new Date(),
        },
      });
  }
}

function shortHwid(hwidHash: string): string {
  return hwidHash.slice(0, 8).toUpperCase();
}

function normalizeLicenseRole(role?: string): string {
  if (!role) return "user";
  const r = role.toLowerCase();
  if (r === "qa") return "qa";
  if (r === "sliha" || r === "developer" || r === "dev") return "developer";
  if (r === "sponsor_plusplus" || r === "sponsor++") return "sponsor_plusplus";
  if (r === "sponsor_plus" || r === "sponsor+") return "sponsor_plus";
  if (r === "sponsor") return "sponsor";
  if (r === "vip") return "vip";
  return "user";
}

async function sendDiscordDM(discordUserId: string, content: string) {
  const token = process.env.DISCORD_BOT_TOKEN;
  if (!token || !discordUserId) return;
  try {
    const channelRes = await fetch("https://discord.com/api/v10/users/@me/channels", {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ recipient_id: discordUserId }),
    });
    if (!channelRes.ok) {
      console.error("[DiscordDM] Failed to create DM channel:", await channelRes.text());
      return;
    }
    const channel = (await channelRes.json()) as { id: string };
    const msgRes = await fetch(`https://discord.com/api/v10/channels/${channel.id}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ content }),
    });
    if (!msgRes.ok) {
      console.error("[DiscordDM] Failed to send message:", await msgRes.text());
    }
  } catch (e) {
    console.error("[DiscordDM] Error:", e);
  }
}


export const launcherApi = new Hono();

// GET /api/launcher/oauth/start?hwid=...
launcherApi.get("/oauth/start", async (c) => {
  const hwid = c.req.query("hwid");
  const parsed = hwidSchema.safeParse(hwid);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 10, 60000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const { state, nonce } = createLauncherState(parsed.data);
  setCookie(c, "launcher_oauth_nonce", nonce, {
    httpOnly: true,
    path: "/",
    sameSite: "Lax",
    secure: env.isProduction,
    maxAge: 300,
  });

  const discordUrl = new URL(`${env.discordAuthUrl}/api/oauth2/authorize`);
  discordUrl.searchParams.set("client_id", env.appId);
  discordUrl.searchParams.set("redirect_uri", LAUNCHER_REDIRECT_URI);
  discordUrl.searchParams.set("response_type", "code");
  discordUrl.searchParams.set("scope", "identify email");
  discordUrl.searchParams.set("state", state);
  return c.redirect(discordUrl.toString(), 302);
});

// GET /api/launcher/oauth/callback?code=...&state=...
launcherApi.get("/oauth/callback", async (c) => {
  const code = c.req.query("code");
  const state = c.req.query("state");
  const error = c.req.query("error");
  const errorDescription = c.req.query("error_description");

  if (error) {
    return c.html(errorHtml(errorDescription || "Доступ запрещен"), 400);
  }

  if (!code || !state) {
    return c.html(errorHtml("Отсутствуют обязательные параметры."), 400);
  }

  try {
    const stateCheck = verifyLauncherState(state);
    if (!stateCheck.valid) {
      return c.html(errorHtml(stateCheck.error || "INVALID_STATE"), 400);
    }
    const cookieNonce = getCookie(c, "launcher_oauth_nonce");
    if (!cookieNonce || cookieNonce !== stateCheck.nonce) {
      return c.html(errorHtml("CSRF_DETECTED"), 400);
    }
    setCookie(c, "launcher_oauth_nonce", "", {
      httpOnly: true,
      path: "/",
      sameSite: "Lax",
      secure: env.isProduction,
      maxAge: 0,
    });

    const hwid = stateCheck.hwid!;
    const tokenResp = await exchangeAuthCode(code, LAUNCHER_REDIRECT_URI);
    const discordUser = await fetchDiscordUser(tokenResp.access_token);

    if (!discordUser) {
      return c.html(errorHtml("Не удалось получить данные пользователя Discord."), 500);
    }

    await upsertUser({
      unionId: discordUser.id,
      discordId: discordUser.id,
      name: discordUser.username,
      email: discordUser.email ?? null,
      avatar: discordUser.avatar
        ? `https://cdn.discordapp.com/avatars/${discordUser.id}/${discordUser.avatar}.png`
        : null,
    });

    const db = getDb();
    const hwidHash = hashHwid(hwid);

    // One Discord account may only be linked to a single launcher device/HWID.
    const [existingDevice] = await db
      .select()
      .from(launcherDevices)
      .where(eq(launcherDevices.discordId, discordUser.id))
      .limit(1);

    if (existingDevice && existingDevice.hwidHash !== hwidHash) {
      await sendDiscordDM(
        discordUser.id,
        `⚠️ Попытка привязать ваш Discord-аккаунт к другому устройству Hypnosia Launcher (HWID \`${shortHwid(existingDevice.hwidHash)}\`).\n` +
          `Если это были не вы — обратитесь в поддержку.`
      );
      return c.html(
        errorHtml("Этот Discord-аккаунт уже привязан к другому устройству."),
        409
      );
    }

    await db
      .insert(launcherDevices)
      .values({ hwidHash, discordId: discordUser.id })
      .onDuplicateKeyUpdate({
        set: { discordId: discordUser.id, lastUsedAt: new Date(), loggedOutAt: null },
      });

    // Also bind the license/mod account for this HWID to the Discord user
    const linkResult = await linkLicenseAccountToDiscord(
      discordUser.id,
      discordUser.username,
      hwidHash,
    ).catch((err) => {
      console.error("[launcher/oauth/callback] license link failed:", err);
      return null;
    });

    // Sync site role from license role (owner/admin license roles are ignored).
    if (linkResult?.success && linkResult.role && linkResult.role !== "user") {
      const siteRole = normalizeLicenseRole(linkResult.role);
      if (siteRole !== "user") {
        await db
          .update(users)
          .set({ role: siteRole as any, updatedAt: new Date() })
          .where(eq(users.unionId, discordUser.id));

        const [profile] = await db
          .select()
          .from(playerProfiles)
          .where(eq(playerProfiles.discordId, discordUser.id))
          .limit(1);

        if (profile) {
          await db
            .update(playerProfiles)
            .set({ role: siteRole as any, displayName: discordUser.username || profile.displayName })
            .where(eq(playerProfiles.id, profile.id));
        }
      }
    }

    return c.html(successHtml(), 200);
  } catch (err) {
    console.error("[launcher/oauth/callback] error:", err);
    return c.html(errorHtml("Внутренняя ошибка сервера. Попробуйте ещё раз."), 500);
  }
});

// Build unified launcher /me response
async function buildMeResponse(
  device: typeof launcherDevices.$inferSelect,
  hwidHash: string
): Promise<{ authenticated: false } | { authenticated: true; user: any; minecraft: any; discordLinked: boolean; accountId?: number; createdAt?: string }> {
  const db = getDb();

  // Server-side logout: if the device explicitly logged out, do NOT auto-login
  // on /me. An explicit login (login-hwid / oauth / recover) clears this flag.
  if (device.loggedOutAt) {
    return { authenticated: false };
  }

  await db
    .update(launcherDevices)
    .set({ lastUsedAt: new Date() })
    .where(eq(launcherDevices.hwidHash, hwidHash));

  // Prefer Discord-linked profile if available
  if (device.discordId) {
    const user = await findUserByUnionId(device.discordId);
    if (!user) {
      return { authenticated: false };
    }

    let [modAccount] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, device.discordId))
      .orderBy(desc(modAccounts.lastSyncedAt))
      .limit(1);

    // Fallback: if no mod account is linked to this Discord, use the most recently synced HWID account
    if (!modAccount) {
      [modAccount] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.hwidHash, hwidHash))
        .orderBy(desc(modAccounts.lastSyncedAt))
        .limit(1);
    }

    const [profile] = await db
      .select()
      .from(playerProfiles)
      .where(eq(playerProfiles.discordId, device.discordId))
      .limit(1);

    let effectiveRole = user.role;
    if (modAccount?.licenseRoles && modAccount.licenseRoles.length > 0) {
      const r = modAccount.licenseRoles[0].toLowerCase();
      if (r === "owner" || r === "admin") effectiveRole = "admin";
      else if (r === "qa") effectiveRole = "qa";
      else if (r === "sliha" || r === "developer" || r === "dev") effectiveRole = "developer";
      else if (r === "sponsor_plusplus" || r === "sponsor++") effectiveRole = "sponsor_plusplus";
      else if (r === "sponsor_plus" || r === "sponsor+") effectiveRole = "sponsor_plus";
      else if (r === "sponsor") effectiveRole = "sponsor";
      else if (r === "vip") effectiveRole = "vip";
    }

    return {
      authenticated: true,
      user: {
        discordId: user.unionId,
        name: user.name,
        avatar: user.avatar,
        role: effectiveRole,
      },
      minecraft: {
        linked: !!modAccount,
        accountId: modAccount?.accountId ?? null,
        displayName: modAccount?.displayName || profile?.displayName || user.name,
        skinUrl: profile?.skinUrl || null,
        skinModel: profile?.skinModel || 'classic',
      },
      discordLinked: true,
    };
  }

  // HWID account: load from cached mod account (may already be linked to Discord)
  const [modAccount] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.hwidHash, hwidHash))
    .orderBy(desc(modAccounts.lastSyncedAt))
    .limit(1);

  if (!modAccount) {
    return { authenticated: false };
  }

  const discordId = device.discordId || modAccount.discordId;

  // If a Discord user is linked to this HWID/mod account, return full Discord profile
  if (discordId) {
    const user = await findUserByUnionId(discordId);
    if (user) {
      const [profile] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, discordId))
        .limit(1);

      const [discordModAccount] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .orderBy(desc(modAccounts.lastSyncedAt))
        .limit(1);

      const activeModAccount = discordModAccount || modAccount;
      let effectiveRole = user.role;
      if (activeModAccount?.licenseRoles && activeModAccount.licenseRoles.length > 0) {
        const r = activeModAccount.licenseRoles[0].toLowerCase();
        if (r === "owner" || r === "admin") effectiveRole = "admin";
        else if (r === "qa") effectiveRole = "qa";
        else if (r === "sliha" || r === "developer" || r === "dev") effectiveRole = "developer";
        else if (r === "sponsor_plusplus" || r === "sponsor++") effectiveRole = "sponsor_plusplus";
        else if (r === "sponsor_plus" || r === "sponsor+") effectiveRole = "sponsor_plus";
        else if (r === "sponsor") effectiveRole = "sponsor";
        else if (r === "vip") effectiveRole = "vip";
      }

      return {
        authenticated: true,
        accountId: activeModAccount.accountId,
        createdAt: user.createdAt.toISOString(),
        user: {
          discordId: user.unionId,
          name: user.name,
          avatar: user.avatar,
          role: effectiveRole,
        },
        minecraft: {
          linked: true,
          accountId: activeModAccount.accountId,
          displayName: activeModAccount.displayName || profile?.displayName || user.name,
          skinUrl: profile?.skinUrl || null,
          skinModel: profile?.skinModel || 'classic',
        },
        discordLinked: true,
      };
    }
  }

  const displayName = modAccount.displayName || `User ${modAccount.accountId}`;
  const role = modAccount.licenseRoles?.[0]?.toLowerCase() || "user";

  return {
    authenticated: true,
    accountId: modAccount.accountId,
    createdAt: device.createdAt.toISOString(),
    user: {
      discordId: "",
      name: displayName,
      avatar: null,
      role,
    },
    minecraft: {
      linked: true,
      accountId: modAccount.accountId,
      displayName,
      skinUrl: null,
      skinModel: 'classic',
    },
    discordLinked: false,
  };
}

// GET /api/launcher/me?hwid=...
launcherApi.get("/me", async (c) => {
  const hwid = c.req.query("hwid");
  const parsed = hwidSchema.safeParse(hwid);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const db = getDb();
  const hwidHash = hashHwid(parsed.data);

  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!device) {
    return c.json({ authenticated: false }, 200);
  }

  const response = await buildMeResponse(device, hwidHash);
  return c.json(response, 200);
});

// POST /api/launcher/login-hwid { hwid }
launcherApi.post("/login-hwid", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "INVALID_JSON" }, 400);
  }

  const inputSchema = z.object({ hwid: hwidSchema });
  const parsed = inputSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 10, 60000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const hwid = parsed.data.hwid;
  const hwidHash = await getOrCreateHwidOnlyDevice(hwid);
  await clearDeviceLogoutFlag(hwidHash);

  let licenseInfo;
  try {
    licenseInfo = await findOrCreateLicenseAccountByHwid(hwidHash);
  } catch (e: any) {
    console.error("[launcher/login-hwid] License server error:", e.message);
    return c.json({ error: "LICENSE_SERVER_UNAVAILABLE" }, 503);
  }

  if (!licenseInfo || !licenseInfo.accountKey) {
    return c.json({ error: "LICENSE_ACCOUNT_NOT_CREATED" }, 503);
  }

  try {
    await syncLicenseAccountToDb(licenseInfo, hwidHash);
  } catch (err: any) {
    console.error("[launcher/login-hwid] sync failed:", err.message);
    return c.json({ error: err.message || "LICENSE_ACCOUNT_INCOMPLETE" }, 503);
  }

  const db = getDb();
  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!device) {
    return c.json({ authenticated: false }, 200);
  }

  const response = await buildMeResponse(device, hwidHash);
  return c.json(response, 200);
});

// POST /api/launcher/recover-by-key { accountKey, hwid }
launcherApi.post("/recover-by-key", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "INVALID_JSON" }, 400);
  }

  const inputSchema = z.object({
    accountKey: accountKeySchema,
    hwid: hwidSchema,
  });
  const parsed = inputSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ error: "INVALID_INPUT" }, 400);
  }

  const { accountKey, hwid } = parsed.data;
  const ip = getClientIp(c);

  const ipLimit = await checkLauncherRateLimit(ip, 5, 60000);
  if (!ipLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: ipLimit.retryAfter }, 429);
  }

  const keyLimit = await checkLauncherRateLimit(`recover:${accountKey.toUpperCase()}`, 1, 24 * 60 * 60 * 1000);
  if (!keyLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: keyLimit.retryAfter }, 429);
  }

  const hwidHash = await getOrCreateHwidOnlyDevice(hwid);
  await clearDeviceLogoutFlag(hwidHash);

  let licenseInfo: LicenseAccountInfo;
  try {
    licenseInfo = await recoverLicenseAccountByKey(accountKey.toUpperCase(), hwidHash);
  } catch (e: any) {
    console.error("[launcher/recover-by-key] License server rejected:", e.message);
    const message = e.message || "RECOVERY_FAILED";
    if (message.toUpperCase().includes("HWID_ALREADY_BOUND")) {
      return c.json(
        {
          error: "CONTACT_ADMIN",
          message: "HWID не сброшен или аккаунт привязан к другому устройству. Обратитесь в администрацию.",
        },
        403
      );
    }
    if (message.toUpperCase().includes("ACCOUNT_NOT_FOUND")) {
      return c.json({ error: "INVALID_ACCOUNT_KEY" }, 400);
    }
    return c.json({ error: "RECOVERY_FAILED", message }, 400);
  }

  if (!licenseInfo.accountKey) {
    return c.json({ error: "LICENSE_ACCOUNT_INCOMPLETE" }, 503);
  }

  try {
    await syncLicenseAccountToDb(licenseInfo, hwidHash);
  } catch (err: any) {
    console.error("[launcher/recover-by-key] sync failed:", err.message);
    return c.json({ error: err.message || "LICENSE_ACCOUNT_INCOMPLETE" }, 503);
  }

  const db = getDb();

  // If the device already has a Discord link, bind the recovered account to it
  // and remove the Discord link from any other mod account on the same HWID.
  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (device?.discordId) {
    const [recoveredAccount] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.accountKeyHash, hashAccountKey(accountKey.toUpperCase())))
      .limit(1);

    if (recoveredAccount && recoveredAccount.discordId !== device.discordId) {
      await db
        .update(modAccounts)
        .set({ discordId: null, updatedAt: new Date() })
        .where(
          and(
            eq(modAccounts.hwidHash, hwidHash),
            eq(modAccounts.discordId, device.discordId),
            ne(modAccounts.id, recoveredAccount.id)
          )
        );

      await db
        .update(modAccounts)
        .set({ discordId: device.discordId, updatedAt: new Date() })
        .where(eq(modAccounts.id, recoveredAccount.id));
    }
  }

  const [deviceAfterDiscordSync] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!deviceAfterDiscordSync) {
    return c.json({ authenticated: false }, 200);
  }

  const response = await buildMeResponse(deviceAfterDiscordSync, hwidHash);
  return c.json(response, 200);
});

// POST /api/launcher/logout { hwid }
// Server-side source of truth for an explicit logout. Sets logged_out_at on
// the device so subsequent /me calls return authenticated:false until the
// user logs in again via login-hwid / oauth / recover-by-key.
launcherApi.post("/logout", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "INVALID_JSON" }, 400);
  }

  const inputSchema = z.object({ hwid: hwidSchema });
  const parsed = inputSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 30, 60000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const db = getDb();
  const hwidHash = hashHwid(parsed.data.hwid);
  await db
    .update(launcherDevices)
    .set({ loggedOutAt: new Date() })
    .where(eq(launcherDevices.hwidHash, hwidHash));
  return c.json({ ok: true }, 200);
});

// GET /api/launcher/account-key?hwid=...
// Returns the active account key + id as JSON for the launcher to inject into
// the active profile's account.properties before launching Minecraft. Unlike
// /account-key-file, this does NOT require the account_key inventory item —
// any authenticated device with a bound mod account gets its current key.
launcherApi.get("/account-key", async (c) => {
  const hwid = c.req.query("hwid");
  const parsed = hwidSchema.safeParse(hwid);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 10, 60000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const db = getDb();
  const hwidHash = hashHwid(parsed.data);

  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!device) {
    return c.json({ error: "DEVICE_NOT_AUTHORIZED" }, 401);
  }
  if (device.loggedOutAt) {
    return c.json({ error: "DEVICE_NOT_AUTHORIZED" }, 401);
  }

  // Prefer a Discord-linked mod account, fall back to the most recently
  // synced HWID-bound one.
  let [modAccount] = device.discordId
    ? await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, device.discordId))
        .orderBy(desc(modAccounts.lastSyncedAt))
        .limit(1)
    : [null];

  if (!modAccount) {
    [modAccount] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.hwidHash, hwidHash))
      .orderBy(desc(modAccounts.lastSyncedAt))
      .limit(1);
  }

  if (!modAccount || !modAccount.accountKey) {
    return c.json({ error: "ACCOUNT_KEY_NOT_FOUND" }, 404);
  }

  return c.json({
    accountKey: modAccount.accountKey,
    accountId: modAccount.accountId,
    filename: `account-${modAccount.accountId}.properties`,
  });
});

// GET /api/launcher/account-key-file?hwid=...
launcherApi.get("/account-key-file", async (c) => {
  const hwid = c.req.query("hwid");
  const parsed = hwidSchema.safeParse(hwid);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 10, 60000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const db = getDb();
  const hwidHash = hashHwid(parsed.data);

  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!device) {
    return c.json({ error: "DEVICE_NOT_AUTHORIZED" }, 401);
  }

  const [modAccount] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.hwidHash, hwidHash))
    .orderBy(desc(modAccounts.lastSyncedAt))
    .limit(1);

  if (!modAccount || !modAccount.accountKey) {
    return c.json({ error: "ACCOUNT_KEY_NOT_FOUND" }, 404);
  }

  const [accountKeyItem] = await db
    .select()
    .from(storeItems)
    .where(eq(storeItems.sku, "account_key"))
    .limit(1);

  if (!accountKeyItem) {
    return c.json({ error: "ACCOUNT_KEY_ITEM_NOT_CONFIGURED" }, 503);
  }

  const [owned] = await db
    .select()
    .from(userInventory)
    .where(
      and(
        eq(userInventory.accountId, modAccount.id),
        eq(userInventory.storeItemId, accountKeyItem.id),
        eq(userInventory.isActive, "true")
      )
    )
    .limit(1);

  if (!owned) {
    return c.json({ error: "ACCOUNT_KEY_NOT_AVAILABLE" }, 403);
  }

  const content = `# Hypnosia account config.\naccount.key=${modAccount.accountKey}\naccount.id=${modAccount.accountId}\n`;
  const filename = `account-${modAccount.accountId}.properties`;

  c.header("Content-Type", "text/plain; charset=utf-8");
  c.header("Content-Disposition", `attachment; filename="${filename}"`);
  return c.body(content, 200);
});

// POST /api/launcher/link-minecraft { hwid, code }
launcherApi.post("/link-minecraft", async (c) => {
  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    return c.json({ error: "INVALID_JSON" }, 400);
  }

  const inputSchema = z.object({
    hwid: z.string().min(8).max(128),
    code: codeSchema,
  });

  const parsed = inputSchema.safeParse(body);
  if (!parsed.success) {
    return c.json({ error: "INVALID_INPUT" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 5, 300000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const db = getDb();
  const hwidHash = hashHwid(parsed.data.hwid);

  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!device) {
    return c.json({ error: "DEVICE_NOT_AUTHORIZED" }, 401);
  }

  if (!device.discordId) {
    return c.json({ error: "DISCORD_REQUIRED_FOR_LINKING" }, 403);
  }

  const user = await findUserByUnionId(device.discordId);
  if (!user) {
    return c.json({ error: "USER_NOT_FOUND" }, 404);
  }

  try {
    const result = await linkMinecraftByDiscordId(
      parsed.data.code,
      device.discordId,
      user.name || "",
    );
    return c.json(result, 200);
  } catch (err) {
    if (err instanceof LinkMinecraftException) {
      return c.json({ error: err.code, message: err.message }, 400);
    }
    console.error("[launcher/link-minecraft] error:", err);
    return c.json({ error: "INTERNAL_ERROR" }, 500);
  }
});

// GET /api/launcher/launch-info?hwid=...
launcherApi.get("/launch-info", async (c) => {
  const hwid = c.req.query("hwid");
  const parsed = hwidSchema.safeParse(hwid);
  if (!parsed.success) {
    return c.json({ error: "INVALID_HWID" }, 400);
  }

  const ip = getClientIp(c);
  const rateLimit = await checkLauncherRateLimit(ip, 30, 60000);
  if (!rateLimit.allowed) {
    return c.json({ error: "RATE_LIMITED", retryAfter: rateLimit.retryAfter }, 429);
  }

  const db = getDb();
  const hwidHash = hashHwid(parsed.data);

  const [device] = await db
    .select()
    .from(launcherDevices)
    .where(eq(launcherDevices.hwidHash, hwidHash))
    .limit(1);

  if (!device) {
    return c.json({ error: "DEVICE_NOT_AUTHORIZED" }, 401);
  }

  const [modAccount] = device.discordId
    ? await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, device.discordId))
        .orderBy(desc(modAccounts.lastSyncedAt))
        .limit(1)
    : await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.hwidHash, hwidHash))
        .orderBy(desc(modAccounts.lastSyncedAt))
        .limit(1);

  if (!modAccount || !modAccount.accountKey) {
    return c.json({ error: "MINECRAFT_ACCOUNT_NOT_LINKED" }, 403);
  }

  let licenseInfo;
  try {
    licenseInfo = await getLicenseAccountInfo(modAccount.accountKey, hwidHash);
  } catch (e: any) {
    console.error("[launcher/launch-info] License server unreachable:", e.message);
    return c.json({ error: "LICENSE_SERVER_UNAVAILABLE" }, 503);
  }

  if (!licenseInfo) {
    return c.json({ error: "LICENSE_NOT_FOUND" }, 403);
  }

  const roles = licenseInfo.roles || [];
  const primaryRole = roles[0] || "USER";
  const allowed = primaryRole !== "USER" || roles.length > 1;

  return c.json({
    allowed,
    accountId: modAccount.accountId,
    accountKey: modAccount.accountKey,
    hwidHash,
    displayName: licenseInfo.name || modAccount.displayName,
    roles,
    primaryRole,
  }, 200);
});
