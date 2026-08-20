import { useState } from 'react';
import { useNavigate } from 'react-router';
import { trpc } from '@/providers/trpc';
import { formatRoleName } from '@/lib/roles';
import { formatPlaytime } from '@/lib/formatPlaytime';
import TopThreePodium from '@/components/TopThreePodium';

type RoleKey = 'owner' | 'developer' | 'vip' | 'user' | 'sponsor' | 'sponsor_plus' | 'sponsor_plusplus' | 'qa' | 'admin' | 'moderator' | 'helper';

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

type TopPeriod = 'monthly' | 'alltime';

export default function TopsPage() {
  const navigate = useNavigate();
  const [searchId, setSearchId] = useState('');
  const [period, setPeriod] = useState<TopPeriod>('monthly');

  const { data: topsData, isLoading } = trpc.tops.list.useQuery(
    { period, limit: 50 },
    { staleTime: 1000 * 60 * 2 }
  );

  const handleSearch = () => {
    const key = searchId.toLowerCase().trim();
    if (key) navigate(`/profile/${key}`);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => { if (e.key === 'Enter') handleSearch(); };

  return (
    <div className="relative min-h-screen" style={{ zIndex: 1 }}>
      <div className="fixed inset-0" style={{ zIndex: 0, background: `radial-gradient(ellipse 80% 60% at 50% 0%, rgba(128, 255, 151, 0.06) 0%, transparent 60%), radial-gradient(ellipse 60% 50% at 80% 60%, rgba(107, 183, 255, 0.05) 0%, transparent 50%), linear-gradient(180deg, #0B0D12 0%, #0F1218 40%, #0B0D12 100%)` }} />

      <div className="relative mx-auto px-6 lg:px-12 pt-4 pb-6" style={{ maxWidth: 1000, zIndex: 1 }}>
        {/* Search bar — 57px below nav */}
        <div className="mb-6" style={{ marginTop: 57 }}>
          <div className="flex items-center gap-3 px-4 py-3 rounded-xl" style={{ background: 'rgba(15, 18, 24, 0.6)', backdropFilter: 'blur(12px)', border: '1px solid rgba(128, 255, 151, 0.12)' }}>
            <svg width={18} height={18} viewBox="0 0 24 24" fill="none"><circle cx="10" cy="10" r="7" stroke="#80FF97" strokeWidth="1.5" /><line x1="15" y1="15" x2="21" y2="21" stroke="#80FF97" strokeWidth="1.5" strokeLinecap="round" /></svg>
            <input
              type="text"
              placeholder="Введите Account ID, Discord ID или ник..."
              value={searchId}
              onChange={(e) => setSearchId(e.target.value)}
              onKeyDown={handleKeyDown}
              className="bg-transparent outline-none font-body text-sm w-full placeholder:text-vanta-muted"
              style={{ color: '#E8E4E0' }}
            />
            {searchId && (
              <button onClick={() => setSearchId('')} className="text-vanta-muted hover:text-vanta-text transition-colors">
                <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
              </button>
            )}
            <button
              onClick={handleSearch}
              className="font-mono text-xs font-semibold uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all duration-200 hover:scale-105"
              style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
            >
              Найти
            </button>
          </div>
        </div>

        {/* Top 3 Podium */}
        <TopThreePodium entries={topsData?.slice(0, 3) ?? []} />

        {/* Period Tabs — above the table */}
        <div className="flex items-center justify-center gap-2 mb-4">
          {([
            { key: 'monthly', label: 'Месяц' },
            { key: 'alltime', label: 'Всё время' },
          ] as const).map((tab) => (
            <button
              key={tab.key}
              onClick={() => setPeriod(tab.key)}
              className="font-mono text-xs font-semibold uppercase tracking-[1px] px-5 py-2 rounded-lg transition-all duration-200"
              style={{
                background: period === tab.key ? 'linear-gradient(135deg, #80FF97, #6BB7FF)' : 'rgba(128, 255, 151, 0.08)',
                color: period === tab.key ? '#0B0D12' : '#7A8A9E',
                transform: period === tab.key ? 'scale(1.02)' : 'scale(1)',
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Top 50 table */}
        <div className="rounded-xl overflow-hidden" style={{ background: 'rgba(15, 18, 24, 0.6)', backdropFilter: 'blur(12px)', border: '1px solid rgba(128, 255, 151, 0.1)' }}>
          <div className="grid gap-4 px-6 py-3 font-mono text-xs tracking-wide uppercase" style={{ gridTemplateColumns: '50px 1fr 80px 80px 80px 80px', color: '#7A8A9E', borderBottom: '1px solid rgba(128, 255, 151, 0.08)' }}>
            <span>#</span>
            <span>Player</span>
            <span className="text-right">Acc.ID</span>
            <span className="text-right">Time</span>
            <span className="text-right">Role</span>
            <span className="text-right">Joined</span>
          </div>

          {isLoading ? (
            <div className="px-6 py-12 text-center">
              <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка...</p>
            </div>
          ) : !topsData || topsData.length === 0 ? (
            <div className="px-6 py-12 text-center">
              <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Пока нет данных</p>
            </div>
          ) : (
            topsData.map((entry) => {
              const rc = roleConfig[entry.role] ?? roleConfig.user;
              const hasNickGradient = entry.nickGradientFrom && entry.nickGradientTo;
              const hasRoleGradient = entry.roleGradientFrom && entry.roleGradientTo;
              const canNavigate = entry.hasDiscordLink;
              const allRoles = (entry as any).allRoles as string[] | undefined;
              const primaryRole = entry.role;
              const extraRoles = (allRoles ?? []).filter(r => r !== primaryRole);

              const skinAvatar = entry.skinUrl
                ? `/api/skin/face/${entry.skinUrl.split('/').pop()}`
                : '/api/skin/face/steve.png';

              return (
                <div
                  key={entry.rank}
                  className="grid gap-4 px-6 py-3 items-center transition-colors duration-150 hover:bg-white/5"
                  style={{ gridTemplateColumns: '50px 1fr 80px 80px 80px 80px', borderBottom: '1px solid rgba(128, 255, 151, 0.03)', cursor: canNavigate ? 'pointer' : 'default' }}
                  onClick={() => canNavigate && navigate(`/profile/${entry.accountId}`)}
                  title={canNavigate ? '' : 'Игрок не привязал Discord. Профиль недоступен.'}
                >
                  <span className="font-display text-sm font-bold" style={{ color: entry.rank === 1 ? '#80FF97' : entry.rank === 2 ? '#6BB7FF' : entry.rank === 3 ? '#7A8A9E' : '#7A8A9E', opacity: entry.rank > 3 ? 0.5 : 1 }}>
                    {entry.rank}
                  </span>
                  <div className="flex items-center gap-3">
                    <div className="rounded-sm overflow-hidden flex-shrink-0" style={{ width: 28, height: 28 }}>
                      <img
                        src={skinAvatar}
                        alt=""
                        className="w-full h-full"
                        style={{ objectFit: 'cover', imageRendering: 'pixelated' }}
                        loading="lazy"
                        onError={(e) => { (e.target as HTMLImageElement).src = '/api/skin/face/steve.png'; }}
                      />
                    </div>
                    <span
                      className="font-body text-sm font-medium"
                      style={hasNickGradient ? {
                        display: 'inline-block',
                        backgroundImage: `linear-gradient(135deg, ${entry.nickGradientFrom}, ${entry.nickGradientTo})`,
                        backgroundClip: 'text',
                        WebkitBackgroundClip: 'text',
                        WebkitTextFillColor: 'transparent',
                        color: 'transparent',
                        opacity: canNavigate ? 1 : 0.5,
                      } : { color: '#E8E4E0', opacity: canNavigate ? 1 : 0.5 }}
                    >
                      {entry.username}
                      {!canNavigate && <span className="font-mono text-[9px] ml-1.5 px-1 py-0.5 rounded" style={{ background: 'rgba(122,138,158,0.15)', color: '#7A8A9E' }}>🔗</span>}
                    </span>
                  </div>
                  <span className="font-mono text-xs text-right" style={{ color: '#7A8A9E' }}>#{entry.accountId}</span>
                  <span className="font-mono text-sm text-right" style={{ color: '#E8E4E0' }}>{formatPlaytime(entry.totalMinutes)}</span>
                  <div className="flex items-center justify-end gap-1">
                    <span
                      className="font-mono text-xs px-2 py-1 rounded"
                      style={hasRoleGradient ? {
                        color: rc.color,
                        backgroundImage: `linear-gradient(135deg, ${entry.roleGradientFrom}20, ${entry.roleGradientTo}20)`,
                        border: `1px solid ${rc.color}20`,
                      } : {
                        color: rc.color,
                        background: `${rc.color}10`,
                      }}
                    >
                      {formatRoleName(entry.role, (entry as any).customRoleName)}
                    </span>
                    {extraRoles.length === 1 && (
                      <span className="font-mono text-[9px] px-1.5 py-1 rounded" style={{ background: 'rgba(122,138,158,0.15)', color: '#7A8A9E' }} title={extraRoles.map(r => roleConfig[r]?.label ?? r).join(', ')}>
                        [+]
                      </span>
                    )}
                    {extraRoles.length === 2 && (
                      <span className="font-mono text-[9px] px-1.5 py-1 rounded" style={{ background: 'rgba(122,138,158,0.15)', color: '#7A8A9E' }} title={extraRoles.map(r => roleConfig[r]?.label ?? r).join(', ')}>
                        [++]
                      </span>
                    )}
                    {extraRoles.length >= 3 && (
                      <span className="font-mono text-[9px] px-1.5 py-1 rounded" style={{ background: 'rgba(122,138,158,0.15)', color: '#7A8A9E' }} title={extraRoles.map(r => roleConfig[r]?.label ?? r).join(', ')}>
                        [{extraRoles.length}]
                      </span>
                    )}
                  </div>
                  <span className="font-mono text-xs text-right" style={{ color: '#7A8A9E' }}>{entry.joined}</span>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
