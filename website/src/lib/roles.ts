export const roleFormatMap: Record<string, string> = {
  owner: 'Owner',
  admin: 'Admin',
  moderator: 'Moderator',
  helper: 'Helper',
  qa: 'QA',
  developer: 'Developer',
  sponsor_plusplus: 'Sponsor [++]',
  sponsor_plus_plus: 'Sponsor [++]',
  sponsor_plus: 'Sponsor [+]',
  sponsor: 'Sponsor',
  vip: 'VIP',
  user: 'User',
  SPONSOR: 'Sponsor',
  SPONSOR_PLUS: 'Sponsor [+]',
  SPONSOR_PLUSPLUS: 'Sponsor [++]',
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MODERATOR: 'Moderator',
  HELPER: 'Helper',
  QA: 'QA',
  SLIHA: 'Developer',
  DEV: 'Developer',
  DEVELOPER: 'Developer',
  VIP: 'VIP',
  USER: 'User',
};

export const roleColorMap: Record<string, string> = {
  owner: '#9932CC',
  admin: '#FF6464',
  moderator: '#3BA55D',
  helper: '#5865F2',
  qa: '#C084FC',
  developer: '#80FF97',
  sponsor_plusplus: '#FFD700',
  sponsor_plus_plus: '#FFD700',
  sponsor_plus: '#6BB7FF',
  sponsor: '#80FF97',
  vip: '#6BB7FF',
  user: '#7A8A9E',
};

export function formatRoleName(role: string | undefined, customName?: string | null, displayNames?: Record<string, string> | null): string {
  if (customName) return customName;
  if (displayNames && displayNames[role ?? '']) return displayNames[role ?? ''];
  return roleFormatMap[role ?? ''] ?? role ?? 'User';
}

/**
 * Map normalized role name to local icon path.
 * Returns null if no icon is available for the role.
 */
export function getRoleIconPath(role: string | undefined): string | null {
  const map: Record<string, string> = {
    owner: '/icons/role_owner.png',
    admin: '/icons/role_admin.png',
    moderator: '/icons/role_moderator.png',
    helper: '/icons/role_helper.png',
    qa: '/icons/role_qa.png',
    sponsor: '/icons/role_sponsor.png',
    sponsor_plus: '/icons/role_sponsor.png',
    sponsor_plusplus: '/icons/role_sponsor.png',
    sponsor_plus_plus: '/icons/role_sponsor.png',
  };
  return map[role ?? ''] ?? null;
}
