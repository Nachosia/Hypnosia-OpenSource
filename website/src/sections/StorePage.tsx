import { useState } from 'react';
import { useAuth } from '@/hooks/useAuth';
import { trpc } from '@/providers/trpc';
import { useNavigate } from 'react-router';

interface SponsorTierDef {
  id: string;
  name: string;
  badge: string;
  badgeColor: string;
  features: string[];
}

const tierDefs: Record<string, SponsorTierDef> = {
  sponsor: {
    id: 'sponsor',
    name: 'Спонсорка',
    badge: '',
    badgeColor: '#80FF97',
    features: [
      '10 дополнительных слотов',
      'GIF до 5 МБ',
      'Макс 3 конфига с GIF',
    ],
  },
  sponsor_plus: {
    id: 'sponsor_plus',
    name: 'Спонсорка +',
    badge: 'PLUS',
    badgeColor: '#6BB7FF',
    features: [
      '25 дополнительных слотов',
      'GIF до 10 МБ',
      'Макс 6 конфигов с GIF',
      'Бесплатная смена градиента ника и роли',
    ],
  },
  sponsor_plusplus: {
    id: 'sponsor_plusplus',
    name: 'Спонсорка ++',
    badge: 'PLUS PLUS',
    badgeColor: '#FFD700',
    features: [
      '70 дополнительных слотов',
      'GIF до 30 МБ',
      '20 конфигов с GIF',
      'Бесплатная смена ника и роли',
      'Сброс HWID без ограничений',
    ],
  },
};

const TIER_PRIORITY: Record<string, number> = {
  sponsor: 1,
  sponsor_plus: 2,
  sponsor_plusplus: 3,
};

const DAILY_RATES: Record<string, number> = {
  sponsor: 16,        // 500/30 = 16.66 → floor = 16
  sponsor_plus: 33,   // 1000/30 = 33.33 → floor = 33
  sponsor_plusplus: 100, // 3000/30 = 100
};

const MAX_SUBSCRIPTION_DAYS = 90;

const cosmeticItems = [
  { id: 'gradient', title: 'Градиент ник + роль', subtitle: 'Кастомный градиент для ника и роли в чате', price: 500, sku: 'gradient_pass' },
];

const techItems = [
  { id: 'hwid', title: 'Сброс HWID', subtitle: 'Сбросить привязку к железу', price: 1000, sku: 'hwid_reset' },
];

const pointPackages = [1000, 1500, 2000, 3500, 5000, 7500, 10000];

const FUNPAY_URLS: Record<number, string> = {
  1000: 'https://funpay.com/lots/offer?id=69983829',
  1500: 'https://funpay.com/lots/offer?id=69983890',
  2000: 'https://funpay.com/lots/offer?id=69983950',
  3500: 'https://funpay.com/lots/offer?id=69984037',
  5000: 'https://funpay.com/lots/offer?id=69984394',
  7500: 'https://funpay.com/lots/offer?id=69984417',
  10000: 'https://funpay.com/lots/offer?id=69984453',
};

function DiamondIcon({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 26 27" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M4.92503 4.43442C5.42503 3.26775 6.92503 0.934418 8.92503 0.934418C11.425 0.934418 13.925 1.43442 12.425 5.93442C10.925 10.4344 9.92503 18.9344 4.92503 23.9344C-0.0749726 28.9344 -0.190518 21.5885 1.42503 19.4344C2.92502 17.4344 4.92507 14.4344 11.9251 14.4344C14.2584 14.6011 17.925 14.4344 19.425 12.9344C21.9251 10.4344 23.925 8.43445 24.425 5.93442C25.2095 2.01209 22.9989 -1.13955 20.425 1.43442C18.925 2.93449 17.925 7.93442 16.925 11.4344C16.0895 14.3588 13.4251 20.4344 15.425 24.4344C17.425 28.4344 22.425 24.9344 22.925 23.9344M3.42505 7.93445C4.92503 6.93445 5.42501 8.43445 4.92503 8.93445C4.42505 9.43445 3.92505 9.93445 3.92505 10.9344C3.92505 11.7344 6.25838 10.2678 7.42505 9.43445C6.59172 10.9344 4.62505 13.9344 3.42505 13.9344C2.22505 13.9344 1.92505 12.6011 1.92505 11.9344M20.925 15.4344C20.8192 16.1755 20.3233 18.5171 19.668 20.4344M19.668 20.4344C19.2917 21.5355 18.8099 22.5496 18.425 22.9344C18.1929 23.1666 17.425 24.4344 17.425 21.9344C17.425 20.9344 17.925 19.9344 18.925 18.9344C19.9766 17.8829 22.425 16.9344 22.425 16.9344C23.425 16.6011 25.125 16.3344 23.925 17.9344C22.725 19.5344 20.587 20.2678 19.668 20.4344Z" stroke="url(#diamondGrad)" />
      <defs>
        <linearGradient id="diamondGrad" x1="12.5428" y1="0.5" x2="12.5428" y2="26.307" gradientUnits="userSpaceOnUse">
          <stop stopColor="#80FF97" />
          <stop offset="1" stopColor="#6BB7FF" />
        </linearGradient>
      </defs>
    </svg>
  );
}

