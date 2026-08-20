#!/usr/bin/env tsx
/**
 * Grant admin (or any) role to a user by License Server accountKey.
 * Usage: npx tsx scripts/grant-admin.ts --accountKey=<key> [--role=admin] [--discordId=<id>]
 */
import { eq } from "drizzle-orm";
import { getDb } from "../api/queries/connection";
import { modAccounts, playerProfiles, users } from "../db/schema";

function parseArgs() {
  const args = process.argv.slice(2);
  const map: Record<string, string> = {};
  for (const arg of args) {
    if (arg.startsWith("--")) {
      const [k, v] = arg.slice(2).split("=");
      map[k] = v ?? "true";
    }
  }
  return map;
}

async function main() {
  const args = parseArgs();
  const accountKey = args.accountKey;
  const targetRole = args.role || "admin";

  if (!accountKey) {
    console.error("Usage: npx tsx scripts/grant-admin.ts --accountKey=<accountKey> [--role=admin]");
    process.exit(1);
  }

  const db = getDb();
  const now = new Date();

  // 1. Find mod_account by accountKey
  const [account] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.accountKey, accountKey))
    .limit(1);

  if (!account) {
    console.error(`Account with accountKey="${accountKey}" not found in mod_accounts.`);
    process.exit(1);
  }

  console.log(`Found account: id=${account.id}, accountId=${account.accountId}, discordId=${account.discordId}`);

  // 2. Update mod_accounts.licenseRoles
  const licenseRoles = [targetRole.toUpperCase()];
  await db
    .update(modAccounts)
    .set({ licenseRoles, updatedAt: now })
    .where(eq(modAccounts.id, account.id));
  console.log(`Updated mod_accounts.licenseRoles → ${JSON.stringify(licenseRoles)}`);

  // 3. Update or create player_profiles
  if (account.discordId) {
    const [existingProfile] = await db
      .select()
      .from(playerProfiles)
      .where(eq(playerProfiles.discordId, account.discordId))
      .limit(1);

    if (existingProfile) {
      await db
        .update(playerProfiles)
        .set({ role: targetRole, updatedAt: now })
        .where(eq(playerProfiles.id, existingProfile.id));
      console.log(`Updated player_profiles.role → "${targetRole}" (id=${existingProfile.id})`);
    } else {
      const result = await db.insert(playerProfiles).values({
        discordId: account.discordId,
        displayName: account.displayName || "Player",
        role: targetRole,
        minecraftUuid: "", // required by schema, will be empty
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
      const insertedId = Number((result as any).insertId);
      console.log(`Created player_profiles (id=${insertedId}) with role="${targetRole}"`);
    }

    // 4. Update users.role
    await db
      .update(users)
      .set({ role: targetRole, updatedAt: now })
      .where(eq(users.unionId, account.discordId));
    console.log(`Updated users.role → "${targetRole}" for discordId=${account.discordId}`);
  } else {
    console.warn("No discordId linked to this mod_account. Skipped player_profiles & users update.");
  }

  console.log("\n✅ Done!");
  console.log(`   AccountKey : ${accountKey}`);
  console.log(`   Discord    : ${account.discordId || "N/A"}`);
  console.log(`   Role       : ${targetRole}`);
  process.exit(0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
