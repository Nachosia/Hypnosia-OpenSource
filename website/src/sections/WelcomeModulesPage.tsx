import { useState, useEffect } from 'react';
import { trpc } from '@/providers/trpc';

interface ModuleItem {
  id: string;
  title: string;
  description: string;
  image: string;
  side: 'left' | 'right';
}

const hiHero = {
  id: 'hi',
  title: 'HI',
  description: 'Приветственная страничка мода на визуалы Hypnosia Visuals',
  image: '/screenshots/hi.jpg',
};

const modules: ModuleItem[] = [
  { id: 'home', title: 'Home', description: 'Возможность добавлять друзей чтобы на них тоже распространялись визуалы', image: '/screenshots/home.jpg', side: 'left' },
  { id: 'visuals', title: 'Visuals', description: 'На данный момент почти ничего нет, но в будущем будет очень много', image: '/screenshots/visuals.jpg', side: 'right' },
  { id: 'world', title: 'World', description: 'На данный момент мало что есть, но будет больше', image: '/screenshots/world.jpg', side: 'left' },
  { id: 'client', title: 'Client', description: 'В будущем будет больше возможностей в кастоме своего клиента', image: '/screenshots/client.jpg', side: 'right' },
  { id: 'hud', title: 'HUD', description: 'На данный момент мало что есть, но будет больше', image: '/screenshots/hud.jpg', side: 'left' },
  { id: 'other', title: 'Other', description: 'Пока что мало есть, но сюда войдут утилиты в будущем под Funtime и многие анархо-сервера', image: '/screenshots/other.jpg', side: 'right' },
  { id: 'account', title: 'Account', description: 'Cloud configs. Upload, download, and share configurations with friends. 4 модуля', image: '/screenshots/account.jpg', side: 'left' },
];

