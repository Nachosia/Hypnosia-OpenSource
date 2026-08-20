#!/usr/bin/env tsx
/**
 * Backfill account_key for existing mod_accounts by querying License Server via hwidHash.
 * Usage: npx tsx scripts/backfill-account-keys.ts [--dry-run]
 */
import { eq, isNull, and, not } from "drizzle-orm";
import { getDb } from "../api/queries/connection";
import { modAccounts, playerProfiles, users } from "../db/schema";
import { findOrCreateLicenseAccountByHwid, getRoleGradient, parseGradientColors } from "../api/lib/license-client";

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

async function main() {
  const dryRun = process.argv.includes("--dry-run");
  const db = getDb();

  // Find all mod_accounts with NULL account_key but existing hwid_hash
  const accounts = await db
    .select()
    .from(modAccounts)
    .where(and(isNull(modAccounts.accountKey), not(isNull(modAccounts.hwidHash))));

  console.log(`[Backfill] Found ${accounts.length} accounts with NULL account_key and non-NULL hwid_hash`);
  if (accounts.length === 0) {
    console.log("[Backfill] Nothing to do.");
    process.exit(0);
  }

  let updated = 0;
  let skipped = 0;
  let errors = 0;

  for (const account of accounts) {
    const hwidHash = account.hwidHash!;
    console.log(`\n[Backfill] Processing account id=${account.id}, displayName=${account.displayName}, hwidHash=${hwidHash.slice(0, 16)}...`);

    // Query License Server by HWID (find or create)
    const licenseInfo = await findOrCreateLicenseAccountByHwid(hwidHash);
    if (!licenseInfo) {
      console.log(`[Backfill]   -> License Server unreachable or no response, skipping.`);
      errors++;
      continue;
    }

    if (!licenseInfo.accountKey) {
      console.log(`[Backfill]   -> License Server did not return accountKey, skipping.`);
      skipped++;
      continue;
    }

    const lsAccountId = licenseInfo.accountId ?? licenseInfo.id ?? 0;
    console.log(`[Backfill]   -> License Server returned accountId=${lsAccountId}, accountKey=${licenseInfo.accountKey!.slice(0, 16)}..., roles=${JSON.stringify(licenseInfo.roles)}`);

    if (dryRun) {
      console.log(`[Backfill]   -> DRY RUN, skipping DB updates.`);
      updated++;
      continue;
    }

    const now = new Date();
    const licenseRoles = licenseInfo.roles || [];
    const primaryRole = licenseRoles[0] || "USER";
    const siteRole = normalizeRole(primaryRole);
    const displayName = licenseInfo.name || account.displayName || "Player";
    const nickG = getRoleGradient(licenseInfo, "nick");
    const roleG = getRoleGradient(licenseInfo, "role");

    // 1. Update mod_accounts
    await db
      .update(modAccounts)
      .set({
        accountId: licenseInfo.accountId ?? licenseInfo.id ?? account.accountId,
        accountKey: licenseInfo.accountKey,
        displayName,
        licenseRoles,
        contact: licenseInfo.contact ?? account.contact,
        lastSyncedAt: now,
        updatedAt: now,
      })
      .where(eq(modAccounts.id, account.id));
    console.log(`[Backfill]   -> Updated mod_accounts (account_key, license_roles, display_name)`);

    // 2. Update or create player_profiles
    if (account.discordId) {
      const [existingProfile] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, account.discordId))
        .limit(1);

      if (existingProfile) {
        await db.update(playerProfiles).set({
          displayName,
          role: siteRole,
          nickGradientFrom: nickG?.from ?? existingProfile.nickGradientFrom,
          nickGradientTo: nickG?.to ?? existingProfile.nickGradientTo,
          roleGradientFrom: roleG?.from ?? existingProfile.roleGradientFrom,
          roleGradientTo: roleG?.to ?? existingProfile.roleGradientTo,
          updatedAt: now,
        }).where(eq(playerProfiles.id, existingProfile.id));
        console.log(`[Backfill]   -> Updated player_profiles (role=${siteRole}, gradients)`);
      } else {
        await db.insert(playerProfiles).values({
          discordId: account.discordId,
          displayName,
          role: siteRole,
          minecraftUuid: "",
          siteJoined: now,
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
          skinModel: "classic",
        } as any);
        console.log(`[Backfill]   -> Created player_profiles (role=${siteRole})`);
      }

      // 3. Update users
      await db.update(users).set({
        role: siteRole,
        name: displayName,
        updatedAt: now,
      }).where(eq(users.unionId, account.discordId));
      console.log(`[Backfill]   -> Updated users (role=${siteRole}, name=${displayName})`);
    } else {
      console.log(`[Backfill]   -> No discordId linked, skipped player_profiles & users`);
    }

    updated++;
  }

  console.log(`\n[Backfill] Done! Updated: ${updated}, Skipped: ${skipped}, Errors: ${errors}`);
  process.exit(0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
