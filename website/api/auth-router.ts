import * as cookie from "cookie";
import { Session } from "@contracts/constants";
import { getSessionCookieOptions } from "./lib/cookies";
import { createRouter, publicQuery } from "./middleware";
import { getDb } from "./queries/connection";
import { modAccounts, playerProfiles } from "@db/schema";
import { eq } from "drizzle-orm";
import { computeEffectiveRole } from "./lib/roles";
import { revokeSessionToken } from "./kimi/session";

export const authRouter = createRouter({
  me: publicQuery.query(async ({ ctx }) => {
    const cookies = cookie.parse(ctx.req.headers.get("cookie") || "");
    const token = cookies[Session.cookieName];
    if (!token) {
      return null;
    }

    // Lazy import to avoid circular deps
    const { verifySessionToken } = await import("./kimi/session");
    const { authenticateRequest } = await import("./kimi/auth");

    const claim = await verifySessionToken(token);
    if (!claim) {
      return null;
    }

    try {
      const user = await authenticateRequest(ctx.req.headers);
      const db = getDb();

      const [modAccount] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, user.unionId))
        .limit(1);

      const [profile] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, user.unionId))
        .limit(1);

      const effectiveRole = computeEffectiveRole(
        modAccount?.licenseRoles ?? undefined,
        profile?.role ?? undefined,
        user.role ?? undefined,
      );

      return {
        ...user,
        effectiveRole,
      };
    } catch {
      return null;
    }
  }),
  logout: publicQuery.mutation(async ({ ctx }) => {
    const cookies = cookie.parse(ctx.req.headers.get("cookie") || "");
    const token = cookies[Session.cookieName];

    if (token) {
      await revokeSessionToken(token);
    }

    const opts = getSessionCookieOptions(ctx.req.headers);
    ctx.resHeaders.append(
      "set-cookie",
      cookie.serialize(Session.cookieName, "", {
        httpOnly: opts.httpOnly,
        path: opts.path,
        sameSite: opts.sameSite?.toLowerCase() as "lax" | "none",
        secure: opts.secure,
        maxAge: 0,
      }),
    );
    return { success: true };
  }),
});
