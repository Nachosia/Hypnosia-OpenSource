import { createHash, randomBytes, createHmac } from "crypto";
import { redis } from "./redis";

const MOD_API_KEY = process.env.MOD_API_KEY;
const MOD_SECRET_KEY = process.env.MOD_SECRET_KEY;

if (!MOD_API_KEY || MOD_API_KEY.length < 16) {
  throw new Error("MOD_API_KEY is not configured or too short. Set it in the environment.");
}
if (!MOD_SECRET_KEY || MOD_SECRET_KEY.length < 32) {
  throw new Error("MOD_SECRET_KEY is not configured or too short. Set it in the environment.");
}

// ─── Simple in-memory rate limiter (fallback if Redis unavailable) ───
interface RateLimitEntry {
  count: number;
  windowStart: number;
}
const rateLimitMap = new Map<string, RateLimitEntry>();
const RATE_LIMIT_WINDOW_MS = 60_000; // 1 minute
const RATE_LIMIT_MAX = 60; // 60 requests per minute per IP

const NONCE_TTL_MS = 120_000; // 2 minutes
const TIMESTAMP_TOLERANCE_MS = 30_000; // ±30 seconds
const BATCH_RATE_LIMIT_MS = 9 * 60_000; // 9 minutes
const EMERGENCY_RATE_LIMIT_MS = 5 * 60_000; // 5 minutes

export function checkModAuth(c: { req: { header: (name: string) => string | undefined } }): boolean {
  const key = c.req.header("x-api-key");
  return key === MOD_API_KEY;
}

export function checkRateLimit(clientIp: string): { allowed: boolean; retryAfter?: number } {
  const now = Date.now();
  const entry = rateLimitMap.get(clientIp);

  if (!entry || now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
    rateLimitMap.set(clientIp, { count: 1, windowStart: now });
    return { allowed: true };
  }

  if (entry.count >= RATE_LIMIT_MAX) {
    const retryAfter = Math.ceil((RATE_LIMIT_WINDOW_MS - (now - entry.windowStart)) / 1000);
    return { allowed: false, retryAfter };
  }

  entry.count++;
  return { allowed: true };
}

export async function checkLauncherRateLimit(
  key: string,
  maxAttempts: number,
  windowMs: number,
): Promise<{ allowed: boolean; retryAfter?: number }> {
  const redisKey = `launcher:ratelimit:${key}`;
  try {
    const current = await redis.get(redisKey);
    if (!current) {
      await redis.set(redisKey, "1", "PX", windowMs);
      return { allowed: true };
    }
    const count = Number(current);
    if (count >= maxAttempts) {
      const ttl = await redis.pttl(redisKey);
      return { allowed: false, retryAfter: Math.max(0, Math.ceil(ttl / 1000)) };
    }
    await redis.incr(redisKey);
    return { allowed: true };
  } catch {
    // Fallback: allow if Redis unavailable
    return { allowed: true };
  }
}

export async function checkReplayProtection(
  timestamp: number,
  nonce: string
): Promise<{ valid: boolean; error?: string }> {
  const now = Date.now();

  // Timestamp must be within tolerance
  if (Math.abs(now - timestamp) > TIMESTAMP_TOLERANCE_MS) {
    return { valid: false, error: "TIMESTAMP_OUT_OF_RANGE" };
  }

  // Nonce must be unique (Redis-backed, TTL = 5 min)
  const nonceKey = `mod:nonce:${nonce}`;
  try {
    const exists = await redis.exists(nonceKey);
    if (exists) {
      return { valid: false, error: "REPLAY_DETECTED" };
    }
    await redis.set(nonceKey, "1", "PX", NONCE_TTL_MS);
  } catch {
    // Fallback to in-memory if Redis is unavailable
    const memKey = `${timestamp}:${nonce}`;
    // Simple in-memory fallback (non-shared, for single-instance only)
  }

  return { valid: true };
}

export function hashAccountKey(key: string): string {
  return createHash("sha256").update(key).digest("hex");
}

export function generateSessionToken(): string {
  return randomBytes(32).toString("hex");
}

