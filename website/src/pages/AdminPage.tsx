import { useState, useEffect } from 'react';
import { useAuth } from '@/hooks/useAuth';
import { trpc } from '@/providers/trpc';
import AttachmentPreview from '@/components/AttachmentPreview';

const LICENSE_SERVER_URL = import.meta.env.VITE_LICENSE_SERVER_URL || 'http://127.0.0.1:8080';

export default function AdminPage() {
  const { user, isLoading: authLoading } = useAuth();
  const [sessionVerified, setSessionVerified] = useState(false);
  const [totpCode, setTotpCode] = useState('');
  const [showSetup2FA, setShowSetup2FA] = useState(false);
  const [setupSecret, setSetupSecret] = useState('');
  const [setupToken, setSetupToken] = useState('');

  // Tabs
  const [activeTab, setActiveTab] = useState<'main' | 'gifs' | 'accounts' | 'roadmap' | 'tickets'>('main');

  // Accounts sub-tab
  const [accountSubTab, setAccountSubTab] = useState<'frozen' | 'banned'>('frozen');
  const [accountActionReason, setAccountActionReason] = useState('');
  const [accountActionId, setAccountActionId] = useState<number | null>(null);
  const [accountActionType, setAccountActionType] = useState<'freeze' | 'ban' | null>(null);

  // Stats
  const { data: stats } = trpc.admin.dashboard.useQuery(undefined, { enabled: sessionVerified });
  const { data: codesList, refetch: refetchCodes } = trpc.admin.listCodes.useQuery(undefined, { enabled: sessionVerified });
  const { data: fa2Status } = trpc.admin.get2FAStatus.useQuery();
  const { data: notificationsList, refetch: refetchNotifications } = trpc.admin.listNotifications.useQuery(undefined, { enabled: sessionVerified });
  const { data: roleSettingsList, refetch: refetchRoleSettings } = trpc.admin.listRoleSettings.useQuery(undefined, { enabled: sessionVerified });
  const { data: roadmapData, refetch: refetchRoadmap } = trpc.roadmap.list.useQuery(undefined, { enabled: sessionVerified });
  const createRoadmapItem = trpc.roadmap.create.useMutation({ onSuccess: () => { refetchRoadmap(); showToast('Roadmap item создан'); } });
  const updateRoadmapItem = trpc.roadmap.update.useMutation({ onSuccess: () => { refetchRoadmap(); showToast('Roadmap item обновлён'); setEditingRoadmapItem(null); } });
  const deleteRoadmapItem = trpc.roadmap.delete.useMutation({ onSuccess: () => { refetchRoadmap(); showToast('Roadmap item удалён'); } });
  const reorderRoadmapItem = trpc.roadmap.reorder.useMutation({ onSuccess: () => refetchRoadmap() });
  const createVersion = trpc.roadmap.versionCreate.useMutation({ onSuccess: () => { refetchRoadmap(); showToast('Версия создана'); setNewVersionName(''); } });
  const reorderVersion = trpc.roadmap.versionReorder.useMutation({ onSuccess: () => refetchRoadmap() });
  const deleteVersion = trpc.roadmap.versionDelete.useMutation({ onSuccess: () => { refetchRoadmap(); showToast('Версия удалена'); } });

  // GIF Configs
  const [gifStatus, setGifStatus] = useState<'pending' | 'approved' | 'denied' | 'all'>('pending');
  const { data: gifConfigsList, refetch: refetchGifConfigs } = trpc.admin.listGifConfigs.useQuery(
    { status: gifStatus },
    { enabled: sessionVerified }
  );
  const approveGif = trpc.admin.approveGifConfig.useMutation({
    onSuccess: () => { refetchGifConfigs(); showToast('GIF конфиг одобрен'); }
  });
  const denyGif = trpc.admin.denyGifConfig.useMutation({
    onSuccess: () => { refetchGifConfigs(); showToast('GIF конфиг отклонен'); }
  });

  // Freeze / Ban queries
  const { data: frozenAccounts, refetch: refetchFrozen } = trpc.admin.listFrozenAccounts.useQuery(
    undefined, { enabled: sessionVerified && activeTab === 'accounts' }
  );
  const { data: bannedAccounts, refetch: refetchBanned } = trpc.admin.listBannedAccounts.useQuery(
    undefined, { enabled: sessionVerified && activeTab === 'accounts' }
  );
  const freezeAccount = trpc.admin.freezeAccount.useMutation({
    onSuccess: () => { refetchFrozen(); refetchBanned(); showToast('Аккаунт заморожен'); setAccountActionType(null); setAccountActionId(null); setAccountActionReason(''); }
  });
  const unfreezeAccount = trpc.admin.unfreezeAccount.useMutation({
    onSuccess: () => { refetchFrozen(); refetchBanned(); showToast('Аккаунт разморожен'); }
  });
  const banAccount = trpc.admin.banAccount.useMutation({
    onSuccess: () => { refetchFrozen(); refetchBanned(); showToast('Аккаунт забанен'); setAccountActionType(null); setAccountActionId(null); setAccountActionReason(''); }
  });
  const unbanAccount = trpc.admin.unbanAccount.useMutation({
    onSuccess: () => { refetchFrozen(); refetchBanned(); showToast('Аккаунт разбанен'); }
  });

  // Mutations
  const verify2FA = trpc.admin.verifySession.useMutation({ onSuccess: () => setSessionVerified(true) });
  const setup2FA = trpc.admin.setup2FA.useMutation({ onSuccess: () => { setShowSetup2FA(false); setSessionVerified(true); } });
  const generateCode = trpc.admin.generateCode.useMutation({ onSuccess: () => refetchCodes() });

  const sendNotification = trpc.admin.sendNotification.useMutation({ onSuccess: () => { refetchNotifications(); showToast('Уведомление отправлено'); setNotifMessage(''); } });
  const upsertRoleSetting = trpc.admin.upsertRoleSetting.useMutation({ onSuccess: () => { refetchRoleSettings(); showToast('Настройки роли сохранены'); } });
  const deleteRoleSetting = trpc.admin.deleteRoleSetting.useMutation({ onSuccess: () => { refetchRoleSettings(); showToast('Настройка удалена'); } });

  const [genPoints, setGenPoints] = useState(1000);
  const [genCount, setGenCount] = useState(1);
  const [genForSale, setGenForSale] = useState(false);
  const [generatedCodes, setGeneratedCodes] = useState<string[] | null>(null);
  const [codeTab, setCodeTab] = useState<'active' | 'used'>('active');
  const [codePointsFilter, setCodePointsFilter] = useState<number | 'all'>('all');
  const [toast, setToast] = useState<string | null>(null);
  const [notifMessage, setNotifMessage] = useState('');
  const [notifAccountId, setNotifAccountId] = useState('');
  const [notifTtl, setNotifTtl] = useState(60);

  // Roadmap state
  const [editingRoadmapItem, setEditingRoadmapItem] = useState<number | null>(null);
  const [editingRoadmapForm, setEditingRoadmapForm] = useState({ title: '', description: '', version: '', status: 'planned' as const });
  const [expandedVersions, setExpandedVersions] = useState<Set<string>>(new Set());
  const [newVersionName, setNewVersionName] = useState('');
  const [versionForms, setVersionForms] = useState<Record<string, { title: string; description: string; status: string }>>({});
  
  const getVersionForm = (version: string) => versionForms[version] ?? { title: '', description: '', status: 'planned' };
  const setVersionForm = (version: string, data: Partial<{ title: string; description: string; status: string }>) => {
    setVersionForms(prev => ({ ...prev, [version]: { ...getVersionForm(version), ...data } }));
  };
  
  const roadmapVersions = roadmapData?.versions ?? [];
  const roadmapItems = roadmapData?.items ?? [];

  // Tickets admin state
  const [selectedAdminTicketId, setSelectedAdminTicketId] = useState<number | null>(null);
  const [adminReplyText, setAdminReplyText] = useState('');
  const [adminReplyFiles, setAdminReplyFiles] = useState<File[]>([]);
  const [ticketStatusFilter, setTicketStatusFilter] = useState<'all' | 'open' | 'closed'>('all');
  const [showCloseModal, setShowCloseModal] = useState(false);
  const [closeReasonText, setCloseReasonText] = useState('');
  const { data: adminTicketsList, refetch: refetchAdminTickets } = trpc.ticket.adminList.useQuery(undefined, { enabled: sessionVerified && activeTab === 'tickets' });
  const { data: adminTicketDetail } = trpc.ticket.adminGetById.useQuery(
    { id: selectedAdminTicketId! },
    { enabled: selectedAdminTicketId !== null && sessionVerified && activeTab === 'tickets' }
  );
  const assignTicket = trpc.ticket.assign.useMutation({ onSuccess: () => { refetchAdminTickets(); showToast('Тикет взят'); } });
  const closeTicket = trpc.ticket.close.useMutation({ onSuccess: () => { refetchAdminTickets(); setSelectedAdminTicketId(null); showToast('Тикет закрыт'); } });
  const adminReply = trpc.ticket.adminReply.useMutation({
    onSuccess: () => {
      refetchAdminTickets();
      setAdminReplyText('');
      showToast('Ответ отправлен');
    }
  });

  // Role gradient form
  const [roleForm, setRoleForm] = useState({
    roleName: '',
    nickGradientFrom: '',
    nickGradientTo: '',
    roleGradientFrom: '',
    roleGradientTo: '',
    iconUrl: '',
  });

  const showToast = (msg: string) => { setToast(msg); setTimeout(() => setToast(null), 2000); };

  const generate2FASecret = trpc.admin.generate2FASecret.useQuery(undefined, { enabled: showSetup2FA && !setupSecret });

  // Check if 2FA is not set up
  useEffect(() => {
    if (fa2Status && !fa2Status.enabled) {
      setShowSetup2FA(true);
    }
  }, [fa2Status]);

  useEffect(() => {
    if (generate2FASecret.data?.secret) {
      setSetupSecret(generate2FASecret.data.secret);
    }
  }, [generate2FASecret.data]);

  if (authLoading) {
    return (
      <div className="relative flex items-center justify-center" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
        <p className="font-mono text-sm" style={{ color: '#7A8A9E' }}>Загрузка...</p>
      </div>
    );
  }

  const userRole = (user as any)?.effectiveRole ?? user?.role;
  if (userRole !== 'admin' && userRole !== 'owner') {
    return (
      <div className="relative flex items-center justify-center" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
        <div className="text-center max-w-sm mx-auto px-6">
          <div className="w-16 h-16 rounded-2xl mx-auto mb-6 flex items-center justify-center" style={{ background: 'rgba(255,100,100,0.1)', border: '1px solid rgba(255,100,100,0.2)' }}>
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ff6464" strokeWidth="1.5"><path d="M12 9v4M12 17h.01M4.93 4.93l14.14 14.14M4.93 19.07L19.07 4.93" /></svg>
          </div>
          <h2 className="font-display text-xl font-bold mb-2" style={{ color: '#E8E4E0' }}>Доступ запрещён</h2>
          <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Эта страница доступна только администраторам</p>
        </div>
      </div>
    );
  }

  if (!sessionVerified && !showSetup2FA) {
    return (
      <div className="relative flex items-center justify-center" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
        <div className="text-center max-w-sm mx-auto px-6">
          <div className="w-16 h-16 rounded-2xl mx-auto mb-6 flex items-center justify-center" style={{ background: 'rgba(255,100,100,0.1)', border: '1px solid rgba(255,100,100,0.2)' }}>
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ff6464" strokeWidth="1.5"><rect x="3" y="11" width="18" height="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></svg>
          </div>
          <h2 className="font-display text-xl font-bold mb-2" style={{ color: '#E8E4E0' }}>Двухфакторная аутентификация</h2>
          <p className="font-body text-xs mb-6" style={{ color: '#7A8A9E' }}>Введите 6-значный код из приложения-аутентификатора</p>
          <div className="flex gap-2 justify-center">
            <input
              type="text" maxLength={6} placeholder="000000" value={totpCode}
              onChange={(e) => setTotpCode(e.target.value.replace(/\D/g, ''))}
              className="font-mono text-lg text-center tracking-[8px] w-40 px-4 py-3 rounded-lg outline-none"
              style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,100,100,0.2)' }}
            />
          </div>
          {verify2FA.error && <p className="font-body text-xs mt-3" style={{ color: '#ff6464' }}>Неверный код</p>}
          <button
            onClick={() => verify2FA.mutate({ token: totpCode })}
            disabled={totpCode.length !== 6}
            className="w-full mt-4 font-mono text-xs font-semibold uppercase py-3 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
            style={{ background: 'linear-gradient(135deg, #ff6464, #FF8C42)', color: '#0B0D12' }}
          >
            Подтвердить
          </button>
        </div>
      </div>
    );
  }

  if (showSetup2FA) {
    return (
      <div className="relative flex items-center justify-center" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
        <div className="text-center max-w-sm mx-auto px-6">
          <h2 className="font-display text-xl font-bold mb-2" style={{ color: '#E8E4E0' }}>Настройка 2FA</h2>
          <p className="font-body text-xs mb-4" style={{ color: '#7A8A9E' }}>Отсканируйте QR-код или введите секрет вручную</p>

          {generate2FASecret.data?.qrDataUrl ? (
            <div className="rounded-xl p-4 mb-4 flex justify-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
              <img src={generate2FASecret.data.qrDataUrl} alt="2FA QR" className="rounded-lg" style={{ width: 200, height: 200 }} />
            </div>
          ) : (
            <div className="rounded-xl p-4 mb-4" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
              <p className="font-mono text-xs" style={{ color: '#7A8A9E' }}>Генерация QR-кода...</p>
            </div>
          )}

          <div className="rounded-xl p-4 mb-4" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
            <p className="font-mono text-xs mb-2" style={{ color: '#7A8A9E' }}>Секретный ключ:</p>
            <p className="font-mono text-sm tracking-[2px]" style={{ color: '#80FF97' }}>{setupSecret || '—'}</p>
          </div>
          <input type="text" maxLength={6} placeholder="Код подтверждения" value={setupToken}
            onChange={(e) => setSetupToken(e.target.value.replace(/\D/g, ''))}
            className="w-full font-mono text-sm text-center tracking-[4px] px-4 py-3 rounded-lg outline-none mb-4"
            style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}
          />
          {setup2FA.error && <p className="font-body text-xs mb-3" style={{ color: '#ff6464' }}>Неверный код</p>}
          <button
            onClick={() => setup2FA.mutate({ secret: setupSecret, token: setupToken })}
            disabled={!setupSecret || setupToken.length !== 6}
            className="w-full font-mono text-xs font-semibold uppercase py-3 rounded-lg disabled:opacity-50"
            style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
          >
            Активировать 2FA
          </button>
        </div>
      </div>
    );
  }

  const accountList = accountSubTab === 'frozen' ? (frozenAccounts ?? []) : (bannedAccounts ?? []);

  const allCodes = codesList ?? [];
  const activeCodes = allCodes.filter(c => c.used === 'false');
  const usedCodes = allCodes.filter(c => c.used === 'true');
  const filteredActiveCodes = codePointsFilter === 'all' ? activeCodes : activeCodes.filter(c => c.points === codePointsFilter);
  const displayCodes = codeTab === 'active' ? filteredActiveCodes : usedCodes;

  return (
    <div className="relative" style={{ zIndex: 1, minHeight: '100vh', paddingTop: 64 }}>
      <div className="mx-auto px-6 lg:px-12 py-16" style={{ maxWidth: 1000 }}>
        {/* Header */}
        <div className="text-center mb-10">
          <p className="font-mono text-xs tracking-[4px] mb-3" style={{ color: '#ff6464' }}>// ADMIN</p>
          <h1 className="font-display font-bold" style={{ fontSize: 'clamp(28px, 5vw, 42px)', color: '#E8E4E0' }}>Панель управления</h1>
        </div>

        {/* Top Tabs */}
        <div className="flex items-center justify-center gap-2 mb-10">
          {([
            { key: 'main', label: 'Ключи и уведомления' },
            { key: 'gifs', label: 'GIF-конфиги' },
            { key: 'accounts', label: 'Аккаунты' },
            { key: 'roadmap', label: 'Roadmap' },
            { key: 'tickets', label: 'Тикеты' },
          ] as const).map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className="font-mono text-xs font-semibold uppercase tracking-[1px] px-5 py-2.5 rounded-lg transition-all duration-200"
              style={{
                background: activeTab === tab.key ? 'linear-gradient(135deg, #ff6464, #FF8C42)' : 'rgba(255,255,255,0.03)',
                color: activeTab === tab.key ? '#0B0D12' : '#7A8A9E',
                border: `1px solid ${activeTab === tab.key ? 'rgba(255,100,100,0.3)' : 'rgba(255,255,255,0.06)'}`,
                transform: activeTab === tab.key ? 'scale(1.02)' : 'scale(1)',
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* ─── TAB: TICKETS ─── */}
        {activeTab === 'tickets' && (
          <div className="space-y-4">
            {selectedAdminTicketId !== null ? (
              <div>
                <button
                  onClick={() => setSelectedAdminTicketId(null)}
                  className="font-mono text-xs uppercase mb-4 flex items-center gap-1 transition-colors hover:text-[#6BB7FF]"
                  style={{ color: '#7A8A9E' }}
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="15 18 9 12 15 6" /></svg>
                  Назад к списку
                </button>
                {!adminTicketDetail ? (
                  <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Загрузка...</p>
                  </div>
                ) : (
                  <div className="rounded-xl p-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <div className="flex items-center justify-between mb-4">
                      <div className="flex items-center gap-3">
                        <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>#{adminTicketDetail.ticket.id}</span>
                        <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: adminTicketDetail.ticket.status === 'open' ? 'rgba(107,183,255,0.1)' : 'rgba(128,255,151,0.1)', color: adminTicketDetail.ticket.status === 'open' ? '#6BB7FF' : '#80FF97' }}>
                          {adminTicketDetail.ticket.status === 'open' ? 'Открыт' : 'Закрыт'}
                        </span>
                        <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}>
                          {adminTicketDetail.ticket.category}
                        </span>
                      </div>
                      <div className="flex items-center gap-2">
                        {adminTicketDetail.ticket.status === 'open' && (
                          <>
                            {!adminTicketDetail.ticket.assignedAdminId && (
                              <button
                                onClick={() => assignTicket.mutate({ id: adminTicketDetail.ticket.id })}
                                disabled={assignTicket.isPending}
                                className="font-mono text-[10px] font-semibold uppercase px-3 py-1.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                                style={{ background: 'rgba(107,183,255,0.1)', color: '#6BB7FF', border: '1px solid rgba(107,183,255,0.2)' }}
                              >
                                Взять тикет
                              </button>
                            )}
                            <button
                              onClick={() => setShowCloseModal(true)}
                              disabled={closeTicket.isPending}
                              className="font-mono text-[10px] font-semibold uppercase px-3 py-1.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                              style={{ background: 'rgba(255,100,100,0.1)', color: '#ff6464', border: '1px solid rgba(255,100,100,0.2)' }}
                            >
                              Закрыть
                            </button>
                          </>
                        )}
                      </div>
                    </div>
                    <h2 className="font-display font-semibold text-base mb-1" style={{ color: '#E8E4E0' }}>{adminTicketDetail.ticket.title}</h2>
                    <p className="font-mono text-[10px] mb-2" style={{ color: '#7A8A9E' }}>Создан: {new Date(adminTicketDetail.ticket.createdAt).toLocaleString('ru-RU')}</p>
                    {adminTicketDetail.ticket.status === 'closed' && adminTicketDetail.ticket.closeReason && (
                      <div className="mb-4 rounded-lg p-3" style={{ background: 'rgba(255,100,100,0.04)', border: '1px solid rgba(255,100,100,0.15)' }}>
                        <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#ff6464' }}>Причина закрытия</p>
                        <p className="font-body text-xs" style={{ color: '#C5CDD8' }}>{adminTicketDetail.ticket.closeReason}</p>
                      </div>
                    )}

                    <div className="space-y-3 mb-6">
                      {adminTicketDetail.messages.map((msg: any) => {
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
                                {isSystem ? 'System' : isAdmin ? (msg.senderName + ' (Admin)') : (msg.senderName ?? 'User')}
                              </span>
                              <span className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>{new Date(msg.createdAt).toLocaleString('ru-RU')}</span>
                            </div>
                            <p className="font-body text-xs whitespace-pre-wrap" style={{ color: '#C5CDD8' }}>{msg.content}</p>
                            <AttachmentPreview attachments={msg.attachments || []} />
                          </div>
                        );
                      })}
                    </div>

                    {adminTicketDetail.ticket.status === 'open' && (
                      <div>
                        <textarea
                          placeholder="Написать ответ..."
                          value={adminReplyText}
                          onChange={(e) => setAdminReplyText(e.target.value)}
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
                                  setAdminReplyFiles(files.slice(0, 3));
                                } else {
                                  setAdminReplyFiles(files);
                                }
                              }}
                              accept="image/*,video/*,.pdf,.txt,.zip"
                            />
                            <span className="font-mono text-[10px] uppercase tracking-[1px] px-3 py-1.5 rounded-lg transition-all hover:bg-white/5" style={{ color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}>
                              📎 Прикрепить файлы
                            </span>
                          </label>
                          {adminReplyFiles.length > 0 && (
                            <div className="flex flex-wrap gap-2">
                              {adminReplyFiles.map((f, i) => (
                                <span key={i} className="font-body text-[10px] px-2 py-1 rounded" style={{ color: '#80FF97', background: 'rgba(128,255,151,0.08)', border: '1px solid rgba(128,255,151,0.15)' }}>
                                  {f.name} ({(f.size / 1024 / 1024).toFixed(2)} MB)
                                  <button onClick={() => setAdminReplyFiles(prev => prev.filter((_, idx) => idx !== i))} className="ml-1" style={{ color: '#ff6464' }}>×</button>
                                </span>
                              ))}
                            </div>
                          )}
                          <button
                            onClick={async () => {
                              let attachments: { url: string; name: string; size: number }[] | undefined;
                              if (adminReplyFiles.length > 0 && adminTicketDetail) {
                                const formData = new FormData();
                                adminReplyFiles.forEach(f => formData.append('file', f));
                                formData.append('ticketId', String(adminTicketDetail.ticket.id));
                                const res = await fetch('/api/upload/ticket', { method: 'POST', body: formData });
                                if (res.ok) {
                                  const data = await res.json();
                                  attachments = data.files;
                                }
                              }
                              adminReply.mutate({
                                ticketId: adminTicketDetail!.ticket.id,
                                content: adminReplyText.trim() || '(Файл)',
                                attachments,
                              });
                              setAdminReplyFiles([]);
                            }}
                            disabled={(!adminReplyText.trim() && adminReplyFiles.length === 0) || adminReply.isPending}
                            className="font-mono text-xs font-semibold uppercase px-4 py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                            style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                          >
                            {adminReply.isPending ? 'Отправка...' : 'Ответить'}
                          </button>
                        </div>
                        {adminReply.error && (
                          <p className="font-body text-xs mt-1" style={{ color: '#ff6464' }}>{adminReply.error.message}</p>
                        )}
                      </div>
                    )}

                    {/* Close ticket modal */}
                    {showCloseModal && (
                      <div className="fixed inset-0 z-50 flex items-center justify-center p-4" style={{ background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)' }}>
                        <div className="rounded-xl p-6 w-full" style={{ maxWidth: 420, background: '#0B0D12', border: '1px solid rgba(255,255,255,0.1)' }}>
                          <h3 className="font-display font-semibold text-sm mb-3" style={{ color: '#E8E4E0' }}>Закрыть тикет #{adminTicketDetail.ticket.id}</h3>
                          <p className="font-body text-xs mb-3" style={{ color: '#7A8A9E' }}>Укажите причину закрытия:</p>
                          <textarea
                            value={closeReasonText}
                            onChange={(e) => setCloseReasonText(e.target.value)}
                            rows={3}
                            className="w-full font-body text-sm px-4 py-3 rounded-lg outline-none resize-none mb-4"
                            style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                            placeholder="Причина..."
                          />
                          <div className="flex items-center gap-2 justify-end">
                            <button
                              onClick={() => { setShowCloseModal(false); setCloseReasonText(''); }}
                              className="font-mono text-[10px] uppercase px-4 py-2 rounded-lg"
                              style={{ color: '#7A8A9E' }}
                            >
                              Отмена
                            </button>
                            <button
                              onClick={() => {
                                closeTicket.mutate({ id: adminTicketDetail.ticket.id, reason: closeReasonText.trim() || undefined });
                                setShowCloseModal(false);
                                setCloseReasonText('');
                              }}
                              disabled={closeTicket.isPending}
                              className="font-mono text-[10px] font-semibold uppercase px-4 py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                              style={{ background: 'rgba(255,100,100,0.15)', color: '#ff6464', border: '1px solid rgba(255,100,100,0.25)' }}
                            >
                              {closeTicket.isPending ? 'Закрытие...' : 'Закрыть тикет'}
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ) : (
              <div>
                <div className="flex items-center gap-2 mb-4">
                  {(['all', 'open', 'closed'] as const).map((f) => (
                    <button
                      key={f}
                      onClick={() => setTicketStatusFilter(f)}
                      className="font-mono text-[10px] uppercase tracking-[1px] px-3 py-1.5 rounded-lg transition-all"
                      style={{
                        background: ticketStatusFilter === f ? 'rgba(107,183,255,0.15)' : 'rgba(255,255,255,0.03)',
                        color: ticketStatusFilter === f ? '#6BB7FF' : '#7A8A9E',
                        border: `1px solid ${ticketStatusFilter === f ? 'rgba(107,183,255,0.3)' : 'rgba(255,255,255,0.06)'}`,
                      }}
                    >
                      {f === 'all' ? 'Все' : f === 'open' ? 'Открытые' : 'Закрытые'}
                    </button>
                  ))}
                </div>
                {!adminTicketsList || adminTicketsList.length === 0 ? (
                  <div className="rounded-xl p-8 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Нет тикетов</p>
                  </div>
                ) : (
                  <div className="space-y-2">
                    {adminTicketsList
                      .filter((t: any) => ticketStatusFilter === 'all' || t.status === ticketStatusFilter)
                      .map((ticket: any) => (
                        <button
                          key={ticket.id}
                          onClick={() => setSelectedAdminTicketId(ticket.id)}
                          className="w-full text-left rounded-xl p-4 transition-all hover:bg-white/[0.02]"
                          style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)' }}
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-1">
                                <span className="font-mono text-xs" style={{ color: '#7A8A9E' }}>#{ticket.id}</span>
                                <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: ticket.status === 'open' ? 'rgba(107,183,255,0.1)' : 'rgba(128,255,151,0.1)', color: ticket.status === 'open' ? '#6BB7FF' : '#80FF97' }}>
                                  {ticket.status === 'open' ? 'Открыт' : 'Закрыт'}
                                </span>
                                {ticket.assignedAdminId && (
                                  <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(255,215,0,0.1)', color: '#FFD700' }}>
                                    Назначен
                                  </span>
                                )}
                              </div>
                              <p className="font-body text-sm truncate" style={{ color: '#E8E4E0' }}>{ticket.title}</p>
                              <p className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>{new Date(ticket.createdAt).toLocaleString('ru-RU')}</p>
                            </div>
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#7A8A9E" strokeWidth="2"><polyline points="9 18 15 12 9 6" /></svg>
                          </div>
                        </button>
                      ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* ─── TAB: MAIN ─── */}
        {/* ─── TAB: ROADMAP ─── */}
        {activeTab === 'roadmap' && (
          <div className="space-y-4">
            {/* Create new version */}
            <div className="rounded-xl p-5" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              <div className="flex items-center gap-3">
                <input
                  type="text"
                  placeholder="Новая версия (например v1.2)"
                  value={newVersionName}
                  onChange={(e) => setNewVersionName(e.target.value)}
                  className="flex-1 font-mono text-sm px-4 py-2.5 rounded-lg outline-none"
                  style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                />
                <button
                  onClick={() => {
                    if (!newVersionName.trim()) return;
                    createVersion.mutate({ name: newVersionName.trim() });
                  }}
                  disabled={!newVersionName.trim() || createVersion.isPending}
                  className="font-mono text-xs font-semibold uppercase px-5 py-2.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                  style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                >Создать версию</button>
              </div>
            </div>

            {/* Versions list */}
            {roadmapVersions.length === 0 ? (
              <p className="font-body text-xs text-center py-8" style={{ color: '#7A8A9E' }}>Нет версий. Создайте первую версию выше.</p>
            ) : (
              roadmapVersions.map((version, vIdx) => {
                const versionItems = roadmapItems.filter(i => i.version === version.name).sort((a, b) => a.orderIndex - b.orderIndex);
                const isExpanded = expandedVersions.has(version.name);
                const vForm = getVersionForm(version.name);
                return (
                  <div key={version.id} className="rounded-xl overflow-hidden" style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid rgba(255,255,255,0.06)' }}>
                    {/* Version header */}
                    <div className="flex items-center justify-between px-5 py-4">
                      <button
                        onClick={() => {
                          const next = new Set(expandedVersions);
                          if (next.has(version.name)) next.delete(version.name);
                          else next.add(version.name);
                          setExpandedVersions(next);
                        }}
                        className="flex items-center gap-3 flex-1 text-left"
                      >
                        <span className="font-mono text-sm font-semibold" style={{ color: '#80FF97' }}>{version.name}</span>
                        <span className="font-mono text-[10px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}>
                          {versionItems.length} items
                        </span>
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#7A8A9E" strokeWidth="2" style={{ transform: isExpanded ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s' }}>
                          <polyline points="6 9 12 15 18 9" />
                        </svg>
                      </button>
                      {/* Version order controls */}
                      <div className="flex items-center gap-1.5 shrink-0 ml-3">
                        {vIdx > 0 && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              const reordered = roadmapVersions.map((v, i) => ({
                                id: v.id,
                                orderIndex: i === vIdx ? roadmapVersions[vIdx - 1].orderIndex : i === vIdx - 1 ? roadmapVersions[vIdx].orderIndex : v.orderIndex,
                              }));
                              reorderVersion.mutate({ items: reordered });
                            }}
                            disabled={reorderVersion.isPending}
                            className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                            style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}
                          >↑</button>
                        )}
                        {vIdx < roadmapVersions.length - 1 && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              const reordered = roadmapVersions.map((v, i) => ({
                                id: v.id,
                                orderIndex: i === vIdx ? roadmapVersions[vIdx + 1].orderIndex : i === vIdx + 1 ? roadmapVersions[vIdx].orderIndex : v.orderIndex,
                              }));
                              reorderVersion.mutate({ items: reordered });
                            }}
                            disabled={reorderVersion.isPending}
                            className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                            style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}
                          >↓</button>
                        )}
                        {versionItems.length === 0 && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              if (confirm(`Удалить версию ${version.name}?`)) deleteVersion.mutate({ id: version.id });
                            }}
                            className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105"
                            style={{ background: 'rgba(255,100,100,0.1)', color: '#ff6464', border: '1px solid rgba(255,100,100,0.2)' }}
                          >Удал.</button>
                        )}
                      </div>
                    </div>

                    {isExpanded && (
                      <div className="px-5 pb-5">
                        {/* Items list */}
                        <div className="space-y-2 mb-4">
                          {versionItems.map((item, idx) => {
                            const isEditing = editingRoadmapItem === item.id;
                            return (
                              <div key={item.id} className="rounded-lg p-3" style={{ background: 'rgba(11,13,18,0.6)', border: '1px solid rgba(255,255,255,0.05)' }}>
                                {isEditing ? (
                                  <div className="flex flex-col gap-2">
                                    <input
                                      type="text"
                                      value={editingRoadmapForm.title}
                                      onChange={(e) => setEditingRoadmapForm({ ...editingRoadmapForm, title: e.target.value })}
                                      className="w-full font-body text-sm px-3 py-2 rounded-lg outline-none"
                                      style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                                    />
                                    <textarea
                                      value={editingRoadmapForm.description}
                                      onChange={(e) => setEditingRoadmapForm({ ...editingRoadmapForm, description: e.target.value })}
                                      rows={2}
                                      className="w-full font-body text-sm px-3 py-2 rounded-lg outline-none resize-none"
                                      style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                                    />
                                    <div className="flex items-center gap-2">
                                      <select
                                        value={editingRoadmapForm.status}
                                        onChange={(e) => setEditingRoadmapForm({ ...editingRoadmapForm, status: e.target.value as any })}
                                        className="font-mono text-xs px-2 py-1.5 rounded-lg outline-none"
                                        style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                                      >
                                        <option value="planned">Запланировано</option>
                                        <option value="in_progress">В разработке</option>
                                        <option value="completed">Выполнено</option>
                                        <option value="cancelled">Отменено</option>
                                      </select>
                                      <button
                                        onClick={() => {
                                          updateRoadmapItem.mutate({ id: item.id, title: editingRoadmapForm.title, description: editingRoadmapForm.description, version: editingRoadmapForm.version, status: editingRoadmapForm.status as any });
                                        }}
                                        disabled={!editingRoadmapForm.title.trim() || updateRoadmapItem.isPending}
                                        className="font-mono text-[9px] font-semibold uppercase px-3 py-1.5 rounded-lg transition-all hover:scale-105 disabled:opacity-50"
                                        style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                                      >Сохранить</button>
                                      <button
                                        onClick={() => setEditingRoadmapItem(null)}
                                        className="font-mono text-[9px] px-3 py-1.5 rounded-lg"
                                        style={{ color: '#7A8A9E', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                                      >Отмена</button>
                                    </div>
                                  </div>
                                ) : (
                                  <div className="flex items-start justify-between gap-3">
                                    <div className="flex-1 min-w-0">
                                      <div className="flex items-center gap-2 mb-1 flex-wrap">
                                        <span className="font-display text-sm font-medium" style={{ color: '#E8E4E0' }}>{item.title}</span>
                                        <span className="font-mono text-[9px] px-2 py-0.5 rounded-full"
                                          style={{
                                            background: item.status === 'completed' ? 'rgba(128,255,151,0.1)' : item.status === 'in_progress' ? 'rgba(255,215,0,0.1)' : item.status === 'cancelled' ? 'rgba(255,100,100,0.1)' : 'rgba(122,138,158,0.1)',
                                            color: item.status === 'completed' ? '#80FF97' : item.status === 'in_progress' ? '#FFD700' : item.status === 'cancelled' ? '#ff6464' : '#7A8A9E',
                                          }}
                                        >
                                          {item.status === 'completed' ? 'Выполнено' : item.status === 'in_progress' ? 'В разработке' : item.status === 'cancelled' ? 'Отменено' : 'Запланировано'}
                                        </span>
                                        {item.statusChangedAt && (
                                          <span className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>
                                            {new Date(item.statusChangedAt).toLocaleDateString('ru-RU')}
                                          </span>
                                        )}
                                      </div>
                                      {item.description && (
                                        <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>{item.description}</p>
                                      )}
                                    </div>
                                    <div className="flex items-center gap-1.5 shrink-0">
                                      {idx > 0 && (
                                        <button
                                          onClick={() => {
                                            const reordered = versionItems.map((it, i) => ({
                                              id: it.id,
                                              orderIndex: i === idx ? versionItems[idx - 1].orderIndex : i === idx - 1 ? versionItems[idx].orderIndex : it.orderIndex,
                                            }));
                                            reorderRoadmapItem.mutate({ items: reordered });
                                          }}
                                          disabled={reorderRoadmapItem.isPending}
                                          className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                                          style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}
                                        >↑</button>
                                      )}
                                      {idx < versionItems.length - 1 && (
                                        <button
                                          onClick={() => {
                                            const reordered = versionItems.map((it, i) => ({
                                              id: it.id,
                                              orderIndex: i === idx ? versionItems[idx + 1].orderIndex : i === idx + 1 ? versionItems[idx].orderIndex : it.orderIndex,
                                            }));
                                            reorderRoadmapItem.mutate({ items: reordered });
                                          }}
                                          disabled={reorderRoadmapItem.isPending}
                                          className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                                          style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}
                                        >↓</button>
                                      )}
                                      <button
                                        onClick={() => {
                                          setEditingRoadmapItem(item.id);
                                          setEditingRoadmapForm({ title: item.title, description: item.description ?? '', version: item.version, status: item.status as any });
                                        }}
                                        className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105"
                                        style={{ background: 'rgba(107,183,255,0.1)', color: '#6BB7FF', border: '1px solid rgba(107,183,255,0.2)' }}
                                      >Ред.</button>
                                      <button
                                        onClick={() => { if (confirm('Удалить этот item?')) deleteRoadmapItem.mutate({ id: item.id }); }}
                                        className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105"
                                        style={{ background: 'rgba(255,100,100,0.1)', color: '#ff6464', border: '1px solid rgba(255,100,100,0.2)' }}
                                      >Удал.</button>
                                    </div>
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>

                        {/* Add item form inside version */}
                        <div className="rounded-lg p-3" style={{ background: 'rgba(128,255,151,0.03)', border: '1px dashed rgba(128,255,151,0.15)' }}>
                          <p className="font-mono text-[9px] uppercase tracking-[1px] mb-2" style={{ color: '#80FF97' }}>Добавить item в {version.name}</p>
                          <div className="flex flex-col gap-2">
                            <input
                              type="text"
                              placeholder="Название"
                              value={vForm.title}
                              onChange={(e) => setVersionForm(version.name, { title: e.target.value })}
                              className="w-full font-body text-sm px-3 py-2 rounded-lg outline-none"
                              style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                            />
                            <textarea
                              placeholder="Описание (опционально)"
                              value={vForm.description}
                              onChange={(e) => setVersionForm(version.name, { description: e.target.value })}
                              rows={2}
                              className="w-full font-body text-sm px-3 py-2 rounded-lg outline-none resize-none"
                              style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                            />
                            <div className="flex items-center gap-2">
                              <select
                                value={vForm.status}
                                onChange={(e) => setVersionForm(version.name, { status: e.target.value })}
                                className="font-mono text-xs px-2 py-1.5 rounded-lg outline-none"
                                style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                              >
                                <option value="planned">Запланировано</option>
                                <option value="in_progress">В разработке</option>
                                <option value="completed">Выполнено</option>
                                <option value="cancelled">Отменено</option>
                              </select>
                              <button
                                onClick={() => {
                                  if (!vForm.title.trim()) return;
                                  createRoadmapItem.mutate({ title: vForm.title, description: vForm.description || undefined, version: version.name, status: vForm.status as any });
                                  setVersionForm(version.name, { title: '', description: '', status: 'planned' });
                                }}
                                disabled={!vForm.title.trim() || createRoadmapItem.isPending}
                                className="font-mono text-xs font-semibold uppercase px-4 py-1.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                                style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                              >Добавить</button>
                            </div>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        )}

        {/* ─── TAB: MAIN ─── */}
        {activeTab === 'main' && (
          <>
            {/* Stats cards */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-10">
              {[
                { label: 'Кодов всего', value: stats?.totalCodes ?? 0, color: '#80FF97' },
                { label: 'Использовано', value: stats?.usedCodes ?? 0, color: '#6BB7FF' },
                { label: 'Ожидает sync', value: stats?.pendingSync ?? 0, color: '#FFD700' },
                { label: 'Активных подписок', value: stats?.activeSubs ?? 0, color: '#FF8C42' },
              ].map((s) => (
                <div key={s.label} className="rounded-xl p-4 text-center" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
                  <p className="font-display text-2xl font-bold" style={{ color: s.color }}>{s.value}</p>
                  <p className="font-mono text-[9px] mt-1" style={{ color: '#7A8A9E' }}>{s.label}</p>
                </div>
              ))}
            </div>

            {/* Role Gradients */}
            <div className="rounded-xl p-6 mb-8" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              <div className="flex items-center gap-2 mb-4">
                <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(153,50,204,0.08)', color: '#C084FC' }}>Градиенты ролей</span>
              </div>

              {roleSettingsList && roleSettingsList.length > 0 && (
                <div className="mb-4">
                  {roleSettingsList.map((rs) => (
                    <div key={rs.id} className="flex items-center justify-between rounded-lg px-3 py-2 mb-2" style={{ background: 'rgba(11,13,18,0.5)', border: '1px solid rgba(128,255,151,0.06)' }}>
                      <div className="flex items-center gap-3">
                        <span className="font-mono text-xs" style={{ color: '#E8E4E0' }}>{rs.roleName}</span>
                        <div className="flex items-center gap-1.5">
                          <div className="w-8 h-2 rounded-full" style={{ background: rs.nickGradientFrom && rs.nickGradientTo ? `linear-gradient(90deg, ${rs.nickGradientFrom}, ${rs.nickGradientTo})` : 'transparent' }} />
                          <span className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>ник</span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          <div className="w-8 h-2 rounded-full" style={{ background: rs.roleGradientFrom && rs.roleGradientTo ? `linear-gradient(90deg, ${rs.roleGradientFrom}, ${rs.roleGradientTo})` : 'transparent' }} />
                          <span className="font-mono text-[9px]" style={{ color: '#7A8A9E' }}>роль</span>
                        </div>
                      </div>
                      <button
                        onClick={() => deleteRoleSetting.mutate({ id: rs.id })}
                        className="font-mono text-[9px] px-2 py-1 rounded transition-colors hover:bg-white/5"
                        style={{ color: '#ff6464' }}
                      >
                        Удалить
                      </button>
                    </div>
                  ))}
                </div>
              )}

              <div className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <select
                    value={roleForm.roleName}
                    onChange={(e) => setRoleForm({ ...roleForm, roleName: e.target.value })}
                    className="font-mono text-sm px-3 py-2 rounded-lg outline-none"
                    style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                  >
                    <option value="">Выберите роль</option>
                    {['OWNER', 'ADMIN', 'QA', 'SLIHA', 'SPONSOR++', 'SPONSOR+', 'SPONSOR', 'VIP', 'USER'].map((r) => (
                      <option key={r} value={r}>{r}</option>
                    ))}
                  </select>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <input type="text" placeholder="Nick от (#XXXXXX)" maxLength={7} value={roleForm.nickGradientFrom} onChange={(e) => setRoleForm({ ...roleForm, nickGradientFrom: e.target.value })} className="font-mono text-xs px-3 py-2 rounded-lg outline-none" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }} />
                  <input type="text" placeholder="Nick до (#XXXXXX)" maxLength={7} value={roleForm.nickGradientTo} onChange={(e) => setRoleForm({ ...roleForm, nickGradientTo: e.target.value })} className="font-mono text-xs px-3 py-2 rounded-lg outline-none" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }} />
                  <input type="text" placeholder="Role от (#XXXXXX)" maxLength={7} value={roleForm.roleGradientFrom} onChange={(e) => setRoleForm({ ...roleForm, roleGradientFrom: e.target.value })} className="font-mono text-xs px-3 py-2 rounded-lg outline-none" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }} />
                  <input type="text" placeholder="Role до (#XXXXXX)" maxLength={7} value={roleForm.roleGradientTo} onChange={(e) => setRoleForm({ ...roleForm, roleGradientTo: e.target.value })} className="font-mono text-xs px-3 py-2 rounded-lg outline-none" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }} />
                </div>
                <button
                  onClick={() => {
                    if (!roleForm.roleName) return;
                    upsertRoleSetting.mutate({
                      roleName: roleForm.roleName,
                      nickGradientFrom: roleForm.nickGradientFrom || null,
                      nickGradientTo: roleForm.nickGradientTo || null,
                      roleGradientFrom: roleForm.roleGradientFrom || null,
                      roleGradientTo: roleForm.roleGradientTo || null,
                      iconUrl: roleForm.iconUrl || null,
                    });
                    setRoleForm({ roleName: '', nickGradientFrom: '', nickGradientTo: '', roleGradientFrom: '', roleGradientTo: '', iconUrl: '' });
                  }}
                  disabled={!roleForm.roleName || upsertRoleSetting.isPending}
                  className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                  style={{ background: 'linear-gradient(135deg, #C084FC, #6BB7FF)', color: '#0B0D12' }}
                >
                  Сохранить
                </button>
              </div>
            </div>

            {/* Generate codes */}
            <div className="rounded-xl p-6 mb-8" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              <div className="flex items-center gap-2 mb-4">
                <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97' }}>Генерация кодов</span>
              </div>

              <div className="flex items-end gap-3 mb-4">
                <div>
                  <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#7A8A9E' }}>Сумма HY-P</p>
                  <select value={genPoints} onChange={(e) => setGenPoints(Number(e.target.value))} className="font-mono text-sm px-3 py-2 rounded-lg outline-none" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    {[1000, 1500, 2000, 3500, 5000, 7500, 10000].map((p) => (
                      <option key={p} value={p}>{p.toLocaleString()}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#7A8A9E' }}>Количество</p>
                  <select value={genCount} onChange={(e) => setGenCount(Number(e.target.value))} className="font-mono text-sm px-3 py-2 rounded-lg outline-none" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}>
                    {[1, 5, 10, 20, 50].map((n) => (
                      <option key={n} value={n}>{n}</option>
                    ))}
                  </select>
                </div>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={genForSale}
                    onChange={(e) => setGenForSale(e.target.checked)}
                    className="w-4 h-4 rounded accent-[#80FF97]"
                  />
                  <span className="font-mono text-[10px]" style={{ color: '#7A8A9E' }}>Для продажи</span>
                </label>
                <button
                  onClick={() => generateCode.mutate({ points: genPoints, count: genCount, forSale: genForSale }, { onSuccess: (data) => setGeneratedCodes(data.codes) })}
                  className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02]"
                  style={{ background: 'linear-gradient(135deg, #80FF97, #6BB7FF)', color: '#0B0D12' }}
                >
                  Сгенерировать
                </button>
              </div>

              {generatedCodes && (
                <div className="rounded-lg p-4" style={{ background: 'rgba(128,255,151,0.04)', border: '1px dashed rgba(128,255,151,0.2)' }}>
                  <p className="font-mono text-[9px] uppercase mb-2" style={{ color: '#80FF97' }}>Сгенерированные коды ({genPoints} HY-P):</p>
                  <div className="flex flex-wrap gap-2">
                    {generatedCodes.map((c) => (
                      <span key={c} className="font-mono text-sm px-3 py-1.5 rounded" style={{ background: 'rgba(128,255,151,0.08)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.15)', letterSpacing: '1px' }}>{c}</span>
                    ))}
                  </div>
                </div>
              )}
            </div>

            {/* Notifications */}
            <div className="rounded-xl p-6 mb-8" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              <div className="flex items-center gap-2 mb-4">
                <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(255,215,0,0.08)', color: '#FFD700' }}>Уведомления</span>
              </div>

              <div className="flex flex-col gap-3 mb-4">
                <textarea
                  value={notifMessage}
                  onChange={(e) => setNotifMessage(e.target.value.slice(0, 500))}
                  placeholder="Текст уведомления..."
                  rows={3}
                  className="w-full font-body text-sm px-4 py-3 rounded-lg outline-none resize-none"
                  style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                />
                <div className="flex items-end gap-3">
                  <div>
                    <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#7A8A9E' }}>Account ID (пусто = всем)</p>
                    <input type="number" value={notifAccountId} onChange={(e) => setNotifAccountId(e.target.value)} placeholder="ID" className="font-mono text-sm px-3 py-2 rounded-lg outline-none w-32" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }} />
                  </div>
                  <div>
                    <p className="font-mono text-[9px] uppercase mb-1" style={{ color: '#7A8A9E' }}>TTL (мин)</p>
                    <input type="number" value={notifTtl} onChange={(e) => setNotifTtl(Math.max(1, Math.min(10080, Number(e.target.value))))} className="font-mono text-sm px-3 py-2 rounded-lg outline-none w-24" style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }} />
                  </div>
                  <button
                    onClick={() => sendNotification.mutate({ message: notifMessage, accountId: notifAccountId ? Number(notifAccountId) : undefined, ttlMinutes: notifTtl })}
                    disabled={!notifMessage.trim() || sendNotification.isPending}
                    className="font-mono text-xs font-semibold uppercase px-6 py-2.5 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                    style={{ background: 'linear-gradient(135deg, #FFD700, #FF8C42)', color: '#0B0D12' }}
                  >
                    Отправить
                  </button>
                </div>
              </div>

              {notificationsList && notificationsList.length > 0 && (
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Сообщение</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Получатель</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Статус</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2" style={{ color: '#7A8A9E' }}>Дата</th>
                      </tr>
                    </thead>
                    <tbody>
                      {notificationsList.slice(0, 50).map((n) => (
                        <tr key={n.id} style={{ borderBottom: '1px solid rgba(255,255,255,0.03)' }}>
                          <td className="font-body text-xs py-2 pr-4" style={{ color: '#E8E4E0' }}>{n.message}</td>
                          <td className="font-mono text-xs py-2 pr-4" style={{ color: '#7A8A9E' }}>{n.targetName ?? 'Всем'}</td>
                          <td className="py-2 pr-4">
                            <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: n.readAt ? 'rgba(128,255,151,0.1)' : 'rgba(255,215,0,0.1)', color: n.readAt ? '#80FF97' : '#FFD700' }}>
                              {n.readAt ? 'Прочитано' : 'Активно'}
                            </span>
                          </td>
                          <td className="font-mono text-[10px] py-2" style={{ color: '#7A8A9E' }}>{new Date(n.createdAt).toLocaleDateString('ru-RU')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* Codes sections */}
            <div className="rounded-xl p-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              {/* Tabs */}
              <div className="flex items-center gap-2 mb-4">
                {(['active', 'used'] as const).map((tab) => (
                  <button
                    key={tab}
                    onClick={() => setCodeTab(tab)}
                    className="font-mono text-xs font-semibold uppercase tracking-[1px] px-4 py-2 rounded-lg transition-all"
                    style={{
                      background: codeTab === tab ? 'linear-gradient(135deg, #6BB7FF, #4D40FF)' : 'rgba(255,255,255,0.03)',
                      color: codeTab === tab ? '#0B0D12' : '#7A8A9E',
                      border: `1px solid ${codeTab === tab ? 'rgba(107,183,255,0.3)' : 'rgba(255,255,255,0.06)'}`,
                    }}
                  >
                    {tab === 'active' ? `Активные (${activeCodes.length})` : `Использованные (${usedCodes.length})`}
                  </button>
                ))}
              </div>

              {/* Points filter (only for active) */}
              {codeTab === 'active' && (
                <div className="flex items-center gap-2 mb-4 flex-wrap">
                  <span className="font-mono text-[9px] uppercase" style={{ color: '#7A8A9E' }}>Номинал:</span>
                  <button
                    onClick={() => setCodePointsFilter('all')}
                    className="font-mono text-[9px] uppercase px-2 py-1 rounded transition-all"
                    style={{
                      background: codePointsFilter === 'all' ? 'rgba(107,183,255,0.15)' : 'rgba(255,255,255,0.03)',
                      color: codePointsFilter === 'all' ? '#6BB7FF' : '#7A8A9E',
                      border: `1px solid ${codePointsFilter === 'all' ? 'rgba(107,183,255,0.2)' : 'rgba(255,255,255,0.06)'}`,
                    }}
                  >Все</button>
                  {[1000, 1500, 2000, 3500, 5000, 7500, 10000].map((p) => (
                    <button
                      key={p}
                      onClick={() => setCodePointsFilter(p)}
                      className="font-mono text-[9px] uppercase px-2 py-1 rounded transition-all"
                      style={{
                        background: codePointsFilter === p ? 'rgba(107,183,255,0.15)' : 'rgba(255,255,255,0.03)',
                        color: codePointsFilter === p ? '#6BB7FF' : '#7A8A9E',
                        border: `1px solid ${codePointsFilter === p ? 'rgba(107,183,255,0.2)' : 'rgba(255,255,255,0.06)'}`,
                      }}
                    >{p.toLocaleString()}</button>
                  ))}
                </div>
              )}

              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Код</th>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>HY-P</th>
                      {codeTab === 'active' && (
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Продажа</th>
                      )}
                      {codeTab === 'used' && (
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Статус</th>
                      )}
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2" style={{ color: '#7A8A9E' }}>{codeTab === 'used' ? 'Кем использован' : 'Дата создания'}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {displayCodes.map((c) => (
                      <tr key={c.id} style={{ borderBottom: '1px solid rgba(255,255,255,0.03)' }}>
                        <td className="font-mono text-xs py-2 pr-4" style={{ color: codeTab === 'used' ? '#7A8A9E' : '#E8E4E0', letterSpacing: '1px' }}>{c.code}</td>
                        <td className="font-body text-xs py-2 pr-4" style={{ color: '#C5CDD8' }}>{c.points.toLocaleString()}</td>
                        {codeTab === 'active' && (
                          <td className="py-2 pr-4">
                            {c.forSale === 'true' ? (
                              <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97' }}>✓ Продажа</span>
                            ) : (
                              <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(122,138,158,0.1)', color: '#7A8A9E' }}>—</span>
                            )}
                          </td>
                        )}
                        {codeTab === 'used' && (
                          <td className="py-2 pr-4">
                            <span className="font-mono text-[9px] px-2 py-0.5 rounded-full" style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97' }}>Использован</span>
                          </td>
                        )}
                        <td className="font-mono text-xs py-2" style={{ color: '#7A8A9E' }}>
                          {codeTab === 'used' ? (
                            <>
                              {c.usedByName ? (
                                <a href={`/profile/${c.usedByUserId}`} className="hover:underline" style={{ color: '#6BB7FF' }}>
                                  {c.usedByName}
                                </a>
                              ) : '—'}
                              {c.usedAt && <span className="block text-[9px]" style={{ color: '#7A8A9E' }}>{new Date(c.usedAt).toLocaleDateString('ru-RU')}</span>}
                            </>
                          ) : (
                            <span className="text-[9px]">{new Date(c.createdAt).toLocaleDateString('ru-RU')}</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {displayCodes.length === 0 && (
                  <p className="font-body text-xs text-center py-4" style={{ color: '#7A8A9E' }}>Нет кодов</p>
                )}
              </div>
            </div>
          </>
        )}

        {/* ─── TAB: GIFS ─── */}
        {activeTab === 'gifs' && (
          <div className="rounded-xl p-6 mb-8" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: 'rgba(77,64,255,0.15)', color: '#4D40FF' }}>GIF Configs</span>
                <span className="font-mono text-[10px]" style={{ color: '#7A8A9E' }}>{gifConfigsList?.length ?? 0} шт.</span>
              </div>
              <div className="flex gap-2">
                {(['pending', 'approved', 'denied', 'all'] as const).map((s) => (
                  <button
                    key={s}
                    onClick={() => setGifStatus(s)}
                    className="font-mono text-[9px] px-2 py-1 rounded transition-all"
                    style={{
                      background: gifStatus === s ? 'rgba(77,64,255,0.2)' : 'rgba(255,255,255,0.03)',
                      color: gifStatus === s ? '#4D40FF' : '#7A8A9E',
                      border: `1px solid ${gifStatus === s ? 'rgba(77,64,255,0.3)' : 'rgba(255,255,255,0.06)'}`,
                    }}
                  >
                    {s === 'pending' ? 'Ожидают' : s === 'approved' ? 'Одобрены' : s === 'denied' ? 'Отклонены' : 'Все'}
                  </button>
                ))}
              </div>
            </div>

            {gifConfigsList && gifConfigsList.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Конфиг</th>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Владелец</th>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Статус</th>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Превью</th>
                      <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2" style={{ color: '#7A8A9E' }}>Действия</th>
                    </tr>
                  </thead>
                  <tbody>
                    {gifConfigsList.map((config) => (
                      <tr key={config.configKey} style={{ borderBottom: '1px solid rgba(255,255,255,0.03)' }}>
                        <td className="py-2 pr-4">
                          <code className="font-mono text-xs" style={{ color: '#E8E4E0' }}>{config.configKey}</code>
                          <p className="font-body text-[10px]" style={{ color: '#7A8A9E' }}>{config.name}</p>
                        </td>
                        <td className="font-mono text-xs py-2 pr-4" style={{ color: '#7A8A9E' }}>
                          #{config.accountId} {config.accountName}
                        </td>
                        <td className="py-2 pr-4">
                          <span className="font-mono text-[9px] px-2 py-0.5 rounded-full"
                            style={{
                              background: config.gifApproved === null ? 'rgba(77,64,255,0.1)' : config.gifApproved ? 'rgba(128,255,151,0.1)' : 'rgba(255,100,100,0.1)',
                              color: config.gifApproved === null ? '#4D40FF' : config.gifApproved ? '#80FF97' : '#ff6464',
                            }}
                          >
                            {config.gifApproved === null ? 'Ожидает' : config.gifApproved ? 'Одобрен' : 'Отклонен'}
                          </span>
                        </td>
                        <td className="py-2 pr-4">
                          {config.gifFileName && (
                            <img
                              src={`${LICENSE_SERVER_URL}/api/gif-assets/${config.accountId}/${config.configKey}`}
                              alt="GIF preview"
                              className="rounded"
                              style={{ maxHeight: 60, maxWidth: 100 }}
                            />
                          )}
                        </td>
                        <td className="py-2">
                          <div className="flex gap-2">
                            {config.gifApproved !== true && (
                              <button
                                onClick={() => approveGif.mutate({ configKey: config.configKey })}
                                disabled={approveGif.isPending}
                                className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                                style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}
                              >
                                Одобрить
                              </button>
                            )}
                            {config.gifApproved !== false && (
                              <button
                                onClick={() => denyGif.mutate({ configKey: config.configKey })}
                                disabled={denyGif.isPending}
                                className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                                style={{ background: 'rgba(255,100,100,0.1)', color: '#ff6464', border: '1px solid rgba(255,100,100,0.2)' }}
                              >
                                Отклонить
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>Нет GIF-конфигов</p>
            )}
          </div>
        )}

        {/* ─── TAB: ACCOUNTS ─── */}
        {activeTab === 'accounts' && (
          <>
            {/* Account sub-tabs */}
            <div className="flex items-center justify-center gap-2 mb-6">
              {([
                { key: 'frozen', label: 'Замороженные' },
                { key: 'banned', label: 'Забаненные' },
              ] as const).map((tab) => (
                <button
                  key={tab.key}
                  onClick={() => setAccountSubTab(tab.key)}
                  className="font-mono text-xs font-semibold uppercase tracking-[1px] px-5 py-2 rounded-lg transition-all duration-200"
                  style={{
                    background: accountSubTab === tab.key ? 'linear-gradient(135deg, #6BB7FF, #4D40FF)' : 'rgba(255,255,255,0.03)',
                    color: accountSubTab === tab.key ? '#0B0D12' : '#7A8A9E',
                    border: `1px solid ${accountSubTab === tab.key ? 'rgba(107,183,255,0.3)' : 'rgba(255,255,255,0.06)'}`,
                  }}
                >
                  {tab.label} ({tab.key === 'frozen' ? (frozenAccounts?.length ?? 0) : (bannedAccounts?.length ?? 0)})
                </button>
              ))}
            </div>

            {/* Action modal */}
            {accountActionType && accountActionId !== null && (
              <div className="rounded-xl p-6 mb-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,100,100,0.2)' }}>
                <p className="font-mono text-xs mb-3" style={{ color: '#E8E4E0' }}>
                  {accountActionType === 'freeze' ? 'Заморозка аккаунта' : 'Бан аккаунта'} #{accountActionId}
                </p>
                <input
                  type="text"
                  placeholder="Причина..."
                  value={accountActionReason}
                  onChange={(e) => setAccountActionReason(e.target.value)}
                  className="w-full font-body text-sm px-4 py-3 rounded-lg outline-none mb-3"
                  style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                />
                <div className="flex gap-2">
                  <button
                    onClick={() => {
                      if (accountActionType === 'freeze') {
                        freezeAccount.mutate({ accountId: accountActionId, reason: accountActionReason });
                      } else {
                        banAccount.mutate({ accountId: accountActionId, reason: accountActionReason });
                      }
                    }}
                    disabled={!accountActionReason.trim() || (accountActionType === 'freeze' ? freezeAccount.isPending : banAccount.isPending)}
                    className="font-mono text-xs font-semibold uppercase px-5 py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                    style={{ background: 'linear-gradient(135deg, #ff6464, #FF8C42)', color: '#0B0D12' }}
                  >
                    Подтвердить
                  </button>
                  <button
                    onClick={() => { setAccountActionType(null); setAccountActionId(null); setAccountActionReason(''); }}
                    className="font-mono text-xs font-semibold uppercase px-5 py-2 rounded-lg transition-all hover:scale-[1.02]"
                    style={{ background: 'rgba(255,255,255,0.05)', color: '#7A8A9E', border: '1px solid rgba(255,255,255,0.08)' }}
                  >
                    Отмена
                  </button>
                </div>
              </div>
            )}

            {/* Accounts list */}
            <div className="rounded-xl p-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              <div className="flex items-center gap-2 mb-4">
                <span className="font-mono text-[10px] px-2 py-0.5 rounded" style={{ background: accountSubTab === 'frozen' ? 'rgba(107,183,255,0.08)' : 'rgba(255,100,100,0.08)', color: accountSubTab === 'frozen' ? '#6BB7FF' : '#ff6464' }}>
                  {accountSubTab === 'frozen' ? 'Замороженные аккаунты' : 'Забаненные аккаунты'}
                </span>
                <span className="font-mono text-[10px]" style={{ color: '#7A8A9E' }}>{accountList.length} шт.</span>
              </div>

              {accountList.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full">
                    <thead>
                      <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>ID</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Ник</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Причина</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2 pr-4" style={{ color: '#7A8A9E' }}>Дата</th>
                        <th className="text-left font-mono text-[9px] uppercase tracking-[1px] py-2" style={{ color: '#7A8A9E' }}>Действия</th>
                      </tr>
                    </thead>
                    <tbody>
                      {accountList.map((acc: any) => (
                        <tr key={acc.id} style={{ borderBottom: '1px solid rgba(255,255,255,0.03)' }}>
                          <td className="font-mono text-xs py-2 pr-4" style={{ color: '#E8E4E0' }}>#{acc.id}</td>
                          <td className="font-body text-xs py-2 pr-4" style={{ color: '#C5CDD8' }}>{acc.accountKey?.slice(0, 8)}...</td>
                          <td className="font-body text-[10px] py-2 pr-4" style={{ color: '#7A8A9E' }}>
                            {accountSubTab === 'frozen' ? acc.playtimeFreezeReason : acc.playtimeBanReason}
                          </td>
                          <td className="font-mono text-[10px] py-2 pr-4" style={{ color: '#7A8A9E' }}>
                            {new Date(accountSubTab === 'frozen' ? acc.playtimeFrozenAt : acc.updatedAt).toLocaleDateString('ru-RU')}
                          </td>
                          <td className="py-2">
                            {accountSubTab === 'frozen' ? (
                              <button
                                onClick={() => unfreezeAccount.mutate({ accountId: acc.id })}
                                disabled={unfreezeAccount.isPending}
                                className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                                style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}
                              >
                                Разморозить
                              </button>
                            ) : (
                              <button
                                onClick={() => unbanAccount.mutate({ accountId: acc.id })}
                                disabled={unbanAccount.isPending}
                                className="font-mono text-[9px] px-2 py-1 rounded transition-all hover:scale-105 disabled:opacity-50"
                                style={{ background: 'rgba(128,255,151,0.1)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}
                              >
                                Разбанить
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="font-body text-xs" style={{ color: '#7A8A9E' }}>
                  {accountSubTab === 'frozen' ? 'Нет замороженных аккаунтов' : 'Нет забаненных аккаунтов'}
                </p>
              )}
            </div>

            {/* Freeze / Ban action buttons for any account */}
            <div className="rounded-xl p-6 mt-6" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
              <p className="font-mono text-[10px] uppercase tracking-[1px] mb-3" style={{ color: '#7A8A9E' }}>Действия с аккаунтом</p>
              <div className="flex items-end gap-3">
                <input
                  type="number"
                  placeholder="Account ID"
                  value={accountActionId ?? ''}
                  onChange={(e) => setAccountActionId(e.target.value ? Number(e.target.value) : null)}
                  className="font-mono text-sm px-3 py-2 rounded-lg outline-none w-32"
                  style={{ color: '#E8E4E0', background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}
                />
                <button
                  onClick={() => setAccountActionType('freeze')}
                  disabled={!accountActionId}
                  className="font-mono text-xs font-semibold uppercase px-4 py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                  style={{ background: 'rgba(107,183,255,0.1)', color: '#6BB7FF', border: '1px solid rgba(107,183,255,0.2)' }}
                >
                  Заморозить
                </button>
                <button
                  onClick={() => setAccountActionType('ban')}
                  disabled={!accountActionId}
                  className="font-mono text-xs font-semibold uppercase px-4 py-2 rounded-lg transition-all hover:scale-[1.02] disabled:opacity-50"
                  style={{ background: 'rgba(255,100,100,0.1)', color: '#ff6464', border: '1px solid rgba(255,100,100,0.2)' }}
                >
                  Забанить
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {toast && <div className="fixed bottom-8 left-1/2 -translate-x-1/2 z-[200] font-mono text-xs px-5 py-2.5 rounded-xl" style={{ background: 'rgba(128,255,151,0.12)', color: '#80FF97', border: '1px solid rgba(128,255,151,0.2)' }}>{toast}</div>}
    </div>
  );
}
