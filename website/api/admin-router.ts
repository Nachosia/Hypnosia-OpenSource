import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, publicQuery, adminQuery } from "./middleware";
import { getDb } from "./queries/connection";
import { redemptionCodes, admin2FA, subscriptionPurchases, syncQueue, modNotifications, modAccounts, playerProfiles, users, siteRoleSettings, modFreezeLogs } from "@db/schema";
import { eq, and, count, desc, isNull, sql } from "drizzle-orm";
import { randomInt } from "crypto";
import speakeasy from "speakeasy";
import QRCode from "qrcode";
import { getGifConfigs, approveGifConfig, denyGifConfig } from "./lib/license-client";
import { generateSessionToken } from "./lib/mod-auth";
import { encrypt, decrypt } from "./lib/encryption";

function decryptOrPlaintext(value: string | null | undefined): string | null {
  if (!value) return null;
  const decrypted = decrypt(value);
  return decrypted !== null ? decrypted : value;
}

// Admin discord ID (Nachosia)
const ADMIN_DISCORD_ID = "743050850154315818";

// Code generator
function generateCode(length = 8): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No confusing chars
  let code = "";
  for (let i = 0; i < length; i++) {
    code += chars.charAt(randomInt(chars.length));
  }
  return code;
}

const DISCORD_BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const DISCORD_MODERATION_CHANNEL_ID = process.env.DISCORD_MODERATION_CHANNEL_ID;

// ─── Simple in-memory rate limiter for TOTP verification ───
const totpRateLimitMap = new Map<string, { count: number; windowStart: number }>();
const TOTP_RATE_LIMIT_WINDOW_MS = 60_000;
const TOTP_RATE_LIMIT_MAX = 5;

function checkTotpRateLimit(clientIp: string): { allowed: boolean; retryAfter?: number } {
  const now = Date.now();
  const entry = totpRateLimitMap.get(clientIp);
  if (!entry || now - entry.windowStart > TOTP_RATE_LIMIT_WINDOW_MS) {
    totpRateLimitMap.set(clientIp, { count: 1, windowStart: now });
    return { allowed: true };
  }
  if (entry.count >= TOTP_RATE_LIMIT_MAX) {
    const retryAfter = Math.ceil((TOTP_RATE_LIMIT_WINDOW_MS - (now - entry.windowStart)) / 1000);
    return { allowed: false, retryAfter };
  }
  entry.count++;
  return { allowed: true };
}

