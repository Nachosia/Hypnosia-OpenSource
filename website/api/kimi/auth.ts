import type { Context } from "hono";
import { setCookie, getCookie } from "hono/cookie";
import * as jose from "jose";
import * as cookie from "cookie";
import { createHmac, randomBytes } from "crypto";
import { env } from "../lib/env";
import { getSessionCookieOptions } from "../lib/cookies";
import { Session } from "@contracts/constants";
import { Errors } from "@contracts/errors";
import { signSessionToken, verifySessionToken } from "./session";
import { findUserByUnionId, upsertUser } from "../queries/users";
import { getDb } from "../queries/connection";
import { modAccounts } from "@db/schema";
import { eq } from "drizzle-orm";
import type { TokenResponse } from "./types";

// ─── OAuth CSRF Protection ───
const ALLOWED_REDIRECT_ORIGINS = [
  "http://127.0.0.1:3000",
  "http://localhost:3000",
  "http://localhost:5173",
];

function isAllowedRedirect(uri: string): boolean {
  try {
    const url = new URL(uri);
    return ALLOWED_REDIRECT_ORIGINS.some((origin) => {
      const o = new URL(origin);
      return url.hostname === o.hostname && url.port === o.port && url.protocol === o.protocol;
    });
  } catch {
    return false;
  }
}

function createOAuthState(redirectUri: string): { state: string; nonce: string } {
  const nonce = randomBytes(16).toString("hex");
  const ts = Date.now();
  const payload = `${nonce}:${redirectUri}:${ts}`;
  const sig = createHmac("sha256", env.appSecret).update(payload).digest("hex");
  const stateObj = { nonce, redirectUri, ts, sig };
  const state = Buffer.from(JSON.stringify(stateObj)).toString("base64url");
  return { state, nonce };
}

function verifyOAuthState(state: string): { valid: boolean; nonce?: string; redirectUri?: string; error?: string } {
  try {
    const obj = JSON.parse(Buffer.from(state, "base64url").toString());
    const { nonce, redirectUri, ts, sig } = obj;
    if (!nonce || !redirectUri || !ts || !sig) {
      return { valid: false, error: "MALFORMED_STATE" };
    }
    const payload = `${nonce}:${redirectUri}:${ts}`;
    const expectedSig = createHmac("sha256", env.appSecret).update(payload).digest("hex");
    if (sig !== expectedSig) {
      return { valid: false, error: "INVALID_STATE_SIGNATURE" };
    }
    if (Date.now() - ts > 5 * 60 * 1000) {
      return { valid: false, error: "STATE_EXPIRED" };
    }
    if (!isAllowedRedirect(redirectUri)) {
      return { valid: false, error: "INVALID_REDIRECT_URI" };
    }
    return { valid: true, nonce, redirectUri };
  } catch {
    return { valid: false, error: "MALFORMED_STATE" };
  }
}

function normalizeRole(licenseRole?: string): string {
  if (!licenseRole) return "user";
  const r = licenseRole.toLowerCase();
  if (r === "owner" || r === "admin") return "admin";
  if (r === "qa") return "qa";
  if (r === "sliha" || r === "developer" || r === "dev") return "developer";
  if (r === "sponsor_plusplus" || r === "sponsor++") return "sponsor_plusplus";
  if (r === "sponsor_plus" || r === "sponsor+") return "sponsor_plus";
  if (r === "sponsor") return "sponsor";
  if (r === "vip") return "vip";
  return "user";
}

async function exchangeAuthCode(
  code: string,
  redirectUri: string,
): Promise<TokenResponse> {
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

  return resp.json() as Promise<TokenResponse>;
}

