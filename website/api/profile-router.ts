import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, publicQuery, authedQuery } from "./middleware";
import { getDb } from "./queries/connection";
import { getLicenseAccountInfo, getRoleGradient, getRoleIconsMap, getRoleDisplayNamesMap, resetHwidOnLicenseServer } from "./lib/license-client";
import { normalizeRole, pickHighestLicenseRole, computeEffectiveRole, getSortedUniqueRoles } from "./lib/roles";
import { hashAccountKey } from "./lib/mod-auth";
import { encrypt } from "./lib/encryption";
import { playerProfiles, users, weeklyStats, modAccounts, modPlaytimeDaily, modPlaytimeMonthly, modPlaytimeAlltime, modPlaytimeServersDaily, modPlaytimeServersAlltime, serverNames, userProfileSettings, userInventory, storeItems, userEntitlements, modSessions, siteRoleSettings, hwidLogs, playerConfigs, siteStats } from "@db/schema";
import { eq, desc, sql, inArray, gte, and, gt, isNull } from "drizzle-orm";

// Simple in-memory cache for tops (1 hour TTL)
const topsCache = new Map<string, { data: unknown; expiresAt: number }>();
const CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

function getCachedTops<T>(key: string): T | null {
  const cached = topsCache.get(key);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.data as T;
  }
  return null;
}

function setCachedTops<T>(key: string, data: T) {
  topsCache.set(key, { data, expiresAt: Date.now() + CACHE_TTL_MS });
}



async function getSiteRoleOverride(db: ReturnType<typeof getDb>, roleName?: string) {
  if (!roleName) return null;
  const [row] = await db
    .select()
    .from(siteRoleSettings)
    .where(eq(siteRoleSettings.roleName, roleName.toUpperCase()))
    .limit(1);
  if (!row) return null;
  return {
    nickGradientFrom: row.nickGradientFrom,
    nickGradientTo: row.nickGradientTo,
    roleGradientFrom: row.roleGradientFrom,
    roleGradientTo: row.roleGradientTo,
  };
}

async function checkOnlineStatus(db: ReturnType<typeof getDb>, accountDbId: number): Promise<string> {
  const fourMinutesAgo = new Date(Date.now() - 4 * 60 * 1000);
  const [activeSession] = await db
    .select()
    .from(modSessions)
    .where(
      and(
        eq(modSessions.accountId, accountDbId),
        gt(modSessions.lastHeartbeatAt, fourMinutesAgo),
        isNull(modSessions.endedAt)
      )
    )
    .limit(1);
  return activeSession ? "true" : "false";
}

// ─── Get top servers for an account ───
async function getTopServersForAccount(
  db: ReturnType<typeof getDb>,
  accountId: number
): Promise<{ serverIp: string; displayName: string; totalMinutes: number; weekMinutes: number; monthMinutes: number }[]> {
  const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
    .toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" });
  const yearMonth = new Date().toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" }).slice(0, 7);

  // Alltime per-server totals
  const alltimeRows = await db
    .select({ serverIp: modPlaytimeServersAlltime.serverIp, totalMinutes: modPlaytimeServersAlltime.totalMinutes })
    .from(modPlaytimeServersAlltime)
    .where(eq(modPlaytimeServersAlltime.accountId, accountId));

  // Weekly per-server totals
  const weekRows = await db
    .select({
      serverIp: modPlaytimeServersDaily.serverIp,
      minutes: sql<number>`COALESCE(SUM(${modPlaytimeServersDaily.minutes}), 0)`,
    })
    .from(modPlaytimeServersDaily)
    .where(and(
      eq(modPlaytimeServersDaily.accountId, accountId),
      gte(modPlaytimeServersDaily.dateMsk, sevenDaysAgo)
    ))
    .groupBy(modPlaytimeServersDaily.serverIp);

  // Monthly per-server totals
  const monthRows = await db
    .select({
      serverIp: modPlaytimeServersDaily.serverIp,
      minutes: sql<number>`COALESCE(SUM(${modPlaytimeServersDaily.minutes}), 0)`,
    })
    .from(modPlaytimeServersDaily)
    .where(and(
      eq(modPlaytimeServersDaily.accountId, accountId),
      gte(modPlaytimeServersDaily.dateMsk, `${yearMonth}-01`)
    ))
    .groupBy(modPlaytimeServersDaily.serverIp);

  const weekMap = new Map(weekRows.map((r) => [r.serverIp, r.minutes]));
  const monthMap = new Map(monthRows.map((r) => [r.serverIp, r.minutes]));

  // Fetch display names from server_names
  const serverIps = alltimeRows.map((r) => r.serverIp);
  let displayMap = new Map<string, string>();
  if (serverIps.length > 0) {
    const names = await db
      .select()
      .from(serverNames)
      .where(inArray(serverNames.serverIp, serverIps));
    displayMap = new Map(names.map((n) => [n.serverIp, n.displayName]));
  }

  const merged = alltimeRows
    .map((r) => ({
      serverIp: r.serverIp,
      displayName: displayMap.get(r.serverIp) || r.serverIp,
      totalMinutes: r.totalMinutes ?? 0,
      weekMinutes: weekMap.get(r.serverIp) ?? 0,
      monthMinutes: monthMap.get(r.serverIp) ?? 0,
    }))
    .sort((a, b) => b.totalMinutes - a.totalMinutes)
    .slice(0, 10);

  return merged;
}

