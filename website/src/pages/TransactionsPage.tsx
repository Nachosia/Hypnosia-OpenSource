import { useAuth } from '@/hooks/useAuth';
import { trpc } from '@/providers/trpc';

const typeLabels: Record<string, { label: string; color: string }> = {
  deposit: { label: 'Пополнение', color: '#80FF97' },
  withdraw: { label: 'Вывод', color: '#FF6464' },
  purchase: { label: 'Покупка', color: '#FF8C42' },
  refund: { label: 'Возврат', color: '#6BB7FF' },
};

export default function TransactionsPage() {
  const { user } = useAuth();
  const { data: transactions, isLoading } = trpc.transaction.list.useQuery();

  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 800 }}>
        {/* Header */}
        <div className="text-center mb-10">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#80FF97' }}>// BALANCE</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(28px, 5vw, 42px)', color: '#E8E4E0' }}>История транзакций</h1>
          <p className="font-body text-sm mt-2" style={{ color: '#7A8A9E' }}>
            Текущий баланс: <span className="font-mono font-bold" style={{ color: '#FFD700' }}>{(user as any)?.points ?? 0} HY-P</span>
          </p>
        </div>

        {/* Table */}
        <div className="rounded-xl overflow-hidden" style={{ background: 'rgba(15, 18, 24, 0.6)', backdropFilter: 'blur(12px)', border: '1px solid rgba(128, 255, 151, 0.1)' }}>
          <div className="grid gap-4 px-6 py-3 font-mono text-xs tracking-wide uppercase" style={{ gridTemplateColumns: '120px 1fr 100px 100px', color: '#7A8A9E', borderBottom: '1px solid rgba(128, 255, 151, 0.08)' }}>
            <span>Дата</span>
            <span>Описание</span>
            <span className="text-right">Тип</span>
            <span className="text-right">Сумма</span>
          </div>

          {isLoading ? (
            <div className="px-6 py-12 text-center">
              <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка...</p>
            </div>
          ) : !transactions || transactions.length === 0 ? (
            <div className="px-6 py-12 text-center">
              <p className="font-mono text-sm mb-2" style={{ color: '#7A8A9E' }}>История пуста</p>
              <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Активируйте код или совершите покупку</p>
            </div>
          ) : (
            transactions.map((tx) => {
              const tl = typeLabels[tx.type] ?? { label: tx.type, color: '#7A8A9E' };
              const isPositive = tx.amount > 0;
              return (
                <div
                  key={tx.id}
                  className="grid gap-4 px-6 py-3 items-center transition-colors duration-150 hover:bg-white/5"
                  style={{ gridTemplateColumns: '120px 1fr 100px 100px', borderBottom: '1px solid rgba(128, 255, 151, 0.03)' }}
                >
                  <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>
                    {new Date(tx.createdAt).toLocaleDateString('ru-RU')}
                  </span>
                  <span className="font-body text-sm truncate" style={{ color: '#E8E4E0' }}>{tx.description || '—'}</span>
                  <span className="font-mono text-xs text-right px-2 py-0.5 rounded justify-self-end" style={{ color: tl.color, background: `${tl.color}10` }}>{tl.label}</span>
                  <span className="font-mono text-sm text-right font-bold" style={{ color: isPositive ? '#80FF97' : '#FF6464' }}>
                    {isPositive ? '+' : ''}{tx.amount}
                  </span>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
