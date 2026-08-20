import * as jose from "jose";
import { env } from "../lib/env";
import { redis } from "../lib/redis";
import { randomUUID } from "crypto";
import type { SessionPayload } from "./types";

const JWT_ALG = "HS256";
const JWT_TTL_SECONDS = 24 * 60 * 60; // 24 hours

export async function signSessionToken(
  payload: SessionPayload,
): Promise<string> {
  const secret = new TextEncoder().encode(env.appSecret);
  const jti = randomUUID();
  return new jose.SignJWT({ ...payload, jti })
    .setProtectedHeader({ alg: JWT_ALG })
    .setIssuedAt()
    .setExpirationTime(`${JWT_TTL_SECONDS}s`)
    .sign(secret);
}

export async function verifySessionToken(
  token: string,
): Promise<SessionPayload | null> {
  if (!token) {
    console.warn("[session] No token provided for verification.");
    return null;
  }
  try {
    const secret = new TextEncoder().encode(env.appSecret);
    const { payload } = await jose.jwtVerify(token, secret, {
      algorithms: [JWT_ALG],
      clockTolerance: 60,
    });
    const { unionId, clientId, jti } = payload;
    if (!unionId || !clientId) {
      console.warn("[session] JWT payload missing required fields.");
      return null;
    }
    // Check Redis revocation list
    if (jti) {
      try {
        const revoked = await redis.exists(`jwt:revoke:${jti}`);
        if (revoked) {
          console.warn("[session] JWT has been revoked:", jti);
          return null;
        }
      } catch {
        // If Redis is down, still allow the token (fail-open for availability)
      }
    }
    return { unionId, clientId } as SessionPayload;
  } catch (error) {
    console.warn("[session] JWT verification failed:", error);
    return null;
  }
}

export async function revokeSessionToken(token: string): Promise<void> {
  try {
    const secret = new TextEncoder().encode(env.appSecret);
    const { payload } = await jose.jwtVerify(token, secret, {
      algorithms: [JWT_ALG],
      clockTolerance: 60,
    });
    const jti = payload.jti as string | undefined;
    if (jti) {
      await redis.set(`jwt:revoke:${jti}`, "1", "EX", JWT_TTL_SECONDS);
    }
  } catch {
    // Ignore invalid tokens
  }
}
