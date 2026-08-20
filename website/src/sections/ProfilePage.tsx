import { useParams, useNavigate } from 'react-router';
import { trpc } from '@/providers/trpc';
import SkinViewer3D from './SkinViewer3D';
import { roleColorMap, formatRoleName, getRoleIconPath } from '@/lib/roles';
import { formatPlaytime } from '@/lib/formatPlaytime';

const roleConfig: Record<string, { label: string; color: string }> = {
  owner: { label: 'Owner', color: '#9932CC' },
  admin: { label: 'Admin', color: '#FF6464' },
  moderator: { label: 'Moderator', color: '#3BA55D' },
  helper: { label: 'Helper', color: '#5865F2' },
  qa: { label: 'QA', color: '#C084FC' },
  developer: { label: 'Developer', color: '#80FF97' },
  sponsor_plusplus: { label: 'Sponsor [++]', color: '#FFD700' },
  sponsor_plus: { label: 'Sponsor [+]', color: '#6BB7FF' },
  sponsor: { label: 'Sponsor', color: '#80FF97' },
  vip: { label: 'VIP', color: '#6BB7FF' },
  user: { label: 'User', color: '#7A8A9E' },
};

export default function ProfilePage() {
  const params = useParams();
  const id = params.id;
  const navigate = useNavigate();

  const { data: profile, isLoading, error } = trpc.profile.getById.useQuery(
    { id: id ?? '' },
    { enabled: !!id, staleTime: 1000 * 60 * 2, retry: false }
  );

  const { data: activity } = trpc.profile.activity.useQuery(
    { discordId: profile?.discordId ?? '' },
    { enabled: !!profile?.discordId, staleTime: 1000 * 60 * 10 }
  );

  const { data: serverStats } = trpc.profile.serverStats.useQuery(
    { accountId: Number(profile?.accountId ?? profile?.id ?? 0) },
    { enabled: !!profile?.id && (profile as any)?.playtimeBanned !== true && (profile as any)?.playtimeFrozen !== true, staleTime: 1000 * 60 * 5 }
  );

  const displayRole = (profile as any)?.effectiveRole ?? profile?.role ?? 'user';
  const rc = profile ? (roleConfig[displayRole] ?? roleConfig.user) : null;
  const hasDiscordLink = (profile as any)?.hasDiscordLink !== false;
  const allRoles = ((profile as any)?.allRoles as string[] | undefined) ?? [displayRole];
  const roleDisplayNames = ((profile as any)?.roleDisplayNames as Record<string, string> | undefined) ?? {};

  const hasNickGradient = !!profile?.nickGradientFrom && !!profile?.nickGradientTo;
  const hasRoleGradient = !!profile?.roleGradientFrom && !!profile?.roleGradientTo;

  // Skin source: custom uploaded skin if set, otherwise the Steve placeholder.
  const hasSkin = !!profile?.skinUrl;

  return (
    <div className="relative min-h-screen" style={{ zIndex: 1 }}>
      <div className="fixed inset-0" style={{
        zIndex: 0,
        background: `radial-gradient(ellipse 80% 60% at 50% 0%, rgba(128, 255, 151, 0.06) 0%, transparent 60%),
          radial-gradient(ellipse 60% 50% at 80% 60%, rgba(107, 183, 255, 0.05) 0%, transparent 50%),
          linear-gradient(180deg, #0B0D12 0%, #0F1218 40%, #0B0D12 100%)`
      }} />

      <div className="relative mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 840, zIndex: 1 }}>
        {/* Back button */}
        <button onClick={() => navigate('/tops')} className="flex items-center gap-2 mb-8 font-body text-sm transition-colors duration-200 hover:text-vanta-text" style={{ color: '#7A8A9E' }}>
          <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M19 12H5M12 19l-7-7 7-7" /></svg>
          Назад к топу
        </button>

        {isLoading ? (
          <div className="text-center py-20">
            <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка...</p>
          </div>
        ) : error || !profile || !rc ? (
          <div className="text-center py-20">
            <p className="font-display text-2xl font-bold mb-3" style={{ color: '#E8E4E0' }}>Профиль не найден</p>
            <p className="font-body text-sm mb-2" style={{ color: '#7A8A9E' }}>Игрок с ID "{id}" не существует</p>
            <button onClick={() => navigate('/tops')} className="font-mono text-xs font-semibold uppercase tracking-[1px] px-6 py-3 rounded-lg transition-all duration-200 hover:scale-105" style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}>
              Вернуться к топу
            </button>
          </div>
        ) : (
          <>
            {/* Profile header — 3D skin left, name + status right */}
            <div className="flex flex-col md:flex-row items-start gap-6 mb-8">
              {/* 3D Skin Viewer — left */}
              <div className="flex-shrink-0 w-full md:w-auto flex justify-center md:justify-start">
                {hasSkin ? (
                  <SkinViewer3D
                    size={300}
                    skinUrl={profile.skinUrl ?? undefined}
                    skinModel={profile.skinModel === 'slim' ? 'slim' : 'classic'}
                  />
                ) : (
                  <div className="flex items-center justify-center rounded-2xl" style={{ width: 300, height: 300, background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <div className="text-center">
                      <img
                        src="/api/skin/face/steve.png"
                        alt="Steve"
                        className="mx-auto mb-3 rounded-sm"
                        style={{ width: 96, height: 96, imageRendering: 'pixelated' }}
                      />
                      <p className="font-mono text-xs" style={{ color: '#7A8A9E' }}>Steve</p>
                      {!hasDiscordLink && (
                        <p className="font-body text-[10px] mt-1" style={{ color: '#7A8A9E' }}>Discord не привязан</p>
                      )}
                    </div>
                  </div>
                )}
              </div>

              {/* Name + Role + Status + Quick stats */}
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-3 mb-2 flex-wrap">
                  <h1 className="font-display font-bold" style={{
                    fontSize: 'clamp(28px, 4vw, 40px)',
                    letterSpacing: '-1px',
                    background: hasNickGradient
                      ? `linear-gradient(135deg, ${profile.nickGradientFrom}, ${profile.nickGradientTo})`
                      : undefined,
                    backgroundClip: hasNickGradient ? 'text' : undefined,
                    WebkitBackgroundClip: hasNickGradient ? 'text' : undefined,
                    WebkitTextFillColor: hasNickGradient ? 'transparent' : undefined,
                    color: hasNickGradient ? 'transparent' : '#E8E4E0',
                  }}>
                    {profile.displayName}
                  </h1>

                  {/* Online / Offline status */}
                  <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full" style={{
                    background: profile.isOnline === 'true' ? 'rgba(128, 255, 151, 0.1)' : 'rgba(122, 138, 158, 0.1)',
                    border: `1px solid ${profile.isOnline === 'true' ? 'rgba(128, 255, 151, 0.25)' : 'rgba(122, 138, 158, 0.15)'}`,
                  }}>
                    <div className="rounded-full" style={{
                      width: 8, height: 8,
                      background: profile.isOnline === 'true' ? '#80FF97' : '#7A8A9E',
                      boxShadow: profile.isOnline === 'true' ? '0 0 6px rgba(128, 255, 151, 0.5)' : 'none',
                    }} />
                    <span className="font-mono text-xs" style={{ color: profile.isOnline === 'true' ? '#80FF97' : '#7A8A9E' }}>
                      {profile.isOnline === 'true' ? 'онлайн' : 'офлайн'}
                    </span>
                  </div>

                  {/* Banned badge */}
                  {(profile as any).playtimeBanned === true && (
                    <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full" style={{
                      background: 'rgba(255, 100, 100, 0.1)',
                      border: '1px solid rgba(255, 100, 100, 0.25)',
                    }}>
                      <span className="font-mono text-xs" style={{ color: '#FF6464' }}>
                        Статистика заблокирована
                      </span>
                    </div>
                  )}

                  {/* Frozen badge */}
                  {(profile as any).playtimeFrozen === true && (
                    <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full" style={{
                      background: 'rgba(255, 200, 50, 0.1)',
                      border: '1px solid rgba(255, 200, 50, 0.25)',
                    }}>
                      <span className="font-mono text-xs" style={{ color: '#FFC832' }}>
                        Аккаунт заморожен
                      </span>
                    </div>
                  )}
                </div>

                {/* All roles badges */}
                <div className="flex items-center gap-2 mb-4 flex-wrap">
                  {allRoles.map((role) => {
                    const r = roleConfig[role] ?? roleConfig.user;
                    const iconPath = getRoleIconPath(role);
                    return (
                      <span
                        key={role}
                        className="font-mono text-xs px-3 py-1 rounded-full flex items-center gap-1"
                        style={{
                          color: r.color,
                          background: `${r.color}15`,
                          border: `1px solid ${r.color}20`,
                        }}
                      >
                        {iconPath && (
                          <img src={iconPath} alt="" className="w-3.5 h-3.5 inline-block" style={{ imageRendering: 'pixelated' }} onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                        )}
                        {formatRoleName(role, (profile as any).customRoleName && role === displayRole ? (profile as any).customRoleName : null, roleDisplayNames)}
                      </span>
                    );
                  })}
                  <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>ID: {profile.accountId ?? profile.id}</span>
                  {(profile as any).accountId !== undefined && (
                    <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>Account: #{(profile as any).accountId}</span>
                  )}
                  {!hasDiscordLink && (
                    <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(122,138,158,0.15)', color: '#7A8A9E' }}>🔗 Discord не привязан</span>
                  )}
                </div>

                {/* Quick info grid */}
                <div className="grid grid-cols-2 gap-2">
                  {[
                    { label: 'TOTAL TIME', value: formatPlaytime((profile as any).totalMinutes ?? Math.round((profile.hoursPlayed || 0) * 60)), show: profile.showHours !== 'false' && (profile as any).playtimeBanned !== true && (profile as any).playtimeFrozen !== true },
                    { label: '7 DAYS', value: formatPlaytime((profile as any).weeklyMinutes ?? 0), show: profile.showHours !== 'false' && (profile as any).playtimeBanned !== true && (profile as any).playtimeFrozen !== true && ((profile as any).weeklyMinutes ?? 0) > 0 },
                    { label: 'JOINED', value: profile.mcJoined ?? '—', show: profile.showMcJoined !== 'false' },
                    { label: 'SITE JOINED', value: profile.siteJoined ? new Date(profile.siteJoined).toISOString().slice(0, 10) : '—', show: true },

                  ].filter(s => s.show).map((stat) => (
                    <div key={stat.label} className="rounded-lg px-3 py-2" style={{ background: 'rgba(11, 13, 18, 0.5)', border: '1px solid rgba(128, 255, 151, 0.06)' }}>
                      <p className="font-mono text-xs tracking-wide mb-0.5" style={{ color: '#7A8A9E' }}>{stat.label}</p>
                      <p className="font-display text-base font-bold" style={{ color: rc.color }}>{stat.value}</p>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {/* Activity section */}
            <div className="rounded-2xl p-6 mb-4" style={{ background: 'rgba(15, 18, 24, 0.4)', backdropFilter: 'blur(12px)', border: '1px solid rgba(128, 255, 151, 0.08)' }}>
              <p className="font-mono text-xs tracking-wide mb-4" style={{ color: '#7A8A9E' }}>LAST 7 DAYS ACTIVITY</p>
              {!activity || activity.length === 0 ? (
                <p className="font-mono text-xs" style={{ color: '#7A8A9E' }}>Нет данных</p>
              ) : (
                <div className="flex items-end gap-3" style={{ height: 100 }}>
                  {activity.map((day) => {
                    const maxH = Math.max(...activity.map((d) => d.hours), 1);
                    const pct = (day.hours / maxH) * 100;
                    return (
                      <div key={day.date} className="flex-1 flex flex-col items-center gap-1.5">
                        <span className="font-mono text-xs" style={{ color: rc.color, opacity: 0.7 }}>{day.hours}h</span>
                        <div className="w-full rounded-t relative overflow-hidden" style={{ height: `${Math.max(pct * 0.6, 4)}px` }}>
                          <div className="absolute inset-0" style={{ background: `linear-gradient(180deg, #80FF97 0%, #6BB7FF 100%)`, opacity: day.hours >= 1 ? 1 : 0.3 }} />
                        </div>
                        <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>{day.dayName}</span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Server stats section */}
            <div className="rounded-2xl p-6" style={{ background: 'rgba(15, 18, 24, 0.4)', backdropFilter: 'blur(12px)', border: '1px solid rgba(128, 255, 151, 0.08)' }}>
              <p className="font-mono text-xs tracking-wide mb-4" style={{ color: '#7A8A9E' }}>СТАТИСТИКА ПО СЕРВЕРАМ</p>
              {(profile as any).playtimeFrozen === true ? (
                <p className="font-mono text-xs" style={{ color: '#FFC832' }}>Статистика недоступна — аккаунт заморожен</p>
              ) : !serverStats || serverStats.playtimeBanned || serverStats.topServers.length === 0 ? (
                <p className="font-mono text-xs" style={{ color: '#7A8A9E' }}>Нет данных</p>
              ) : (
                <div className="flex flex-col gap-3">
                  {serverStats.topServers.map((srv, idx) => {
                    const maxMinutes = serverStats.topServers[0].totalMinutes || 1;
                    const pct = Math.max(5, Math.round((srv.totalMinutes / maxMinutes) * 100));
                    return (
                      <div key={srv.serverIp} className="flex items-center gap-3">
                        <span className="font-mono text-xs w-6 text-center" style={{ color: '#7A8A9E' }}>
                          {idx + 1}
                        </span>
                        <span className="font-body text-sm w-32 truncate" style={{ color: '#E8E4E0' }} title={srv.displayName || srv.serverIp}>
                          {srv.displayName || srv.serverIp}
                        </span>
                        <div className="flex-1 h-2 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,0.06)' }}>
                          <div
                            className="h-full rounded-full"
                            style={{
                              width: `${pct}%`,
                              background: 'linear-gradient(90deg, #80FF97 0%, #6BB7FF 100%)',
                            }}
                          />
                        </div>
                        <span className="font-mono text-xs w-20 text-right" style={{ color: '#7A8A9E' }}>
                          {formatPlaytime(srv.totalMinutes)}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