export const profileRouter = createRouter({
  // Get own profile (authed)
  me: authedQuery.query(async ({ ctx }) => {
    const db = getDb();
    const discordId = String(ctx.user.unionId);

    // Try playerProfiles first
    let [profile] = await db
      .select()
      .from(playerProfiles)
      .where(eq(playerProfiles.discordId, discordId))
      .limit(1);

    // Fallback: try modAccounts
    const [modAccount] = await db
      .select()
      .from(modAccounts)
      .where(eq(modAccounts.discordId, discordId))
      .limit(1);

    // Auto-create player profile if missing
    if (!profile) {
      await db.insert(playerProfiles).values({
        discordId,
        displayName: (ctx.user.name || 'Player').slice(0, 16),
        role: normalizeRole(ctx.user.role ?? 'user'),
        hoursPlayed: 0,
        isOnline: 'false',
        showHours: 'true',
        showMcJoined: 'true',
        showOnline: 'true',
        showRank: 'true',
        nickGradientFrom: '#80FF97',
        nickGradientTo: '#6BB7FF',
        roleGradientFrom: '#6BB7FF',
        roleGradientTo: '#FFD700',
        configsUploaded: 0,
        skinUrl: null,
        skinModel: 'classic',
      } as any);

      [profile] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, discordId))
        .limit(1);
    }

    // Fetch alltime + weekly playtime (computed before the sync block below,
    // which references totalMinutes in its early-return payloads).
    let totalMinutes = 0;
    let weeklyMinutes = 0;
    if (modAccount) {
      // Live aggregate from daily (source of truth) — never drifts.
      const [ptRow] = await db
        .select({ total: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
        .from(modPlaytimeDaily)
        .where(eq(modPlaytimeDaily.accountId, modAccount.accountId));
      totalMinutes = ptRow?.total ?? 0;

      const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
        .toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" });
      const [weeklyRow] = await db
        .select({ total: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
        .from(modPlaytimeDaily)
        .where(and(
          eq(modPlaytimeDaily.accountId, modAccount.accountId),
          gte(modPlaytimeDaily.dateMsk, sevenDaysAgo)
        ));
      weeklyMinutes = weeklyRow?.total ?? 0;
    }

    // Background sync displayName + gradients + roles from License Server
    if (modAccount?.accountKey) {
      try {
        const licenseInfo = await getLicenseAccountInfo(modAccount.accountKey, modAccount.hwidHash || undefined);
        if (licenseInfo) {
          const updatedDisplayName = licenseInfo.name || modAccount.displayName || ctx.user.name || 'Player';
          const updatedRoles = licenseInfo.roles ?? modAccount.licenseRoles ?? [];
          const highestRole = pickHighestLicenseRole(updatedRoles);
          const updatedRole = normalizeRole(highestRole ?? ctx.user.role ?? 'user');
          const nickG = getRoleGradient(licenseInfo, 'nick');
          const roleG = getRoleGradient(licenseInfo, 'role');
          const roleIcons = getRoleIconsMap(licenseInfo);
          const roleDisplayNames = getRoleDisplayNamesMap(licenseInfo);

          // Site override takes priority over License Server
          const siteOverride = await getSiteRoleOverride(db, highestRole);
          const finalNickFrom = siteOverride?.nickGradientFrom ?? nickG?.from ?? profile?.nickGradientFrom ?? '#80FF97';
          const finalNickTo = siteOverride?.nickGradientTo ?? nickG?.to ?? profile?.nickGradientTo ?? '#6BB7FF';
          const finalRoleFrom = siteOverride?.roleGradientFrom ?? roleG?.from ?? profile?.roleGradientFrom ?? '#6BB7FF';
          const finalRoleTo = siteOverride?.roleGradientTo ?? roleG?.to ?? profile?.roleGradientTo ?? '#FFD700';

          // Update playerProfiles if exists
          if (profile) {
            await db.update(playerProfiles).set({
              displayName: updatedDisplayName,
              role: updatedRole,
              nickGradientFrom: finalNickFrom,
              nickGradientTo: finalNickTo,
              roleGradientFrom: finalRoleFrom,
              roleGradientTo: finalRoleTo,
            }).where(eq(playerProfiles.id, profile.id));
          }

          // Update modAccounts
          await db.update(modAccounts).set({
            displayName: updatedDisplayName,
            licenseRoles: updatedRoles,
            contact: licenseInfo.contact ?? modAccount.contact,
          }).where(eq(modAccounts.id, modAccount.id));

          // Update users.role for consistency
          await db.update(users).set({
            role: updatedRole,
            name: updatedDisplayName,
          }).where(eq(users.unionId, discordId));

          // Return updated profile data immediately
          if (profile) {
            return {
              ...profile,
              displayName: updatedDisplayName,
              role: updatedRole,
              nickGradientFrom: finalNickFrom,
              nickGradientTo: finalNickTo,
              roleGradientFrom: finalRoleFrom,
              roleGradientTo: finalRoleTo,
              skinUrl: profile.skinUrl ?? null,
              skinModel: profile.skinModel ?? 'classic',
              totalMinutes,
              roleIcons,
              roleDisplayNames,
            };
          }

          // Build updated fallback profile
          return {
            id: modAccount.id ?? 0,
            discordId,
            displayName: updatedDisplayName,
            role: updatedRole,
            hoursPlayed: 0,
            totalMinutes,
            mcJoined: null,
            siteJoined: modAccount.createdAt ?? ctx.user.createdAt ?? new Date(),
            isOnline: modAccount.isOnline ?? 'false',
            showHours: 'true',
            showMcJoined: 'true',
            showOnline: 'true',
            showRank: 'true',
            nickGradientFrom: finalNickFrom,
            nickGradientTo: finalNickTo,
            roleGradientFrom: finalRoleFrom,
            roleGradientTo: finalRoleTo,
            configsUploaded: 0,
            skinUrl: null,
            skinModel: 'classic',
            customRoleName: null,
            nickGradientEditedAt: null,
            roleGradientEditedAt: null,
            roleIcons,
            roleDisplayNames,
          } as typeof playerProfiles.$inferSelect;
        }
      } catch (e) {
        console.error('License Server sync failed in profile.me:', e);
      }
    }

    const effectiveRole = computeEffectiveRole(modAccount?.licenseRoles, profile?.role, ctx.user?.role);
    const onlineStatus = modAccount ? await checkOnlineStatus(db, modAccount.id) : 'false';

    const allRoles = getSortedUniqueRoles(modAccount?.licenseRoles);

    // If playtime banned or frozen — zero out stats
    if (modAccount?.playtimeBanned === "true" || modAccount?.playtimeFrozen === "true") {
      const isBanned = modAccount.playtimeBanned === "true";
      const isFrozen = modAccount.playtimeFrozen === "true";
      const payload = {
        id: modAccount.id,
        discordId,
        displayName: modAccount.displayName ?? ctx.user.name ?? 'Player',
        role: normalizeRole(modAccount.licenseRoles?.[0] ?? ctx.user.role ?? 'user'),
        effectiveRole: computeEffectiveRole(modAccount.licenseRoles, undefined, ctx.user?.role),
        allRoles,
        hoursPlayed: 0,
        totalMinutes: 0,
        weeklyMinutes: 0,
        playtimeBanned: isBanned,
        playtimeBanReason: isBanned ? modAccount.playtimeBanReason : null,
        playtimeFrozen: isFrozen,
        playtimeFreezeReason: isFrozen ? modAccount.playtimeFreezeReason : null,
        mcJoined: null,
        siteJoined: modAccount.createdAt ?? ctx.user.createdAt ?? new Date(),
        isOnline: onlineStatus,
        showHours: 'true',
        showMcJoined: 'true',
        showOnline: 'true',
        showRank: 'true',
        nickGradientFrom: '#80FF97',
        nickGradientTo: '#6BB7FF',
        roleGradientFrom: '#6BB7FF',
        roleGradientTo: '#FFD700',
        configsUploaded: 0,
        skinUrl: null,
        skinModel: 'classic',
        customRoleName: null,
        nickGradientEditedAt: null,
        roleGradientEditedAt: null,
        roleIcons: {},
        roleDisplayNames: {},
      };
      return payload as any;
    }

    if (profile) {
      return {
        ...profile,
        effectiveRole,
        allRoles,
        isOnline: onlineStatus,
        totalMinutes,
        weeklyMinutes,
        skinUrl: profile.skinUrl ?? null,
        skinModel: profile.skinModel ?? 'classic',
        roleIcons: {},
        roleDisplayNames: {},
      } as any;
    }

    // Build fallback profile
    return {
      id: modAccount?.id ?? 0,
      discordId,
      displayName: ctx.user.name ?? modAccount?.displayName ?? 'Player',
      role: normalizeRole(modAccount?.licenseRoles?.[0] ?? ctx.user.role ?? 'user'),
      effectiveRole,
      allRoles,
      hoursPlayed: 0,
      totalMinutes,
      weeklyMinutes,
      mcJoined: null,
      siteJoined: modAccount?.createdAt ?? ctx.user.createdAt ?? new Date(),
      isOnline: onlineStatus,
      showHours: 'true',
      showMcJoined: 'true',
      showOnline: 'true',
      showRank: 'true',
      nickGradientFrom: '#80FF97',
      nickGradientTo: '#6BB7FF',
      roleGradientFrom: '#6BB7FF',
      roleGradientTo: '#FFD700',
      configsUploaded: 0,
      skinUrl: null,
      skinModel: 'classic',
      customRoleName: null,
      nickGradientEditedAt: null,
      roleGradientEditedAt: null,
      roleIcons: {},
      roleDisplayNames: {},
    } as typeof playerProfiles.$inferSelect;
  }),

  // Get profile by ID (public)
  // Primary: modAccounts.id (numeric). Fallbacks: discordId, license accountId
  getById: publicQuery
    .input(z.object({ id: z.string() }))
    .query(async ({ input }) => {
      const db = getDb();
      const numericId = parseInt(input.id);

      // ─── Primary: lookup by modAccounts.accountId (public License Server ID) ───
      if (!isNaN(numericId)) {
        let [modAccount] = await db
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.accountId, numericId))
          .limit(1);

        // Fallback: old internal modAccounts.id
        if (!modAccount) {
          [modAccount] = await db
            .select()
            .from(modAccounts)
            .where(eq(modAccounts.id, numericId))
            .limit(1);
        }

        if (modAccount) {
          // Background sync displayName + gradients + roles from License Server
          let syncedDisplayName = modAccount.displayName;
          let syncedRole = normalizeRole(modAccount.licenseRoles?.[0] ?? 'user');
          let syncedRoles = modAccount.licenseRoles ?? [];
          let syncedNickFrom: string | undefined;
          let syncedNickTo: string | undefined;
          let syncedRoleFrom: string | undefined;
          let syncedRoleTo: string | undefined;

          let syncedRoleIcons: Record<string, string> = {};
          let syncedRoleDisplayNames: Record<string, string> = {};
          if (modAccount.accountKey) {
            try {
              const licenseInfo = await getLicenseAccountInfo(modAccount.accountKey, modAccount.hwidHash || undefined);
              if (licenseInfo) {
                syncedDisplayName = licenseInfo.name || modAccount.displayName || `Account #${modAccount.accountId}`;
                syncedRoles = licenseInfo.roles ?? modAccount.licenseRoles ?? [];
                const highestRole = pickHighestLicenseRole(syncedRoles);
                syncedRole = normalizeRole(highestRole ?? 'user');
                const nickG = getRoleGradient(licenseInfo, 'nick');
                const roleG = getRoleGradient(licenseInfo, 'role');
                syncedRoleIcons = getRoleIconsMap(licenseInfo);
                syncedRoleDisplayNames = getRoleDisplayNamesMap(licenseInfo);

                // Site override takes priority over License Server
                const siteOverride = await getSiteRoleOverride(db, highestRole);
                syncedNickFrom = siteOverride?.nickGradientFrom ?? nickG?.from;
                syncedNickTo = siteOverride?.nickGradientTo ?? nickG?.to;
                syncedRoleFrom = siteOverride?.roleGradientFrom ?? roleG?.from;
                syncedRoleTo = siteOverride?.roleGradientTo ?? roleG?.to;

                // Update modAccounts
                await db.update(modAccounts).set({
                  displayName: syncedDisplayName,
                  licenseRoles: syncedRoles,
                  contact: licenseInfo.contact ?? modAccount.contact,
                }).where(eq(modAccounts.id, modAccount.id));

                // Update users.role for consistency
                if (modAccount.discordId) {
                  await db.update(users).set({
                    role: syncedRole,
                    name: syncedDisplayName,
                  }).where(eq(users.unionId, modAccount.discordId));

                  // Update playerProfiles if exists
                  const [existingProfile] = await db
                    .select()
                    .from(playerProfiles)
                    .where(eq(playerProfiles.discordId, modAccount.discordId))
                    .limit(1);

                  if (existingProfile) {
                    await db.update(playerProfiles).set({
                      displayName: syncedDisplayName,
                      role: syncedRole,
                      nickGradientFrom: syncedNickFrom ?? existingProfile.nickGradientFrom,
                      nickGradientTo: syncedNickTo ?? existingProfile.nickGradientTo,
                      roleGradientFrom: syncedRoleFrom ?? existingProfile.roleGradientFrom,
                      roleGradientTo: syncedRoleTo ?? existingProfile.roleGradientTo,
                    }).where(eq(playerProfiles.id, existingProfile.id));
                  }
                }
              }
            } catch (e) {
              console.error('License Server sync failed in profile.getById:', e);
            }
          }

          // Find linked profile
          let profile: typeof playerProfiles.$inferSelect | undefined;

          if (modAccount.discordId) {
            [profile] = await db
              .select()
              .from(playerProfiles)
              .where(eq(playerProfiles.discordId, modAccount.discordId))
              .limit(1);
          }

          // Live aggregate from daily (source of truth) — never drifts.
          const [playtimeRow] = await db
            .select({ total: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
            .from(modPlaytimeDaily)
            .where(eq(modPlaytimeDaily.accountId, modAccount.accountId));

          const totalMinutes = playtimeRow?.total ?? 0;
          const hoursPlayed = Math.round((totalMinutes / 60) * 10) / 10;

          const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
            .toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" });
          const [weeklyRow] = await db
            .select({ total: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
            .from(modPlaytimeDaily)
            .where(and(
              eq(modPlaytimeDaily.accountId, modAccount.accountId),
              gte(modPlaytimeDaily.dateMsk, sevenDaysAgo)
            ));
          const weeklyMinutes = weeklyRow?.total ?? 0;

          const onlineStatus = await checkOnlineStatus(db, modAccount.accountId);
          const serverStats = await getTopServersForAccount(db, modAccount.accountId);

          // If playtime banned or frozen — zero out stats
          const isBanned = modAccount.playtimeBanned === "true";
          const isFrozen = modAccount.playtimeFrozen === "true";
          if (isBanned || isFrozen) {
            return {
              id: modAccount.id,
              accountId: modAccount.accountId,
              effectiveRole: computeEffectiveRole(syncedRoles, profile?.role, undefined),
              allRoles: getSortedUniqueRoles(syncedRoles),
              hasDiscordLink: !!modAccount.discordId,
              discordId: modAccount.discordId ?? profile?.discordId ?? '',
              displayName: (syncedDisplayName && syncedDisplayName !== 'None' && syncedDisplayName.trim() !== '') ? syncedDisplayName : (profile?.displayName && profile.displayName !== 'None') ? profile.displayName : `Account #${modAccount.accountId}`,
              role: syncedRole ?? normalizeRole(profile?.role ?? 'user'),
              hoursPlayed: 0,
              totalMinutes: 0,
              weeklyMinutes: 0,
              playtimeBanned: isBanned,
              playtimeBanReason: isBanned ? modAccount.playtimeBanReason : null,
              playtimeFrozen: isFrozen,
              playtimeFreezeReason: isFrozen ? modAccount.playtimeFreezeReason : null,
              serverStats: { topServers: [], playtimeBanned: isBanned, playtimeFrozen: isFrozen },
              mcJoined: profile?.mcJoined ?? null,
              siteJoined: profile?.siteJoined ?? modAccount.createdAt ?? new Date(),
              isOnline: onlineStatus,
              showHours: profile?.showHours ?? 'true',
              showMcJoined: profile?.showMcJoined ?? 'true',
              showOnline: profile?.showOnline ?? 'true',
              showRank: profile?.showRank ?? 'true',
              nickGradientFrom: syncedNickFrom ?? profile?.nickGradientFrom ?? '#80FF97',
              nickGradientTo: syncedNickTo ?? profile?.nickGradientTo ?? '#6BB7FF',
              roleGradientFrom: syncedRoleFrom ?? profile?.roleGradientFrom ?? '#6BB7FF',
              roleGradientTo: syncedRoleTo ?? profile?.roleGradientTo ?? '#FFD700',
              configsUploaded: profile?.configsUploaded ?? 0,
              skinUrl: profile?.skinUrl ?? null,
              skinModel: profile?.skinModel ?? 'classic',
              roleIcons: syncedRoleIcons,
              roleDisplayNames: syncedRoleDisplayNames,
            };
          }

          // Build unified profile response
          const effectiveRole = computeEffectiveRole(syncedRoles, profile?.role, undefined);
          const allRoles = getSortedUniqueRoles(syncedRoles);
          return {
            id: modAccount.accountId,
            internalId: modAccount.id,
            accountId: modAccount.accountId,
            effectiveRole,
            allRoles,
            hasDiscordLink: !!modAccount.discordId,
            discordId: modAccount.discordId ?? profile?.discordId ?? '',
            displayName: (syncedDisplayName && syncedDisplayName !== 'None' && syncedDisplayName.trim() !== '') ? syncedDisplayName : (profile?.displayName && profile.displayName !== 'None') ? profile.displayName : `Account #${modAccount.accountId}`,
            role: syncedRole ?? normalizeRole(profile?.role ?? 'user'),
            hoursPlayed,
            totalMinutes,
            weeklyMinutes,
            serverStats: { topServers: serverStats, playtimeBanned: false },
            mcJoined: profile?.mcJoined ?? null,
            siteJoined: profile?.siteJoined ?? modAccount.createdAt ?? new Date(),
            isOnline: onlineStatus,
            showHours: profile?.showHours ?? 'true',
            showMcJoined: profile?.showMcJoined ?? 'true',
            showOnline: profile?.showOnline ?? 'true',
            showRank: profile?.showRank ?? 'true',
            nickGradientFrom: syncedNickFrom ?? profile?.nickGradientFrom ?? '#80FF97',
            nickGradientTo: syncedNickTo ?? profile?.nickGradientTo ?? '#6BB7FF',
            roleGradientFrom: syncedRoleFrom ?? profile?.roleGradientFrom ?? '#6BB7FF',
            roleGradientTo: syncedRoleTo ?? profile?.roleGradientTo ?? '#FFD700',
            configsUploaded: profile?.configsUploaded ?? 0,
            skinUrl: profile?.skinUrl ?? null,
            skinModel: profile?.skinModel ?? 'classic',
            roleIcons: syncedRoleIcons,
            roleDisplayNames: syncedRoleDisplayNames,
          };
        }
      }

      // ─── Fallbacks: discordId / license accountId ───
      let [profile] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, input.id))
        .limit(1);

      if (!profile && !isNaN(numericId)) {
        const [modAccount] = await db
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.accountId, numericId))
          .limit(1);
        if (modAccount?.discordId) {
          [profile] = await db
            .select()
            .from(playerProfiles)
            .where(eq(playerProfiles.discordId, modAccount.discordId))
            .limit(1);
        }
      }

      if (!profile) {
        throw new TRPCError({ code: "NOT_FOUND", message: "PROFILE_NOT_FOUND" });
      }

      // Compute effectiveRole for fallback profile
      let fallbackModAccount: typeof modAccounts.$inferSelect | undefined;
      if (profile.discordId) {
        [fallbackModAccount] = await db
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.discordId, profile.discordId))
          .limit(1);
      }
      const effectiveRole = computeEffectiveRole(fallbackModAccount?.licenseRoles, profile?.role, undefined);
      const allRoles = getSortedUniqueRoles(fallbackModAccount?.licenseRoles);
      return { ...profile, accountId: fallbackModAccount?.accountId, effectiveRole, allRoles, hasDiscordLink: !!profile.discordId } as any;
    }),

  // Get activity for profile (last 7 days)
  activity: publicQuery
    .input(z.object({ discordId: z.string() }))
    .query(async ({ input }) => {
      const db = getDb();

      // Resolve mod account by discordId
      const [modAccount] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, input.discordId))
        .limit(1);

      if (!modAccount) {
        return [];
      }

      const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
        .toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" });

      const stats = await db
        .select({ dateMsk: modPlaytimeDaily.dateMsk, minutes: modPlaytimeDaily.minutes })
        .from(modPlaytimeDaily)
        .where(and(
          eq(modPlaytimeDaily.accountId, modAccount.accountId),
          gte(modPlaytimeDaily.dateMsk, sevenDaysAgo)
        ))
        .orderBy(modPlaytimeDaily.dateMsk);

      // Build 7-day map (Moscow dates)
      const result: Record<string, number> = {};
      for (let i = 0; i < 7; i++) {
        const d = new Date(Date.now() - (6 - i) * 24 * 60 * 60 * 1000);
        const dateStr = d.toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" });
        result[dateStr] = 0;
      }

      for (const s of stats) {
        const d = s.dateMsk instanceof Date
          ? s.dateMsk.toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" })
          : String(s.dateMsk).slice(0, 10);
        result[d] = (result[d] || 0) + Math.round((s.minutes ?? 0) / 60);
      }

      return Object.entries(result).map(([date, hours]) => ({
        date,
        hours,
        dayName: new Date(date + "T00:00:00").toLocaleDateString('en-US', { weekday: 'short' }),
      }));
    }),

  // Get per-server playtime stats for an account
  serverStats: publicQuery
    .input(z.object({ accountId: z.number().int().positive() }))
    .query(async ({ input }) => {
      const db = getDb();

      // Resolve mod account by accountId
      let [modAccount] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.accountId, input.accountId))
        .limit(1);

      // Fallback: old internal id
      if (!modAccount && !isNaN(input.accountId)) {
        [modAccount] = await db
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.id, input.accountId))
          .limit(1);
      }

      if (!modAccount) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_NOT_FOUND" });
      }

      if (modAccount.playtimeBanned === "true") {
        return { topServers: [], playtimeBanned: true };
      }

      const topServers = await getTopServersForAccount(db, modAccount.accountId);
      return { topServers, playtimeBanned: false };
    }),

  // Update own profile settings (authed)
  updateSettings: authedQuery
    .input(
      z.object({
        showHours: z.enum(["true", "false"]).optional(),
        showMcJoined: z.enum(["true", "false"]).optional(),
        showOnline: z.enum(["true", "false"]).optional(),
        showRank: z.enum(["true", "false"]).optional(),
        skinModel: z.enum(["classic", "slim"]).optional(),
        nickGradientFrom: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
        nickGradientTo: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
        roleGradientFrom: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
        roleGradientTo: z.string().regex(/^#[0-9A-Fa-f]{6}$/).optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      // Build update data
      const updateData: Record<string, unknown> = {};
      if (input.showHours !== undefined) updateData.showHours = input.showHours;
      if (input.showMcJoined !== undefined) updateData.showMcJoined = input.showMcJoined;
      if (input.showOnline !== undefined) updateData.showOnline = input.showOnline;
      if (input.showRank !== undefined) updateData.showRank = input.showRank;
      if (input.skinModel !== undefined) updateData.skinModel = input.skinModel;

      // Gradient permission check
      const hasGradientChange = input.nickGradientFrom !== undefined || input.nickGradientTo !== undefined ||
        input.roleGradientFrom !== undefined || input.roleGradientTo !== undefined;

      if (hasGradientChange) {
        const [modAccount] = await db
          .select()
          .from(modAccounts)
          .where(eq(modAccounts.discordId, discordId))
          .limit(1);

        const effectiveRole = computeEffectiveRole(modAccount?.licenseRoles, undefined, ctx.user?.role);
        const freeGradientRoles = ['owner', 'admin', 'qa', 'developer', 'sponsor_plus', 'sponsor_plusplus'];
        const canEditFree = freeGradientRoles.includes(effectiveRole);

        // Helper: check active subscription
        async function hasActiveSubscription(accountId: number): Promise<boolean> {
          const now = new Date();
          const [sub] = await db
            .select()
            .from(userEntitlements)
            .where(
              and(
                eq(userEntitlements.accountId, accountId),
                eq(userEntitlements.type, "subscription"),
                eq(userEntitlements.isActive, "true"),
                gt(userEntitlements.endsAt, now)
              )
            )
            .limit(1);
          return !!sub;
        }

        if (!canEditFree) {
          if (!modAccount) {
            throw new TRPCError({ code: "FORBIDDEN", message: "GRADIENT_PASS_REQUIRED" });
          }
          // Check for gradient_pass in inventory
          const [gradientPassItem] = await db
            .select()
            .from(storeItems)
            .where(eq(storeItems.sku, 'gradient_pass'))
            .limit(1);

          if (gradientPassItem) {
            const [owned] = await db
              .select()
              .from(userInventory)
              .where(
                and(
                  eq(userInventory.accountId, modAccount.id),
                  eq(userInventory.storeItemId, gradientPassItem.id),
                  eq(userInventory.isActive, "true")
                )
              )
              .limit(1);

            if (!owned) {
              throw new TRPCError({ code: "FORBIDDEN", message: "GRADIENT_PASS_REQUIRED" });
            }
          } else {
            throw new TRPCError({ code: "FORBIDDEN", message: "GRADIENT_PASS_REQUIRED" });
          }

          // Also require active subscription for base sponsor + gradient_pass users
          const hasSub = await hasActiveSubscription(modAccount.id);
          if (!hasSub) {
            throw new TRPCError({ code: "FORBIDDEN", message: "ACTIVE_SUBSCRIPTION_REQUIRED" });
          }
        }

        // 24h cooldown check (separate for nick and role)
        const [profile] = await db
          .select()
          .from(playerProfiles)
          .where(eq(playerProfiles.discordId, discordId))
          .limit(1);

        const hasNickChange = input.nickGradientFrom !== undefined || input.nickGradientTo !== undefined;
        const hasRoleChange = input.roleGradientFrom !== undefined || input.roleGradientTo !== undefined;

        if (hasNickChange && profile?.nickGradientEditedAt) {
          const lastEdit = new Date(profile.nickGradientEditedAt).getTime();
          const hoursSince = (Date.now() - lastEdit) / (1000 * 60 * 60);
          if (hoursSince < 24) {
            const hoursLeft = Math.ceil(24 - hoursSince);
            throw new TRPCError({
              code: "FORBIDDEN",
              message: `NICK_COOLDOWN_ACTIVE:${hoursLeft}`,
            });
          }
        }

        if (hasRoleChange && profile?.roleGradientEditedAt) {
          const lastEdit = new Date(profile.roleGradientEditedAt).getTime();
          const hoursSince = (Date.now() - lastEdit) / (1000 * 60 * 60);
          if (hoursSince < 24) {
            const hoursLeft = Math.ceil(24 - hoursSince);
            throw new TRPCError({
              code: "FORBIDDEN",
              message: `ROLE_COOLDOWN_ACTIVE:${hoursLeft}`,
            });
          }
        }

        if (input.nickGradientFrom !== undefined) updateData.nickGradientFrom = input.nickGradientFrom;
        if (input.nickGradientTo !== undefined) updateData.nickGradientTo = input.nickGradientTo;
        if (input.roleGradientFrom !== undefined) updateData.roleGradientFrom = input.roleGradientFrom;
        if (input.roleGradientTo !== undefined) updateData.roleGradientTo = input.roleGradientTo;
        if (hasNickChange) updateData.nickGradientEditedAt = new Date();
        if (hasRoleChange) updateData.roleGradientEditedAt = new Date();
      }

      // Check if profile exists
      const [existing] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, discordId))
        .limit(1);

      if (existing) {
        await db
          .update(playerProfiles)
          .set({ ...updateData, updatedAt: new Date() })
          .where(eq(playerProfiles.id, existing.id));
      } else {
        // Create profile with defaults + update data
        await db.insert(playerProfiles).values({
          discordId,
          displayName: ctx.user.name || "Player",
          role: "user",
          hoursPlayed: 0,
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
          skinUrl: null,
          skinModel: "classic",
          ...updateData,
        } as any);
      }

      return { success: true };
    }),

  // ─── Reset HWID (consume hwid_reset item, call License Server) ───
  resetHwid: authedQuery
    .mutation(async ({ ctx }) => {
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

      if (!account.accountKey) {
        throw new TRPCError({ code: "NOT_FOUND", message: "NO_LICENSE_KEY" });
      }

      // 24-hour rate limit per Discord account
      const [lastReset] = await db
        .select()
        .from(hwidLogs)
        .where(eq(hwidLogs.discordId, discordId))
        .orderBy(desc(hwidLogs.createdAt))
        .limit(1);

      if (lastReset) {
        const hoursSince = (Date.now() - lastReset.createdAt.getTime()) / (1000 * 60 * 60);
        if (hoursSince < 24) {
          const retryAfterSeconds = Math.ceil((24 * 60 * 60 * 1000 - (Date.now() - lastReset.createdAt.getTime())) / 1000);
          throw new TRPCError({ code: "TOO_MANY_REQUESTS", message: `HWID_RESET_COOLDOWN:${retryAfterSeconds}` });
        }
      }

      // Verify ownership of hwid_reset item
      const [hwidItem] = await db
        .select()
        .from(storeItems)
        .where(eq(storeItems.sku, 'hwid_reset'))
        .limit(1);

      if (!hwidItem) {
        throw new TRPCError({ code: "NOT_FOUND", message: "HWID_RESET_NOT_AVAILABLE" });
      }

      const [owned] = await db
        .select()
        .from(userInventory)
        .where(
          and(
            eq(userInventory.accountId, account.id),
            eq(userInventory.storeItemId, hwidItem.id),
            eq(userInventory.isActive, "true")
          )
        )
        .limit(1);

      if (!owned) {
        throw new TRPCError({ code: "FORBIDDEN", message: "HWID_RESET_REQUIRED" });
      }

      // Call License Server to reset HWID
      console.log("[profile.resetHwid] calling LS for account", account.accountId, "key", account.accountKey);
      const result = await resetHwidOnLicenseServer(account.accountKey);
      console.log("[profile.resetHwid] LS result:", result);
      if (!result.success) {
        throw new TRPCError({ code: "INTERNAL_SERVER_ERROR", message: result.message || "HWID_RESET_FAILED" });
      }

      // Update cached account key and clear HWID binding on the site side
      if (result.newAccountKey) {
        await db
          .update(modAccounts)
          .set({
            accountKey: result.newAccountKey,
            accountKeyEnc: encrypt(result.newAccountKey),
            accountKeyHash: hashAccountKey(result.newAccountKey),
            hwidHash: "",
            updatedAt: new Date(),
          })
          .where(eq(modAccounts.id, account.id));
      }

      // Ensure account_key store item exists (hidden from shop)
      const [accountKeyItem] = await db
        .select()
        .from(storeItems)
        .where(eq(storeItems.sku, 'account_key'))
        .limit(1);

      let accountKeyItemId = accountKeyItem?.id;
      if (!accountKeyItemId) {
        const [{ insertId }] = await db.insert(storeItems).values({
          sku: 'account_key',
          type: 'cosmetic',
          name: 'Ключ аккаунта',
          description: 'Файл восстановления Hypnosia-аккаунта',
          priceCents: 0,
          isActive: "false",
        });
        accountKeyItemId = Number(insertId);
      }

      // Consume the item, log, and grant account_key in a single DB transaction
      await db.transaction(async (tx) => {
        await tx
          .update(userInventory)
          .set({ isActive: "false" })
          .where(eq(userInventory.id, owned.id));

        await tx.insert(hwidLogs).values({
          discordId,
        });

        await tx
          .insert(userInventory)
          .values({
            accountId: account.id,
            storeItemId: accountKeyItemId,
            acquiredFrom: "admin",
            isActive: "true",
          })
          .onDuplicateKeyUpdate({
            set: { isActive: "true", acquiredAt: new Date() },
          });
      });

      return {
        success: true,
        newAccountKey: result.newAccountKey,
      };
    }),

  // ─── Download account-key .properties file ───
  downloadAccountKey: authedQuery
    .mutation(async ({ ctx }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .limit(1);

      if (!account || !account.accountKey) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_KEY_NOT_FOUND" });
      }

      const [accountKeyItem] = await db
        .select()
        .from(storeItems)
        .where(eq(storeItems.sku, 'account_key'))
        .limit(1);

      if (!accountKeyItem) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ACCOUNT_KEY_ITEM_NOT_CONFIGURED" });
      }

      const [owned] = await db
        .select()
        .from(userInventory)
        .where(
          and(
            eq(userInventory.accountId, account.id),
            eq(userInventory.storeItemId, accountKeyItem.id),
            eq(userInventory.isActive, "true")
          )
        )
        .limit(1);

      if (!owned) {
        throw new TRPCError({ code: "FORBIDDEN", message: "ACCOUNT_KEY_NOT_AVAILABLE" });
      }

      const content = `# Hypnosia account config.\naccount.key=${account.accountKey}\naccount.id=${account.accountId}\n`;
      return {
        content,
        filename: `account-${account.accountId}.properties`,
      };
    }),

  // ─── Update custom role name (sponsor++ only) ───
  updateCustomRoleName: authedQuery
    .input(
      z.object({
        customRoleName: z.string().min(1).max(16).optional().nullable(),
      })
    )
    .mutation(async ({ ctx, input }) => {
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

      // Verify sponsor++ role (or owner/admin)
      const effectiveRole = computeEffectiveRole(account.licenseRoles, undefined, ctx.user?.role);
      const allowedRoles = ['sponsor_plusplus', 'owner', 'admin'];
      if (!allowedRoles.includes(effectiveRole)) {
        throw new TRPCError({ code: "FORBIDDEN", message: "SPONSOR_PLUSPLUS_REQUIRED" });
      }

      if (input.customRoleName !== undefined && input.customRoleName !== null) {
        // Validation: letters, numbers, _ - [ ] ( )
        const valid = /^[a-zA-Z0-9_\-\[\]\(\)]{1,16}$/.test(input.customRoleName);
        if (!valid) {
          throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_ROLE_NAME_FORMAT" });
        }

        // Forbidden words filter (normalize by removing allowed special chars)
        const normalized = input.customRoleName.toLowerCase().replace(/[_\-\[\]\(\)]/g, '');
        const forbidden = ['admin', 'owner', 'moderator', 'модератор', 'админ', 'разработчик', 'developer', 'dev', 'hypnosia', 'nachosia', 'staff', 'support', 'qa'];
        for (const word of forbidden) {
          if (normalized.includes(word)) {
            throw new TRPCError({ code: "BAD_REQUEST", message: "FORBIDDEN_ROLE_NAME" });
          }
        }
      }

      const [existing] = await db
        .select()
        .from(playerProfiles)
        .where(eq(playerProfiles.discordId, discordId))
        .limit(1);

      if (existing) {
        await db
          .update(playerProfiles)
          .set({ customRoleName: input.customRoleName ?? null, updatedAt: new Date() })
          .where(eq(playerProfiles.id, existing.id));
      } else {
        await db.insert(playerProfiles).values({
          discordId,
          displayName: ctx.user.name || "Player",
          role: "user",
          hoursPlayed: 0,
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
          skinUrl: null,
          skinModel: "classic",
          customRoleName: input.customRoleName ?? null,
        } as any);
      }

      return { success: true };
    }),

  // ─── Get mod profile settings ───
  modSettings: authedQuery.query(async ({ ctx }) => {
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

    const [settings] = await db
      .select()
      .from(userProfileSettings)
      .where(eq(userProfileSettings.accountId, account.id))
      .limit(1);

    return settings ?? {
      accountId: account.id,
      selectedNicknameGradientId: null,
      selectedRoleGradientId: null,
      selectedProfileStyleId: null,
      selectedProfileBackgroundId: null,
      selectedBadgeId: null,
      showOnline: "true",
      showPlaytime: "true",
      showDiscord: "false",
      customStatus: null,
      profileBio: null,
      updatedAt: new Date(),
    };
  }),

  // ─── Update mod profile settings ───
  updateModSettings: authedQuery
    .input(
      z.object({
        showOnline: z.enum(["true", "false"]).optional(),
        showPlaytime: z.enum(["true", "false"]).optional(),
        showDiscord: z.enum(["true", "false"]).optional(),
        customStatus: z.string().max(128).optional().nullable(),
        profileBio: z.string().max(2000).optional().nullable(),
      })
    )
    .mutation(async ({ ctx, input }) => {
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

      const updateData: Record<string, unknown> = {};
      if (input.showOnline !== undefined) updateData.showOnline = input.showOnline;
      if (input.showPlaytime !== undefined) updateData.showPlaytime = input.showPlaytime;
      if (input.showDiscord !== undefined) updateData.showDiscord = input.showDiscord;
      if (input.customStatus !== undefined) updateData.customStatus = input.customStatus;
      if (input.profileBio !== undefined) updateData.profileBio = input.profileBio;

      await db
        .insert(userProfileSettings)
        .values({ accountId: account.id, ...updateData })
        .onDuplicateKeyUpdate({ set: updateData });

      return { success: true };
    }),

  // ─── Select cosmetic item (must own it) ───
  selectItem: authedQuery
    .input(
      z.object({
        storeItemId: z.number().int().positive(),
        slot: z.enum(["nickname_gradient", "role_gradient", "profile_style", "profile_background", "badge"]),
      })
    )
    .mutation(async ({ ctx, input }) => {
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

      // Verify ownership
      const [owned] = await db
        .select()
        .from(userInventory)
        .where(
          and(
            eq(userInventory.accountId, account.id),
            eq(userInventory.storeItemId, input.storeItemId),
            eq(userInventory.isActive, "true")
          )
        )
        .limit(1);

      if (!owned) {
        throw new TRPCError({ code: "FORBIDDEN", message: "ITEM_NOT_OWNED" });
      }

      // Verify item type matches slot
      const [item] = await db
        .select()
        .from(storeItems)
        .where(eq(storeItems.id, input.storeItemId))
        .limit(1);

      if (!item) {
        throw new TRPCError({ code: "NOT_FOUND", message: "ITEM_NOT_FOUND" });
      }

      const validTypes: Record<string, string[]> = {
        nickname_gradient: ["nickname_gradient"],
        role_gradient: ["role_gradient"],
        profile_style: ["profile_style"],
        profile_background: ["profile_background", "profile_style"],
        badge: ["profile_badge"],
      };

      if (!validTypes[input.slot]?.includes(item.type)) {
        throw new TRPCError({ code: "BAD_REQUEST", message: "INVALID_SLOT_FOR_ITEM" });
      }

      const fieldMap: Record<string, string> = {
        nickname_gradient: "selectedNicknameGradientId",
        role_gradient: "selectedRoleGradientId",
        profile_style: "selectedProfileStyleId",
        profile_background: "selectedProfileBackgroundId",
        badge: "selectedBadgeId",
      };

      const updateData: Record<string, unknown> = {
        [fieldMap[input.slot]]: input.storeItemId,
      };

      await db
        .insert(userProfileSettings)
        .values({ accountId: account.id, ...updateData })
        .onDuplicateKeyUpdate({ set: updateData });

      return { success: true };
    }),
});

