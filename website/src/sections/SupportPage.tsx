import { useState, useEffect } from 'react';
import { useAuth } from '@/hooks/useAuth';
import { useNavigate, useSearchParams } from 'react-router';
import { trpc } from '@/providers/trpc';
import AttachmentPreview from '@/components/AttachmentPreview';

const FAQS = [
  {
    q: 'Как привязать Minecraft-аккаунт?',
    a: 'Войдите на сервер и выполните команду /hypnosia link. После этого ваш Discord и Minecraft будут связаны.',
  },
  {
    q: 'Где скачать клиент?',
    a: 'Ссылка на скачивание доступна в Discord-сервере проекта в канале #downloads.',
  },
  {
    q: 'Как пополнить баланс HY-P?',
    a: 'Перейдите в раздел Store → нажмите на баланс → выберите способ пополнения (FunPay) или активируйте код.',
  },
  {
    q: 'Что делать если слетела привязка HWID?',
    a: 'В магазине (Store) есть пункт "Сброс HWID" за 1000 HY-P. После покупки привязка к железу сбросится.',
  },
  {
    q: 'Как получить роль Sponsor?',
    a: 'Купите подписку Sponsor в разделе Store. Ключ автоматически активируется на License Server.',
  },
  {
    q: 'Почему не работает GIF-конфиг?',
    a: 'GIF-конфиги проходят модерацию. Убедитесь, что ваш конфиг одобрен администратором. Статус можно проверить в профиле.',
  },
];

const TICKET_CATEGORIES: Record<string, { label: string; color: string; bg: string }> = {
  technical: { label: 'Техническая', color: '#FF8C42', bg: 'rgba(255,140,66,0.1)' },
  payment: { label: 'Оплата', color: '#FFD700', bg: 'rgba(255,215,0,0.1)' },
  account: { label: 'Аккаунт', color: '#6BB7FF', bg: 'rgba(107,183,255,0.1)' },
  bug: { label: 'Баг', color: '#ff6464', bg: 'rgba(255,100,100,0.1)' },
  other: { label: 'Другое', color: '#7A8A9E', bg: 'rgba(122,138,158,0.1)' },
};

const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string }> = {
  open: { label: 'Открыт', color: '#6BB7FF', bg: 'rgba(107,183,255,0.1)' },
  answered: { label: 'Есть ответ', color: '#FFD700', bg: 'rgba(255,215,0,0.1)' },
  closed: { label: 'Закрыт', color: '#80FF97', bg: 'rgba(128,255,151,0.1)' },
};

