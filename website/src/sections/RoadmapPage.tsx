import { useState } from 'react';
import { trpc } from '@/providers/trpc';

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string; border: string; dot: string }> = {
  completed: { label: 'Выполнено', color: '#80FF97', bg: 'rgba(128,255,151,0.08)', border: 'rgba(128,255,151,0.2)', dot: '#80FF97' },
  in_progress: { label: 'В разработке', color: '#FFD700', bg: 'rgba(255,215,0,0.08)', border: 'rgba(255,215,0,0.2)', dot: '#FFD700' },
  planned: { label: 'Запланировано', color: '#7A8A9E', bg: 'rgba(122,138,158,0.08)', border: 'rgba(122,138,158,0.15)', dot: '#7A8A9E' },
  cancelled: { label: 'Отменено', color: '#ff6464', bg: 'rgba(255,100,100,0.08)', border: 'rgba(255,100,100,0.15)', dot: '#ff6464' },
};

export default function RoadmapPage() {
  const { data, isLoading } = trpc.roadmap.list.useQuery();
  const [selectedVersion, setSelectedVersion] = useState<string | 'all'>('all');

  const versions = data?.versions ?? [];
  const items = data?.items ?? [];

  const versionNames = versions.map(v => v.name);
  const filtered = selectedVersion === 'all' ? items : items.filter((i) => i.version === selectedVersion);

  // Group by version for display, respecting version order
  const grouped: { version: string; items: typeof filtered }[] = [];
  for (const v of versions) {
    const vItems = filtered.filter(i => i.version === v.name).sort((a, b) => a.orderIndex - b.orderIndex);
    if (vItems.length > 0 || selectedVersion === 'all') {
      grouped.push({ version: v.name, items: vItems });
    }
  }
  // If filtered version is not in versions table yet, append at end
  const groupedNames = new Set(grouped.map(g => g.version));
  for (const item of filtered) {
    if (!groupedNames.has(item.version)) {
      grouped.push({ version: item.version, items: filtered.filter(i => i.version === item.version).sort((a, b) => a.orderIndex - b.orderIndex) });
      groupedNames.add(item.version);
    }
  }

  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 1200 }}>
        {/* Header */}
        <div className="text-center mb-12">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// ROADMAP</p>
          <h1 className="font-display font-bold mb-4" style={{ fontSize: 'clamp(32px, 6vw, 56px)', color: '#E8E4E0' }}>Development Roadmap</h1>
          <p className="font-body text-lg max-w-lg mx-auto" style={{ color: '#7A8A9E' }}>
            Путь развития Hypnosia — от бета-версии до релиза
          </p>
        </div>

        {/* Version filter */}
        <div className="flex items-center justify-center gap-2 mb-12 flex-wrap">
          <button
            onClick={() => setSelectedVersion('all')}
            className="font-mono text-xs uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all"
            style={{
              background: selectedVersion === 'all' ? 'linear-gradient(135deg, #80FF97, #6BB7FF)' : 'rgba(255,255,255,0.03)',
              color: selectedVersion === 'all' ? '#0B0D12' : '#7A8A9E',
              border: `1px solid ${selectedVersion === 'all' ? 'rgba(128,255,151,0.3)' : 'rgba(255,255,255,0.06)'}`,
            }}
          >
            Все версии
          </button>
          {versionNames.map((v) => (
            <button
              key={v}
              onClick={() => setSelectedVersion(v)}
              className="font-mono text-xs uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all"
              style={{
                background: selectedVersion === v ? 'linear-gradient(135deg, #80FF97, #6BB7FF)' : 'rgba(255,255,255,0.03)',
                color: selectedVersion === v ? '#0B0D12' : '#7A8A9E',
                border: `1px solid ${selectedVersion === v ? 'rgba(128,255,151,0.3)' : 'rgba(255,255,255,0.06)'}`,
              }}
            >
              {v}
            </button>
          ))}
        </div>

        {/* Snake Roadmap */}
        {isLoading ? (
          <div className="text-center py-20">
            <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка...</p>
          </div>
        ) : (
          <div className="space-y-16">
            {grouped.map(({ version, items: versionItems }) => (
              <div key={version}>
                {/* Version header */}
                <div className="flex items-center gap-4 mb-8">
                  <div className="h-px flex-1" style={{ background: 'linear-gradient(90deg, transparent, rgba(128,255,151,0.3))' }} />
                  <span className="font-mono text-sm font-semibold tracking-[2px]" style={{ color: '#80FF97' }}>{version}</span>
                  <div className="h-px flex-1" style={{ background: 'linear-gradient(90deg, rgba(128,255,151,0.3), transparent)' }} />
                </div>

                {/* Snake grid */}
                <div className="relative">
                  {/* Connection line background */}
                  <SnakePath itemCount={versionItems.length} />

                  {/* Items */}
                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 relative">
                    {versionItems.map((item, idx) => {
                      const cfg = STATUS_CONFIG[item.status] ?? STATUS_CONFIG.planned;
                      return (
                        <div
                          key={item.id}
                          className="relative rounded-xl p-5 transition-all duration-300 hover:scale-[1.02]"
                          style={{
                            background: cfg.bg,
                            border: `1px solid ${cfg.border}`,
                            marginTop: idx % 2 === 1 ? '2rem' : '0',
                          }}
                        >
                          {/* Status dot */}
                          <div className="flex items-center gap-2 mb-3">
                            <div
                              className="w-3 h-3 rounded-full"
                              style={{
                                background: cfg.dot,
                                boxShadow: item.status === 'in_progress' ? `0 0 8px ${cfg.dot}` : 'none',
                                animation: item.status === 'in_progress' ? 'pulse 2s ease-in-out infinite' : 'none',
                              }}
                            />
                            <span className="font-mono text-[10px] uppercase tracking-[1px]" style={{ color: cfg.color }}>
                              {cfg.label}
                            </span>
                            {item.statusChangedAt && (
                              <span className="font-mono text-[9px] ml-auto" style={{ color: '#7A8A9E' }}>
                                {new Date(item.statusChangedAt).toLocaleDateString('ru-RU')}
                              </span>
                            )}
                          </div>

                          {/* Content */}
                          <h3 className="font-display font-semibold text-base mb-2" style={{ color: '#E8E4E0' }}>
                            {item.title}
                          </h3>
                          {item.description && (
                            <p className="font-body text-xs leading-relaxed" style={{ color: '#7A8A9E' }}>
                              {item.description}
                            </p>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Legend */}
        <div className="mt-16 flex items-center justify-center gap-6 flex-wrap">
          {Object.entries(STATUS_CONFIG).map(([key, cfg]) => (
            <div key={key} className="flex items-center gap-2">
              <div className="w-2.5 h-2.5 rounded-full" style={{ background: cfg.dot }} />
              <span className="font-mono text-[10px]" style={{ color: cfg.color }}>{cfg.label}</span>
            </div>
          ))}
        </div>
      </div>

      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; transform: scale(1); }
          50% { opacity: 0.6; transform: scale(1.2); }
        }
      `}</style>
    </div>
  );
}

// Snake connection path component
function SnakePath({ itemCount }: { itemCount: number }) {
  if (itemCount <= 1) return null;
  
  const rows = Math.ceil(itemCount / 3);
  
  return (
    <svg
      className="absolute inset-0 w-full h-full pointer-events-none"
      style={{ zIndex: 0, overflow: 'visible' }}
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id="snakeGradient" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="rgba(128,255,151,0.2)" />
          <stop offset="50%" stopColor="rgba(107,183,255,0.2)" />
          <stop offset="100%" stopColor="rgba(128,255,151,0.2)" />
        </linearGradient>
      </defs>
      {Array.from({ length: rows - 1 }).map((_, i) => (
        <line
          key={i}
          x1={i % 2 === 0 ? "10%" : "90%"}
          y1={`${((i + 0.5) / rows) * 100}%`}
          x2={i % 2 === 0 ? "90%" : "10%"}
          y2={`${((i + 1.5) / rows) * 100}%`}
          stroke="url(#snakeGradient)"
          strokeWidth="2"
          strokeDasharray="8 4"
          opacity="0.4"
        />
      ))}
    </svg>
  );
}
