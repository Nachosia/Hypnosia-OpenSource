// License role priority (highest to lowest)
export const ROLE_PRIORITY = [
  "OWNER",
  "ADMIN",
  "MODERATOR",
  "HELPER",
  "QA",
  "SLIHA",
  "DEVELOPER",
  "DEV",
  "SPONSOR++",
  "SPONSOR+",
  "SPONSOR",
  "VIP",
  "USER",
];

export function normalizeRole(role?: string): string {
  if (!role) return "user";
  const r = role.toLowerCase();
  if (r === "owner") return "owner";
  if (r === "admin") return "admin";
  if (r === "moderator" || r === "moder" || r === "mod") return "moderator";
  if (r === "helper" || r === "help") return "helper";
  if (r === "developer" || r === "dev" || r === "sliha") return "developer";
  if (r === "vip") return "vip";
  if (r === "sponsor_plusplus" || r === "sponsor_plus_plus" || r === "sponsor++") return "sponsor_plusplus";
  if (r === "sponsor_plus" || r === "sponsor+") return "sponsor_plus";
  if (r === "sponsor") return "sponsor";
  if (r === "qa") return "qa";
  return "user";
}

export function pickHighestLicenseRole(roles?: string[]): string | undefined {
  if (!roles || roles.length === 0) return undefined;
  const upperRoles = roles.map((r) => r.toUpperCase());
  for (const pri of ROLE_PRIORITY) {
    const found = upperRoles.find((r) => r === pri || r.startsWith(pri));
    if (found) return found;
  }
  return upperRoles[0];
}

export const roleFormatMap: Record<string, string> = {
  owner: "Owner",
  admin: "Admin",
  moderator: "Moderator",
  helper: "Helper",
  qa: "QA",
  developer: "Developer",
  sponsor_plusplus: "Sponsor [++]",
  sponsor_plus: "Sponsor [+]",
  sponsor: "Sponsor",
  vip: "VIP",
  user: "User",
  SPONSOR: "Sponsor",
  SPONSOR_PLUS: "Sponsor [+]",
  SPONSOR_PLUS_PLUS: "Sponsor [++]",
  OWNER: "Owner",
  ADMIN: "Admin",
  MODERATOR: "Moderator",
  HELPER: "Helper",
  QA: "QA",
  SLIHA: "Developer",
  DEV: "Developer",
  DEVELOPER: "Developer",
  VIP: "VIP",
  USER: "User",
};

export function formatRoleName(role?: string, customName?: string | null): string {
  if (customName) return customName;
  return roleFormatMap[role ?? ""] ?? role ?? "User";
}

/**
 * Normalize, deduplicate, sort by priority, and drop "user" when higher roles exist.
 */
export function getSortedUniqueRoles(roles?: string[]): string[] {
  if (!roles || roles.length === 0) return [];
  const normalized = roles.map(normalizeRole).filter(Boolean);
  const unique = Array.from(new Set(normalized));
  const hasHigher = unique.some((r) => r !== "user");
  const filtered = hasHigher ? unique.filter((r) => r !== "user") : unique;
  const priorityMap = new Map<string, number>(
    ROLE_PRIORITY.map((r, i) => [r.toLowerCase().replace(/\+\+/g, "_plusplus").replace(/\+/g, "_plus"), i])
  );
  return filtered.sort((a, b) => {
    const pa = priorityMap.get(a) ?? 999;
    const pb = priorityMap.get(b) ?? 999;
    return pa - pb;
  });
}

export function computeEffectiveRole(
  licenseRoles?: string[],
  profileRole?: string,
  userRole?: string
): string {
  // Priority 1: player_profiles.role (if set and not defaulting to stale data)
  if (profileRole && profileRole !== "user") {
    return profileRole;
  }
  // Priority 2: mod_accounts.license_roles -> highest priority role mapped to site role
  if (licenseRoles && licenseRoles.length > 0) {
    const highest = pickHighestLicenseRole(licenseRoles);
    return normalizeRole(highest);
  }
  // Priority 3: users.role
  return userRole || "user";
}