async function sendDiscordDM(discordId: string, content: string) {
  if (!DISCORD_BOT_TOKEN || !discordId) return;
  try {
    // 1. Create DM channel
    const channelRes = await fetch("https://discord.com/api/v10/users/@me/channels", {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ recipient_id: discordId }),
    });
    if (!channelRes.ok) {
      console.error("[DiscordDM] Failed to create DM channel:", await channelRes.text());
      return;
    }
    const channel = await channelRes.json();
    // 2. Send message
    const msgRes = await fetch(`https://discord.com/api/v10/channels/${channel.id}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
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

async function sendDiscordChannelMessage(channelId: string, content: string) {
  if (!DISCORD_BOT_TOKEN || !channelId) return;
  try {
    const res = await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ content }),
    });
    if (!res.ok) {
      console.error("[DiscordChannel] Failed to send message:", await res.text());
    }
  } catch (e) {
    console.error("[DiscordChannel] Error:", e);
  }
}

function verifyTOTP(token: string, secret: string): { valid: boolean; window?: number } {
  const valid = speakeasy.totp.verify({
    secret,
    encoding: "base32",
    token,
    window: 2,
  });
  return { valid: valid === true, window: 2 };
}

export const adminRouter = createRouter({
  // ─── Generate 2FA secret ───
  generate2FASecret: adminQuery.query(async () => {
    const secret = speakeasy.generateSecret({
      length: 32,
      name: "Nachosia Admin",
    });
    const otpauthUrl = secret.otpauth_url ?? "";
    let qrDataUrl = "";
    if (otpauthUrl) {
      try {
        qrDataUrl = await QRCode.toDataURL(otpauthUrl, { width: 256, margin: 2 });
      } catch {
        qrDataUrl = "";
      }
    }
    return { secret: secret.base32, otpauthUrl, qrDataUrl };
  }),

  // ─── 2FA Status ───
  get2FAStatus: adminQuery.query(async () => {
    const db = getDb();
    const [record] = await db.select().from(admin2FA).where(eq(admin2FA.discordId, ADMIN_DISCORD_ID));
    return { enabled: record?.enabled === "true" };
  }),

  // ─── Setup 2FA ───
  setup2FA: adminQuery
    .input(z.object({ secret: z.string().min(1), token: z.string().length(6) }))
    .mutation(async ({ input }) => {
      const { valid } = verifyTOTP(input.token, input.secret);
      if (!valid) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_TOTP" });
      }
      const db = getDb();
      const encryptedSecret = encrypt(input.secret);
      await db.insert(admin2FA).values({
        discordId: ADMIN_DISCORD_ID,
        secret: encryptedSecret,
        enabled: "true",
        verifiedAt: new Date(),
      }).onDuplicateKeyUpdate({
        set: { secret: encryptedSecret, enabled: "true", verifiedAt: new Date() },
      });
      return { success: true };
    }),

  // ─── Verify 2FA for session ───
  verifySession: publicQuery
    .input(z.object({ token: z.string().length(6) }))
    .mutation(async ({ input, ctx }) => {
      const clientIp = ctx.req.headers.get("x-forwarded-for")?.split(",")[0].trim()
        || ctx.req.headers.get("x-real-ip")
        || "unknown";
      const rateLimit = checkTotpRateLimit(clientIp);
      if (!rateLimit.allowed) {
        throw new TRPCError({ code: "TOO_MANY_REQUESTS", message: `RATE_LIMITED: retry after ${rateLimit.retryAfter}s` });
      }
      const db = getDb();
      const [record] = await db.select().from(admin2FA)
        .where(and(eq(admin2FA.discordId, ADMIN_DISCORD_ID), eq(admin2FA.enabled, "true")));
      if (!record) throw new TRPCError({ code: "UNAUTHORIZED", message: "2FA_NOT_ENABLED" });
      const dbSecret = decryptOrPlaintext(record.secret);
      if (!dbSecret) {
        throw new TRPCError({ code: "INTERNAL_SERVER_ERROR", message: "SECRET_DECRYPT_FAILED" });
      }
      const { valid } = verifyTOTP(input.token, dbSecret);
      if (!valid) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_TOTP" });
      }
      return { success: true, sessionToken: generateSessionToken() };
    }),

  // ─── Debug 2FA (admin only) ───
  debug2FA: adminQuery
    .input(z.object({ token: z.string().length(6) }))
    .mutation(async ({ input }) => {
      const db = getDb();
      const [record] = await db.select().from(admin2FA)
        .where(eq(admin2FA.discordId, ADMIN_DISCORD_ID));
      const secret = decryptOrPlaintext(record?.secret) ?? "";
      const generated = secret
        ? speakeasy.totp({ secret, encoding: "base32" })
        : "";
      const { valid, window } = verifyTOTP(input.token, secret);
      return {
        serverTime: Date.now(),
        dbSecretExists: !!record,
        dbSecretLength: secret.length,
        generatedTOTP: generated,
        userInput: input.token,
        verifyResult: valid,
        windowUsed: window ?? 2,
        algorithm: "SHA1",
        digits: 6,
      };
    }),

  // ─── Generate redemption code ───
  generateCode: adminQuery
    .input(z.object({
      points: z.number().int().positive(),
      count: z.number().int().min(1).max(50).default(1),
      forSale: z.boolean().default(false),
    }))
    .mutation(async ({ input, ctx }) => {
      const db = getDb();
      const codes: string[] = [];
      const createdBy = ctx.user.unionId;

      for (let i = 0; i < input.count; i++) {
        let code = generateCode(10);
        let attempts = 0;
        // Ensure uniqueness
        while (attempts < 5) {
          const [existing] = await db.select().from(redemptionCodes).where(eq(redemptionCodes.code, code));
          if (!existing) break;
          code = generateCode(10);
          attempts++;
        }

        await db.insert(redemptionCodes).values({
          code,
          points: input.points,
          forSale: input.forSale ? "true" : "false",
          createdBy,
        });
        codes.push(code);
      }

      return { codes, points: input.points };
    }),

  // ─── List codes ───
  listCodes: adminQuery.query(async () => {
    const db = getDb();
    const codes = await db.select()
      .from(redemptionCodes)
      .leftJoin(users, eq(redemptionCodes.usedBy, users.discordId))
      .orderBy(redemptionCodes.createdAt);
    return codes.map((row) => ({
      ...row.redemption_codes,
      usedByName: row.users?.name ?? row.users?.unionId ?? null,
      usedByUserId: row.users?.id ?? null,
    }));
  }),

  // ─── Cleanup used codes ───
  cleanupUsedCodes: adminQuery.mutation(async () => {
    const db = getDb();
    const result = await db.delete(redemptionCodes).where(sql`${redemptionCodes.usedAt} IS NOT NULL`);
    const deleted = Number(result[0].affectedRows || 0);
    console.log('[ADMIN] Cleaned up used codes:', { deleted });
    return { deleted };
  }),

  // ─── Dashboard stats ───
  dashboard: adminQuery.query(async () => {
    const db = getDb();
    const [totalCodes] = await db.select({ count: count() }).from(redemptionCodes);
    const [usedCodes] = await db.select({ count: count() }).from(redemptionCodes).where(eq(redemptionCodes.used, "true"));
    const [pendingSync] = await db.select({ count: count() }).from(syncQueue).where(eq(syncQueue.status, "pending"));
    const [activeSubs] = await db.select({ count: count() }).from(subscriptionPurchases).where(eq(subscriptionPurchases.roleAssigned, "true"));

    return {
      totalCodes: totalCodes?.count ?? 0,
      usedCodes: usedCodes?.count ?? 0,
      pendingSync: pendingSync?.count ?? 0,
      activeSubs: activeSubs?.count ?? 0,
    };
  }),

  // ─── Send notification ───
  sendNotification: adminQuery
    .input(z.object({
      message: z.string().min(1).max(500),
      accountId: z.number().int().positive().optional(),
      ttlMinutes: z.number().int().min(1).max(10080).default(60),
    }))
    .mutation(async ({ input, ctx }) => {
      const db = getDb();
      const expiresAt = new Date(Date.now() + input.ttlMinutes * 60 * 1000);

      await db.insert(modNotifications).values({
        accountId: input.accountId || null,
        message: input.message,
        expiresAt,
        createdBy: ctx.user.unionId,
      });

      return { success: true, sentTo: input.accountId || "all" };
    }),

  // ─── List notifications ───
  listNotifications: adminQuery.query(async () => {
    const db = getDb();
    const notifications = await db
      .select({
        id: modNotifications.id,
        accountId: modNotifications.accountId,
        message: modNotifications.message,
        createdAt: modNotifications.createdAt,
        expiresAt: modNotifications.expiresAt,
        readAt: modNotifications.readAt,
        createdBy: modNotifications.createdBy,
        targetName: modAccounts.displayName,
      })
      .from(modNotifications)
      .leftJoin(modAccounts, eq(modNotifications.accountId, modAccounts.id))
      .orderBy(desc(modNotifications.createdAt))
      .limit(100);

    return notifications;
  }),

  // ─── List site role settings ───
  listRoleSettings: adminQuery.query(async () => {
    const db = getDb();
    const rows = await db.select().from(siteRoleSettings).orderBy(siteRoleSettings.roleName);
    return rows;
  }),

  // ─── Upsert site role setting ───
  upsertRoleSetting: adminQuery
    .input(z.object({
      roleName: z.string().min(1).max(32),
      nickGradientFrom: z.string().max(7).optional().nullable(),
      nickGradientTo: z.string().max(7).optional().nullable(),
      roleGradientFrom: z.string().max(7).optional().nullable(),
      roleGradientTo: z.string().max(7).optional().nullable(),
      iconUrl: z.string().max(512).optional().nullable(),
    }))
    .mutation(async ({ input }) => {
      const db = getDb();
      await db.insert(siteRoleSettings).values({
        roleName: input.roleName.toUpperCase(),
        nickGradientFrom: input.nickGradientFrom,
        nickGradientTo: input.nickGradientTo,
        roleGradientFrom: input.roleGradientFrom,
        roleGradientTo: input.roleGradientTo,
        iconUrl: input.iconUrl,
      }).onDuplicateKeyUpdate({
        set: {
          nickGradientFrom: input.nickGradientFrom,
          nickGradientTo: input.nickGradientTo,
          roleGradientFrom: input.roleGradientFrom,
          roleGradientTo: input.roleGradientTo,
          iconUrl: input.iconUrl,
          updatedAt: new Date(),
        },
      });
      return { success: true };
    }),

  // ─── Delete site role setting ───
  deleteRoleSetting: adminQuery
    .input(z.object({ id: z.number().int().positive() }))
    .mutation(async ({ input }) => {
      const db = getDb();
      await db.delete(siteRoleSettings).where(eq(siteRoleSettings.id, input.id));
      return { success: true };
    }),

  // ─── Grant role by accountKey (protected by x-admin-secret) ───
  grantRole: publicQuery
    .input(z.object({
      accountKey: z.string().min(1),
      role: z.enum(["owner", "admin", "moderator", "helper", "qa", "developer", "sponsor", "sponsor_plus", "sponsor_plusplus", "vip", "user"]).default("admin"),
    }))
    .mutation(async ({ ctx, input }) => {
      const secret = ctx.req.headers.get("x-admin-secret");
      if (secret !== process.env.ADMIN_SECRET) {
        throw new TRPCError({ code: "UNAUTHORIZED", message: "INVALID_ADMIN_SECRET" });
      }

      const db = getDb();
      const now = new Date();

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.accountKey, input.accountKey))
        .limit(1);

      if (!account) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_FOUND" });
      }

      const licenseRoles = [input.role.toUpperCase()];
      await db
        .update(modAccounts)
        .set({ licenseRoles, updatedAt: now })
        .where(eq(modAccounts.id, account.id));

      let profileId: number | null = null;
      if (account.discordId) {
        const [existingProfile] = await db
          .select()
          .from(playerProfiles)
          .where(eq(playerProfiles.discordId, account.discordId))
          .limit(1);

        if (existingProfile) {
          await db
            .update(playerProfiles)
            .set({ role: input.role, updatedAt: now })
            .where(eq(playerProfiles.id, existingProfile.id));
          profileId = existingProfile.id;
        } else {
          const result = await db.insert(playerProfiles).values({
            discordId: account.discordId,
            displayName: account.displayName || "Player",
            role: input.role,
            siteJoined: now,
            isOnline: "false",
            showHours: "true",
            showMcJoined: "true",
            showOnline: "true",
            showRank: "true",
            nickGradientFrom: "#80FF97",
            nickGradientTo: "#6BB7FF",
            roleGradientFrom: "#6BB7FF",
            roleGradientTo: "#FFD700",
            configsUploaded: 0,
            skinModel: "classic",
          } as any);
          profileId = Number((result as any).insertId);
        }

        await db
          .update(users)
          .set({ role: input.role, updatedAt: now })
          .where(eq(users.unionId, account.discordId));
      }

      return {
        success: true,
        accountId: account.accountId,
        accountKey: input.accountKey,
        discordId: account.discordId,
        role: input.role,
        profileId,
      };
    }),

  // ─── GIF Configs ───
  listGifConfigs: adminQuery
    .input(z.object({ status: z.enum(["pending", "approved", "denied", "all"]).default("all") }))
    .query(async ({ input }) => {
      const status = input.status === "all" ? undefined : input.status;
      const configs = await getGifConfigs(status);
      return configs;
    }),

  approveGifConfig: adminQuery
    .input(z.object({ configKey: z.string().min(1) }))
    .mutation(async ({ input }) => {
      const success = await approveGifConfig(input.configKey);
      if (!success) {
        throw new TRPCError({ code: "INTERNAL_SERVER_ERROR", message: "FAILED_TO_APPROVE" });
      }
      return { success: true };
    }),

  denyGifConfig: adminQuery
    .input(z.object({ configKey: z.string().min(1) }))
    .mutation(async ({ input }) => {
      const configs = await getGifConfigs("all");
      const cfg = configs.find((c: any) => c.configKey === input.configKey.toUpperCase());
      const success = await denyGifConfig(input.configKey);
      if (!success) {
        throw new TRPCError({ code: "INTERNAL_SERVER_ERROR", message: "FAILED_TO_DENY" });
      }
      if (cfg?.accountId) {
        const db = getDb();
        const [acc] = await db
          .select({ discordId: modAccounts.discordId, displayName: modAccounts.displayName })
          .from(modAccounts)
          .where(eq(modAccounts.id, cfg.accountId))
          .limit(1);
        if (acc?.discordId) {
          await sendDiscordDM(
            acc.discordId,
            `🚨 **Ваш GIF конфиг заблокирован**\nКонфиг: \`${input.configKey.toUpperCase()}\`\n\nДоступ к Cloud Config заблокирован до выяснения обстоятельств.\n\nОбратитесь к администрации.`
          );
        }
      }
      return { success: true };
    }),

  // ─── Frozen Accounts ───
  listFrozenAccounts: adminQuery.query(async () => {
    const db = getDb();
    const accounts = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.playtimeFrozen, "true"))
      .orderBy(desc(modAccounts.playtimeFrozenAt));
    return accounts;
  }),

  freezeAccount: adminQuery
    .input(z.object({ accountId: z.number().int().positive(), reason: z.string().min(1) }))
    .mutation(async ({ input, ctx }) => {
      const db = getDb();
      const now = new Date();

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.id, input.accountId))
        .limit(1);

      await db
        .update(modAccounts)
        .set({
          playtimeFrozen: "true",
          playtimeFreezeReason: input.reason,
          playtimeFrozenAt: now,
          updatedAt: now,
        })
        .where(eq(modAccounts.id, input.accountId));

      await db.insert(modFreezeLogs).values({
        accountId: input.accountId,
        action: "freeze",
        reason: input.reason,
        performedBy: "admin",
        adminDiscordId: ctx.user.unionId,
      });

      if (account?.discordId) {
        await sendDiscordDM(account.discordId, `❄️ Ваш аккаунт был заморожен.\nПричина: ${input.reason}\n\nОбратитесь к администрации для разморозки.`);
      }

      return { success: true };
    }),

  unfreezeAccount: adminQuery
    .input(z.object({ accountId: z.number().int().positive() }))
    .mutation(async ({ input, ctx }) => {
      const db = getDb();
      const now = new Date();

      // Get account to log reason
      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.id, input.accountId))
        .limit(1);

      await db
        .update(modAccounts)
        .set({
          playtimeFrozen: "false",
          playtimeFreezeReason: null,
          playtimeFrozenAt: null,
          playtimeFrozenSessionId: null,
          updatedAt: now,
        })
        .where(eq(modAccounts.id, input.accountId));

      await db.insert(modFreezeLogs).values({
        accountId: input.accountId,
        action: "unfreeze",
        reason: account?.playtimeFreezeReason ?? "manual unfreeze",
        performedBy: "admin",
        adminDiscordId: ctx.user.unionId,
      });

      if (account?.discordId) {
        await sendDiscordDM(account.discordId, `✅ Ваш аккаунт был разморожен.\n\nТеперь вы можете снова зарабатывать игровое время.`);
      }

      return { success: true };
    }),

  // ─── Banned Accounts (Top Bans) ───
  listBannedAccounts: adminQuery.query(async () => {
    const db = getDb();
    const accounts = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.playtimeBanned, "true"))
      .orderBy(desc(modAccounts.updatedAt));
    return accounts;
  }),

  banAccount: adminQuery
    .input(z.object({ accountId: z.number().int().positive(), reason: z.string().min(1) }))
    .mutation(async ({ input, ctx }) => {
      const db = getDb();
      const now = new Date();

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.id, input.accountId))
        .limit(1);

      await db
        .update(modAccounts)
        .set({
          playtimeBanned: "true",
          playtimeBanReason: input.reason,
          updatedAt: now,
        })
        .where(eq(modAccounts.id, input.accountId));

      await db.insert(modFreezeLogs).values({
        accountId: input.accountId,
        action: "ban",
        reason: input.reason,
        performedBy: "admin",
        adminDiscordId: ctx.user.unionId,
      });

      if (account?.discordId) {
        await sendDiscordDM(account.discordId, `🚫 Ваш аккаунт был забанен.\nПричина: ${input.reason}\n\nОбратитесь к администрации для апелляции.`);
      }

      return { success: true };
    }),

  unbanAccount: adminQuery
    .input(z.object({ accountId: z.number().int().positive() }))
    .mutation(async ({ input, ctx }) => {
      const db = getDb();
      const now = new Date();

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.id, input.accountId))
        .limit(1);

      await db
        .update(modAccounts)
        .set({
          playtimeBanned: "false",
          playtimeBanReason: null,
          updatedAt: now,
        })
        .where(eq(modAccounts.id, input.accountId));

      await db.insert(modFreezeLogs).values({
        accountId: input.accountId,
        action: "unban",
        reason: account?.playtimeBanReason ?? "manual unban",
        performedBy: "admin",
        adminDiscordId: ctx.user.unionId,
      });

      if (account?.discordId) {
        await sendDiscordDM(account.discordId, `✅ Ваш аккаунт был разбанен.\n\nТеперь вы можете снова пользоваться всеми функциями.`);
      }

      return { success: true };
    }),
});
