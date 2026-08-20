import { useNavigate } from 'react-router';
import { formatPlaytime } from '@/lib/formatPlaytime';
import { formatRoleName, getRoleIconPath } from '@/lib/roles';
import SkinPreview3D from './SkinPreview3D';

interface PodiumEntry {
  rank: number;
  id: number;
  accountId: number;
  discordId: string | null;
  skinUrl: string | null;
  username: string;
  totalMinutes: number;
  role: string;
  allRoles?: string[];
  isOnline: boolean;
  hasDiscordLink: boolean;
  nickGradientFrom: string | null;
  nickGradientTo: string | null;
  roleGradientFrom: string | null;
  roleGradientTo: string | null;
  customRoleName: string | null;
}

interface Props {
  entries: PodiumEntry[];
}

const PLACE_ORDER: Record<number, number> = {
  1: 2,
  2: 1,
  3: 3,
};

const PLACE_DELAY: Record<number, number> = {
  1: 0.15,
  2: 0,
  3: 0.3,
};

const PLACE_CONFIG: Record<number, { skinSize: number; minCardWidth: number; platformWidth: number; baseWidth: number }> = {
  1: { skinSize: 150, minCardWidth: 160, platformWidth: 170, baseWidth: 176 },
  2: { skinSize: 120, minCardWidth: 140, platformWidth: 150, baseWidth: 156 },
  3: { skinSize: 120, minCardWidth: 140, platformWidth: 150, baseWidth: 156 },
};

const PLACE_OFFSET: Record<number, number> = {
  1: -56,
  2: 0,
  3: 40,
};

const PARTICLES = [
  { top: 25, left: 42, size: 3, color: '#80FF97', opacity: 0.5, anim: 'particleFloat1', duration: 4, delay: 0 },
  { top: 35, left: 55, size: 2, color: '#6BB7FF', opacity: 0.4, anim: 'particleFloat2', duration: 5, delay: 0.5 },
  { top: 20, left: 48, size: 4, color: '#80FF97', opacity: 0.6, anim: 'particleFloat3', duration: 3.5, delay: 1 },
  { top: 45, left: 38, size: 2, color: '#6BB7FF', opacity: 0.35, anim: 'particleFloat1', duration: 4.5, delay: 1.5 },
  { top: 30, left: 60, size: 3, color: '#80FF97', opacity: 0.45, anim: 'particleFloat2', duration: 5.5, delay: 0.8 },
  { top: 40, left: 50, size: 2, color: '#6BB7FF', opacity: 0.5, anim: 'particleFloat3', duration: 4, delay: 2 },
  { top: 28, left: 45, size: 3, color: '#80FF97', opacity: 0.55, anim: 'particleFloat1', duration: 3, delay: 0.3 },
  { top: 38, left: 52, size: 2, color: '#6BB7FF', opacity: 0.4, anim: 'particleFloat2', duration: 6, delay: 1.2 },
];

function PlaceholderEntry(rank: number): PodiumEntry {
  return {
    rank,
    id: 0,
    accountId: 0,
    discordId: null,
    skinUrl: null,
    username: '—',
    totalMinutes: 0,
    role: '—',
    allRoles: [],
    isOnline: false,
    hasDiscordLink: false,
    nickGradientFrom: null,
    nickGradientTo: null,
    roleGradientFrom: null,
    roleGradientTo: null,
    customRoleName: null,
  };
}