export default function StorePage() {
  const { user, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const utils = trpc.useUtils();

  const [localPoints, setLocalPoints] = useState(0);
  const [showCodeModal, setShowCodeModal] = useState(false);
  const [showPaymentModal, setShowPaymentModal] = useState(false);
  const [selectedAmount, setSelectedAmount] = useState(0);
  const [codeInput, setCodeInput] = useState('');
  const [toast, setToast] = useState<string | null>(null);
  const [expandedTier, setExpandedTier] = useState<string | null>(null);
  const [purchaseResult, setPurchaseResult] = useState<{ type: 'key'; value: string } | { type: 'hwid'; newKey?: string } | null>(null);

  const points = user?.points ?? localPoints;

  const { data: storeItemsData } = trpc.store.items.useQuery();
  const { data: myInventory } = trpc.store.myInventory.useQuery();
  const { data: accountData } = trpc.account.me.useQuery(undefined, { enabled: isAuthenticated });

  const activeTier = (myInventory?.activeSubscription?.tier as string) ?? null;
  const activeTierPriority = activeTier ? (TIER_PRIORITY[activeTier] ?? 0) : 0;

  const subscriptionItems = storeItemsData?.filter((i) => i.type === 'subscription_key') ?? [];

  // Group by tier metadata
  const tierGroups = new Map<string, typeof subscriptionItems>();
  for (const item of subscriptionItems) {
    const tier = (item.metadata as Record<string, unknown>)?.tier as string ?? 'sponsor';
    if (!tierGroups.has(tier)) tierGroups.set(tier, []);
    tierGroups.get(tier)!.push(item);
  }

  // Sort tiers in defined order
  const tierOrder = ['sponsor', 'sponsor_plus', 'sponsor_plusplus'];
  const sortedTiers = tierOrder
    .filter((t) => tierGroups.has(t))
    .map((t) => ({ def: tierDefs[t], items: tierGroups.get(t)!.sort((a, b) => (a.durationDays ?? 0) - (b.durationDays ?? 0)) }));

  const purchaseMutation = trpc.store.purchase.useMutation({
    onSuccess: (data) => {
      utils.auth.me.invalidate();
      utils.store.myInventory.invalidate();
      utils.transaction.list.invalidate();
      utils.profile.me.invalidate();
      if (data.key) {
        setPurchaseResult({ type: 'key', value: data.key });
        showToast(data.isUpgrade ? 'Апгрейд выполнен! Ключ ниже.' : data.isRenewal ? 'Подписка продлена!' : 'Ключ сгенерирован! Скопируйте его ниже.');
      } else if (data.hwidResetResult?.success) {
        setPurchaseResult({ type: 'hwid', newKey: data.hwidResetResult.newAccountKey });
        showToast('HWID сброшен!');
      } else {
        showToast(`Куплено! Остаток: ${data.remainingBalance} HY-P`);
      }
    },
    onError: (err) => {
      const msg = err.message === 'INSUFFICIENT_FUNDS' ? 'Недостаточно HY-P' : err.message === 'ITEM_NOT_FOUND' ? 'Товар не найден' : err.message === 'ITEM_NOT_AVAILABLE' ? 'Товар недоступен' : err.message === 'ACCOUNT_NOT_LINKED' ? 'Привяжите аккаунт' : err.message === 'MINECRAFT_NOT_LINKED' ? 'Привяжите Minecraft-аккаунт' : err.message === 'LICENSE_SERVER_CREATE_FAILED' ? 'Ошибка License Server' : err.message?.includes('License Server') || err.message?.includes('LICENSE') ? 'Ошибка License Server: ' + err.message : 'Ошибка покупки';
      showToast(msg);
    },
  });

  const redeemMutation = trpc.code.redeem.useMutation({
    onSuccess: (data) => {
      utils.auth.me.invalidate();
      showToast(`+${data.points} HY-P!`);
      setCodeInput('');
      setShowCodeModal(false);
    },
    onError: (err) => {
      showToast(err.message === 'CODE_NOT_FOUND' ? 'Код не найден' : err.message === 'CODE_ALREADY_USED' ? 'Код уже использован' : err.message === 'MINECRAFT_NOT_LINKED' ? 'Привяжите Minecraft-аккаунт' : 'Ошибка активации');
    },
  });

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  };

  const handleSelectPackage = (amount: number) => {
    setSelectedAmount(amount);
    setShowPaymentModal(true);
  };

  const handleFunPayOpen = () => {
    const url = FUNPAY_URLS[selectedAmount];
    if (url) {
      window.open(url, '_blank');
    } else {
      window.open('https://funpay.com/users/000000/', '_blank');
    }
    setShowPaymentModal(false);
    setShowCodeModal(false);
    showToast('FunPay открыт в новой вкладке');
  };

  const handleActivateCode = () => {
    const val = codeInput.trim().toUpperCase();
    if (val.length >= 6) {
      redeemMutation.mutate({ code: val });
    } else {
      showToast('Введите корректный код');
    }
  };

  const handlePurchase = (sku: string, title: string, price: number) => {
    if (points >= price) {
      purchaseMutation.mutate({ sku });
    } else {
      showToast('Недостаточно HY-P!');
    }
  };

  function getDaysLeft(): number {
    if (!myInventory?.activeSubscription?.endsAt) return 0;
    return Math.max(0, Math.ceil((new Date(myInventory.activeSubscription.endsAt).getTime() - Date.now()) / (1000 * 60 * 60 * 24)));
  }

  function declineDays(n: number): string {
    if (n % 10 === 1 && n % 100 !== 11) return 'день';
    if ([2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100)) return 'дня';
    return 'дней';
  }

  function getItemDisplayPrice(item: typeof subscriptionItems[0]): { price: number; label: string; sublabel: string; title: string; isUpgrade: boolean; isRenewal: boolean; addDays: number; isHidden: boolean } {
    const itemTier = (item.metadata as Record<string, unknown>)?.tier as string ?? 'sponsor';
    const itemPriority = TIER_PRIORITY[itemTier] ?? 0;
    const isUpgrade = activeTierPriority > 0 && itemPriority > activeTierPriority;
    const isRenewal = activeTier === itemTier;
    const daysLeft = getDaysLeft();

    if (isUpgrade && myInventory?.activeSubscription) {
      const currentTier = myInventory.activeSubscription.tier as string;
      const currentDailyRate = DAILY_RATES[currentTier] ?? 16;
      const remainingValue = daysLeft * currentDailyRate;
      const upgradePrice = Math.max(0, item.priceCents - remainingValue);
      return { price: upgradePrice, label: 'Апгрейд', sublabel: `Доплатить ${upgradePrice} HY-P`, title: `${item.durationDays ?? 30} дней`, isUpgrade: true, isRenewal: false, addDays: 0, isHidden: false };
    }

    if (isRenewal) {
      const targetDays = Math.min(MAX_SUBSCRIPTION_DAYS, item.durationDays ?? 30);
      const addDays = Math.max(0, targetDays - daysLeft);
      const dailyRate = DAILY_RATES[itemTier] ?? Math.floor(item.priceCents / (item.durationDays ?? 30));
      const extensionPrice = addDays * dailyRate;
      if (daysLeft >= MAX_SUBSCRIPTION_DAYS) {
        return { price: 0, label: 'Максимум', sublabel: 'Максимальный срок достигнут', title: 'Максимум', isUpgrade: false, isRenewal: true, addDays: 0, isHidden: true };
      }
      if (addDays <= 0) {
        return { price: 0, label: '', sublabel: '', title: `${targetDays} дней`, isUpgrade: false, isRenewal: true, addDays: 0, isHidden: true };
      }
      const title = `+${addDays} ${declineDays(addDays)}`;
      const sublabel = `+${addDays}д × ${dailyRate} HY-P/день`;
      return { price: extensionPrice, label: 'Продлить', sublabel, title, isUpgrade: false, isRenewal: true, addDays, isHidden: false };
    }

    return { price: item.priceCents, label: '', sublabel: '', title: `${item.durationDays ?? 30} дней`, isUpgrade: false, isRenewal: false, addDays: 0, isHidden: false };
  }

  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 1000 }}>
        {/* Header */}
        <div className="text-center mb-8">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// STORE</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(32px, 6vw, 48px)', letterSpacing: '-1px', color: '#E8E4E0' }}>
            Магазин
          </h1>
        </div>

        {/* Auth guard */}
        {!isAuthenticated && (
          <div className="rounded-xl p-8 text-center mb-12" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
            <div className="w-16 h-16 rounded-2xl mx-auto mb-5 flex items-center justify-center" style={{ background: 'rgba(107,183,255,0.1)', border: '1px solid rgba(107,183,255,0.2)' }}>
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#6BB7FF" strokeWidth="1.5"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M13.8 12H3" /></svg>
            </div>
            <h2 className="font-display text-lg font-bold mb-2" style={{ color: '#E8E4E0' }}>Требуется авторизация</h2>
            <p className="font-body text-xs mb-5" style={{ color: '#7A8A9E' }}>Войдите через Discord, чтобы открыть магазин</p>
            <button
              onClick={() => navigate('/login')}
              className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02]"
              style={{ background: 'linear-gradient(135deg, #6BB7FF, #4D40FF)', color: '#0B0D12' }}
            >
              Войти через Discord
            </button>
          </div>
        )}

        {isAuthenticated && accountData && !accountData.minecraft && (
          <div className="rounded-xl p-8 text-center mb-12" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
            <div className="w-16 h-16 rounded-2xl mx-auto mb-5 flex items-center justify-center" style={{ background: 'rgba(255,215,0,0.1)', border: '1px solid rgba(255,215,0,0.2)' }}>
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#FFD700" strokeWidth="1.5"><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M9 21V9" /></svg>
            </div>
            <h2 className="font-display text-lg font-bold mb-2" style={{ color: '#E8E4E0' }}>Привяжите Minecraft-аккаунт</h2>
            <p className="font-body text-xs mb-5" style={{ color: '#7A8A9E' }}>Для доступа к магазину необходимо привязать Minecraft-аккаунт. Выполните команду <code className="font-mono text-[10px] px-1.5 py-0.5 rounded" style={{ background: 'rgba(255,255,255,0.05)', color: '#FFD700' }}>/hypnosia link</code> в игре.</p>
          </div>
        )}

        {/* Points balance */}
        {isAuthenticated && accountData?.minecraft && (
        <>
        <div className="flex justify-center mb-12">
          <button
            onClick={() => setShowCodeModal(true)}
            className="relative rounded-xl px-8 py-4 transition-all duration-200 hover:scale-[1.02]"
            style={{
              background: 'linear-gradient(135deg, rgba(128,255,151,0.1), rgba(107,183,255,0.1))',
              border: '1px solid rgba(128, 255, 151, 0.2)',
            }}
          >
            <div className="flex items-center gap-4">
              <div className="flex items-center justify-center" style={{ width: 36, height: 36 }}>
                <DiamondIcon size={26} />
              </div>
              <div className="text-left">
                <p className="font-mono text-[10px] tracking-[2px]" style={{ color: '#7A8A9E' }}>БАЛАНС HY-P</p>
                <p className="font-display text-2xl font-bold" style={{ color: '#E8E4E0' }}>{points.toLocaleString()}</p>
              </div>
              <div className="ml-4 font-mono text-[10px] px-3 py-1.5 rounded-lg" style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.15)' }}>
                + HY-P
              </div>
            </div>
          </button>
        </div>

        {/* Active subscription banner */}
        {activeTier && (
          <div className="rounded-xl p-4 mb-8 text-center" style={{ background: 'rgba(255,215,0,0.04)', border: '1px solid rgba(255,215,0,0.15)' }}>
            <p className="font-mono text-[10px] tracking-[2px] uppercase" style={{ color: '#FFD700' }}>
              Активна {tierDefs[activeTier]?.name ?? activeTier}
              {myInventory?.activeSubscription?.endsAt && (
                <span style={{ color: '#7A8A9E' }}>
                  {' '}· до {new Date(myInventory.activeSubscription.endsAt).toLocaleDateString('ru-RU')}
                  {' '}· {Math.max(0, Math.ceil((new Date(myInventory.activeSubscription.endsAt).getTime() - Date.now()) / (1000 * 60 * 60 * 24)))} дн. осталось
                </span>
              )}
            </p>
          </div>
        )}

        {/* Purchase Result Banner */}
        {purchaseResult && (
          <div className="rounded-xl p-5 mb-8 text-center" style={{ background: 'rgba(128,255,151,0.06)', border: '1px solid rgba(128,255,151,0.2)' }}>
            {purchaseResult.type === 'key' ? (
              <>
                <p className="font-mono text-[10px] tracking-[2px] uppercase mb-2" style={{ color: '#80FF97' }}>Ваш ключ подписки</p>
                <p className="font-mono text-sm font-bold mb-3" style={{ color: '#E8E4E0', wordBreak: 'break-all' }}>{purchaseResult.value}</p>
                <button
                  onClick={() => { navigator.clipboard.writeText(purchaseResult.value); showToast('Ключ скопирован!'); }}
                  className="font-mono text-[10px] font-semibold uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all hover:scale-[1.02]"
                  style={{ background: 'rgba(128,255,151,0.12)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}
                >
                  Скопировать ключ
                </button>
              </>
            ) : (
              <>
                <p className="font-mono text-[10px] tracking-[2px] uppercase mb-2" style={{ color: '#FF8C42' }}>HWID сброшен</p>
                <p className="font-body text-sm" style={{ color: '#E8E4E0' }}>
                  {purchaseResult.newKey ? `Новый ключ: ${purchaseResult.newKey}` : 'HWID успешно сброшен на License Server'}
                </p>
              </>
            )}
            <button onClick={() => setPurchaseResult(null)} className="block mx-auto mt-3 font-mono text-[10px]" style={{ color: '#7A8A9E' }}>Закрыть</button>
          </div>
        )}

        {/* Sponsor Tiers */}
        <div className="mb-6">
          <div className="flex items-center gap-3 mb-5">
            <div className="w-2 h-2 rounded-full" style={{ background: '#FFD700' }} />
            <h2 className="font-mono text-xs tracking-[3px] uppercase" style={{ color: '#A0AEBF' }}>Спонсорка</h2>
            <div className="flex-1 h-px" style={{ background: 'rgba(255,255,255,0.1)' }} />
          </div>

          {sortedTiers.length === 0 ? (
            <div className="rounded-xl p-6 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
              <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка товаров...</p>
            </div>
          ) : (
            <div className="space-y-4">
              {sortedTiers.map(({ def, items }) => {
                const tierPriority = TIER_PRIORITY[def.id] ?? 0;
                const isLowerThanActive = activeTierPriority > 0 && tierPriority < activeTierPriority;
                return (
                  <div
                    key={def.id}
                    className="rounded-xl overflow-hidden transition-all duration-200"
                    style={{
                      background: def.id === 'sponsor_plus' ? 'rgba(107,183,255,0.04)' : def.id === 'sponsor_plusplus' ? 'rgba(255,215,0,0.04)' : 'rgba(255,255,255,0.03)',
                      border: def.id === 'sponsor_plus' ? '1px solid rgba(107,183,255,0.2)' : def.id === 'sponsor_plusplus' ? '1px solid rgba(255,215,0,0.2)' : '1px solid rgba(255,255,255,0.08)',
                      opacity: isLowerThanActive ? 0.6 : 1,
                    }}
                  >
                    {/* Tier header */}
                    <div className="p-5">
                      <div className="flex items-center gap-3 mb-4">
                        <h3 className="font-display font-bold text-lg" style={{ color: def.badgeColor }}>{def.name}</h3>
                        {def.badge && (
                          <span className="font-mono text-[9px] tracking-[1px] uppercase px-2 py-0.5 rounded-full" style={{ background: `${def.badgeColor}20`, color: def.badgeColor, border: `1px solid ${def.badgeColor}40` }}>
                            {def.badge}
                          </span>
                        )}
                        {activeTier === def.id && (
                          <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}>Активна</span>
                        )}
                      </div>

                      {/* Price cards */}
                      <div className="grid grid-cols-3 gap-3 mb-4">
                        {items.map((item) => {
                          const { price: displayPrice, label, sublabel, title, isUpgrade, isRenewal, isHidden } = getItemDisplayPrice(item);
                          if (isHidden) return null;
                          const is90 = (item.durationDays ?? 0) >= 90;
                          const canAfford = points >= displayPrice;
                          return (
                            <div key={item.id} className="rounded-lg p-3 text-center relative" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
                              {is90 && !isRenewal && (
                                <span className="absolute -top-2 left-1/2 -translate-x-1/2 font-mono text-[8px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(255,215,0,0.12)', color: '#FFD700', border: '1px solid rgba(255,215,0,0.25)' }}>ВЫГОДНО</span>
                              )}
                              <p className="font-display font-bold" style={{ color: def.badgeColor }}>{title}</p>
                              {isRenewal && (
                                <p className="font-mono text-[8px] mt-0.5" style={{ color: '#7A8A9E' }}>(будет {item.durationDays ?? 30} дней)</p>
                              )}
                              <div className="flex items-center justify-center gap-1 mt-2">
                                <DiamondIcon size={12} />
                                <span className="font-display text-sm font-bold" style={{ color: '#E8E4E0' }}>
                                  {isUpgrade ? (
                                    <>
                                      <span style={{ textDecoration: 'line-through', color: '#7A8A9E', fontSize: 11 }}>{item.priceCents.toLocaleString()}</span>
                                      {' '}{displayPrice.toLocaleString()}
                                    </>
                                  ) : (
                                    displayPrice.toLocaleString()
                                  )}
                                </span>
                              </div>
                              {sublabel && (
                                <p className="font-mono text-[8px] mt-0.5" style={{ color: isUpgrade ? '#FFD700' : '#7A8A9E' }}>{sublabel}</p>
                              )}
                              <button
                                onClick={() => handlePurchase(item.sku, item.name, displayPrice)}
                                disabled={purchaseMutation.isPending || isLowerThanActive}
                                className="w-full mt-2 font-mono text-[10px] font-semibold uppercase tracking-[1px] py-2 rounded-md transition-all duration-200 hover:scale-[1.02] disabled:opacity-50"
                                style={{ background: canAfford && !isLowerThanActive ? `${def.badgeColor}18` : 'rgba(255,255,255,0.03)', color: canAfford && !isLowerThanActive ? def.badgeColor : '#7A8A9E', border: `1px solid ${canAfford && !isLowerThanActive ? `${def.badgeColor}30` : 'rgba(255,255,255,0.06)'}`, opacity: canAfford && !isLowerThanActive ? 1 : 0.6 }}
                              >
                                {isLowerThanActive ? 'Ниже текущего' : purchaseMutation.isPending ? '...' : label || 'Купить'}
                              </button>
                            </div>
                          );
                        })}
                      </div>

                      {/* Features toggle */}
                      <button
                        onClick={() => setExpandedTier(expandedTier === def.id ? null : def.id)}
                        className="font-mono text-[10px] tracking-[1px] uppercase transition-colors"
                        style={{ color: '#7A8A9E' }}
                      >
                        {expandedTier === def.id ? 'Скрыть' : 'Подробнее'} {'\u25BC'}
                      </button>

                      {expandedTier === def.id && (
                        <div className="mt-3 space-y-1.5">
                          {def.features.map((f, i) => (
                            <div key={i} className="flex items-center gap-2">
                              <div className="w-1 h-1 rounded-full flex-shrink-0" style={{ background: def.badgeColor }} />
                              <p className="font-body text-xs" style={{ color: '#A0AEBF' }}>{f}</p>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Cosmetics */}
        <div className="mb-6">
          <div className="flex items-center gap-3 mb-5">
            <div className="w-2 h-2 rounded-full" style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)' }} />
            <h2 className="font-mono text-xs tracking-[3px] uppercase" style={{ color: '#A0AEBF' }}>Косметика</h2>
            <div className="flex-1 h-px" style={{ background: 'rgba(255,255,255,0.1)' }} />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4" style={{ maxWidth: 500 }}>
            {cosmeticItems.map((item) => (
              <div key={item.id} className="rounded-xl p-5" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                <div className="flex items-center gap-3 mb-3">
                  <div className="w-10 h-10 rounded-lg flex items-center justify-center" style={{ background: 'linear-gradient(135deg, rgba(128,255,151,0.1), rgba(107,183,255,0.1))' }}>
                    <span style={{ fontSize: 18 }}>🎨</span>
                  </div>
                  <div>
                    <p className="font-display font-bold text-sm" style={{ color: '#E8E4E0' }}>{item.title}</p>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>{item.subtitle}</p>
                  </div>
                </div>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <DiamondIcon size={12} />
                    <span className="font-display font-bold" style={{ color: '#E8E4E0' }}>{item.price.toLocaleString()}</span>
                  </div>
                    <button
                    onClick={() => handlePurchase(item.sku, item.title, item.price)}
                    disabled={purchaseMutation.isPending}
                    className="font-mono text-[10px] font-semibold uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all duration-200 hover:scale-[1.02] disabled:opacity-50"
                    style={{ background: points >= item.price ? 'linear-gradient(135deg, #80FF97, #6BB7FF)' : 'rgba(255,255,255,0.05)', color: points >= item.price ? '#0B0D12' : '#7A8A9E', opacity: points >= item.price ? 1 : 0.5 }}
                  >
                    {purchaseMutation.isPending ? '...' : 'Купить'}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Tech */}
        <div className="mb-8">
          <div className="flex items-center gap-3 mb-5">
            <div className="w-2 h-2 rounded-full" style={{ background: '#FF8C42' }} />
            <h2 className="font-mono text-xs tracking-[3px] uppercase" style={{ color: '#A0AEBF' }}>Техническое</h2>
            <div className="flex-1 h-px" style={{ background: 'rgba(255,255,255,0.1)' }} />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4" style={{ maxWidth: 500 }}>
            {techItems.map((item) => (
              <div key={item.id} className="rounded-xl p-5" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                <div className="flex items-center gap-3 mb-3">
                  <div className="w-10 h-10 rounded-lg flex items-center justify-center" style={{ background: 'rgba(255,140,66,0.1)' }}>
                    <span style={{ fontSize: 18 }}>⚙️</span>
                  </div>
                  <div>
                    <p className="font-display font-bold text-sm" style={{ color: '#E8E4E0' }}>{item.title}</p>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>{item.subtitle}</p>
                  </div>
                </div>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <DiamondIcon size={12} />
                    <span className="font-display font-bold" style={{ color: '#E8E4E0' }}>{item.price.toLocaleString()}</span>
                  </div>
                  <button
                    onClick={() => handlePurchase(item.sku, item.title, item.price)}
                    disabled={purchaseMutation.isPending}
                    className="font-mono text-[10px] font-semibold uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all duration-200 hover:scale-[1.02] disabled:opacity-50"
                    style={{ background: points >= item.price ? '#FF8C42' : 'rgba(255,255,255,0.05)', color: points >= item.price ? '#0B0D12' : '#7A8A9E', opacity: points >= item.price ? 1 : 0.5 }}
                  >
                    {purchaseMutation.isPending ? '...' : 'Купить'}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </>)}
      </div>

      {/* Points + Code Modal */}
      {showCodeModal && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(8px)' }} onClick={() => setShowCodeModal(false)}>
          <div className="rounded-xl p-6 mx-4" style={{ background: '#141924', border: '1px solid rgba(128,255,151,0.15)', maxWidth: 400, width: '100%' }} onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="font-display font-bold" style={{ color: '#E8E4E0' }}>Пополнить баланс</h3>
              <button onClick={() => setShowCodeModal(false)} style={{ color: '#7A8A9E' }}>✕</button>
            </div>

            <p className="font-mono text-[10px] tracking-[1px] uppercase mb-3" style={{ color: '#7A8A9E' }}>Купить HY-P</p>
            <div className="grid grid-cols-3 gap-2 mb-5">
              {pointPackages.map((pkg) => (
                <button key={pkg} onClick={() => handleSelectPackage(pkg)} className="rounded-lg py-2 px-1 text-center transition-all duration-200 hover:scale-[1.02]" style={{ background: 'rgba(128,255,151,0.08)', border: '1px solid rgba(128,255,151,0.15)' }}>
                  <p className="font-display text-sm font-bold" style={{ color: '#E8E4E0' }}>{pkg.toLocaleString()}</p>
                </button>
              ))}
            </div>

            <div className="h-px mb-5" style={{ background: 'rgba(255,255,255,0.06)' }} />

            <p className="font-mono text-[10px] tracking-[1px] uppercase mb-3" style={{ color: '#7A8A9E' }}>Активировать код</p>
            <div className="flex gap-2">
              <input type="text" placeholder="Введите код..." value={codeInput} onChange={(e) => setCodeInput(e.target.value.replace(/\s/g, '').toUpperCase())} className="flex-1 bg-transparent outline-none font-mono text-sm px-4 py-3 rounded-lg placeholder:text-vanta-muted" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }} />
              <button onClick={handleActivateCode} disabled={redeemMutation.isPending} className="font-mono text-xs font-semibold uppercase tracking-[1px] px-4 py-3 rounded-lg transition-all duration-200 hover:scale-[1.02] disabled:opacity-50" style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}>{redeemMutation.isPending ? '...' : 'OK'}</button>
            </div>
          </div>
        </div>
      )}

      {/* FunPay Payment Modal */}
      {showPaymentModal && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(8px)' }} onClick={() => setShowPaymentModal(false)}>
          <div className="rounded-xl p-6 mx-4" style={{ background: '#141924', border: '1px solid rgba(128,255,151,0.15)', maxWidth: 380, width: '100%' }} onClick={(e) => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5">
              <h3 className="font-display font-bold" style={{ color: '#E8E4E0' }}>Пополнение HY-P</h3>
              <button onClick={() => setShowPaymentModal(false)} style={{ color: '#7A8A9E' }}>✕</button>
            </div>

            <div className="rounded-lg p-4 mb-5 text-center" style={{ background: 'rgba(128,255,151,0.04)', border: '1px solid rgba(128,255,151,0.1)' }}>
              <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#7A8A9E' }}>Сумма пополнения</p>
              <p className="font-display text-3xl font-bold" style={{ color: '#80FF97' }}>{selectedAmount.toLocaleString()} <span style={{ fontSize: 14 }}>HY-P</span></p>
            </div>

            <p className="font-mono text-[9px] tracking-[1px] uppercase mb-3" style={{ color: '#7A8A9E' }}>Выберите платёжную систему</p>

            {/* FunPay */}
            <button
              onClick={handleFunPayOpen}
              className="w-full rounded-xl p-4 mb-3 text-left transition-all hover:scale-[1.02]"
              style={{ background: 'rgba(255,140,66,0.08)', border: '1px solid rgba(255,140,66,0.2)' }}
            >
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-lg flex items-center justify-center" style={{ background: 'rgba(255,140,66,0.15)' }}>
                  <span className="font-display text-sm font-bold" style={{ color: '#FF8C42' }}>F</span>
                </div>
                <div>
                  <p className="font-display text-sm font-bold" style={{ color: '#E8E4E0' }}>FunPay</p>
                  <p className="font-body text-[10px]" style={{ color: '#7A8A9E' }}>Быстрая оплата картой</p>
                </div>
                <span className="ml-auto font-mono text-[9px] px-2 py-1 rounded" style={{ background: 'rgba(255,140,66,0.1)', color: '#FF8C42' }}>РЕКОМЕНДУЕМ</span>
              </div>
            </button>

            {/* Placeholder for future providers */}
            <div className="rounded-xl p-4 text-center" style={{ background: 'rgba(255,255,255,0.015)', border: '1px dashed rgba(255,255,255,0.06)' }}>
              <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Скоро: Crypto, FreeKassa</p>
            </div>
          </div>
        </div>
      )}

      {/* Toast */}
      {toast && (
        <div className="fixed bottom-8 left-1/2 -translate-x-1/2 z-[200] font-mono text-xs px-6 py-3 rounded-xl" style={{ background: 'rgba(128,255,151,0.15)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.25)', backdropFilter: 'blur(12px)' }}>
          {toast}
        </div>
      )}
    </div>
  );
}
