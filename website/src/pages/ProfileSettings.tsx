import { useState, useEffect, useMemo } from 'react';
import { useAuth } from '@/hooks/useAuth';
import { trpc } from '@/providers/trpc';
import SkinViewer3D from '@/sections/SkinViewer3D';
import { formatRoleName, getRoleIconPath } from '@/lib/roles';

interface ToggleProps {
  label: string;
  desc: string;
  enabled: boolean;
  onChange: () => void;
}

function Toggle({ label, desc, enabled, onChange }: ToggleProps) {
  return (
    <div className="flex items-center justify-between py-3">
      <div>
        <p className="font-body text-sm font-medium" style={{ color: '#E8E4E0' }}>{label}</p>
        <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>{desc}</p>
      </div>
      <button
        onClick={onChange}
        className="relative w-11 h-6 rounded-full transition-all duration-200"
        style={{ background: enabled ? '#80FF97' : '#2a2f3a' }}
      >
        <div
          className="absolute top-1 w-4 h-4 rounded-full transition-all duration-200"
          style={{ left: enabled ? 22 : 4, background: enabled ? '#0B0D12' : '#7A8A9E' }}
        />
      </button>
    </div>
  );
}

const GRADIENT_PRESETS = [
  { from: '#80FF97', to: '#6BB7FF', label: 'Client' },
  { from: '#A217FF', to: '#2C37FF', label: 'Ночной Токио' },
  { from: '#FF4D17', to: '#FFED2C', label: 'Приятный вечер' },
  { from: '#2A2A2A', to: '#ACACAC', label: 'Пепел' },
  { from: '#000000', to: '#FFFFFF', label: 'Тёмный Друн' },
  { from: '#FFA500', to: '#FFD700', label: 'Золото' },
  { from: '#1EAAF0', to: '#F2F2F2', label: 'Голубое небо' },
];

function canUseGradients(role: string | undefined): boolean {
  return ['sponsor', 'sponsor_plus', 'sponsor_plusplus', 'admin', 'owner', 'qa', 'developer'].includes(role ?? '');
}

function canEditGradients(role: string | undefined): boolean {
  return ['sponsor_plus', 'sponsor_plusplus', 'admin', 'owner', 'qa', 'developer'].includes(role ?? '');
}

function hoursLeft(timestamp: string | null | undefined): number | null {
  if (!timestamp) return null;
  const lastEdit = new Date(timestamp).getTime();
  const hoursSince = (Date.now() - lastEdit) / (1000 * 60 * 60);
  const left = 24 - hoursSince;
  return left > 0 ? Math.ceil(left) : null;
}

