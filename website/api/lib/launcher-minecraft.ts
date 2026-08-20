import { getDb } from "../queries/connection";
import {
  modLinkCodes,
  modAccounts,
  users,
  playerProfiles,
} from "@db/schema";
import { eq, and, gt, isNull, desc } from "drizzle-orm";
import { hashLinkCode } from "./mod-auth";
import { getLicenseAccountInfo, getRoleGradient } from "./license-client";

function mapLicenseRoleToSiteRole(licenseRole?: string): string {
  if (!licenseRole) return "user";
  const r = licenseRole.toUpperCase();
  if (r === "OWNER" || r === "ADMIN") return "admin";
  if (r === "QA") return "qa";
  if (r === "SLIHA") return "developer";
  if (r === "SPONSOR_PLUSPLUS" || r === "SPONSOR++") return "sponsor_plusplus";
  if (r === "SPONSOR_PLUS" || r === "SPONSOR+") return "sponsor_plus";
  if (r === "SPONSOR") return "sponsor";
  return "user";
}

export type LinkMinecraftResult = {
  success: true;
  accountId: number;
  displayName: string;
  role: string;
};

export type LinkMinecraftError =
  | "INVALID_CODE"
  | "ACCOUNT_NOT_FOUND"
  | "ACCOUNT_KEY_MISSING"
  | "LICENSE_SERVER_UNAVAILABLE"
  | "UNKNOWN_ACCOUNT_KEY";

export class LinkMinecraftException extends Error {
  constructor(
    public code: LinkMinecraftError,
    message: string,
  ) {
    super(message);
  }
}

export async function linkMinecraftByDiscordId(
  code: string,
  discordId: string,
  discordUsername: string,
): Promise<LinkMinecraftResult> {
  const db = getDb();
  const now = new Date();

  const codeHash = hashLinkCode(code);
  const [linkCode] = await db
    .select()
    .from(modLinkCodes)
    .where(
      and(
        eq(modLinkCodes.codeHash, codeHash),
        gt(modLinkCodes.expiresAt, now),
        isNull(modLinkCodes.usedAt),
      ),
    )
    .limit(1);

  if (!linkCode) {
    throw new LinkMinecraftException("INVALID_CODE", "Код не найден или просрочен");
  }

  const [modAccount] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.id, linkCode.accountId))
    .limit(1);

  if (!modAccount) {
    throw new LinkMinecraftException("ACCOUNT_NOT_FOUND", "Аккаунт не найден");
  }

  const accountKey = linkCode.accountKey || modAccount.accountKeyEnc;
  if (!accountKey) {
    throw new LinkMinecraftException(
      "ACCOUNT_KEY_MISSING",
      "Невозможно синхронизировать аккаунт",
    );
  }

  let licenseInfo;
  try {
    licenseInfo = await getLicenseAccountInfo(accountKey);
  } catch (e: any) {
    console.error("[linkMinecraftByDiscordId] License server unreachable:", e.message);
    throw new LinkMinecraftException(
      "LICENSE_SERVER_UNAVAILABLE",
      "License Server недоступен",
    );
  }

  if (!licenseInfo) {
    throw new LinkMinecraftException(
      "UNKNOWN_ACCOUNT_KEY",
      "Аккаунт не найден на License Server",
    );
  }

  const licenseRoles = licenseInfo.roles || [];
  const primaryRole = licenseRoles[0] || "USER";
  const siteRole = mapLicenseRoleToSiteRole(primaryRole);
  const nickG = getRoleGradient(licenseInfo, "nick");
  const roleG = getRoleGradient(licenseInfo, "role");

  await db
    .update(modAccounts)
    .set({
      discordId,
      accountKey: linkCode.accountKey || modAccount.accountKey || accountKey,
      displayName: licenseInfo.name || modAccount.displayName,
      contact: discordUsername,
      licenseRoles,
      lastSyncedAt: now,
      updatedAt: now,
    })
    .where(eq(modAccounts.id, modAccount.id));

  await db
    .update(users)
    .set({ role: siteRole as any, updatedAt: now })
    .where(eq(users.unionId, discordId));

  const existingProfile = await db
    .select()
    .from(playerProfiles)
    .where(eq(playerProfiles.discordId, discordId))
    .limit(1);

  const profileDefaults = {
    discordId,
    displayName: licenseInfo.name || modAccount.displayName || discordUsername || "Player",
    role: siteRole,
    contact: discordUsername,
    minecraftUuid: null,
    hoursPlayed: 0,
    isOnline: "false" as const,
    showHours: "true" as const,
    showMcJoined: "true" as const,
    showOnline: "true" as const,
    showRank: "true" as const,
    nickGradientFrom: nickG?.from ?? "#80FF97",
    nickGradientTo: nickG?.to ?? "#6BB7FF",
    roleGradientFrom: roleG?.from ?? "#6BB7FF",
    roleGradientTo: roleG?.to ?? "#FFD700",
    configsUploaded: 0,
    skinUrl: null,
    skinModel: "classic" as const,
  };

  if (existingProfile.length > 0) {
    await db
      .update(playerProfiles)
      .set({
        displayName: profileDefaults.displayName,
        role: profileDefaults.role,
        contact: profileDefaults.contact,
        nickGradientFrom: profileDefaults.nickGradientFrom,
        nickGradientTo: profileDefaults.nickGradientTo,
        roleGradientFrom: profileDefaults.roleGradientFrom,
        roleGradientTo: profileDefaults.roleGradientTo,
      })
      .where(eq(playerProfiles.id, existingProfile[0].id));
  } else {
    await db.insert(playerProfiles).values(profileDefaults as any);
  }

  await db
    .update(modLinkCodes)
    .set({ usedAt: now })
    .where(eq(modLinkCodes.id, linkCode.id));

  return {
    success: true,
    accountId: modAccount.accountId,
    displayName: profileDefaults.displayName,
    role: siteRole,
  };
}


