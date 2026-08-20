import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, publicQuery, authedQuery } from "./middleware";
import { getDb } from "./queries/connection";
import {
  modAccounts,
  modPlaytimeDaily,
  userInventory,
  userEntitlements,
  userProfileSettings,
  storeItems,
} from "@db/schema";
import { eq, and, desc, gte } from "drizzle-orm";

export const accountRouter = createRouter({
  // ─── Get current account info (linked mod account + Discord) ───
  me: authedQuery.query(async ({ ctx }) => {
    const db = getDb();
    const discordId = String(ctx.user.unionId);

    // Find mod account by discordId
    const [account] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, discordId))
      .limit(1);

    return {
      discordId,
      name: ctx.user.name,
      avatar: ctx.user.avatar,
      role: ctx.user.role,
      modAccount: account
        ? {
            id: account.id,
            accountId: account.accountId,
            displayName: account.displayName,
            isOnline: account.isOnline === "true",
            lastSeenAt: account.lastSeenAt,
            licenseRoles: account.licenseRoles,
          }
        : null,
      // `minecraft` is kept as a linked-account indicator for the store UI.
      minecraft: account
        ? {
            accountId: account.accountId,
            displayName: account.displayName,
          }
        : null,
    };
  }),

  // ─── Get playtime stats (last N days) ───
  playtime: authedQuery
    .input(z.object({ days: z.number().min(1).max(365).default(30) }))
    .query(async ({ ctx, input }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .limit(1);

      if (!account) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_LINKED" });
      }

      const since = new Date();
      since.setDate(since.getDate() - input.days);

      const rows = await db
        .select()
        .from(modPlaytimeDaily)
        .where(
          and(
            eq(modPlaytimeDaily.accountId, account.id),
            gte(modPlaytimeDaily.dateMsk, since.toISOString().slice(0, 10))
          )
        )
        .orderBy(modPlaytimeDaily.dateMsk);

      const totalSeconds = rows.reduce((sum, r) => sum + (r.seconds || 0), 0);

      return {
        totalSeconds,
        totalHours: Math.round((totalSeconds / 3600) * 10) / 10,
        daily: rows.map((r) => ({
          date: r.dateMsk,
          seconds: r.seconds,
          hours: Math.round((r.seconds / 3600) * 10) / 10,
        })),
      };
    }),

  // ─── Get inventory ───
  inventory: authedQuery.query(async ({ ctx }) => {
    const db = getDb();
    const discordId = String(ctx.user.unionId);

    const [account] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, discordId))
      .limit(1);

    if (!account) {
      throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_LINKED" });
    }

    const items = await db
      .select({
        inventory: userInventory,
        storeItem: storeItems,
      })
      .from(userInventory)
      .leftJoin(storeItems, eq(userInventory.storeItemId, storeItems.id))
      .where(eq(userInventory.accountId, account.id));

    return items.map((i) => ({
      id: i.inventory.id,
      storeItemId: i.inventory.storeItemId,
      name: i.storeItem?.name ?? "Unknown",
      type: i.storeItem?.type ?? "cosmetic",
      sku: i.storeItem?.sku ?? "",
      acquiredAt: i.inventory.acquiredAt,
      expiresAt: i.inventory.expiresAt,
      isActive: i.inventory.isActive === "true",
    }));
  }),

  // ─── Get active entitlements ───
  entitlements: authedQuery.query(async ({ ctx }) => {
    const db = getDb();
    const discordId = String(ctx.user.unionId);

    const [account] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, discordId))
      .limit(1);

    if (!account) {
      throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_LINKED" });
    }

    const now = new Date();

    const ents = await db
      .select()
      .from(userEntitlements)
      .where(
        and(
          eq(userEntitlements.accountId, account.id),
          eq(userEntitlements.isActive, "true"),
          gte(userEntitlements.endsAt, now)
        )
      );

    return ents;
  }),
});