async function fetchDiscordUser(accessToken: string) {
  const resp = await fetch(`${env.discordApiUrl}/users/@me`, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
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

export async function authenticateRequest(headers: Headers) {
  const cookies = cookie.parse(headers.get("cookie") || "");
  const token = cookies[Session.cookieName];
  if (!token) {
    console.warn("[auth] No session cookie found in request.");
    throw Errors.forbidden("Invalid authentication token.");
  }
  const claim = await verifySessionToken(token);
  if (!claim) {
    throw Errors.forbidden("Invalid authentication token.");
  }
  const user = await findUserByUnionId(claim.unionId);
  if (!user) {
    throw Errors.forbidden("User not found. Please re-login.");
  }

  // Compute effectiveRole: mod_accounts.license_roles -> site role fallback
  let effectiveRole = user.role;
  try {
    const db = getDb();
    const [modAccount] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, user.unionId))
      .limit(1);
    if (modAccount?.licenseRoles && modAccount.licenseRoles.length > 0) {
      const licenseRole = modAccount.licenseRoles[0];
      effectiveRole = normalizeRole(licenseRole);
    }
  } catch {
    // ignore db errors, fallback to user.role
  }

  return { ...user, effectiveRole } as typeof user & { effectiveRole: string };
}

export function createOAuthStartHandler() {
  return async (c: Context) => {
    const redirectUri = c.req.query("redirectUri");
    if (!redirectUri || !isAllowedRedirect(redirectUri)) {
      return c.json({ error: "INVALID_REDIRECT_URI" }, 400);
    }
    const { state, nonce } = createOAuthState(redirectUri);
    setCookie(c, "oauth_nonce", nonce, {
      httpOnly: true,
      path: "/",
      sameSite: "Lax",
      secure: env.isProduction,
      maxAge: 300, // 5 minutes
    });
    const discordUrl = new URL(`${env.discordAuthUrl}/api/oauth2/authorize`);
    discordUrl.searchParams.set("client_id", env.appId);
    discordUrl.searchParams.set("redirect_uri", redirectUri);
    discordUrl.searchParams.set("response_type", "code");
    discordUrl.searchParams.set("scope", "identify email");
    discordUrl.searchParams.set("state", state);
    return c.redirect(discordUrl.toString(), 302);
  };
}

export function createOAuthCallbackHandler() {
  return async (c: Context) => {
    const code = c.req.query("code");
    const state = c.req.query("state");
    const error = c.req.query("error");
    const errorDescription = c.req.query("error_description");

    if (error) {
      if (error === "access_denied") {
        return c.redirect("/", 302);
      }
      return c.json(
        { error, error_description: errorDescription },
        400,
      );
    }

    if (!code || !state) {
      return c.json({ error: "code and state are required" }, 400);
    }

    try {
      const stateCheck = verifyOAuthState(state);
      if (!stateCheck.valid) {
        return c.json({ error: stateCheck.error || "INVALID_STATE" }, 400);
      }
      const cookieNonce = getCookie(c, "oauth_nonce");
      if (!cookieNonce || cookieNonce !== stateCheck.nonce) {
        return c.json({ error: "CSRF_DETECTED" }, 400);
      }
      // Clear the nonce cookie immediately after validation
      setCookie(c, "oauth_nonce", "", {
        httpOnly: true,
        path: "/",
        sameSite: "Lax",
        secure: env.isProduction,
        maxAge: 0,
      });
      const redirectUri = stateCheck.redirectUri!;
      const tokenResp = await exchangeAuthCode(code, redirectUri);
      const discordUser = await fetchDiscordUser(tokenResp.access_token);

      if (!discordUser) {
        return c.json({ error: "Failed to fetch Discord user" }, 500);
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

      const sessionToken = await signSessionToken({
        unionId: discordUser.id,
        clientId: env.appId,
      });

      const opts = getSessionCookieOptions(c.req.raw.headers);
      setCookie(c, Session.cookieName, sessionToken, {
        httpOnly: opts.httpOnly,
        path: opts.path,
        sameSite: opts.sameSite,
        secure: opts.secure,
        maxAge: opts.maxAge,
      });

      return c.redirect(redirectUri.replace("/api/oauth/callback", ""), 302);
    } catch (err) {
      console.error("[oauth] callback error:", err);
      return c.json({ error: "Internal server error" }, 500);
    }
  };
}
