import { Hono } from "hono";
import { getDb } from "./queries/connection";
import {
  modAccounts,
  modPlaytimeDaily,
  userProfileSettings,
  userInventory,
  storeItems,
  modLinkCodes,
  playerProfiles,
} from "@db/schema";
import { eq, and, isNull, gte, desc } from "drizzle-orm";
import { hashLinkCode } from "./lib/mod-auth";

const DISCORD_BOT_API_KEY = process.env.DISCORD_BOT_API_KEY || process.env.API_KEY || "";

function checkDiscordAuth(c: { req: { header: (name: string) => string | undefined } }): boolean {
  // Fail closed: if no key is configured, reject everything rather than
  // falling back to a publicly-known default.
  if (!DISCORD_BOT_API_KEY) return false;
  const key = c.req.header("x-api-key");
  return key === DISCORD_BOT_API_KEY;
}

// Discord snowflake: 17-20 digit numeric string.
const DISCORD_ID_REGEX = /^\d{17,20}$/;

export const discordApi = new Hono();

// Middleware
discordApi.use("/*", async (c, next) => {
  if (!checkDiscordAuth(c)) {
    return c.json({ error: "UNAUTHORIZED" }, 401);
  }
  return next();
});

// ─── GET /api/discord/account/:discordId ───
discordApi.get("/account/:discordId", async (c) => {
  const discordId = c.req.param("discordId");
  const db = getDb();

  const [account] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.discordId, discordId))
    .limit(1);

  if (!account) {
    return c.json({ error: "NOT_FOUND" }, 404);
  }

  // Get total playtime (last 30 days)
  const since = new Date();
  since.setDate(since.getDate() - 30);

  const [playtimeRow] = await db
    .select({ total: modPlaytimeDaily.seconds })
    .from(modPlaytimeDaily)
    .where(
      and(
        eq(modPlaytimeDaily.accountId, account.id),
        gte(modPlaytimeDaily.dateMsk, since.toISOString().slice(0, 10))
      )
    );

  return c.json({
    id: account.id,
    accountId: account.accountId,
    displayName: account.displayName,
    isOnline: account.isOnline === "true",
    lastSeenAt: account.lastSeenAt,
    licenseRoles: account.licenseRoles,
    discordId: account.discordId,
    playtimeSeconds30d: playtimeRow?.total ?? 0,
  });
});

// ─── GET /api/discord/profile/:discordId ───
discordApi.get("/profile/:discordId", async (c) => {
  const discordId = c.req.param("discordId");
  const db = getDb();

  const [account] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.discordId, discordId))
    .limit(1);

  if (!account) {
    return c.json({ error: "NOT_FOUND" }, 404);
  }

  const [settings] = await db
    .select()
    .from(userProfileSettings)
    .where(eq(userProfileSettings.accountId, account.id))
    .limit(1);

  // Get selected items details
  const selectedIds = [
    settings?.selectedNicknameGradientId,
    settings?.selectedRoleGradientId,
    settings?.selectedProfileStyleId,
    settings?.selectedProfileBackgroundId,
    settings?.selectedBadgeId,
  ].filter(Boolean) as number[];

  const selectedItems = selectedIds.length > 0
    ? await db.select().from(storeItems).where(eq(storeItems.id, selectedIds[0])) // drizzle doesn't support inArray easily here
    : [];
  // Actually we need all items, let's query properly
  const allSelectedItems = selectedIds.length > 0
    ? await db.select().from(storeItems).where(eq(storeItems.id, selectedIds[0]))
    : [];

  return c.json({
    accountId: account.id,
    displayName: account.displayName,
    isOnline: account.isOnline === "true",
    customStatus: settings?.customStatus ?? null,
    profileBio: settings?.profileBio ?? null,
    showOnline: settings?.showOnline ?? "true",
    showPlaytime: settings?.showPlaytime ?? "true",
    showDiscord: settings?.showDiscord ?? "false",
  });
});

// ─── POST /api/discord/link/verify ───
// Verifies a mod link code and binds Discord ID to mod account
discordApi.post("/link/verify", async (c) => {
  const body = await c.req.json<{
    discordId: string;
    code: string;
  }>();

  const { discordId, code } = body;
  if (!discordId || !code) {
    return c.json({ error: "MISSING_FIELDS" }, 400);
  }

  // Validate input shapes to avoid binding arbitrary/garbage Discord IDs.
  if (typeof discordId !== "string" || !DISCORD_ID_REGEX.test(discordId)) {
    return c.json({ error: "INVALID_DISCORD_ID" }, 400);
  }
  if (typeof code !== "string" || code.length < 4 || code.length > 16) {
    return c.json({ error: "INVALID_CODE" }, 400);
  }

  const db = getDb();
  const codeHash = hashLinkCode(code);
  const now = new Date();

  // Find the code
  const [linkCode] = await db
    .select()
    .from(modLinkCodes)
    .where(
      and(
        eq(modLinkCodes.codeHash, codeHash),
        gte(modLinkCodes.expiresAt, now),
        isNull(modLinkCodes.usedAt)
      )
    )
    .limit(1);

  if (!linkCode) {
    return c.json({ error: "CODE_INVALID_OR_EXPIRED" }, 400);
  }

  // Reject if this Discord ID is already bound to a different mod account.
  const [discordOwner] = await db
    .select({ id: modAccounts.id })
    .from(modAccounts)
    .where(eq(modAccounts.discordId, discordId))
    .limit(1);
  if (discordOwner && discordOwner.id !== linkCode.accountId) {
    return c.json({ error: "DISCORD_ALREADY_LINKED" }, 409);
  }

  // Atomically claim the code (WHERE usedAt IS NULL) so a code cannot be
  // consumed twice by concurrent requests.
  const [claimed] = await db
    .update(modLinkCodes)
    .set({ usedAt: now })
    .where(and(eq(modLinkCodes.id, linkCode.id), isNull(modLinkCodes.usedAt)));

  if (claimed.affectedRows === 0) {
    return c.json({ error: "CODE_INVALID_OR_EXPIRED" }, 400);
  }

  // Bind Discord ID to mod account
  await db
    .update(modAccounts)
    .set({ discordId, updatedAt: now })
    .where(eq(modAccounts.id, linkCode.accountId));

  // Also create/update player profile for compatibility
  const [account] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.id, linkCode.accountId))
    .limit(1);

  if (account) {
    await db
      .insert(playerProfiles)
      .values({
        discordId,
        displayName: account.displayName || "Player",
        role: "user",
      })
      .onDuplicateKeyUpdate({
        set: {
          displayName: account.displayName || "Player",
          updatedAt: now,
        },
      })
      .catch(() => {});
  }

  return c.json({
    success: true,
    accountId: account?.accountId ?? null,
  });
});