export async function linkLicenseAccountToDiscord(
  discordId: string,
  discordUsername: string,
  hwidHash: string
): Promise<{ success: true; accountId: number; displayName: string; role: string } | null> {
  const db = getDb();
  const now = new Date();

  const [modAccount] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.hwidHash, hwidHash))
    .orderBy(desc(modAccounts.lastSyncedAt))
    .limit(1);

  if (!modAccount) {
    return null;
  }

  const accountKey = modAccount.accountKey || modAccount.accountKeyEnc;
  if (!accountKey) {
    console.error("[linkLicenseAccountToDiscord] accountKey missing for account", modAccount.accountId);
    return null;
  }

  let licenseInfo;
  try {
    licenseInfo = await getLicenseAccountInfo(accountKey);
  } catch (e: any) {
    console.error("[linkLicenseAccountToDiscord] License server unreachable:", e.message);
    return null;
  }

  if (!licenseInfo) {
    console.error("[linkLicenseAccountToDiscord] License account not found for", modAccount.accountId);
    return null;
  }

  const licenseRoles = licenseInfo.roles || [];
  const primaryRole = licenseRoles[0] || "USER";
  const siteRole = mapLicenseRoleToSiteRole(primaryRole);
  const nickG = getRoleGradient(licenseInfo, "nick");
  const roleG = getRoleGradient(licenseInfo, "role");

  await db
    .update(modAccounts)
    .set({
      discordId,
      accountKey: modAccount.accountKey || accountKey,
      displayName: licenseInfo.name || modAccount.displayName,
      contact: discordUsername,
      licenseRoles,
      lastSyncedAt: now,
      updatedAt: now,
    })
    .where(eq(modAccounts.id, modAccount.id));

  await db
    .update(users)
    .set({ role: siteRole as any, updatedAt: now })
    .where(eq(users.unionId, discordId));

  const existingProfile = await db
    .select()
    .from(playerProfiles)
    .where(eq(playerProfiles.discordId, discordId))
    .limit(1);

  const profileDefaults = {
    discordId,
    displayName: licenseInfo.name || modAccount.displayName || discordUsername || "Player",
    role: siteRole,
    contact: discordUsername,
    minecraftUuid: null,
    hoursPlayed: 0,
    isOnline: "false" as const,
    showHours: "true" as const,
    showMcJoined: "true" as const,
    showOnline: "true" as const,
    showRank: "true" as const,
    nickGradientFrom: nickG?.from ?? "#80FF97",
    nickGradientTo: nickG?.to ?? "#6BB7FF",
    roleGradientFrom: roleG?.from ?? "#6BB7FF",
    roleGradientTo: roleG?.to ?? "#FFD700",
    configsUploaded: 0,
    skinUrl: null,
    skinModel: "classic" as const,
  };

  if (existingProfile.length > 0) {
    await db
      .update(playerProfiles)
      .set({
        displayName: profileDefaults.displayName,
        role: profileDefaults.role,
        contact: profileDefaults.contact,
        nickGradientFrom: profileDefaults.nickGradientFrom,
        nickGradientTo: profileDefaults.nickGradientTo,
        roleGradientFrom: profileDefaults.roleGradientFrom,
        roleGradientTo: profileDefaults.roleGradientTo,
      })
      .where(eq(playerProfiles.id, existingProfile[0].id));
  } else {
    await db.insert(playerProfiles).values(profileDefaults as any);
  }

  return {
    success: true,
    accountId: modAccount.accountId,
    displayName: profileDefaults.displayName,
    role: siteRole,
  };
}
