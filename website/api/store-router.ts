import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, publicQuery, authedQuery } from "./middleware";
import { getDb } from "./queries/connection";
import {
  storeItems,
  orders,
  orderItems,
  subscriptionKeys,
  userEntitlements,
  userInventory,
  modAccounts,
  users,
  transactions,
} from "@db/schema";
import { eq, and, inArray, desc, sql, gt, gte } from "drizzle-orm";
import { randomInt } from "crypto";
import { hashAccountKey } from "./lib/mod-auth";
import { encrypt } from "./lib/encryption";
import { applyKeyOnLicenseServer, resetHwidOnLicenseServer, findOrCreateLicenseAccountByHwid, createOrUpdateLicenseOnServer } from "./lib/license-client";

function generateRandomKey(length: number): string {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  return Array.from({ length }, () => chars.charAt(randomInt(chars.length))).join("");
}

// Tier priority for sponsor subscriptions (higher = better)
const TIER_PRIORITY: Record<string, number> = {
  sponsor: 1,
  sponsor_plus: 2,
  sponsor_plusplus: 3,
};

const LICENSE_ROLE_MAP: Record<string, string> = {
  sponsor: "SPONSOR",
  sponsor_plus: "SPONSOR+",
  sponsor_plusplus: "SPONSOR++",
};

const TIER_LIMITS: Record<string, { slots: number; maxGifSize: number; maxConfigsWithGif: number }> = {
  sponsor: { slots: 10, maxGifSize: 5, maxConfigsWithGif: 3 },
  sponsor_plus: { slots: 25, maxGifSize: 10, maxConfigsWithGif: 6 },
  sponsor_plusplus: { slots: 70, maxGifSize: 30, maxConfigsWithGif: 20 },
};

// Fixed daily rates for proration/extension calculations (HY-P per day)
const DAILY_RATES: Record<string, number> = {
  sponsor: 16,        // 500/30 = 16.66 → floor = 16
  sponsor_plus: 33,   // 1000/30 = 33.33 → floor = 33
  sponsor_plusplus: 100, // 3000/30 = 100
};

const MAX_SUBSCRIPTION_DAYS = 90;

function getItemTier(item: typeof storeItems.$inferSelect): string {
  const meta = (item.metadata ?? {}) as Record<string, unknown>;
  return (meta.tier as string) ?? "sponsor";
}

function computeProratedUpgradePrice(
  newItem: typeof storeItems.$inferSelect,
  existingEntitlement: typeof userEntitlements.$inferSelect,
  existingItem: typeof storeItems.$inferSelect
): number {
  const now = Date.now();
  const endsAt = existingEntitlement.endsAt ? new Date(existingEntitlement.endsAt).getTime() : now;
  const remainingMs = Math.max(0, endsAt - now);
  const remainingDays = remainingMs / (1000 * 60 * 60 * 24);

  const existingTier = getItemTier(existingItem);
  const dailyRate = DAILY_RATES[existingTier] ?? Math.floor(existingItem.priceCents / (existingItem.durationDays ?? 30));
  const remainingValue = Math.floor(remainingDays * dailyRate);

  return Math.max(1, Math.floor(newItem.priceCents - remainingValue));
}

