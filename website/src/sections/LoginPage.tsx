import { useState } from 'react';

export default function LoginPage() {
  const [imgLoaded, setImgLoaded] = useState(false);

  return (
    <div className="relative flex items-center justify-center" style={{ zIndex: 1, background: '#0B0D12', minHeight: '100vh', paddingTop: 64 }}>
      <div ref={contentRef} className="flex flex-col items-center gap-8 px-6" style={{ maxWidth: 420 }}>
        <div className="login-animate">
          <div className="flex items-center justify-center" style={{ width: 140, height: 140, borderRadius: '50%', background: 'linear-gradient(135deg, rgba(128,255,151,0.15), rgba(107,183,255,0.15))', border: '2px solid rgba(128,255,151,0.2)' }}>
            <img
              src="https://mc-heads.net/avatar/fe008fc7387e4477a8260219bd8c0c13/128"
              alt="Avatar"
              style={{ width: 100, height: 100, borderRadius: 12, imageRendering: 'pixelated' }}
              onLoad={() => setImgLoaded(true)}
            />
          </div>
        </div>

        <div className="text-center login-animate">
          <h1 className="font-display font-bold text-vanta-text" style={{ fontSize: 36, letterSpacing: '-1px' }}>Sign in</h1>
          <p className="font-body text-sm text-vanta-muted mt-2">Choose how you want to continue</p>
        </div>

        <div className="flex flex-col gap-3 w-full login-animate">
          <button className="w-full flex items-center justify-center gap-3 py-3.5 px-6 rounded-xl font-body text-sm font-semibold transition-all duration-200 hover:scale-[1.02] hover:brightness-110" style={{ background: '#5865F2', color: 'white', border: 'none', cursor: 'pointer' }}>
            <svg width={20} height={20} viewBox="0 0 24 24" fill="none"><path d="M20.3 4.5c-1.5-.7-3.1-1.2-4.8-1.5-.2.4-.5 1-.6 1.4-1.8-.3-3.6-.3-5.4 0-.2-.5-.4-1-.7-1.4-1.7.3-3.3.8-4.8 1.5C1.4 9.6.9 14.5 1.2 19.3c1.9 1.4 3.8 2.2 5.6 2.8.5-.6.8-1.3 1.1-2-.6-.2-1.2-.5-1.7-.9.1-.1.3-.2.4-.3 3.3 1.5 6.8 1.5 10.1 0 .1.1.3.2.4.3-.5.3-1.1.6-1.7.9.3.7.7 1.4 1.1 2 1.8-.6 3.7-1.4 5.6-2.8.4-5.6-.9-10.4-3.7-14.8z" fill="white" /></svg>
            Login with Discord
          </button>

          <button className="w-full flex items-center justify-center gap-3 py-3.5 px-6 rounded-xl font-body text-sm font-medium transition-all duration-200 hover:scale-[1.02]" style={{ background: 'transparent', color: '#7A8A9E', border: '1px solid rgba(122, 138, 158, 0.2)', cursor: 'pointer' }}>
            <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="#7A8A9E" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></svg>
            Continue as Guest
          </button>
        </div>

        <p className="text-center font-body text-xs text-vanta-muted login-animate" style={{ maxWidth: 320, lineHeight: 1.6 }}>
          Guest mode lets you browse without an account. Some features will be limited until you link Discord.
        </p>
      </div>
    </div>
  );
}
