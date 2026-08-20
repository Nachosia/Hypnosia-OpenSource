import { useState } from 'react';
import { useAuth } from '@/hooks/useAuth';
import { trpc } from '@/providers/trpc';
import { useNavigate } from 'react-router-dom';

export default function MinecraftLinkPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const utils = trpc.useUtils();

  const { data: status, isLoading: statusLoading } = trpc.minecraft.licenseLinkStatus.useQuery(undefined, {
    staleTime: 1000 * 30,
    retry: false,
  });

  const verifyMutation = trpc.minecraft.verifyLicenseCode.useMutation({
    onSuccess: (data) => {
      utils.minecraft.licenseLinkStatus.invalidate();
      setCode('');
      showToast(`Привязано: ${data.displayName}`);
    },
    onError: (err) => showToast(err.message),
  });

  const [code, setCode] = useState('');
  const [toast, setToast] = useState<string | null>(null);

  const showToast = (msg: string) => { setToast(msg); setTimeout(() => setToast(null), 2500); };

  const isLinked = status?.linked === true;

  const roleColor = (role?: string) => {
    if (role === 'admin') return '#FF6464';
    if (role === 'qa') return '#C084FC';
    if (role?.startsWith('sponsor')) return '#FFD700';
    return '#7A8A9E';
  };

  const roleLabel = (role?: string) => {
    if (role === 'admin') return 'ADMIN';
    if (role === 'qa') return 'QA';
    if (role === 'developer') return 'DEV';
    if (role === 'sponsor_plusplus') return 'SPONSOR++';
    if (role === 'sponsor_plus') return 'SPONSOR+';
    if (role === 'sponsor') return 'SPONSOR';
    return 'USER';
  };

  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 800 }}>
        {/* Header */}
        <div className="text-center mb-10">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// LINK</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(28px, 5vw, 42px)', color: '#E8E4E0' }}>
            Привязка аккаунта
          </h1>
          <p className="font-body text-sm mt-2" style={{ color: '#7A8A9E' }}>
            {isLinked
              ? `Привязан: ${status.displayName || 'Player'}`
              : 'Свяжите ваш License Server аккаунт с Discord'
            }
          </p>
        </div>

        {statusLoading ? (
          <div className="text-center py-12">
            <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка...</p>
          </div>
        ) : isLinked ? (
          /* Linked status card */
          <div className="rounded-xl p-6 mb-8" style={{ background: 'rgba(128, 255, 151, 0.06)', border: '1px solid rgba(128, 255, 151, 0.18)' }}>
            <div className="flex items-center justify-between mb-4">
              <div>
                <p className="font-display text-lg font-bold" style={{ color: '#E8E4E0' }}>{status.displayName || 'Player'}</p>
                <div className="flex items-center gap-2 mt-1">
                  <span className="font-mono text-[10px] px-2 py-0.5 rounded-full" style={{ background: `${roleColor(status.role)}20`, color: roleColor(status.role), border: `1px solid ${roleColor(status.role)}30` }}>
                    {roleLabel(status.role)}
                  </span>
                  <span className="font-mono text-[10px]" style={{ color: '#7A8A9E' }}>ID: {status.accountId}</span>
                </div>
              </div>
              <span className="font-mono text-[10px] px-2 py-1 rounded" style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97' }}>АКТИВЕН</span>
            </div>

            <div className="flex items-center gap-2 flex-wrap">
              <button
                onClick={() => navigate(`/profile/${status.accountId}`)}
                className="font-mono text-[10px] uppercase px-3 py-2 rounded-lg transition-all hover:scale-105"
                style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
              >
                Перейти в профиль
              </button>
              <button
                onClick={() => navigate('/profile/settings')}
                className="font-mono text-[10px] uppercase px-3 py-2 rounded-lg transition-all hover:scale-105"
                style={{ background: 'rgba(128,255,151,0.06)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.12)' }}
              >
                ⚡ Настройки
              </button>
            </div>
          </div>
        ) : (
          /* Not linked — enter code */
          <div className="rounded-xl p-6 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
            <div className="flex items-center gap-2 mb-3">
              <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>Код из мода</span>
            </div>
            <h3 className="font-display font-bold text-base mb-2" style={{ color: '#E8E4E0' }}>Введите код привязки</h3>
            <p className="font-body text-xs mb-4" style={{ color: '#7A8A9E' }}>
              Введите в игре <span style={{ color: '#80FF97' }}>/hypnosia link</span>, получите 6-значный код и введите его здесь
            </p>

            <div className="flex gap-2">
              <input
                type="text"
                placeholder="XXXXXX"
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6))}
                className="flex-1 bg-transparent outline-none font-mono text-sm px-4 py-3 rounded-lg placeholder:text-vanta-muted uppercase"
                style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}
              />
              <button
                onClick={() => verifyMutation.mutate({ code })}
                disabled={verifyMutation.isPending || code.length !== 6}
                className="font-mono text-xs font-semibold uppercase tracking-[1px] px-5 py-3 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
              >
                {verifyMutation.isPending ? '...' : 'Привязать'}
              </button>
            </div>
          </div>
        )}

        {/* Commands reference */}
        <div className="rounded-xl p-5" style={{ background: 'rgba(15, 18, 24, 0.4)', border: '1px solid rgba(255,255,255,0.05)' }}>
          <p className="font-mono text-xs mb-3" style={{ color: '#7A8A9E' }}>// КОМАНДЫ В MINECRAFT</p>
          <div className="space-y-2 font-mono text-xs" style={{ color: '#E8E4E0' }}>
            <div className="flex gap-3"><span style={{ color: '#80FF97', minWidth: 140 }}>/hypnosia link</span><span style={{ color: '#7A8A9E' }}>— Получить код для привязки</span></div>
          </div>
        </div>
      </div>

      {/* Toast */}
      {toast && (
        <div className="fixed bottom-8 left-1/2 -translate-x-1/2 z-[200] font-mono text-xs px-5 py-2.5 rounded-xl" style={{ background: 'rgba(128,255,151,0.12)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}>{toast}</div>
      )}
    </div>
  );
}