export default function ProfileSettingsPage() {
  const { user } = useAuth();
  const utils = trpc.useUtils();

  const { data: profile, isLoading: profileLoading } = trpc.profile.me.useQuery(undefined, {
    staleTime: 1000 * 60 * 5,
    retry: false,
  });

  const { data: myInventory } = trpc.store.myInventory.useQuery();

  const updateMutation = trpc.profile.updateSettings.useMutation({
    onSuccess: () => {
      utils.profile.me.invalidate();
      showToast('Настройки сохранены!');
    },
    onError: (err) => {
      const msg = err.message;
      if (msg.includes('NICK_COOLDOWN_ACTIVE')) {
        const hours = msg.split(':')[1];
        showToast(`Кулдаун ника: подождите ${hours}ч`);
      } else if (msg.includes('ROLE_COOLDOWN_ACTIVE')) {
        const hours = msg.split(':')[1];
        showToast(`Кулдаун роли: подождите ${hours}ч`);
      } else if (msg === 'GRADIENT_PASS_REQUIRED') {
        showToast('Требуется Gradient Pass');
      } else if (msg === 'ACTIVE_SUBSCRIPTION_REQUIRED') {
        showToast('Требуется активная подписка');
      } else {
        showToast('Ошибка сохранения');
      }
    },
  });

  const resetHwidMutation = trpc.profile.resetHwid.useMutation({
    onSuccess: (data) => {
      showToast('HWID сброшен! Ключ скачан.');
      utils.store.myInventory.invalidate();
      utils.profile.me.invalidate();
      if (data.newAccountKey && profile?.accountId) {
        const blob = new Blob(
          [`# Hypnosia account config.\naccount.key=${data.newAccountKey}\naccount.id=${profile.accountId}\n`],
          { type: 'text/plain' }
        );
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `account-${profile.accountId}.properties`;
        a.click();
        URL.revokeObjectURL(url);
      }
    },
    onError: (err) => {
      if (err.message === 'HWID_RESET_REQUIRED') showToast('Требуется товар HWID Reset');
      else if (err.message === 'NO_LICENSE_KEY') showToast('Нет привязанного ключа');
      else if (err.message.startsWith('HWID_RESET_COOLDOWN:')) {
        const hours = Math.ceil(Number(err.message.split(':')[1]) / 3600);
        showToast(`Кулдаун сброса HWID: ${hours}ч`);
      } else showToast('Ошибка сброса HWID');
    },
  });

  const downloadKeyMutation = trpc.profile.downloadAccountKey.useMutation({
    onSuccess: (data) => {
      const blob = new Blob([data.content], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = data.filename;
      a.click();
      URL.revokeObjectURL(url);
      showToast('Ключ аккаунта скачан');
    },
    onError: () => showToast('Ключ аккаунта недоступен'),
  });

  const updateRoleNameMutation = trpc.profile.updateCustomRoleName.useMutation({
    onSuccess: () => {
      utils.profile.me.invalidate();
      showToast('Имя роли сохранено!');
    },
    onError: (err) => {
      if (err.message === 'SPONSOR_PLUSPLUS_REQUIRED') showToast('Недостаточно прав');
      else if (err.message === 'INVALID_ROLE_NAME_FORMAT') showToast('Недопустимые символы (a-z, 0-9, _ - [ ] ( ))');
      else if (err.message === 'FORBIDDEN_ROLE_NAME') showToast('Это название запрещено');
      else showToast('Ошибка сохранения');
    },
  });

  const [visibility, setVisibility] = useState({
    showHours: true,
    showMcJoined: true,
    showOnline: true,
    showRank: true,
  });

  const [nickFrom, setNickFrom] = useState('#80FF97');
  const [nickTo, setNickTo] = useState('#6BB7FF');
  const [roleFrom, setRoleFrom] = useState('#6BB7FF');
  const [roleTo, setRoleTo] = useState('#FFD700');
  const [skinModel, setSkinModel] = useState<'classic' | 'slim'>('classic');
  const [skinPreview, setSkinPreview] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [customRoleName, setCustomRoleName] = useState('');

  // Load profile data when available
  useEffect(() => {
    if (profile) {
      setVisibility({
        showHours: profile.showHours === 'true',
        showMcJoined: profile.showMcJoined === 'true',
        showOnline: profile.showOnline === 'true',
        showRank: (profile as unknown as Record<string, string>).showRank === 'true',
      });
      if (profile.nickGradientFrom) setNickFrom(profile.nickGradientFrom);
      if (profile.nickGradientTo) setNickTo(profile.nickGradientTo);
      if (profile.roleGradientFrom) setRoleFrom(profile.roleGradientFrom);
      if (profile.roleGradientTo) setRoleTo(profile.roleGradientTo);
      if (profile.skinModel) setSkinModel(profile.skinModel as 'classic' | 'slim');
      if (profile.skinUrl) setSkinPreview(profile.skinUrl);
      const crn = (profile as any).customRoleName;
      if (crn) setCustomRoleName(crn);
    }
  }, [profile]);

  const showToast = (msg: string) => { setToast(msg); setTimeout(() => setToast(null), 3000); };

  const effectiveRole = (user as any)?.effectiveRole ?? user?.role ?? 'user';
  const hasGradientAccess = canUseGradients(effectiveRole);
  const canEditGradient = canEditGradients(effectiveRole);
  const roleDisplayNames = ((profile as any)?.roleDisplayNames as Record<string, string> | undefined) ?? {};

  const nickCooldownHours = useMemo(() => hoursLeft((profile as any)?.nickGradientEditedAt), [profile]);
  const roleCooldownHours = useMemo(() => hoursLeft((profile as any)?.roleGradientEditedAt), [profile]);

  const handleSaveGeneral = () => {
    updateMutation.mutate({
      showHours: visibility.showHours ? 'true' : 'false',
      showMcJoined: visibility.showMcJoined ? 'true' : 'false',
      showOnline: visibility.showOnline ? 'true' : 'false',
      showRank: visibility.showRank ? 'true' : 'false',
      skinModel,
    });
  };

  const handleSaveNick = () => {
    updateMutation.mutate({
      nickGradientFrom: nickFrom,
      nickGradientTo: nickTo,
    });
  };

  const handleSaveRole = () => {
    updateMutation.mutate({
      roleGradientFrom: roleFrom,
      roleGradientTo: roleTo,
    });
  };

  const displayName = profile?.displayName ?? user?.name ?? 'User';

  const roleConfigMap: Record<string, { label: string; color: string }> = {
    owner: { label: 'Owner', color: '#9932CC' },
    admin: { label: 'Admin', color: '#FF6464' },
    moderator: { label: 'Moderator', color: '#3BA55D' },
    helper: { label: 'Helper', color: '#5865F2' },
    qa: { label: 'QA', color: '#C084FC' },
    developer: { label: 'Developer', color: '#80FF97' },
    sponsor_plusplus: { label: 'Sponsor [++]', color: '#FFD700' },
    sponsor_plus_plus: { label: 'Sponsor [++]', color: '#FFD700' },
    sponsor_plus: { label: 'Sponsor [+]', color: '#6BB7FF' },
    sponsor: { label: 'Sponsor', color: '#80FF97' },
    vip: { label: 'VIP', color: '#6BB7FF' },
    user: { label: 'User', color: '#7A8A9E' },
  };
  const rc = roleConfigMap[effectiveRole] ?? roleConfigMap.user;
  const roleLabel = formatRoleName(effectiveRole, customRoleName || null);
  const roleColor = rc.color;

  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 800 }}>
        {/* Header */}
        <div className="text-center mb-10">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// SETTINGS</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(28px, 5vw, 42px)', color: '#E8E4E0' }}>Настройки профиля</h1>
          <p className="font-body text-sm mt-2" style={{ color: '#7A8A9E' }}>Управление видимостью и внешним видом профиля</p>
        </div>

        {/* Preview card */}
        <div className="rounded-xl p-6 mb-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
          <p className="font-mono text-[9px] tracking-[2px] uppercase mb-3" style={{ color: '#7A8A9E' }}>Превью профиля</p>
          <div className="flex flex-col items-center gap-3">
            {/* Face avatar + nick + role badge */}
            <div className="flex items-center gap-3">
              <div className="rounded-sm overflow-hidden flex-shrink-0" style={{ width: 64, height: 64 }}>
                {skinPreview ? (
                  <img
                    src={`/api/skin/face/${skinPreview.split('/').pop()}`}
                    alt=""
                    className="w-full h-full"
                    style={{ objectFit: 'cover', imageRendering: 'pixelated' }}
                    onError={(e) => { (e.target as HTMLImageElement).src = '/api/skin/face/steve.png'; }}
                  />
                ) : (
                  <img
                    src="/api/skin/face/steve.png"
                    alt="Steve"
                    className="w-full h-full"
                    style={{ objectFit: 'cover', imageRendering: 'pixelated' }}
                  />
                )}
              </div>
              <div className="flex flex-col justify-center gap-1.5">
                <span className="font-display text-lg font-bold" style={{
                  display: 'inline',
                  width: 'auto',
                  lineHeight: 1.2,
                  backgroundImage: hasGradientAccess ? `linear-gradient(135deg, ${nickFrom}, ${nickTo})` : undefined,
                  backgroundClip: hasGradientAccess ? 'text' : undefined,
                  WebkitBackgroundClip: hasGradientAccess ? 'text' : undefined,
                  WebkitTextFillColor: hasGradientAccess ? 'transparent' : undefined,
                  color: hasGradientAccess ? 'transparent' : '#E8E4E0',
                }}>{displayName}</span>
                <div className="flex items-center gap-1.5 flex-wrap">
                  {((profile as any)?.allRoles as string[] | undefined)?.map((r: string) => {
                    const cfg = roleConfigMap[r] ?? roleConfigMap.user;
                    const isPrimary = r === effectiveRole;
                    const iconPath = getRoleIconPath(r);
                    return (
                      <span key={r} className="font-mono text-[10px] px-2 py-0.5 rounded-full w-fit flex items-center gap-1" style={{
                        background: isPrimary && (roleFrom && roleTo) ? `linear-gradient(90deg, ${roleFrom}, ${roleTo})` : `${cfg.color}20`,
                        color: isPrimary && (roleFrom && roleTo) ? '#fff' : cfg.color,
                        border: `1px solid ${cfg.color}30`,
                      }}>
                        {iconPath && (
                          <img src={iconPath} alt="" className="w-3.5 h-3.5 inline-block" style={{ imageRendering: 'pixelated' }} onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                        )}
                        {formatRoleName(r, isPrimary ? (customRoleName || null) : null, roleDisplayNames)}
                      </span>
                    );
                  }) ?? (
                    <span className="font-mono text-[10px] px-2 py-0.5 rounded-full w-fit" style={{
                      background: (roleFrom && roleTo) ? `linear-gradient(90deg, ${roleFrom}, ${roleTo})` : `${roleColor}20`,
                      color: (roleFrom && roleTo) ? '#fff' : roleColor,
                      border: `1px solid ${roleColor}30`,
                    }}>{roleLabel}</span>
                  )}
                </div>
              </div>
            </div>

            {/* Full skin preview (3D) */}
            <div className="mx-auto" style={{ width: 140 }}>
              <SkinViewer3D
                size={140}
                skinUrl={skinPreview ?? undefined}
                skinModel={skinModel}
              />
            </div>
          </div>
        </div>

        {/* Visibility section */}
        <div className="rounded-xl p-5 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
          <div className="flex items-center gap-2 mb-1">
            <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>Видимость</span>
          </div>
          <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>Что видят другие игроки в вашем профиле</p>

          <Toggle label="Часы в игре" desc="Показывать количество наигранных часов" enabled={visibility.showHours} onChange={() => setVisibility(v => ({ ...v, showHours: !v.showHours }))} />
          <div className="h-px" style={{ background: 'rgba(255,255,255,0.04)' }} />
          <Toggle label="Дата регистрации MC" desc="Показывать дату регистрации в Minecraft" enabled={visibility.showMcJoined} onChange={() => setVisibility(v => ({ ...v, showMcJoined: !v.showMcJoined }))} />
          <div className="h-px" style={{ background: 'rgba(255,255,255,0.04)' }} />
          <Toggle label="Статус онлайн" desc="Показывать онлайн/офлайн статус" enabled={visibility.showOnline} onChange={() => setVisibility(v => ({ ...v, showOnline: !v.showOnline }))} />
          <div className="h-px" style={{ background: 'rgba(255,255,255,0.04)' }} />
          <Toggle label="Позиция в топе" desc="Показывать вашу позицию в таблице лидеров" enabled={visibility.showRank} onChange={() => setVisibility(v => ({ ...v, showRank: !v.showRank }))} />

          <button
            onClick={handleSaveGeneral}
            disabled={updateMutation.isPending || profileLoading}
            className="w-full mt-4 font-mono text-xs font-semibold uppercase tracking-[1px] py-3 rounded-xl transition-all hover:scale-[1.02] disabled:opacity-50"
            style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
          >
            {updateMutation.isPending ? 'Сохранение...' : 'Сохранить видимость'}
          </button>
        </div>

        {/* Skin upload + model */}
        <div className="rounded-xl p-5 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
          <div className="flex items-center gap-2 mb-1">
            <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>Скин</span>
          </div>
          <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>Загрузите свой скин (PNG 64x64) и выберите модель</p>

          <div className="flex items-center gap-3 mb-4">
            <button onClick={() => setSkinModel('classic')} className="flex-1 font-mono text-xs py-2.5 rounded-lg transition-all" style={{ background: skinModel === 'classic' ? 'linear-gradient(135deg, #80FF97, #6BB7FF)' : 'rgba(128,255,151,0.08)', color: skinModel === 'classic' ? '#0B0D12' : '#7A8A9E', border: `1px solid ${skinModel === 'classic' ? 'transparent' : 'rgba(128,255,151,0.15)'}` }}>Стив (Classic)</button>
            <button onClick={() => setSkinModel('slim')} className="flex-1 font-mono text-xs py-2.5 rounded-lg transition-all" style={{ background: skinModel === 'slim' ? 'linear-gradient(135deg, #80FF97, #6BB7FF)' : 'rgba(128,255,151,0.08)', color: skinModel === 'slim' ? '#0B0D12' : '#7A8A9E', border: `1px solid ${skinModel === 'slim' ? 'transparent' : 'rgba(128,255,151,0.15)'}` }}>Алекс (Slim)</button>
          </div>

          <div className="flex items-center gap-3">
            <label className="flex-1 cursor-pointer">
              <input type="file" accept="image/png" className="hidden" onChange={async (e) => {
                const file = e.target.files?.[0]; if (!file) return;
                const formData = new FormData(); formData.append('skin', file);
                try {
                  const res = await fetch('/api/upload/skin', { method: 'POST', body: formData, credentials: 'include' });
                  const data = await res.json();
                  if (data.success) { setSkinPreview(data.skinUrl); showToast('Скин загружен!'); utils.profile.me.invalidate(); }
                  else showToast(data.error || 'Ошибка загрузки');
                } catch { showToast('Ошибка загрузки'); }
              }} />
              <div className="font-mono text-xs text-center py-3 rounded-lg transition-all hover:scale-[1.02]" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97', border: '1px dashed rgba(128,255,151,0.25)' }}>{skinPreview ? 'Сменить скин' : 'Загрузить скин PNG'}</div>
            </label>
            {skinPreview && (
              <button onClick={() => setSkinPreview(null)} className="font-mono text-xs px-3 py-3 rounded-lg transition-all" style={{ background: 'rgba(255,100,100,0.08)', color: '#FF6464', border: '1px solid rgba(255,100,100,0.15)' }}>Удалить</button>
            )}
          </div>

          {skinPreview && (
            <div className="mt-3 flex items-center gap-2">
              <img src={skinPreview} alt="Skin preview" className="rounded" style={{ width: 64, height: 64, imageRendering: 'pixelated' }} />
              <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>Загруженный скин</span>
            </div>
          )}
        </div>

        {/* Gradients */}
        {hasGradientAccess && (
          <div className="rounded-xl p-5 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <div className="flex items-center gap-2 mb-1">
              <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>Градиенты</span>
              {!canEditGradient && <span className="font-mono text-[9px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(255,200,0,0.08)', color: '#FFD700' }}>Требуется Gradient Pass</span>}
              {canEditGradient && <span className="font-mono text-[9px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>Бесплатно</span>}
            </div>
            <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>
              {canEditGradient ? 'Кулдаун 24 часа для ника и роли — независимо друг от друга.' : 'Sponsor+ и выше могут менять бесплатно. Sponsor требует Gradient Pass (магазин).'}</p>

            {/* Nick presets */}
            <p className="font-mono text-[9px] uppercase mb-2" style={{ color: '#7A8A9E' }}>Пресеты ника</p>
            <div className="flex flex-wrap gap-2 mb-3">
              {GRADIENT_PRESETS.map((p) => (
                <button key={`nick-${p.label}`} onClick={() => { setNickFrom(p.from); setNickTo(p.to); }} className="flex items-center gap-1.5 font-mono text-[10px] px-2.5 py-1.5 rounded-lg transition-all hover:scale-[1.05]" style={{ background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)', color: '#C5CDD8' }}>
                  <div className="w-6 h-2 rounded-full" style={{ background: `linear-gradient(90deg, ${p.from}, ${p.to})` }} />{p.label}
                </button>
              ))}
            </div>

            {/* Role presets */}
            <p className="font-mono text-[9px] uppercase mb-2" style={{ color: '#7A8A9E' }}>Пресеты роли</p>
            <div className="flex flex-wrap gap-2 mb-4">
              {GRADIENT_PRESETS.map((p) => (
                <button key={`role-${p.label}`} onClick={() => { setRoleFrom(p.from); setRoleTo(p.to); }} className="flex items-center gap-1.5 font-mono text-[10px] px-2.5 py-1.5 rounded-lg transition-all hover:scale-[1.05]" style={{ background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)', color: '#C5CDD8' }}>
                  <div className="w-6 h-2 rounded-full" style={{ background: `linear-gradient(90deg, ${p.from}, ${p.to})` }} />{p.label}
                </button>
              ))}
            </div>

            {/* Nick gradient */}
            <div className="rounded-lg px-3 py-2 mb-4" style={{ background: 'rgba(11,13,18,0.5)', border: '1px solid rgba(128,255,151,0.06)' }}>
              <div className="flex items-center justify-between mb-2">
                <p className="font-mono text-[9px] uppercase" style={{ color: '#7A8A9E' }}>Ник</p>
                {nickCooldownHours !== null && (
                  <span className="font-mono text-[9px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(255,100,100,0.08)', color: '#FF6464' }}>Кулдаун: {nickCooldownHours}ч</span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <input type="color" value={nickFrom} onChange={(e) => setNickFrom(e.target.value)} className="w-8 h-8 rounded cursor-pointer border-0 p-0" style={{ background: 'transparent' }} />
                <span className="font-mono text-xs" style={{ color: '#C5CDD8' }}>{nickFrom}</span>
                <span style={{ color: '#7A8A9E' }}>→</span>
                <input type="color" value={nickTo} onChange={(e) => setNickTo(e.target.value)} className="w-8 h-8 rounded cursor-pointer border-0 p-0" style={{ background: 'transparent' }} />
                <span className="font-mono text-xs" style={{ color: '#C5CDD8' }}>{nickTo}</span>
                <div className="ml-auto w-16 h-3 rounded-full" style={{ background: `linear-gradient(90deg, ${nickFrom}, ${nickTo})` }} />
              </div>
              <button
                onClick={handleSaveNick}
                disabled={updateMutation.isPending || nickCooldownHours !== null}
                className="w-full mt-3 font-mono text-xs py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.15)' }}
              >
                {updateMutation.isPending ? 'Сохранение...' : 'Сохранить градиент ника'}
              </button>
            </div>



            {/* Role gradient */}
            <div className="rounded-lg px-3 py-2" style={{ background: 'rgba(11,13,18,0.5)', border: '1px solid rgba(128,255,151,0.06)' }}>
              <div className="flex items-center justify-between mb-2">
                <p className="font-mono text-[9px] uppercase" style={{ color: '#7A8A9E' }}>Роль</p>
                {roleCooldownHours !== null && (
                  <span className="font-mono text-[9px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(255,100,100,0.08)', color: '#FF6464' }}>Кулдаун: {roleCooldownHours}ч</span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <input type="color" value={roleFrom} onChange={(e) => setRoleFrom(e.target.value)} className="w-8 h-8 rounded cursor-pointer border-0 p-0" style={{ background: 'transparent' }} />
                <span className="font-mono text-xs" style={{ color: '#C5CDD8' }}>{roleFrom}</span>
                <span style={{ color: '#7A8A9E' }}>→</span>
                <input type="color" value={roleTo} onChange={(e) => setRoleTo(e.target.value)} className="w-8 h-8 rounded cursor-pointer border-0 p-0" style={{ background: 'transparent' }} />
                <span className="font-mono text-xs" style={{ color: '#C5CDD8' }}>{roleTo}</span>
                <div className="ml-auto w-16 h-3 rounded-full" style={{ background: `linear-gradient(90deg, ${roleFrom}, ${roleTo})` }} />
              </div>
              <button
                onClick={handleSaveRole}
                disabled={updateMutation.isPending || roleCooldownHours !== null}
                className="w-full mt-3 font-mono text-xs py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.15)' }}
              >
                {updateMutation.isPending ? 'Сохранение...' : 'Сохранить градиент роли'}
              </button>
            </div>
          </div>
        )}

        {!hasGradientAccess && (
          <div className="rounded-xl p-5 mb-6 text-center" style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <p className="font-mono text-xs mb-2" style={{ color: '#7A8A9E' }}>🎨 Градиенты ника и роли</p>
            <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Доступны для спонсоров, администраторов, QA и разработчиков</p>
          </div>
        )}

        {/* Custom Role Name (sponsor++ / owner / admin) */}
        {['sponsor_plusplus', 'owner', 'admin'].includes(effectiveRole) && (
          <div className="rounded-xl p-5 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <div className="flex items-center gap-2 mb-1">
              <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(255,200,0,0.08)', color: '#FFD700' }}>Имя роли</span>
              <span className="font-mono text-[9px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>{effectiveRole === 'sponsor_plusplus' ? 'Sponsor++' : effectiveRole.toUpperCase()}</span>
            </div>
            <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>Задайте собственное отображаемое имя роли (до 16 символов)</p>
            <div className="flex items-center gap-2">
              <input type="text" value={customRoleName} onChange={(e) => setCustomRoleName(e.target.value.slice(0, 16))} placeholder="MyRole" className="flex-1 font-mono text-xs px-3 py-2.5 rounded-lg outline-none" style={{ background: 'rgba(11,13,18,0.5)', border: '1px solid rgba(255,255,255,0.08)', color: '#E8E4E0' }} />
              <button onClick={() => updateRoleNameMutation.mutate({ customRoleName: customRoleName || null })} disabled={updateRoleNameMutation.isPending} className="font-mono text-xs px-4 py-2.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50" style={{ background: 'linear-gradient(135deg, #FFD700, #FF8C42)', color: '#0B0D12' }}>{updateRoleNameMutation.isPending ? '...' : 'Сохранить'}</button>
            </div>
            <p className="font-mono text-[9px] mt-2" style={{ color: '#7A8A9E' }}>Разрешено: a-z, 0-9, _ - [ ] ( ). Макс 16 символов.</p>
          </div>
        )}

        {/* HWID Reset / Account Key */}
        <div className="rounded-xl p-5 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
          <div className="flex items-center gap-2 mb-1">
            <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(255,100,100,0.08)', color: '#FF6464' }}>HWID</span>
          </div>
          <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>Сбросить привязку устройства и получить ключ для входа на новом ПК</p>
          <button onClick={() => { if (confirm('Сбросить HWID? Товар будет израсходован. Новый ключ будет скачан автоматически.')) resetHwidMutation.mutate(); }} disabled={resetHwidMutation.isPending} className="w-full font-mono text-xs py-3 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50" style={{ background: 'rgba(255,100,100,0.08)', color: '#FF6464', border: '1px solid rgba(255,100,100,0.15)' }}>{resetHwidMutation.isPending ? 'Обработка...' : 'Сбросить HWID (требуется товар)'}</button>

          {myInventory?.items.some((inv) => inv.storeItem?.sku === 'account_key' && inv.isActive === 'true') && (
            <button
              onClick={() => downloadKeyMutation.mutate()}
              disabled={downloadKeyMutation.isPending}
              className="w-full mt-3 font-mono text-xs py-3 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
              style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.15)' }}
            >
              {downloadKeyMutation.isPending ? 'Скачивание...' : 'Скачать ключ аккаунта (.properties)'}
            </button>
          )}

          <p className="font-mono text-[9px] mt-2" style={{ color: '#7A8A9E' }}>После сброса войдите в лаунчере через «Восстановить по ключу»</p>
        </div>

        {/* Inventory */}
        {myInventory && myInventory.items.length > 0 && (
          <div className="rounded-xl p-5 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <div className="flex items-center gap-2 mb-1">
              <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(107,183,255,0.08)', color: '#6BB7FF' }}>Инвентарь</span>
            </div>
            <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>Ваши приобретённые товары</p>
            <div className="space-y-2">
              {myInventory.items.map((inv) => {
                const item = inv.storeItem;
                const isExpired = inv.expiresAt ? new Date(inv.expiresAt) < new Date() : false;
                const daysLeft = inv.expiresAt
                  ? Math.max(0, Math.ceil((new Date(inv.expiresAt).getTime() - Date.now()) / (1000 * 60 * 60 * 24)))
                  : null;
                const isConsumed = inv.isActive === 'false';
                const acquiredLabel = inv.acquiredFrom === 'purchase'
                  ? 'Куплено'
                  : inv.acquiredFrom === 'key'
                    ? 'Ключ'
                    : inv.acquiredFrom === 'admin'
                      ? 'Выдано'
                      : inv.acquiredFrom;
                const statusLabel = isConsumed
                  ? 'Израсходовано'
                  : isExpired
                    ? 'Истёк'
                    : daysLeft !== null
                      ? `${daysLeft}д`
                      : 'Активно';
                return (
                  <div key={inv.id} className="flex items-center justify-between rounded-lg px-3 py-2" style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.04)' }}>
                    <div>
                      <p className="font-body text-xs font-medium" style={{ color: '#E8E4E0' }}>{item?.name ?? 'Неизвестно'}</p>
                      <p className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>
                        {acquiredLabel}
                        {inv.expiresAt && !isConsumed && ` · до ${new Date(inv.expiresAt).toLocaleDateString('ru-RU')}`}
                        {daysLeft !== null && !isExpired && !isConsumed && ` · ${daysLeft} дн.`}
                      </p>
                    </div>
                    <span className="font-mono text-[9px] px-2 py-0.5 rounded" style={{
                      background: isConsumed || isExpired ? 'rgba(255,100,100,0.08)' : 'rgba(128,255,151,0.08)',
                      color: isConsumed || isExpired ? '#FF6464' : '#80FF97',
                      border: `1px solid ${isConsumed || isExpired ? 'rgba(255,100,100,0.15)' : 'rgba(128,255,151,0.15)'}`,
                    }}>
                      {statusLabel}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {/* Toast */}
        {toast && (
          <div className="fixed bottom-8 left-1/2 -translate-x-1/2 z-[200] font-mono text-xs px-5 py-2.5 rounded-xl" style={{ background: 'rgba(128,255,151,0.12)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}>{toast}</div>
        )}
      </div>
    </div>
  );
}