export const topsRouter = createRouter({
  // Get leaderboard (uses mod_accounts + mod_playtime_daily)
  list: publicQuery
    .input(
      z.object({
        period: z.enum(["monthly", "alltime"]).default("monthly"),
        limit: z.number().min(1).max(100).default(50),
      })
    )
    .query(async ({ input }) => {
      const cacheKey = `tops:${input.period}:${input.limit}`;
      const cached = getCachedTops(cacheKey);
      if (cached) return cached;

      const db = getDb();

      // 1. Get playtime from rollup tables (fast, pre-aggregated)
      let playtimeRows: { accountId: number; totalMinutes: number }[] = [];
      if (input.period === "monthly") {
        const now = new Date();
        const yearMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;

        playtimeRows = await db
          .select({
            accountId: modPlaytimeMonthly.accountId,
            totalMinutes: modPlaytimeMonthly.minutes,
          })
          .from(modPlaytimeMonthly)
          .where(eq(modPlaytimeMonthly.yearMonth, yearMonth));
      } else {
        playtimeRows = await db
          .select({
            accountId: modPlaytimeAlltime.accountId,
            totalMinutes: modPlaytimeAlltime.minutes,
          })
          .from(modPlaytimeAlltime);
      }

      const playtimeMap = new Map(playtimeRows.map((r) => [r.accountId, r.totalMinutes]));

      // 2. Fetch non-banned and non-frozen mod_accounts (up to a safe limit)
      const allAccounts = await db
        .select()
        .from(modAccounts)
        .where(
          and(
            eq(modAccounts.playtimeBanned, "false"),
            eq(modAccounts.playtimeFrozen, "false")
          )
        )
        .limit(500);

      // 3. Get linked profiles for accounts with discordId
      const discordIds = allAccounts.map((a) => a.discordId).filter(Boolean) as string[];

      const profiles = discordIds.length > 0
        ? await db.select().from(playerProfiles).where(inArray(playerProfiles.discordId, discordIds))
        : [];
      const profileMap = new Map(profiles.map((p) => [p.discordId, p]));

      // 4. Merge, sort by playtime desc, take limit
      const merged = allAccounts
        .map((a) => {
          const profile = a.discordId ? profileMap.get(a.discordId) : undefined;
          const totalMinutes = playtimeMap.get(a.accountId) || 0;
          const effectiveRole = computeEffectiveRole(a?.licenseRoles, profile?.role, undefined);
          return {
            id: a.id,
            accountId: a.accountId,
            discordId: a.discordId ?? null,
            skinUrl: profile?.skinUrl ?? null,
            username: (a.displayName && a.displayName !== 'None' && a.displayName.trim() !== '') ? a.displayName : `Account #${a.accountId}`,
            totalMinutes,
            role: effectiveRole,
            allRoles: getSortedUniqueRoles(a?.licenseRoles),
            joined: a.createdAt ? new Date(a.createdAt).toISOString().slice(0, 10) : "—",
            isOnline: a.isOnline === "true",
            hasDiscordLink: !!a.discordId,
            nickGradientFrom: profile?.nickGradientFrom ?? null,
            nickGradientTo: profile?.nickGradientTo ?? null,
            roleGradientFrom: profile?.roleGradientFrom ?? null,
            roleGradientTo: profile?.roleGradientTo ?? null,
            customRoleName: profile?.customRoleName ?? null,
          };
        })
        .sort((a, b) => b.totalMinutes - a.totalMinutes)
        .slice(0, input.limit)
        .map((entry, i) => ({ ...entry, rank: i + 1 }));

      setCachedTops(cacheKey, merged);
      return merged;
    }),
});

export const statsRouter = createRouter({
  // Get site-wide stats for welcome page
  overview: publicQuery.query(async () => {
    const db = getDb();

    const [userCount] = await db
      .select({ count: sql<number>`COUNT(*)` })
      .from(modAccounts);

    const [hoursResult] = await db
      .select({ total: sql<number>`COALESCE(SUM(${modPlaytimeAlltime.minutes}), 0) / 60` })
      .from(modPlaytimeAlltime);

    const [onlineResult] = await db
      .select({ count: sql<number>`COUNT(*)` })
      .from(modAccounts)
      .where(eq(modAccounts.isOnline, "true"));

    const [configsResult] = await db
      .select({ value: siteStats.statValue })
      .from(siteStats)
      .where(eq(siteStats.statKey, "total_configs"))
      .limit(1);

    return {
      users: userCount.count,
      hours: Math.round((hoursResult.total || 0) * 10) / 10,
      online: onlineResult.count,
      configs: configsResult?.value ?? 0,
    };
  }),
});