export default function TopThreePodium({ entries }: Props) {
  const navigate = useNavigate();
  const slotRanks = [2, 1, 3];

  const getEntryForRank = (rank: number): PodiumEntry => {
    return entries.find((e) => e.rank === rank) ?? PlaceholderEntry(rank);
  };

  return (
    <div className="podium-wrapper relative mb-10" style={{ minHeight: 420 }}>
      <style>{`
        @keyframes podiumEntry {
          from { opacity: 0; transform: translateY(60px); }
          to { opacity: 1; transform: translateY(0); }
        }
        @keyframes podiumGlowPulse {
          0%, 100% { opacity: 0.6; transform: translate(-50%, -50%) scale(1); }
          50% { opacity: 1; transform: translate(-50%, -50%) scale(1.15); }
        }
        @keyframes glowFadeIn {
          from { opacity: 0; }
          to { opacity: 0.6; }
        }
        @keyframes particleFloat1 {
          0%, 100% { transform: translate(0, 0); }
          25% { transform: translate(12px, -18px); }
          50% { transform: translate(-8px, -28px); }
          75% { transform: translate(-14px, -12px); }
        }
        @keyframes particleFloat2 {
          0%, 100% { transform: translate(0, 0); }
          33% { transform: translate(-15px, -22px); }
          66% { transform: translate(10px, -10px); }
        }
        @keyframes particleFloat3 {
          0%, 100% { transform: translate(0, 0); }
          50% { transform: translate(18px, -20px); }
        }
        .podium-glow {
          animation: glowFadeIn 1s ease-out 0.5s forwards, podiumGlowPulse 4s ease-in-out infinite 1.5s;
          opacity: 0;
        }
        .podium-card {
          animation: podiumEntry 0.6s ease-out forwards;
          opacity: 0;
        }
        @media (max-width: 767px) {
          .podium-container { flex-direction: column !important; align-items: center !important; gap: 32px !important; }
          .podium-card { order: 0 !important; transform: none !important; }
          .podium-card.first { order: -1 !important; }
          .podium-glow { width: 250px !important; height: 250px !important; }
        }
      `}</style>

      {/* Glow behind top-1 */}
      <div
        className="podium-glow"
        style={{
          position: 'absolute',
          width: 400,
          height: 400,
          borderRadius: '50%',
          background: 'radial-gradient(circle, rgba(128, 255, 151, 0.15) 0%, rgba(107, 183, 255, 0.08) 40%, transparent 70%)',
          filter: 'blur(40px)',
          pointerEvents: 'none',
          zIndex: 0,
          top: '30%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
        }}
      />

      {/* Rays */}
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', zIndex: 0, overflow: 'hidden' }}>
        {[0, 60, 120, 180, 240, 300].map((deg) => (
          <div
            key={deg}
            style={{
              position: 'absolute',
              top: '30%',
              left: '50%',
              width: 280,
              height: 1,
              background: 'linear-gradient(90deg, transparent 0%, rgba(128, 255, 151, 0.18) 25%, rgba(107, 183, 255, 0.1) 50%, transparent 100%)',
              transform: `translate(-50%, -50%) rotate(${deg}deg)`,
              transformOrigin: 'center',
            }}
          />
        ))}
      </div>

      {/* Particles */}
      <div style={{ position: 'absolute', inset: 0, pointerEvents: 'none', zIndex: 0, overflow: 'hidden' }}>
        {PARTICLES.map((p, i) => (
          <div
            key={i}
            style={{
              position: 'absolute',
              width: p.size,
              height: p.size,
              borderRadius: '50%',
              background: p.color,
              opacity: p.opacity,
              top: `${p.top}%`,
              left: `${p.left}%`,
              animation: `${p.anim} ${p.duration}s ease-in-out infinite`,
              animationDelay: `${p.delay}s`,
            }}
          />
        ))}
      </div>

      {/* Podium */}
      <div className="podium-container relative flex items-start justify-center gap-10 px-5 pt-10 pb-6" style={{ zIndex: 1 }}>
        {slotRanks.map((rank) => {
          const entry = getEntryForRank(rank);
          const order = PLACE_ORDER[rank] ?? rank;
          const delay = PLACE_DELAY[rank] ?? 0;
          const isReal = entry.id !== 0;
          const canNavigate = isReal && entry.hasDiscordLink;
          const hasNickGradient = entry.nickGradientFrom && entry.nickGradientTo;
          const hasRoleGradient = entry.roleGradientFrom && entry.roleGradientTo;

          const isFirst = rank === 1;
          const cfg = PLACE_CONFIG[rank];

          const iconPath = getRoleIconPath(entry.role);

          const faceUrl = entry.skinUrl
            ? `/api/skin/face/${entry.skinUrl.split('/').pop()}`
            : '/api/skin/face/steve.png';

          // ── Compact card (column layout, 8px padding) ──
          const cardEl = (
            <div
              className="relative"
              style={{
                minWidth: cfg.minCardWidth,
                width: 'max-content',
                maxWidth: 220,
                padding: 8,
                borderRadius: 0,
                background: 'rgba(26, 26, 26, 0.8)',
                border: hasRoleGradient
                  ? '2px solid transparent'
                  : '1px solid rgba(255,255,255,0.1)',
                backgroundClip: hasRoleGradient ? 'padding-box' : undefined,
              }}
            >
              {hasRoleGradient && (
                <div
                  style={{
                    position: 'absolute',
                    inset: -2,
                    borderRadius: 0,
                    background: `linear-gradient(135deg, ${entry.roleGradientFrom}, ${entry.roleGradientTo})`,
                    zIndex: -1,
                  }}
                />
              )}

              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {/* Row: face + nickname + role/ID */}
                <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                  {/* Face 32×32 */}
                  <img
                    src={faceUrl}
                    width={32}
                    height={32}
                    style={{
                      borderRadius: 4,
                      imageRendering: 'pixelated',
                      flexShrink: 0,
                      display: 'block',
                      opacity: isReal ? 1 : 0.4,
                    }}
                    onError={(e) => { (e.target as HTMLImageElement).src = '/api/skin/face/steve.png'; }}
                  />

                  {/* Nickname + Role/ID */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                    <button
                      onClick={() => canNavigate && navigate(`/profile/${entry.id}`)}
                      className="font-body font-bold text-left"
                      style={{
                        fontSize: 14,
                        whiteSpace: 'nowrap',
                        cursor: canNavigate ? 'pointer' : 'default',
                        ...(hasNickGradient
                          ? {
                              backgroundImage: `linear-gradient(135deg, ${entry.nickGradientFrom}, ${entry.nickGradientTo})`,
                              backgroundClip: 'text',
                              WebkitBackgroundClip: 'text',
                              WebkitTextFillColor: 'transparent',
                              color: 'transparent',
                            }
                          : { color: isReal ? '#E8E4E0' : '#7A8A9E', opacity: isReal ? 1 : 0.5 }),
                      }}
                      title={canNavigate ? '' : 'Игрок не привязал Discord'}
                    >
                      {entry.username}
                    </button>

                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span
                        className="font-mono flex items-center gap-1"
                        style={{ fontSize: 10, color: '#7A8A9E', opacity: isReal ? 1 : 0.4 }}
                      >
                        {iconPath && (
                          <img src={iconPath} alt="" width={10} height={10} style={{ imageRendering: 'pixelated' }} onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                        )}
                        {isReal ? formatRoleName(entry.role, entry.customRoleName) : '—'}
                      </span>
                      <span className="font-mono" style={{ fontSize: 10, color: '#6BB7FF', opacity: isReal ? 0.8 : 0.4 }}>
                        ID: {isReal ? entry.accountId : '—'}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Playtime — under the face, left-aligned */}
                <span className="font-mono font-semibold" style={{ fontSize: 12, color: '#80FF97', opacity: isReal ? 1 : 0.4 }}>
                  {isReal ? formatPlaytime(entry.totalMinutes) : '0м'}
                </span>
              </div>
            </div>
          );

          // ── 3D Skin ──
          const skinEl = (
            <div style={{ width: cfg.skinSize, height: Math.round(cfg.skinSize * 1.33), position: 'relative', zIndex: 2 }}>
              <SkinPreview3D
                size={cfg.skinSize}
                skinUrl={entry.skinUrl ?? undefined}
              />
            </div>
          );

          return (
            <div
              key={rank}
              className={`podium-card ${isFirst ? 'first' : ''}`}
              style={{
                order,
                animationDelay: `${delay}s`,
                transform: `translateY(${PLACE_OFFSET[rank]}px)`,
              }}
            >
              <div className="flex flex-col items-center" style={{ position: 'relative' }}>
                {/* 3D Skin */}
                {skinEl}

                {/* Card */}
                <div style={{ marginTop: 12 }}>
                  {cardEl}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
