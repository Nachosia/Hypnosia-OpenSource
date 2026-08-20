import { useState } from 'react';
import SkinViewer3D from '@/sections/SkinViewer3D';

function getOAuthUrl() {
  const redirectUri = `${window.location.origin}/api/oauth/callback`;
  const url = new URL('/api/oauth/start', window.location.origin);
  url.searchParams.set("redirectUri", redirectUri);
  return url.toString();
}

export default function Login() {
  const [loading, setLoading] = useState(false);
  const [agreed, setAgreed] = useState(false);

  const handleLogin = () => {
    if (!agreed) return;
    setLoading(true);
    window.location.href = getOAuthUrl();
  };

  return (
    <div className="relative flex items-center justify-center" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="flex flex-col items-center gap-8 px-6" style={{ maxWidth: 420 }}>
        {/* 3D Skin preview */}
        <div className="relative">
          <div
            className="rounded-2xl overflow-hidden"
            style={{
              background: 'radial-gradient(circle at 50% 10%, rgba(74, 121, 255, 0.18), transparent 42%), #0b0d12',
              border: '1px solid rgba(255, 255, 255, 0.08)',
              boxShadow: '0 20px 60px rgba(0, 0, 0, 0.35)',
            }}
          >
            <SkinViewer3D size={180} />
          </div>
          {/* Online dot */}
          <div
            className="absolute bottom-3 right-3 w-4 h-4 rounded-full"
            style={{
              background: '#80FF97',
              border: '3px solid #0b0d12',
              boxShadow: '0 0 8px rgba(128, 255, 151, 0.5)',
            }}
          />
        </div>

        {/* Text */}
        <div className="text-center">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// LOGIN</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(24px, 5vw, 36px)', letterSpacing: '-1px', color: '#E8E4E0' }}>
            Вход в аккаунт
          </h1>
          <p className="font-body text-sm mt-2" style={{ color: '#7A8A9E' }}>
            Авторизуйтесь через Discord для доступа к личному кабинету
          </p>
        </div>

        {/* Terms agreement */}
        <label className="flex items-start gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-1 w-4 h-4 rounded border-gray-600 bg-transparent text-[#6BB7FF] focus:ring-[#6BB7FF]"
          />
          <span className="font-body text-xs" style={{ color: '#7A8A9E' }}>
            Я согласен с{' '}
            <a href="/#/privacy" target="_blank" rel="noreferrer" className="underline hover:text-[#6BB7FF]" style={{ color: '#6BB7FF' }}>Политикой конфиденциальности</a>
            {' '}и{' '}
            <a href="/#/terms" target="_blank" rel="noreferrer" className="underline hover:text-[#6BB7FF]" style={{ color: '#6BB7FF' }}>Правилами пользования</a>
          </span>
        </label>

        {/* Discord Login Button */}
        <button
          onClick={handleLogin}
          disabled={loading || !agreed}
          className="w-full font-display text-sm font-semibold rounded-xl px-6 py-4 transition-all duration-200 hover:scale-[1.02] disabled:opacity-50"
          style={{
            background: 'linear-gradient(135deg, #5865F2 0%, #4752C4 100%)',
            color: '#ffffff',
            boxShadow: '0 8px 32px rgba(88, 101, 242, 0.25)',
          }}
        >
          <div className="flex items-center justify-center gap-3">
            {/* Discord icon */}
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
              <path d="M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z"/>
            </svg>
            {loading ? 'Загрузка...' : 'Войти через Discord'}
          </div>
        </button>

        {/* Guest link */}
        <button
          onClick={() => window.history.back()}
          className="font-body text-xs transition-colors"
          style={{ color: '#7A8A9E' }}
          onMouseEnter={(e) => (e.currentTarget.style.color = '#A0AEBF')}
          onMouseLeave={(e) => (e.currentTarget.style.color = '#7A8A9E')}
        >
          ← Вернуться назад
        </button>
      </div>
    </div>
  );
}