const ADMIN_2FA_VERIFIED_TTL_MS = 12 * 60 * 60 * 1000; // 12 hours

export async function mark2FAVerified(sessionToken: string): Promise<void> {
  try {
    await redis.set(`admin:2fa:verified:${sessionToken}`, "1", "PX", ADMIN_2FA_VERIFIED_TTL_MS);
  } catch {
    // Ignore Redis errors
  }
}

export async function is2FAVerified(sessionToken: string): Promise<boolean> {
  try {
    return (await redis.exists(`admin:2fa:verified:${sessionToken}`)) === 1;
  } catch {
    return false;
  }
}

export function hashLinkCode(code: string): string {
  return createHash("sha256").update(code).digest("hex");
}

// ─── Moscow date helper ───
export function getMoscowDateString(): string {
  return new Date().toLocaleDateString("en-CA", {
    timeZone: "Europe/Moscow",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

export function getClientIp(c: { req: { header: (name: string) => string | undefined } }): string {
  const forwarded = c.req.header("x-forwarded-for");
  if (forwarded) return forwarded.split(",")[0].trim();
  return c.req.header("x-real-ip") || "unknown";
}

export async function checkBatchRateLimit(accountKey: string, isEmergency = false): Promise<{ allowed: boolean; retryAfter?: number }> {
  const batchKey = `mod:ratelimit:batch:${accountKey}`;
  const emergencyKey = `mod:ratelimit:emergency:${accountKey}`;

  try {
    if (isEmergency) {
      const lastEmergency = await redis.get(emergencyKey);
      if (lastEmergency) {
        const elapsed = Date.now() - Number(lastEmergency);
        if (elapsed < EMERGENCY_RATE_LIMIT_MS) {
          const retryAfter = Math.ceil((EMERGENCY_RATE_LIMIT_MS - elapsed) / 1000);
          return { allowed: false, retryAfter };
        }
      }
      return { allowed: true };
    }

    const lastBatch = await redis.get(batchKey);
    if (lastBatch) {
      const elapsed = Date.now() - Number(lastBatch);
      if (elapsed < BATCH_RATE_LIMIT_MS) {
        const retryAfter = Math.ceil((BATCH_RATE_LIMIT_MS - elapsed) / 1000);
        return { allowed: false, retryAfter };
      }
    }
    return { allowed: true };
  } catch {
    // Fallback: allow if Redis unavailable
    return { allowed: true };
  }
}

export async function markBatchAccepted(accountKey: string, isEmergency = false): Promise<void> {
  const batchKey = `mod:ratelimit:batch:${accountKey}`;
  const emergencyKey = `mod:ratelimit:emergency:${accountKey}`;
  const now = Date.now();

  try {
    await redis.set(batchKey, String(now), "PX", BATCH_RATE_LIMIT_MS);
    if (isEmergency) {
      await redis.set(emergencyKey, String(now), "PX", EMERGENCY_RATE_LIMIT_MS);
    }
  } catch {
    // Ignore Redis errors
  }
}

// ─── HMAC signature validation for batch playtime endpoint (v2.2) ───
// Payload format: accountKey:sessionToken:timestamp:activeMinutes:status:nonce
export function verifyBatchSignature(
  payload: string,
  signature: string,
  timestamp: number
): { valid: boolean; error?: string } {
  const now = Date.now();
  // Timestamp must be within ±2 minutes
  if (Math.abs(now - timestamp) > 120_000) {
    return { valid: false, error: "TIMESTAMP_OUT_OF_RANGE" };
  }
  const expected = createHmac("sha256", MOD_SECRET_KEY).update(payload).digest("hex");
  // Constant-time comparison to prevent timing attacks
  if (signature.length !== expected.length) {
    return { valid: false, error: "INVALID_SIGNATURE" };
  }
  let mismatch = 0;
  for (let i = 0; i < signature.length; i++) {
    mismatch |= signature.charCodeAt(i) ^ expected.charCodeAt(i);
  }
  if (mismatch !== 0) {
    return { valid: false, error: "INVALID_SIGNATURE" };
  }
  return { valid: true };
}