function formatNumber(n: number): string {
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M+`;
  if (n >= 1000) return `${(n / 1000).toFixed(1)}K+`;
  return `${n}+`;
}

function AnimatedNumber({ target, formatter }: { target: number; formatter: (n: number) => string }) {
  const [display, setDisplay] = useState(0);
  useEffect(() => {
    let raf: number;
    const startTime = performance.now();
    const duration = 3000;
    const animate = (now: number) => {
      const progress = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 4);
      setDisplay(Math.floor(target * eased));
      if (progress < 1) {
        raf = requestAnimationFrame(animate);
      }
    };
    raf = requestAnimationFrame(animate);
    return () => cancelAnimationFrame(raf);
  }, [target]);
  return <>{formatter(display)}</>;
}

export default function WelcomeModulesPage() {
  const { data: stats } = trpc.stats.overview.useQuery(undefined, {
    staleTime: 1000 * 60 * 5,
  });



  return (
    <div className="relative" style={{ zIndex: 1, background: 'transparent' }}>
      {/* Title */}
      <div className="relative pt-12 pb-4 text-center" style={{ zIndex: 2 }}>
        <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>
          MINECRAFT UTILITY CLIENT | 1.21.11
        </p>
        <h1 className="font-display font-bold" style={{ fontSize: 'clamp(36px, 7vw, 72px)', letterSpacing: '-2px', color: '#E8E4E0' }}>
          Hypnosia <span style={{ color: '#80FF97' }}>Visuals</span>
        </h1>
      </div>

      {/* HI Hero */}
      <div className="relative mx-auto px-4 mb-8" style={{ maxWidth: 1100 }}>
        <div className="flex justify-center">
          <div
            className="relative overflow-hidden rounded-2xl"
            style={{ background: 'rgba(13, 15, 20, 0.6)', backdropFilter: 'blur(8px)', border: '1px solid rgba(128, 255, 151, 0.12)' }}
          >
            <img
              src={hiHero.image}
              alt={hiHero.title}
              style={{ maxWidth: '100%', height: 'auto', display: 'block' }}
              loading="eager"
            />
            <div className="absolute bottom-0 left-0 right-0 h-1" style={{ background: 'linear-gradient(90deg, #80FF97, #6BB7FF, transparent)' }} />
          </div>
        </div>
        <div className="mt-4">
          <h2 className="font-display font-semibold" style={{ fontSize: 'clamp(24px, 3vw, 32px)', letterSpacing: '-0.5px', color: '#E8E4E0' }}>
            {hiHero.title}
          </h2>
          <p className="font-body text-sm mt-1" style={{ maxWidth: 480, lineHeight: 1.6, color: '#7A8A9E' }}>
            {hiHero.description}
          </p>
        </div>
      </div>

      {/* Timeline modules */}
      <div className="relative mx-auto px-4" style={{ maxWidth: 1100 }}>
        {/* Center vertical line */}
        <div className="absolute left-1/2 top-0 bottom-0 -translate-x-1/2" style={{ width: 2, zIndex: 1 }}>
          <div className="w-full h-full" style={{ background: 'rgba(128, 255, 151, 0.08)' }} />
          <div
            className="absolute top-0 left-0 w-full"
            style={{
              height: '100%',
              background: 'linear-gradient(180deg, rgba(128,255,151,0.5) 0%, rgba(107,183,255,0.3) 50%, rgba(128,255,151,0.1) 100%)',
            }}
          />
        </div>

        {/* Dot markers */}
        <div className="absolute left-1/2 -translate-x-1/2 flex flex-col items-center" style={{ top: 0, bottom: 0, zIndex: 2 }}>
          {modules.map((mod, idx) => (
            <div key={mod.id} className="absolute" style={{ top: 60 + idx * 420 }}>
              <div
                className="rounded-full"
                style={{
                  width: 10,
                  height: 10,
                  background: idx === 0 ? '#80FF97' : 'rgba(107, 183, 255, 0.4)',
                  border: '2px solid #0B0D12',
                  boxShadow: idx === 0 ? '0 0 12px rgba(128,255,151,0.5)' : 'none',
                }}
              />
            </div>
          ))}
        </div>

        {/* Module items */}
        <div className="relative" style={{ zIndex: 3 }}>
          {modules.map((mod) => (
            <div
              key={mod.id}
              className={`flex ${mod.side === 'left' ? 'flex-row' : 'flex-row-reverse'}`}
              style={{ marginBottom: 60, minHeight: 340, gap: 40, alignItems: 'flex-start' }}
            >
              {/* Content side */}
              <div className="flex flex-col" style={{ width: 'calc(50% - 30px)' }}>
                <h2
                  className="font-display font-semibold mb-3"
                  style={{ fontSize: 'clamp(20px, 3vw, 28px)', letterSpacing: '-0.5px', color: '#E8E4E0' }}
                >
                  {mod.title}
                </h2>

                {/* Screenshot */}
                <div
                  className="relative overflow-hidden rounded-xl"
                  style={{ background: 'rgba(13, 15, 20, 0.5)', backdropFilter: 'blur(4px)', border: '1px solid rgba(128, 255, 151, 0.08)' }}
                >
                  <img
                    src={mod.image}
                    alt={mod.title}
                    style={{ maxWidth: '100%', height: 'auto', display: 'block' }}
                    loading="lazy"
                  />
                  <div
                    className="absolute bottom-0 left-0 right-0 h-0.5"
                    style={{ background: 'linear-gradient(90deg, rgba(128,255,151,0.3), rgba(107,183,255,0.15), transparent)' }}
                  />
                </div>

                <p className="font-body text-sm mt-3" style={{ maxWidth: 380, lineHeight: 1.6, color: '#7A8A9E' }}>
                  {mod.description}
                </p>
              </div>

              {/* Stats or empty space for the other side */}
              <div className="flex flex-col justify-center" style={{ width: 'calc(50% - 30px)' }}>
                {mod.id === 'home' && (
                  <div
                    className="rounded-xl p-8 text-center transition-all duration-300 hover:-translate-y-1"
                    style={{
                      background: 'rgba(13, 15, 20, 0.5)',
                      backdropFilter: 'blur(12px)',
                      border: '1px solid rgba(128, 255, 151, 0.1)',
                    }}
                    onMouseEnter={(e) => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(128, 255, 151, 0.35)';
                    }}
                    onMouseLeave={(e) => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(128, 255, 151, 0.1)';
                    }}
                  >
                    <p className="font-display text-5xl font-bold" style={{ color: '#80FF97' }}>
                      <AnimatedNumber target={stats?.users ?? 0} formatter={formatNumber} />
                    </p>
                    <p className="font-mono text-xs tracking-[2px] mt-3" style={{ color: '#7A8A9E' }}>
                      АККАУНТОВ
                    </p>
                    <p className="font-body text-sm mt-3" style={{ maxWidth: 280, margin: '12px auto 0', lineHeight: 1.5, color: '#7A8A9E' }}>
                      Сколько людей нас выбрало, чтобы играть с визуалами
                    </p>
                  </div>
                )}

                {mod.id === 'client' && (
                  <div
                    className="rounded-xl p-8 text-center transition-all duration-300 hover:-translate-y-1"
                    style={{
                      background: 'rgba(13, 15, 20, 0.5)',
                      backdropFilter: 'blur(12px)',
                      border: '1px solid rgba(107, 183, 255, 0.1)',
                    }}
                    onMouseEnter={(e) => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(107, 183, 255, 0.35)';
                    }}
                    onMouseLeave={(e) => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(107, 183, 255, 0.1)';
                    }}
                  >
                    <p className="font-display text-5xl font-bold" style={{ color: '#6BB7FF' }}>
                      <AnimatedNumber target={Math.floor(stats?.hours ?? 0)} formatter={formatNumber} />
                    </p>
                    <p className="font-mono text-xs tracking-[2px] mt-3" style={{ color: '#7A8A9E' }}>
                      ЧАСОВ НАИГРАНО
                    </p>
                    <p className="font-body text-sm mt-3" style={{ maxWidth: 280, margin: '12px auto 0', lineHeight: 1.5, color: '#7A8A9E' }}>
                      Сколько времени с нашим клиентом наиграли люди
                    </p>
                  </div>
                )}
                {mod.id === 'account' && (
                  <div
                    className="rounded-xl p-8 text-center transition-all duration-300 hover:-translate-y-1"
                    style={{
                      background: 'rgba(13, 15, 20, 0.5)',
                      backdropFilter: 'blur(12px)',
                      border: '1px solid rgba(255, 215, 0, 0.1)',
                    }}
                    onMouseEnter={(e) => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(255, 215, 0, 0.35)';
                    }}
                    onMouseLeave={(e) => {
                      (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(255, 215, 0, 0.1)';
                    }}
                  >
                    <p className="font-display text-5xl font-bold" style={{ color: '#FFD700' }}>
                      <AnimatedNumber target={stats?.configs ?? 0} formatter={formatNumber} />
                    </p>
                    <p className="font-mono text-xs tracking-[2px] mt-3" style={{ color: '#7A8A9E' }}>
                      КОНФИГОВ
                    </p>
                    <p className="font-body text-sm mt-3" style={{ maxWidth: 280, margin: '12px auto 0', lineHeight: 1.5, color: '#7A8A9E' }}>
                      Сколько конфигов у нас уже хранится на серверах
                    </p>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Stats footer */}
      <div className="relative mx-auto px-4 py-20 text-center" style={{ maxWidth: 1100, zIndex: 2 }}>
        <h2
          className="font-display font-bold mb-4"
          style={{ fontSize: 'clamp(24px, 4vw, 40px)', letterSpacing: '-1px', color: '#E8E4E0' }}
        >
          Не просто играй. <span style={{ color: '#80FF97' }}>Впечатляйся.</span>
        </h2>
        <p className="font-body text-base mb-12" style={{ color: '#7A8A9E' }}>
          Более {(stats?.users ?? 0).toLocaleString('ru-RU')} игроков уже открыли для себя новый Minecraft. Твой ход — бесплатно.
        </p>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-6">
          <div
            className="rounded-xl p-8 transition-all duration-300 hover:-translate-y-1"
            style={{
              background: 'rgba(13, 15, 20, 0.5)',
              backdropFilter: 'blur(12px)',
              border: '1px solid rgba(128, 255, 151, 0.1)',
            }}
            onMouseEnter={(e) => {
              (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(128, 255, 151, 0.35)';
            }}
            onMouseLeave={(e) => {
              (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(128, 255, 151, 0.1)';
            }}
          >
            <p className="font-display text-5xl font-bold" style={{ color: '#80FF97' }}>
              <AnimatedNumber target={stats?.users ?? 0} formatter={formatNumber} />
            </p>
            <p className="font-mono text-xs tracking-[2px] mt-3" style={{ color: '#7A8A9E' }}>
              ПОЛЬЗОВАТЕЛЕЙ
            </p>
          </div>

          <div
            className="rounded-xl p-8 transition-all duration-300 hover:-translate-y-1"
            style={{
              background: 'rgba(13, 15, 20, 0.5)',
              backdropFilter: 'blur(12px)',
              border: '1px solid rgba(107, 183, 255, 0.1)',
            }}
            onMouseEnter={(e) => {
              (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(107, 183, 255, 0.35)';
            }}
            onMouseLeave={(e) => {
              (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(107, 183, 255, 0.1)';
            }}
          >
            <p className="font-display text-5xl font-bold" style={{ color: '#6BB7FF' }}>
              <AnimatedNumber target={Math.floor(stats?.hours ?? 0)} formatter={formatNumber} />
            </p>
            <p className="font-mono text-xs tracking-[2px] mt-3" style={{ color: '#7A8A9E' }}>
              ЧАСОВ НАИГРАНО
            </p>
          </div>

          <div
            className="rounded-xl p-8 transition-all duration-300 hover:-translate-y-1"
            style={{
              background: 'rgba(13, 15, 20, 0.5)',
              backdropFilter: 'blur(12px)',
              border: '1px solid rgba(255, 215, 0, 0.1)',
            }}
            onMouseEnter={(e) => {
              (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(255, 215, 0, 0.35)';
            }}
            onMouseLeave={(e) => {
              (e.currentTarget as HTMLDivElement).style.borderColor = 'rgba(255, 215, 0, 0.1)';
            }}
          >
            <p className="font-display text-5xl font-bold" style={{ color: '#FFD700' }}>
              <AnimatedNumber target={stats?.configs ?? 0} formatter={formatNumber} />
            </p>
            <p className="font-mono text-xs tracking-[2px] mt-3" style={{ color: '#7A8A9E' }}>
              КОНФИГОВ ВЫГРУЖЕНО
            </p>
          </div>
        </div>

        <a
          href="https://github.com/Nachosia/Hypnosia-Visuals/releases/download/v1.0-beta/hypnosia-1.0-beta.jar"
          download
          className="inline-block font-display text-base font-semibold rounded-lg mt-12 transition-all duration-250 hover:scale-[1.03]"
          style={{
            background: 'linear-gradient(135deg, #80FF97 0%, #6BB7FF 100%)',
            color: '#0B0D12',
            padding: '14px 40px',
          }}
        >
          Скачать Hypnosia Visuals
        </a>
      </div>

      <div style={{ height: 40 }} />
    </div>
  );
}