// ─── Public: list store items ───
export const storeRouter = createRouter({
  items: publicQuery.query(async () => {
    const db = getDb();
    return db
      .select()
      .from(storeItems)
      .where(eq(storeItems.isActive, "true"))
      .orderBy(storeItems.priceCents);
  }),

  // ─── Authed: get my inventory + active entitlements ───
  myInventory: authedQuery.query(async ({ ctx }) => {
    const db = getDb();
    const discordId = String(ctx.user.unionId);

    const [account] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, discordId))
      .limit(1);

    if (!account) {
      return { items: [], entitlements: [], activeSubscription: null };
    }

    const inventory = await db
      .select()
      .from(userInventory)
      .where(eq(userInventory.accountId, account.id));

    const entitlements = await db
      .select()
      .from(userEntitlements)
      .where(
        and(
          eq(userEntitlements.accountId, account.id),
          eq(userEntitlements.isActive, "true")
        )
      )
      .orderBy(desc(userEntitlements.createdAt));

    // Find active subscription. Use the LATEST end date among active subscription
    // entitlements so the displayed expiry matches the unified License Server date
    // (all roles of a tier share one end date).
    const now = new Date();
    const activeSubs = entitlements
      .filter(
        (e) =>
          e.type === "subscription" &&
          e.isActive === "true" &&
          (e.endsAt === null || new Date(e.endsAt) > now)
      )
      .sort((a, b) => {
        const am = a.endsAt ? new Date(a.endsAt).getTime() : Infinity;
        const bm = b.endsAt ? new Date(b.endsAt).getTime() : Infinity;
        return bm - am;
      });
    const activeSub = activeSubs[0];

    let activeSubscription = null;
    if (activeSub) {
      const [subItem] = await db
        .select()
        .from(storeItems)
        .where(eq(storeItems.id, activeSub.storeItemId))
        .limit(1);
      activeSubscription = {
        ...activeSub,
        item: subItem ?? null,
        tier: subItem ? getItemTier(subItem) : null,
      };
    }

    // Enrich inventory with item details
    const itemIds = inventory.map((i) => i.storeItemId);
    const items = itemIds.length > 0
      ? await db.select().from(storeItems).where(inArray(storeItems.id, itemIds))
      : [];
    const itemMap = new Map(items.map((i) => [i.id, i]));

    return {
      items: inventory.map((inv) => ({
        ...inv,
        storeItem: itemMap.get(inv.storeItemId) ?? null,
      })),
      entitlements,
      activeSubscription,
    };
  }),

  // ─── Authed: create order (placeholder for payment integration) ───
  createOrder: authedQuery
    .input(
      z.object({
        items: z.array(
          z.object({
            storeItemId: z.number().int().positive(),
            quantity: z.number().int().min(1).default(1),
          })
        ),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      return await db.transaction(async (tx) => {
        const [account] = await tx
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.discordId, discordId))
          .limit(1);

        const itemIds = input.items.map((i) => i.storeItemId);
        const items = await tx
          .select()
          .from(storeItems)
          .where(inArray(storeItems.id, itemIds));

        if (items.length !== itemIds.length) {
          throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_ITEM" });
        }

        const itemMap = new Map(items.map((i) => [i.id, i]));
        let totalCents = 0;
        for (const line of input.items) {
          const item = itemMap.get(line.storeItemId);
          if (!item) throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_ITEM" });
          totalCents += item.priceCents * line.quantity;
        }

        const [orderResult] = await tx.insert(orders).values({
          accountId: account?.id ?? null,
          discordId,
          totalCents,
          currency: "USD",
          status: "pending",
        });

        const orderId = Number(orderResult.insertId);

        for (const line of input.items) {
          const item = itemMap.get(line.storeItemId)!;
          await tx.insert(orderItems).values({
            orderId,
            storeItemId: item.id,
            quantity: line.quantity,
            priceCents: item.priceCents,
          });
        }

        return { orderId, totalCents, status: "pending" };
      });
    }),

  // ─── Authed: purchase item with points (atomic) ───
  purchase: authedQuery
    .input(
      z.object({
        sku: z.string().min(1).max(64),
        quantity: z.number().int().min(1).default(1),
        isRenewal: z.boolean().default(false),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);
      const quantity = input.quantity;

      // Require a linked account (via /hypnosia link + License Server)
      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .limit(1);
      if (!account) {
        throw new TRPCError({ code: "FORBIDDEN", message: "MINECRAFT_NOT_LINKED" });
      }

      return await db.transaction(async (tx) => {
        console.log('[Purchase Step 1] User discordId:', discordId, 'SKU:', input.sku);

        // 1. Find store item
        const [item] = await tx
          .select()
          .from(storeItems)
          .where(eq(storeItems.sku, input.sku))
          .limit(1);

        if (!item) {
          console.log('[Purchase Error] ITEM_NOT_FOUND:', input.sku);
          throw new TRPCError({ code: "NOT_FOUND", message: "ITEM_NOT_FOUND" });
        }
        if (item.isActive !== "true") {
          console.log('[Purchase Error] ITEM_NOT_AVAILABLE:', input.sku);
          throw new TRPCError({ code: "BAD_REQUEST", message: "ITEM_NOT_AVAILABLE" });
        }

        // 2. Find user
        const [user] = await tx
          .select()
          .from(users)
          .where(eq(users.unionId, ctx.user.unionId))
          .limit(1);

        if (!user) {
          console.log('[Purchase Error] USER_NOT_FOUND:', ctx.user.unionId);
          throw new TRPCError({ code: "NOT_FOUND", message: "USER_NOT_FOUND" });
        }
        console.log('[Purchase Step 2] User:', user.id, 'Balance:', user.points, 'Item:', item.sku, 'Price:', item.priceCents);

        // 3. Find mod account
        const [account] = await tx
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.discordId, discordId))
          .limit(1);

        if (!account) {
          console.log('[Purchase Error] ACCOUNT_NOT_LINKED:', discordId);
          throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_LINKED" });
        }
        console.log('[Purchase Step 3] ModAccount:', account.id, 'accountKey:', account.accountKey ? 'exists' : 'NULL', 'licenseRoles:', account.licenseRoles);

        // ─── Subscription-specific logic ───
        let finalPrice = item.priceCents * quantity;
        let isUpgrade = false;
        let isRenewal = input.isRenewal;
        let existingEntitlement: typeof userEntitlements.$inferSelect | null = null;
        let existingItem: typeof storeItems.$inferSelect | null = null;
        let renewalAddDays = 0;
        // Single unified end date for ALL roles of this purchase (tier + sub-tiers
        // always end on the same day). Computed for every subscription purchase.
        let subTargetEndsAt: Date | null = null;

        if (item.type === "subscription_key") {
          const itemTier = getItemTier(item);
          const itemPriority = TIER_PRIORITY[itemTier] ?? 0;

          // Find existing active subscription
          const now = new Date();
          const [existing] = await tx
            .select()
            .from(userEntitlements)
            .where(
              and(
                eq(userEntitlements.accountId, account.id),
                eq(userEntitlements.type, "subscription"),
                eq(userEntitlements.isActive, "true"),
                gt(userEntitlements.endsAt, now)
              )
            )
            .orderBy(desc(userEntitlements.endsAt))
            .limit(1);

          if (existing) {
            const [exItem] = await tx
              .select()
              .from(storeItems)
              .where(eq(storeItems.id, existing.storeItemId))
              .limit(1);

            if (exItem) {
              existingEntitlement = existing;
              existingItem = exItem;
              const existingTier = getItemTier(exItem);
              const existingPriority = TIER_PRIORITY[existingTier] ?? 0;

              if (itemPriority > existingPriority) {
                // Upgrade
                isUpgrade = true;
                finalPrice = computeProratedUpgradePrice(item, existing, exItem);
              } else if (itemPriority === existingPriority) {
                // Renewal / extension
                isRenewal = true;
                const nowMs = Date.now();
                const endsAtMs = existing.endsAt ? new Date(existing.endsAt).getTime() : nowMs;
                const daysLeft = Math.max(0, Math.ceil((endsAtMs - nowMs) / (1000 * 60 * 60 * 24)));
                const targetDays = Math.min(MAX_SUBSCRIPTION_DAYS, (item.durationDays ?? 30) * quantity);
                renewalAddDays = Math.max(0, targetDays - daysLeft);
                const tier = getItemTier(item);
                const dailyRate = DAILY_RATES[tier] ?? Math.floor(item.priceCents / (item.durationDays ?? 30));
                finalPrice = Math.max(0, renewalAddDays * dailyRate);
              }
              // If lower tier, treat as full price (new purchase, will coexist)
            }
          }

          // Unified target end date for ALL roles of this purchase.
          // daysLeft is taken from the latest active subscription (max endsAt),
          // so an upgrade/renewal never shortens remaining time. The final date
          // is min(MAX, max(daysLeft, requestedDays)) and is shared by every role.
          const nowMs = Date.now();
          const latestEndMs = existing?.endsAt ? new Date(existing.endsAt).getTime() : nowMs;
          const daysLeftNow = Math.max(0, Math.ceil((latestEndMs - nowMs) / (1000 * 60 * 60 * 24)));
          const requestedDays = (item.durationDays ?? 30) * quantity;
          const finalTargetDays = Math.min(MAX_SUBSCRIPTION_DAYS, Math.max(daysLeftNow, requestedDays));
          subTargetEndsAt = new Date(nowMs + finalTargetDays * 24 * 60 * 60 * 1000);
        }

        if (user.points < finalPrice) {
          throw new TRPCError({ code: "BAD_REQUEST", message: "INSUFFICIENT_FUNDS" });
        }

        // 4. Deduct balance atomically. The conditional UPDATE
        // (WHERE points >= finalPrice) guarantees the balance can never go
        // negative even under concurrent purchases — the earlier check above
        // is based on a possibly stale snapshot, so this is the real guard.
        const [deducted] = await tx
          .update(users)
          .set({
            points: sql`${users.points} - ${finalPrice}`,
          })
          .where(and(eq(users.unionId, ctx.user.unionId), gte(users.points, finalPrice)));

        if (deducted.affectedRows === 0) {
          throw new TRPCError({ code: "BAD_REQUEST", message: "INSUFFICIENT_FUNDS" });
        }

        // 5. Create completed order
        const [orderResult] = await tx.insert(orders).values({
          accountId: account.id,
          discordId,
          totalCents: finalPrice,
          currency: item.currency,
          status: "paid",
          paidAt: new Date(),
        });

        const orderId = Number(orderResult.insertId);

        // 6. Create order items
        await tx.insert(orderItems).values({
          orderId,
          storeItemId: item.id,
          quantity,
          priceCents: item.priceCents,
          metadata: isUpgrade ? { upgrade: true } : isRenewal ? { renewal: true } : undefined,
        });

        // 7. Add to inventory
        await tx
          .insert(userInventory)
          .values({
            accountId: account.id,
            storeItemId: item.id,
            acquiredFrom: "purchase",
          })
          .onDuplicateKeyUpdate({
            set: { acquiredAt: new Date() },
          });

        // 8. Log transaction
        await tx.insert(transactions).values({
          userId: user.id,
          type: "purchase",
          amount: finalPrice,
          description: `Куплено: ${item.name} x${quantity}${isUpgrade ? " (upgrade)" : isRenewal ? " (renewal)" : ""}`,
          relatedId: orderId,
        });

        // ─── Type-specific fulfillment ───
        let generatedKey: string | undefined;
        let hwidResult: { success: boolean; newAccountKey?: string; message?: string } | undefined;

        if (item.type === "subscription_key") {
          // Ensure accountKey is available for License Server
          if (!account.accountKey && account.hwidHash) {
            const licenseInfo = await findOrCreateLicenseAccountByHwid(account.hwidHash);
            if (licenseInfo?.accountKey) {
              await tx.update(modAccounts)
                .set({ accountKey: licenseInfo.accountKey })
                .where(eq(modAccounts.id, account.id));
              account.accountKey = licenseInfo.accountKey;
            }
          }
          if (!account.accountKey) {
            throw new TRPCError({ code: "NOT_FOUND", message: "Аккаунт не привязан к License Server. Выполните /hypnosia link в игре." });
          }

          const itemTier = getItemTier(item);
          const licenseRole = LICENSE_ROLE_MAP[itemTier] ?? "SPONSOR";
          const limits = TIER_LIMITS[itemTier];

          // TODO(security): Move License Server call outside the DB transaction
          // to prevent connection pool exhaustion. Current design holds the tx open
          // during an external HTTP request. Refactor: commit balance deduction first,
          // then call License Server, then open a second tx for keys/entitlements.
          // Single unified end date for every role of this purchase.
          const targetEndsAt = subTargetEndsAt ?? new Date(Date.now() + (item.durationDays ?? 30) * quantity * 86400000);
          console.log('[Purchase Step 4] License Server request:', { accountKey: account.accountKey, role: licenseRole, targetExpiresAt: targetEndsAt.toISOString(), limits });
          const licenseResult = await createOrUpdateLicenseOnServer(
            account.accountKey,
            licenseRole,
            Math.max(1, Math.ceil((targetEndsAt.getTime() - Date.now()) / 86400000)),
            limits,
            targetEndsAt.toISOString()
          );
          console.log('[Purchase Step 5] License Server response:', licenseResult);

          if (!licenseResult.success || !licenseResult.keys || licenseResult.keys.length === 0) {
            console.log('[Purchase Error] License Server failed:', licenseResult.message);
            throw new TRPCError({ code: "INTERNAL_SERVER_ERROR", message: licenseResult.message || "LICENSE_SERVER_CREATE_FAILED" });
          }

          const now = new Date();

          // Deactivate any prior active subscription entitlements so we don't pile
          // up duplicates / divergent dates. The fresh keys below become the source
          // of truth, all sharing the same unified end date.
          await tx
            .update(userEntitlements)
            .set({ isActive: "false" } as any)
            .where(
              and(
                eq(userEntitlements.accountId, account.id),
                eq(userEntitlements.type, "subscription"),
                eq(userEntitlements.isActive, "true")
              )
            );

          // Store all keys returned by License Server. Every role-key gets the
          // SAME unified end date (targetEndsAt) so tier and sub-tiers align.
          for (const keyResult of licenseResult.keys) {
            const keyHash = hashAccountKey(keyResult.key);

            await tx.insert(subscriptionKeys).values({
              storeItemId: item.id,
              keyHash,
              keyPrefix: keyResult.key.substring(0, 4),
              keyValue: encrypt(keyResult.key),
              status: "sold",
              durationDays: (item.durationDays ?? 30) * quantity,
              orderId,
              buyerAccountId: account.id,
              soldAt: new Date(),
            });

            // Create entitlement for each role key with the unified end date
            await tx.insert(userEntitlements).values({
              accountId: account.id,
              type: "subscription",
              storeItemId: item.id,
              source: "purchase",
              startsAt: now,
              endsAt: targetEndsAt,
              metadata: {
                tier: itemTier,
                isUpgrade,
                isRenewal,
                licenseRole: keyResult.role,
                licenseKey: keyResult.key,
              },
            });
          }

          generatedKey = licenseResult.keys.find((k) => k.role === licenseRole)?.key;

          // Update modAccounts.licenseRoles with ALL roles from License Server
          const currentRoles = new Set(account.licenseRoles ?? []);
          for (const keyResult of licenseResult.keys) {
            currentRoles.add(keyResult.role);
          }

          await tx
            .update(modAccounts)
            .set({
              licenseRoles: Array.from(currentRoles),
              updatedAt: new Date(),
            })
            .where(eq(modAccounts.id, account.id));

        } else if (item.type === "hwid_reset") {
          if (account.accountKey) {
            hwidResult = await resetHwidOnLicenseServer(account.accountKey);
          }
        } else if (
          ["nickname_gradient", "role_gradient", "cosmetic", "custom_role", "profile_style", "profile_badge", "profile_background"].includes(item.type)
        ) {
          await tx.insert(userEntitlements).values({
            accountId: account.id,
            type: item.type as any,
            storeItemId: item.id,
            source: "purchase",
            startsAt: new Date(),
            endsAt: item.durationDays
              ? new Date(Date.now() + item.durationDays * 86400000)
              : null,
          });
        }

        console.log('[Purchase Success] orderId:', orderId, 'finalPrice:', finalPrice, 'remainingBalance:', user.points - finalPrice, 'isUpgrade:', isUpgrade, 'isRenewal:', isRenewal);
        return {
          orderId,
          totalPrice: finalPrice,
          remainingBalance: user.points - finalPrice,
          key: generatedKey,
          hwidResetResult: hwidResult,
          isUpgrade,
          isRenewal,
        };
      });
    }),

  // ─── Authed: redeem subscription key ───
  redeemKey: authedQuery
    .input(z.object({ key: z.string().min(1).max(64) }))
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      const keyHash = hashAccountKey(input.key);

      const [keyRecord] = await db
        .select()
        .from(subscriptionKeys)
        .where(eq(subscriptionKeys.keyHash, keyHash))
        .limit(1);

      if (!keyRecord) {
        throw new TRPCError({ code: "NOT_FOUND", message: "KEY_NOT_FOUND" });
      }

      if (keyRecord.status === "redeemed") {
        throw new TRPCError({ code: "CONFLICT", message: "KEY_ALREADY_REDEEMED" });
      }

      if (keyRecord.status === "revoked") {
        throw new TRPCError({ code: "FORBIDDEN", message: "KEY_REVOKED" });
      }

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .limit(1);

      if (!account) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_LINKED" });
      }

      const now = new Date();
      const expiresAfterRedeem = keyRecord.durationDays
        ? new Date(now.getTime() + keyRecord.durationDays * 24 * 60 * 60 * 1000)
        : null;

      // Atomic redemption: claim the key with a conditional UPDATE
      // (WHERE status='sold') inside a transaction so two concurrent requests
      // cannot both redeem the same key (prevents double-grant of entitlements).
      await db.transaction(async (tx) => {
        const [claimed] = await tx
          .update(subscriptionKeys)
          .set({
            status: "redeemed",
            redeemedByAccountId: account.id,
            redeemedAt: now,
            expiresAfterRedeemAt: expiresAfterRedeem,
          })
          .where(and(eq(subscriptionKeys.id, keyRecord.id), eq(subscriptionKeys.status, "sold")));

        if (claimed.affectedRows === 0) {
          // Another request already redeemed it, or it was never in a redeemable state.
          throw new TRPCError({ code: "CONFLICT", message: "KEY_ALREADY_REDEEMED" });
        }

        await tx.insert(userEntitlements).values({
          accountId: account.id,
          type: "subscription",
          storeItemId: keyRecord.storeItemId,
          source: "key",
          startsAt: now,
          endsAt: expiresAfterRedeem,
        });

        await tx
          .insert(userInventory)
          .values({
            accountId: account.id,
            storeItemId: keyRecord.storeItemId,
            acquiredFrom: "key",
            expiresAt: expiresAfterRedeem,
          })
          .onDuplicateKeyUpdate({
            set: {
              isActive: "true",
              expiresAt: expiresAfterRedeem,
              acquiredAt: now,
            },
          });
      });

      return {
        success: true,
        itemName: keyRecord.keyPrefix,
        expiresAt: expiresAfterRedeem,
      };
    }),
});
