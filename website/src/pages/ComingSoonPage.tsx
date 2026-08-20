import { useNavigate } from 'react-router';

interface ComingSoonPageProps {
  title: string;
}

export default function ComingSoonPage({ title }: ComingSoonPageProps) {
  const navigate = useNavigate();

  return (
    <div className="relative flex items-center justify-center" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="text-center max-w-md mx-auto px-6">
        <div className="mb-8">
          <div className="w-20 h-20 rounded-2xl mx-auto mb-6 flex items-center justify-center" style={{ background: 'rgba(128, 255, 151, 0.08)', border: '1px solid rgba(128, 255, 151, 0.15)' }}>
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="#80FF97" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
            </svg>
          </div>
          <h1 className="font-display font-bold mb-3" style={{ fontSize: 'clamp(28px, 5vw, 42px)', color: '#E8E4E0' }}>{title}</h1>
          <div className="flex items-center justify-center gap-3 mb-4">
            <div className="h-px w-12" style={{ background: 'linear-gradient(90deg, transparent, rgba(128,255,151,0.3))' }} />
            <span className="font-mono text-xs tracking-[4px] uppercase" style={{ color: '#80FF97' }}>Coming Soon</span>
            <div className="h-px w-12" style={{ background: 'linear-gradient(90deg, rgba(128,255,151,0.3), transparent)' }} />
          </div>
          <p className="font-body text-sm leading-relaxed" style={{ color: '#7A8A9E' }}>
            Этот раздел находится в разработке.<br />
            Мы активно работаем над ним и скоро выпустим обновление.
          </p>
        </div>

        <button
          onClick={() => navigate('/welcome')}
          className="font-mono text-xs font-semibold uppercase tracking-[1px] px-8 py-3 rounded-lg transition-all hover:scale-[1.02]"
          style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
        >
          На главную
        </button>
      </div>
    </div>
  );
}
