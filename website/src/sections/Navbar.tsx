import { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router';
import { useAuth } from '@/hooks/useAuth';
import { trpc } from '@/providers/trpc';

export default function Navbar() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const { data: profile } = trpc.profile.me.useQuery(undefined, {
    enabled: !!user,
    staleTime: 1000 * 60 * 5,
    retry: false,
  });

  // Close dropdown on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const effectiveRole = (user as any)?.effectiveRole ?? user?.role;
  const isAdmin = effectiveRole === 'admin' || effectiveRole === 'owner';

  const getActiveTab = () => {
    const path = location.pathname;
    if (path === '/' || path === '/welcome') return 'welcome';
    if (path === '/tops') return 'tops';
    if (path === '/team') return 'team';
    if (path === '/store') return 'store';
    if (path === '/roadmap') return 'roadmap';
    if (path === '/support') return 'support';
    if (path === '/admin') return 'admin';
    if (path === '/link') return 'link';
    if (path.startsWith('/profile')) return 'profile';
    if (path === '/login') return 'login';
    if (path === '/transactions') return 'transactions';
    return 'welcome';
  };

  const activeTab = getActiveTab();

  const mainTabs = [
    { key: 'welcome', label: 'Welcome', path: '/welcome' },
    { key: 'tops', label: 'Tops', path: '/tops' },
    { key: 'team', label: 'Team', path: '/team' },
    { key: 'store', label: 'Store', path: '/store' },
    { key: 'roadmap', label: 'Roadmap', path: '/roadmap' },
    { key: 'support', label: 'Support', path: '/support' },
  ];

  const gradientStyle = (from: string, to: string) => ({
    background: `linear-gradient(135deg, ${from}, ${to})`,
    backgroundClip: 'text' as const,
    WebkitBackgroundClip: 'text' as const,
    WebkitTextFillColor: 'transparent' as const,
    color: 'transparent' as const,
  });

  const avatarLetter = user?.name?.charAt(0).toUpperCase() ?? '?';
  const skinUrl = profile?.skinUrl ?? null;
  const faceUrl = skinUrl ? `/api/skin/face/${skinUrl.split('/').pop()}` : null;

  const renderAvatar = (size: number) => {
    if (faceUrl) {
      return (
        <img
          src={faceUrl}
          alt=""
          className="rounded-sm"
          style={{ width: size, height: size, objectFit: 'cover', imageRendering: 'pixelated' }}
          onError={(e) => { (e.target as HTMLImageElement).src = '/api/skin/face/steve.png'; }}
        />
      );
    }
    return (
      <div className="rounded-sm flex items-center justify-center" style={{ width: size, height: size, background: 'linear-gradient(135deg, #80FF97, #6BB7FF)' }}>
        <span className="font-display font-bold" style={{ fontSize: size * 0.4, color: '#0B0D12' }}>{avatarLetter}</span>
      </div>
    );
  };

  return (
    <div
      className="fixed top-0 left-0 w-full z-50"
      style={{
        background: 'rgba(11, 13, 18, 0.85)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        borderBottom: '1px solid rgba(128, 255, 151, 0.08)',
      }}
    >
      <div className="mx-auto px-6 lg:px-12 flex items-center justify-between" style={{ maxWidth: 1200, height: 64 }}>
        {/* Logo */}
        <button onClick={() => navigate('/welcome')} className="font-mono text-sm font-bold tracking-[3px] transition-colors duration-200" style={{ color: '#80FF97' }}>
          HYPNOSIA
        </button>

        {/* Main tabs */}
        <div className="flex items-center gap-1">
          {mainTabs.map((tab) => (
            <button
              key={tab.key}
              onClick={() => navigate(tab.path)}
              className="relative px-4 py-2 font-body text-sm font-medium transition-colors duration-200 rounded-md"
              style={{ color: activeTab === tab.key ? '#80FF97' : '#7A8A9E' }}
            >
              {tab.label}
              {activeTab === tab.key && <span className="absolute bottom-0 left-1/2 -translate-x-1/2 h-0.5 rounded-full" style={{ width: '60%', background: '#80FF97' }} />}
            </button>
          ))}
          {/* Admin tab — visible only for admin */}
          {isAdmin && (
            <button
              onClick={() => navigate('/admin')}
              className="relative px-4 py-2 font-body text-sm font-medium transition-colors duration-200 rounded-md"
              style={{ color: activeTab === 'admin' ? '#ff6464' : '#7A8A9E' }}
            >
              <span style={{ color: activeTab === 'admin' ? '#ff6464' : '#7A8A9E' }}>Admin</span>
              {activeTab === 'admin' && <span className="absolute bottom-0 left-1/2 -translate-x-1/2 h-0.5 rounded-full" style={{ width: '60%', background: '#ff6464' }} />}
            </button>
          )}
        </div>

        {/* Right side */}
        <div className="flex items-center gap-3">
          {user ? (
            <div className="relative" ref={dropdownRef}>
              {/* Account button */}
              <button
                onClick={() => setDropdownOpen(!dropdownOpen)}
                className="flex items-center gap-2 px-3 py-2 rounded-md transition-all"
                style={{ background: dropdownOpen ? 'rgba(128,255,151,0.06)' : 'transparent' }}
              >
                {renderAvatar(28)}
                <span className="font-body text-sm font-medium" style={gradientStyle('#80FF97', '#6BB7FF')}>{user.name ?? 'User'}</span>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#7A8A9E" strokeWidth="2"><path d="M6 9l6 6 6-6" /></svg>
              </button>

              {/* Dropdown */}
              {dropdownOpen && (
                <div className="absolute right-0 top-full mt-2 w-56 rounded-xl overflow-hidden" style={{ background: '#141924', border: '1px solid rgba(255,255,255,0.08)', boxShadow: '0 20px 60px rgba(0,0,0,0.5)' }}>
                  {/* Profile header */}
                  <div className="px-4 py-3" style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                    <div className="flex items-center gap-2">
                      {renderAvatar(32)}
                      <div>
                        <p className="font-display text-sm font-bold" style={gradientStyle('#80FF97', '#6BB7FF')}>{user.name ?? 'User'}</p>
                        <span className="font-mono text-[9px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97' }}>{user.role?.toUpperCase() ?? 'USER'}</span>
                        <span className="font-mono text-[9px] ml-1 px-1.5 py-0.5 rounded" style={{ background: 'rgba(255,200,0,0.1)', color: '#FFD700' }}>{(user as any).points ?? 0} HY-P</span>
                      </div>
                    </div>
                  </div>

                  {/* Menu items */}
                  <div className="py-1">
                    <button onClick={() => { navigate('/profile/settings'); setDropdownOpen(false); }} className="w-full text-left px-4 py-2.5 font-body text-sm transition-colors hover:bg-white/5 flex items-center gap-3" style={{ color: '#E8E4E0' }}>
                      <span>⚙️</span> Настройки профиля
                    </button>
                    <button onClick={() => { navigate('/link'); setDropdownOpen(false); }} className="w-full text-left px-4 py-2.5 font-body text-sm transition-colors hover:bg-white/5 flex items-center gap-3" style={{ color: '#E8E4E0' }}>
                      <span>🔗</span> Привязка Minecraft
                    </button>
                    <button onClick={() => { navigate('/transactions'); setDropdownOpen(false); }} className="w-full text-left px-4 py-2.5 font-body text-sm transition-colors hover:bg-white/5 flex items-center gap-3" style={{ color: '#E8E4E0' }}>
                      <span>📜</span> История и баланс
                    </button>
                    <button onClick={() => { navigate('/support?tab=my'); setDropdownOpen(false); }} className="w-full text-left px-4 py-2.5 font-body text-sm transition-colors hover:bg-white/5 flex items-center gap-3" style={{ color: '#E8E4E0' }}>
                      <span>🎫</span> Мои тикеты
                    </button>
                  </div>

                  {/* Admin section */}
                  {isAdmin && (
                    <>
                      <div className="h-px mx-4" style={{ background: 'rgba(255,100,100,0.1)' }} />
                      <div className="py-1">
                        <button onClick={() => { navigate('/admin'); setDropdownOpen(false); }} className="w-full text-left px-4 py-2.5 font-body text-sm transition-colors hover:bg-white/5 flex items-center gap-3" style={{ color: '#ff6464' }}>
                          <span>🔴</span> Панель управления
                        </button>
                      </div>
                    </>
                  )}

                  {/* Logout */}
                  <div className="h-px mx-4" style={{ background: 'rgba(255,255,255,0.06)' }} />
                  <div className="py-1">
                    <button onClick={() => { logout(); setDropdownOpen(false); }} className="w-full text-left px-4 py-2.5 font-body text-sm transition-colors hover:bg-white/5 flex items-center gap-3" style={{ color: '#7A8A9E' }}>
                      <span>🚪</span> Выйти
                    </button>
                  </div>
                </div>
              )}
            </div>
          ) : (
            <button onClick={() => navigate('/login')} className="font-body text-sm font-medium transition-colors duration-200" style={{ color: activeTab === 'login' ? '#6BB7FF' : '#7A8A9E' }}>
              Login
            </button>
          )}
          <a href="#" onClick={(e) => e.preventDefault()} className="font-mono text-xs font-semibold uppercase tracking-[1px] px-5 py-2 rounded-md transition-all duration-200 hover:scale-105" style={{ background: 'linear-gradient(135deg, #80FF97 0%, #6BB7FF 100%)', color: '#0B0D12' }}>
            Download
          </a>
        </div>
      </div>
    </div>
  );
}
