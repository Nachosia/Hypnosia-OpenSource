import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, authedQuery } from "./middleware";
import { getDb } from "./queries/connection";
import {
  modAccounts,
  modLinkCodes,
  playerProfiles,
  users,
} from "@db/schema";
import { eq, and, gt, isNull } from "drizzle-orm";
import { getLicenseAccountInfo, getRoleGradient } from "./lib/license-client";
import { hashLinkCode } from "./lib/mod-auth";

// Rate limiting store (in-memory, per-IP)
const rateLimitStore = new Map<string, { attempts: number; resetAt: number }>();

function checkRateLimit(ip: string, maxAttempts = 5, windowMs = 300000): boolean {
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

function mapLicenseRoleToSiteRole(licenseRole?: string): string {
  if (!licenseRole) return "user";
  const r = licenseRole.toUpperCase();
  if (r === "OWNER" || r === "ADMIN") return "admin";
  if (r === "MODERATOR") return "moderator";
  if (r === "HELPER") return "helper";
  if (r === "QA") return "qa";
  if (r === "SLIHA") return "developer";
  if (r === "SPONSOR_PLUSPLUS" || r === "SPONSOR++") return "sponsor_plusplus";
  if (r === "SPONSOR_PLUS" || r === "SPONSOR+") return "sponsor_plus";
  if (r === "SPONSOR") return "sponsor";
  return "user";
}

export const minecraftRouter = createRouter({
  // ═════════════════════════════════════════════════════════════════
  // License Server account linking via /hypnosia link code
  // ═════════════════════════════════════════════════════════════════
  verifyLicenseCode: authedQuery
    .input(z.object({
      code: z.string().regex(/^[A-Z0-9]{6}$/),
    }))
    .mutation(async ({ ctx, input }) => {
      const ip = ctx.req.headers.get("x-forwarded-for") || "unknown";
      if (!checkRateLimit(ip)) {
        throw new TRPCError({ code: "TOO_MANY_REQUESTS", message: "Слишком много попыток. Попробуйте позже." });
      }

      const db = getDb();
      const discordId = String(ctx.user.unionId);
      const discordUsername = ctx.user.name || "";
      const now = new Date();
      const codeHash = hashLinkCode(input.code);

      const [linkCode] = await db.select()
        .from(modLinkCodes)
        .where(and(
          eq(modLinkCodes.codeHash, codeHash),
          gt(modLinkCodes.expiresAt, now),
          isNull(modLinkCodes.usedAt),
        ))
        .limit(1);

      if (!linkCode) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_CODE|Код не найден или просрочен" });
      }

      const [modAccount] = await db.select()
        .from(modAccounts)
        .where(eq(modAccounts.id, linkCode.accountId))
        .limit(1);

      if (!modAccount) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "ACCOUNT_NOT_FOUND|Аккаунт не найден" });
      }

      const accountKey = linkCode.accountKey || modAccount.accountKeyEnc;
      if (!accountKey) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "ACCOUNT_KEY_MISSING|Невозможно синхронизировать аккаунт" });
      }

      let licenseInfo: Awaited<ReturnType<typeof getLicenseAccountInfo>> = null;
      try {
        licenseInfo = await getLicenseAccountInfo(accountKey);
      } catch (e: any) {
        console.error("[verifyLicenseCode] License server unreachable:", e.message);
        throw new TRPCError({ code: "SERVICE_UNAVAILABLE", message: "LICENSE_SERVER_UNAVAILABLE" });
      }
      if (!licenseInfo) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "UNKNOWN_ACCOUNT_KEY|Аккаунт не найден на License Server" });
      }

      const licenseRoles = licenseInfo.roles || [];
      const primaryRole = licenseRoles[0] || "USER";
      const siteRole = mapLicenseRoleToSiteRole(primaryRole);
      const nickG = getRoleGradient(licenseInfo, "nick");
      const roleG = getRoleGradient(licenseInfo, "role");

      await db.update(modAccounts).set({
        discordId,
        accountKey: linkCode.accountKey || modAccount.accountKey || accountKey,
        displayName: licenseInfo.name || modAccount.displayName,
        contact: discordUsername,
        licenseRoles,
        lastSyncedAt: now,
        updatedAt: now,
      }).where(eq(modAccounts.id, modAccount.id));

      await db.update(users).set({ role: siteRole as any, updatedAt: now }).where(eq(users.unionId, discordId));

      const existingProfile = await db.select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, discordId))
        .limit(1);

      const profileDefaults = {
        discordId,
        displayName: licenseInfo.name || modAccount.displayName || discordUsername || "Player",
        role: siteRole,
        contact: discordUsername,
        hoursPlayed: 0,
        isOnline: "false",
        showHours: "true",
        showMcJoined: "true",
        showOnline: "true",
        showRank: "true",
        nickGradientFrom: nickG?.from ?? "#80FF97",
        nickGradientTo: nickG?.to ?? "#6BB7FF",
        roleGradientFrom: roleG?.from ?? "#6BB7FF",
        roleGradientTo: roleG?.to ?? "#FFD700",
        configsUploaded: 0,
        skinUrl: null,
        skinModel: "classic",
      };

      if (existingProfile.length > 0) {
        await db.update(playerProfiles).set({
          displayName: profileDefaults.displayName,
          role: profileDefaults.role as any,
          contact: profileDefaults.contact,
          nickGradientFrom: profileDefaults.nickGradientFrom,
          nickGradientTo: profileDefaults.nickGradientTo,
          roleGradientFrom: profileDefaults.roleGradientFrom,
          roleGradientTo: profileDefaults.roleGradientTo,
          updatedAt: now,
        }).where(eq(playerProfiles.id, existingProfile[0].id));
      } else {
        await db.insert(playerProfiles).values(profileDefaults as any);
      }

      await db.update(modLinkCodes).set({ usedAt: now }).where(eq(modLinkCodes.id, linkCode.id));

      return {
        success: true,
        accountId: modAccount.accountId,
        displayName: profileDefaults.displayName,
        role: siteRole,
      };
    }),

  licenseLinkStatus: authedQuery
    .query(async ({ ctx }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      const [account] = await db.select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .limit(1);

      if (!account) {
        return { linked: false as const };
      }

      return {
        linked: true as const,
        accountId: account.accountId,
        displayName: account.displayName,
        role: mapLicenseRoleToSiteRole(account.licenseRoles?.[0]),
      };
    }),
});