function formatDate(d: string | Date | null) {
  if (!d) return '';
  return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export default function SupportPage() {
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const urlTab = searchParams.get('tab');
  const utils = trpc.useUtils();

  const [activeTab, setActiveTab] = useState<'faq' | 'new' | 'my'>(
    urlTab === 'my' ? 'my' : urlTab === 'new' ? 'new' : 'faq'
  );
  const [openFaq, setOpenFaq] = useState<number | null>(null);
  const [ticketCategory, setTicketCategory] = useState('technical');
  const [ticketSubject, setTicketSubject] = useState('');
  const [ticketDescription, setTicketDescription] = useState('');
  const [ticketFiles, setTicketFiles] = useState<File[]>([]);
  const [selectedTicketId, setSelectedTicketId] = useState<number | null>(null);
  const [replyText, setReplyText] = useState('');
  const [replyFiles, setReplyFiles] = useState<File[]>([]);

  const urlTicketId = searchParams.get('ticket');

  useEffect(() => {
    if (urlTicketId) {
      const id = Number(urlTicketId);
      if (!isNaN(id) && id > 0) {
        setSelectedTicketId(id);
        setActiveTab('my');
        return;
      }
    }
    if (urlTab === 'my') setActiveTab('my');
    else if (urlTab === 'new') setActiveTab('new');
  }, [urlTab, urlTicketId]);

  const { data: ticketsData, isLoading: ticketsLoading } = trpc.ticket.list.useQuery(undefined, {
    enabled: isAuthenticated,
  });

  const { data: ticketDetail, isLoading: detailLoading, error: ticketDetailError } = trpc.ticket.getById.useQuery(
    { id: selectedTicketId! },
    { enabled: selectedTicketId !== null && isAuthenticated }
  );

  const createMutation = trpc.ticket.create.useMutation({
    onSuccess: () => {
      utils.ticket.list.invalidate();
      setActiveTab('my');
      setTicketSubject('');
      setTicketDescription('');
      setTicketCategory('technical');
    },
  });

  const messageMutation = trpc.ticket.message.useMutation({
    onSuccess: () => {
      utils.ticket.getById.invalidate({ id: selectedTicketId! });
      utils.ticket.list.invalidate();
      setReplyText('');
    },
  });

  const uploadFiles = async (files: File[], ticketId: number): Promise<{ url: string; name: string; size: number }[]> => {
    const formData = new FormData();
    files.forEach(f => formData.append('file', f));
    formData.append('ticketId', String(ticketId));
    try {
      const res = await fetch('/api/upload/ticket', {
        method: 'POST',
        body: formData,
      });
      if (!res.ok) {
        const err = await res.json();
        throw new Error(err.error || 'Upload failed');
      }
      const data = await res.json();
      return data.files || [];
    } catch (e: any) {
      console.error('Upload error:', e);
      alert('Ошибка загрузки файла: ' + e.message);
      return [];
    }
  };

  const handleSubmitTicket = async () => {
    if (!ticketSubject.trim() || !ticketDescription.trim()) return;

    // Upload files first (need ticketId, but we don't have it yet... so create ticket first, then upload)
    const result = await createMutation.mutateAsync({
      title: ticketSubject.trim(),
      description: ticketDescription.trim(),
      category: ticketCategory,
    });

    const ticketId = result.id;

    if (ticketFiles.length > 0 && ticketId) {
      const uploaded = await uploadFiles(ticketFiles, ticketId);
      if (uploaded.length > 0) {
        await messageMutation.mutateAsync({
          ticketId,
          content: ticketDescription.trim(),
          attachments: uploaded,
        });
      }
    }

    setTicketFiles([]);
  };

  const handleSendReply = async () => {
    if ((!replyText.trim() && replyFiles.length === 0) || selectedTicketId === null) return;
    let attachments: { url: string; name: string; size: number }[] | undefined;

    if (replyFiles.length > 0) {
      attachments = await uploadFiles(replyFiles, selectedTicketId);
    }

    messageMutation.mutate({
      ticketId: selectedTicketId,
      content: replyText.trim() || '(Файл)',
      attachments,
    });
    setReplyFiles([]);
  };

    return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 900 }}>
        {/* Header */}
        <div className="text-center mb-10">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#6BB7FF' }}>// SUPPORT</p>
          <h1 className="font-display font-bold mb-3" style={{ fontSize: 'clamp(32px, 6vw, 48px)', color: '#E8E4E0' }}>
            Центр поддержки
          </h1>
          <p className="font-body text-base max-w-md mx-auto" style={{ color: '#7A8A9E' }}>
            FAQ, тикеты и контакты команды Hypnosia
          </p>
        </div>

        {/* Discord quick link */}
        <div className="rounded-xl p-5 mb-8 flex items-center justify-between gap-4" style={{ background: 'rgba(88,101,242,0.08)', border: '1px solid rgba(88,101,242,0.2)' }}>
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg flex items-center justify-center" style={{ background: 'rgba(88,101,242,0.15)' }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#5865F2" strokeWidth="2"><path d="M18 9a5 5 0 0 0-5-5H9a5 5 0 0 0-5 5v6a5 5 0 0 0 5 5h4a5 5 0 0 0 5-5V9z"/><path d="M9 12h6M12 9v6"/></svg>
            </div>
            <div>
              <p className="font-display text-sm font-semibold" style={{ color: '#E8E4E0' }}>Discord сервер</p>
              <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Получайте уведомления об аккаунте и ответах на тикеты</p>
            </div>
          </div>
          <a
            href="https://discord.gg/gyZnEaG3ub"
            target="_blank"
            rel="noreferrer"
            className="font-mono text-[10px] font-semibold uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all hover:scale-[1.02]"
            style={{ background: '#5865F2', color: '#fff' }}
          >
            Перейти
          </a>
        </div>

        {/* Tabs */}
        <div className="flex items-center justify-center gap-2 mb-8 flex-wrap">
          {([
            { key: 'faq', label: 'FAQ', icon: '❓' },
            { key: 'new', label: 'Новый тикет', icon: '✏️' },
            { key: 'my', label: 'Мои тикеты', icon: '🎫' },
          ] as const).map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className="font-mono text-xs font-semibold uppercase tracking-[1px] px-5 py-2.5 rounded-lg transition-all duration-200 flex items-center gap-2"
              style={{
                background: activeTab === tab.key ? 'linear-gradient(135deg, #6BB7FF, #4D40FF)' : 'rgba(255,255,255,0.03)',
                color: activeTab === tab.key ? '#0B0D12' : '#7A8A9E',
                border: `1px solid ${activeTab === tab.key ? 'rgba(107,183,255,0.3)' : 'rgba(255,255,255,0.06)'}`,
                transform: activeTab === tab.key ? 'scale(1.02)' : 'scale(1)',
              }}
            >
              <span>{tab.icon}</span>
              {tab.label}
            </button>
          ))}
        </div>

        {/* ─── TAB: FAQ ─── */}
        {activeTab === 'faq' && (
          <div className="space-y-2">
            {FAQS.map((faq, idx) => (
              <div
                key={idx}
                className="rounded-xl overflow-hidden"
                style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)' }}
              >
                <button
                  onClick={() => setOpenFaq(openFaq === idx ? null : idx)}
                  className="w-full flex items-center justify-between px-5 py-4 text-left transition-colors hover:bg-white/[0.02]"
                >
                  <span className="font-body text-sm" style={{ color: '#E8E4E0' }}>{faq.q}</span>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#7A8A9E" strokeWidth="2" style={{ transform: openFaq === idx ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s', flexShrink: 0 }}>
                    <polyline points="6 9 12 15 18 9" />
                  </svg>
                </button>
                {openFaq === idx && (
                  <div className="px-5 pb-4">
                    <p className="font-body text-xs leading-relaxed" style={{ color: '#7A8A9E' }}>{faq.a}</p>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {/* ─── TAB: NEW TICKET ─── */}
        {activeTab === 'new' && (
          <>
            {!isAuthenticated ? (
              <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                <div className="w-16 h-16 rounded-2xl mx-auto mb-5 flex items-center justify-center" style={{ background: 'rgba(107,183,255,0.1)', border: '1px solid rgba(107,183,255,0.2)' }}>
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#6BB7FF" strokeWidth="1.5"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M13.8 12H3" /></svg>
                </div>
                <h2 className="font-display text-lg font-bold mb-2" style={{ color: '#E8E4E0' }}>Требуется авторизация</h2>
                <p className="font-body text-xs mb-5" style={{ color: '#7A8A9E' }}>Войдите через Discord, чтобы создать тикет</p>
                <button
                  onClick={() => navigate('/login')}
                  className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02]"
                  style={{ background: 'linear-gradient(135deg, #6BB7FF, #4D40FF)', color: '#0B0D12' }}
                >
                  Войти через Discord
                </button>
              </div>
            ) : createMutation.isSuccess ? (
              <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(128,255,151,0.04)', border: '1px solid rgba(128,255,151,0.2)' }}>
                <div className="w-16 h-16 rounded-2xl mx-auto mb-5 flex items-center justify-center" style={{ background: 'rgba(128,255,151,0.1)', border: '1px solid rgba(128,255,151,0.2)' }}>
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#80FF97" strokeWidth="2"><polyline points="20 6 9 17 4 12" /></svg>
                </div>
                <h2 className="font-display text-lg font-bold mb-2" style={{ color: '#E8E4E0' }}>Тикет создан</h2>
                <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Команда поддержки получила уведомление. Ответ придёт в Discord.</p>
              </div>
            ) : (
              <div className="rounded-xl p-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                <div className="flex flex-col gap-3">
                  <div>
                    <p className="font-mono text-[9px] uppercase tracking-[1px] mb-2" style={{ color: '#7A8A9E' }}>Категория</p>
                    <div className="flex flex-wrap gap-2">
                      {Object.entries(TICKET_CATEGORIES).map(([key, cat]) => (
                        <button
                          key={key}
                          onClick={() => setTicketCategory(key)}
                          className="font-mono text-[10px] uppercase tracking-[1px] px-3 py-1.5 rounded-lg transition-all"
                          style={{
                            background: ticketCategory === key ? `${cat.color}18` : 'rgba(255,255,255,0.03)',
                            color: ticketCategory === key ? cat.color : '#7A8A9E',
                            border: `1px solid ${ticketCategory === key ? `${cat.color}30` : 'rgba(255,255,255,0.06)'}`,
                          }}
                        >
                          {cat.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <input
                    type="text"
                    placeholder="Тема обращения"
                    value={ticketSubject}
                    onChange={(e) => setTicketSubject(e.target.value)}
                    className="w-full font-body text-sm px-4 py-3 rounded-lg outline-none"
                    style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                  />

                  <textarea
                    placeholder="Опишите проблему подробно..."
                    value={ticketDescription}
                    onChange={(e) => setTicketDescription(e.target.value)}
                    rows={5}
                    className="w-full font-body text-sm px-4 py-3 rounded-lg outline-none resize-none"
                    style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                  />

                  {/* File attachment */}
                  <div>
                    <label className="flex items-center gap-2 cursor-pointer">
                      <input
                        type="file"
                        multiple
                        className="hidden"
                        onChange={(e) => {
                          const files = Array.from(e.target.files || []);
                          if (files.length > 3) {
                            alert('Максимум 3 файла за раз');
                            setTicketFiles(files.slice(0, 3));
                          } else {
                            setTicketFiles(files);
                          }
                        }}
                        accept="image/*,video/*,.pdf,.txt,.zip"
                      />
                      <span className="font-mono text-[10px] uppercase tracking-[1px] px-3 py-1.5 rounded-lg transition-all hover:bg-white/5" style={{ color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}>
                        📎 Прикрепить файлы
                      </span>
                    </label>
                    {ticketFiles.length > 0 && (
                      <div className="flex flex-wrap gap-2 mt-2">
                        {ticketFiles.map((f, i) => (
                          <span key={i} className="font-body text-[10px] px-2 py-1 rounded" style={{ color: '#80FF97', background: 'rgba(128,255,151,0.08)', border: '1px solid rgba(128,255,151,0.15)' }}>
                            {f.name} ({(f.size / 1024 / 1024).toFixed(2)} MB)
                            <button onClick={() => setTicketFiles(prev => prev.filter((_, idx) => idx !== i))} className="ml-1" style={{ color: '#ff6464' }}>×</button>
                          </span>
                        ))}
                      </div>
                    )}
                    <p className="font-mono text-[9px] mt-1" style={{ color: '#7A8A9E' }}>Макс. 3 файла по 5 MB каждый. Всего на тикет — 10 файлов.</p>
                  </div>

                  {createMutation.error && (
                    <p className="font-body text-xs" style={{ color: '#ff6464' }}>
                      {createMutation.error.message}
                    </p>
                  )}

                  <div className="flex items-center gap-2">
                    <button
                      onClick={handleSubmitTicket}
                      disabled={!ticketSubject.trim() || !ticketDescription.trim() || createMutation.isPending}
                      className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                      style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                    >
                      {createMutation.isPending ? 'Отправка...' : 'Отправить'}
                    </button>
                    <button
                      onClick={() => setActiveTab('faq')}
                      className="font-mono text-xs uppercase px-4 py-2.5 rounded-lg"
                      style={{ color: '#7A8A9E' }}
                    >
                      Отмена
                    </button>
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {/* ─── TAB: MY TICKETS ─── */}
        {activeTab === 'my' && (
          <>
            {!isAuthenticated ? (
              <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                <div className="w-16 h-16 rounded-2xl mx-auto mb-5 flex items-center justify-center" style={{ background: 'rgba(107,183,255,0.1)', border: '1px solid rgba(107,183,255,0.2)' }}>
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#6BB7FF" strokeWidth="1.5"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M13.8 12H3" /></svg>
                </div>
                <h2 className="font-display text-lg font-bold mb-2" style={{ color: '#E8E4E0' }}>Требуется авторизация</h2>
                <p className="font-body text-xs mb-5" style={{ color: '#7A8A9E' }}>Войдите через Discord, чтобы просмотреть свои тикеты</p>
                <button
                  onClick={() => navigate('/login')}
                  className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02]"
                  style={{ background: 'linear-gradient(135deg, #6BB7FF, #4D40FF)', color: '#0B0D12' }}
                >
                  Войти через Discord
                </button>
              </div>
            ) : selectedTicketId !== null ? (
              /* Ticket detail view */
              <div>
                <button
                  onClick={() => setSelectedTicketId(null)}
                  className="font-mono text-xs uppercase mb-4 flex items-center gap-1 transition-colors hover:text-[#6BB7FF]"
                  style={{ color: '#7A8A9E' }}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="15 18 9 12 15 6" /></svg>
                  Назад к списку
                </button>
                {detailLoading ? (
                  <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Загрузка...</p>
                  </div>
                ) : ticketDetailError ? (
                  <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,100,100,0.03)', border: '1px solid rgba(255,100,100,0.2)' }}>
                    <p className="font-body text-sm mb-2" style={{ color: '#ff6464' }}>❌ {ticketDetailError.message}</p>
                    <button
                      onClick={() => { setSelectedTicketId(null); navigate('/support?tab=my'); }}
                      className="font-mono text-xs uppercase px-4 py-2 rounded-lg transition-colors hover:text-[#6BB7FF]"
                      style={{ color: '#7A8A9E' }}
                    >
                      Назад к списку
                    </button>
                  </div>
                ) : ticketDetail ? (
                  <div className="rounded-xl p-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <div className="flex items-center gap-3 mb-4">
                      <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>#{ticketDetail.ticket.id}</span>
                      <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: TICKET_CATEGORIES[ticketDetail.ticket.category]?.bg ?? 'rgba(255,255,255,0.05)', color: TICKET_CATEGORIES[ticketDetail.ticket.category]?.color ?? '#7A8A9E', border: `1px solid ${TICKET_CATEGORIES[ticketDetail.ticket.category]?.color ?? '#7A8A9E'}30` }}>
                        {TICKET_CATEGORIES[ticketDetail.ticket.category]?.label ?? ticketDetail.ticket.category}
                      </span>
                      <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: STATUS_CONFIG[ticketDetail.ticket.status]?.bg, color: STATUS_CONFIG[ticketDetail.ticket.status]?.color }}>
                        {STATUS_CONFIG[ticketDetail.ticket.status]?.label}
                      </span>
                    </div>
                    <h2 className="font-display font-semibold text-base mb-1" style={{ color: '#E8E4E0' }}>{ticketDetail.ticket.title}</h2>
                    <p className="font-mono text-[10px] mb-6" style={{ color: '#7A8A9E' }}>Создан: {formatDate(ticketDetail.ticket.createdAt)}</p>

                    {/* Messages */}
                    <div className="space-y-3">
                      {ticketDetail.messages.map((msg: any) => {
                        const isAdmin = msg.senderType === 'admin';
                        const isSystem = msg.senderType === 'system';
                        return (
                          <div
                            key={msg.id}
                            className="rounded-lg p-3"
                            style={{
                              background: isSystem ? 'rgba(255,255,255,0.03)' : isAdmin ? 'rgba(128,255,151,0.05)' : 'rgba(107,183,255,0.05)',
                              border: `1px solid ${isSystem ? 'rgba(255,255,255,0.06)' : isAdmin ? 'rgba(128,255,151,0.1)' : 'rgba(107,183,255,0.1)'}`,
                            }}
                          >
                            <div className="flex items-center gap-2 mb-1">
                              <span className="font-mono text-[9px] uppercase" style={{ color: isSystem ? '#7A8A9E' : isAdmin ? '#80FF97' : '#6BB7FF' }}>
                                {isSystem ? 'System' : isAdmin ? msg.senderName + ' (Admin)' : msg.senderName ?? 'Вы'}
                              </span>
                              <span className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>{formatDate(msg.createdAt)}</span>
                            </div>
                            <p className="font-body text-xs whitespace-pre-wrap" style={{ color: '#C5CDD8' }}>{msg.content}</p>
                            <AttachmentPreview attachments={msg.attachments || []} />
                          </div>
                        );
                      })}
                    </div>

                    {ticketDetail.ticket.status === 'closed' && ticketDetail.ticket.closeReason && (
                      <div className="mt-4 rounded-lg p-3" style={{ background: 'rgba(255,100,100,0.04)', border: '1px solid rgba(255,100,100,0.15)' }}>
                        <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#ff6464' }}>Причина закрытия</p>
                        <p className="font-body text-xs" style={{ color: '#C5CDD8' }}>{ticketDetail.ticket.closeReason}</p>
                      </div>
                    )}

                    {ticketDetail.ticket.status !== 'closed' && (
                      <div className="mt-4">
                        <textarea
                          placeholder="Написать ответ..."
                          value={replyText}
                          onChange={(e) => setReplyText(e.target.value)}
                          rows={3}
                          className="w-full font-body text-sm px-4 py-3 rounded-lg outline-none resize-none"
                          style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                        />
                        <div className="flex items-center justify-between mt-2">
                          <label className="flex items-center gap-2 cursor-pointer">
                            <input
                              type="file"
                              multiple
                              className="hidden"
                              onChange={(e) => {
                                const files = Array.from(e.target.files || []);
                                if (files.length > 3) {
                                  alert('Максимум 3 файла за раз');
                                  setReplyFiles(files.slice(0, 3));
                                } else {
                                  setReplyFiles(files);
                                }
                              }}
                              accept="image/*,video/*,.pdf,.txt,.zip"
                            />
                            <span className="font-mono text-[10px] uppercase tracking-[1px] px-3 py-1.5 rounded-lg transition-all hover:bg-white/5" style={{ color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}>
                              📎 Прикрепить файлы
                            </span>
                          </label>
                          {replyFiles.length > 0 && (
                            <div className="flex flex-wrap gap-2">
                              {replyFiles.map((f, i) => (
                                <span key={i} className="font-body text-[10px] px-2 py-1 rounded" style={{ color: '#80FF97', background: 'rgba(128,255,151,0.08)', border: '1px solid rgba(128,255,151,0.15)' }}>
                                  {f.name} ({(f.size / 1024 / 1024).toFixed(2)} MB)
                                  <button onClick={() => setReplyFiles(prev => prev.filter((_, idx) => idx !== i))} className="ml-1" style={{ color: '#ff6464' }}>×</button>
                                </span>
                              ))}
                            </div>
                          )}
                          <button
                            onClick={handleSendReply}
                            disabled={(!replyText.trim() && replyFiles.length === 0) || messageMutation.isPending}
                            className="font-mono text-xs font-semibold uppercase px-4 py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                            style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                          >
                            {messageMutation.isPending ? 'Отправка...' : 'Ответить'}
                          </button>
                        </div>
                        {messageMutation.error && (
                          <p className="font-body text-xs mt-1" style={{ color: '#ff6464' }}>{messageMutation.error.message}</p>
                        )}
                      </div>
                    )}
                  </div>
                ) : null}
              </div>
            ) : (
              <div>
                {ticketsLoading ? (
                  <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Загрузка...</p>
                  </div>
                ) : !ticketsData || ticketsData.length === 0 ? (
                  <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>У вас пока нет тикетов</p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {ticketsData.map((ticket: any) => {
                      const cat = TICKET_CATEGORIES[ticket.category];
                      const st = STATUS_CONFIG[ticket.status];
                      return (
                        <button
                          key={ticket.id}
                          onClick={() => setSelectedTicketId(ticket.id)}
                          className="w-full text-left rounded-xl p-4 transition-all hover:bg-white/[0.02]"
                          style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)' }}
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-1">
                                <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>#{ticket.id}</span>
                                <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: cat?.bg ?? 'rgba(255,255,255,0.05)', color: cat?.color ?? '#7A8A9E', border: `1px solid ${cat?.color ?? '#7A8A9E'}30` }}>
                                  {cat?.label ?? ticket.category}
                                </span>
                                <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: st?.bg, color: st?.color }}>
                                  {st?.label}
                                </span>
                              </div>
                              <p className="font-body text-sm truncate" style={{ color: '#E8E4E0' }}>{ticket.title}</p>
                              <p className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>{formatDate(ticket.createdAt)}</p>
                            </div>
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#7A8A9E" strokeWidth="2"><polyline points="9 18 15 12 9 6" /></svg>
                          </div>
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
