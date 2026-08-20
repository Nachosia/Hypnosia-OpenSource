import { getDb } from "./queries/connection";
import {
  tickets,
  ticketMessages,
  modAccounts,
  playerProfiles,
  modPlaytimeAlltime,
  modPlaytimeMonthly,
  modPlaytimeDaily,
  modPlaytimeServersAlltime,
  serverNames,
} from "@db/schema";
import { eq, and, inArray, gte, sql, desc } from "drizzle-orm";
import { env } from "./lib/env";
import {
  computeEffectiveRole,
  getSortedUniqueRoles,
  formatRoleName,
  pickHighestLicenseRole,
  normalizeRole,
} from "./lib/roles";
import {
  getGifConfigs,
  approveGifConfig,
  denyGifConfig,
} from "./lib/license-client";

const SITE = "http://127.0.0.1:3000";
const EPHEMERAL = 64;
const BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;

// ─── Per-user, per-command cooldowns ───
const cooldowns = new Map<string, number>();
function checkCooldown(commandKey: string, ms: number): { ok: boolean; retryAfter?: number } {
  const now = Date.now();
  const until = cooldowns.get(commandKey) ?? 0;
  if (now < until) {
    return { ok: false, retryAfter: Math.ceil((until - now) / 1000) };
  }
  cooldowns.set(commandKey, now + ms);
  return { ok: true };
}

// ─── Response helpers (type 4 = CHANNEL_MESSAGE_WITH_SOURCE) ───
function reply(content: string, ephemeral = true) {
  return { type: 4, data: { content, flags: ephemeral ? EPHEMERAL : 0 } };
}
function replyEmbed(embed: any, ephemeral = true) {
  return { type: 4, data: { embeds: [embed], flags: ephemeral ? EPHEMERAL : 0 } };
}

// Extract invoking user's Discord id/name from either guild or DM context.
function getCaller(interaction: any): { id: string; name: string } {
  const u = interaction.member?.user ?? interaction.user ?? {};
  return { id: String(u.id ?? ""), name: u.username ?? "User" };
}

// Read a slash-command option value by name.
function getOption(interaction: any, name: string): any {
  const opts = interaction.data?.options ?? [];
  return opts.find((o: any) => o.name === name)?.value;
}

function formatPlaytime(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h <= 0) return `${m}м`;
  return `${h}ч ${m}м`;
}

// Owner = OWNER_UNION_ID, or anyone whose mod account has admin/owner license role.
async function isStaff(discordId: string): Promise<boolean> {
  if (env.ownerUnionId && discordId === env.ownerUnionId) return true;
  if (!discordId) return false;
  const db = getDb();
  const [acc] = await db
    .select({ licenseRoles: modAccounts.licenseRoles })
    .from(modAccounts)
    .where(eq(modAccounts.discordId, discordId))
    .limit(1);
  if (!acc) return false;
  const role = normalizeRole(pickHighestLicenseRole(acc.licenseRoles ?? []));
  return role === "owner" || role === "admin";
}

async function sendDM(discordUserId: string, content: string) {
  if (!BOT_TOKEN || !discordUserId) return;
  try {
    const ch = await fetch("https://discord.com/api/v10/users/@me/channels", {
      method: "POST",
      headers: { Authorization: `Bot ${BOT_TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify({ recipient_id: discordUserId }),
    });
    if (!ch.ok) return;
    const channel = (await ch.json()) as { id: string };
    await fetch(`https://discord.com/api/v10/channels/${channel.id}/messages`, {
      method: "POST",
      headers: { Authorization: `Bot ${BOT_TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify({ content }),
    });
  } catch (e) {
    console.error("[DiscordCmd] DM error:", e);
  }
}

async function sendChannelMessage(channelId: string, content: string) {
  if (!BOT_TOKEN || !channelId) return;
  try {
    await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
      method: "POST",
      headers: { Authorization: `Bot ${BOT_TOKEN}`, "Content-Type": "application/json" },
      body: JSON.stringify({ content }),
    });
  } catch (e) {
    console.error("[DiscordCmd] channel msg error:", e);
  }
}

// ─── Main dispatcher for APPLICATION_COMMAND (interaction.type === 2) ───
export async function handleSlashCommand(interaction: any): Promise<any> {
  const name = interaction.data?.name as string;
  const caller = getCaller(interaction);

  switch (name) {
    case "help":
      return handleHelp(caller);
    case "profile":
      return handleProfile(caller);
    case "top":
      return handleTop(interaction, caller);
    case "stats":
      return handleStats(caller);
    case "rank":
      return handleRank(caller);
    case "online":
      return handleOnline(caller);
    case "ticket":
      return handleTicket(interaction, caller);
    case "gif":
      return handleGif(interaction, caller);
    default:
      return reply("❌ Неизвестная команда.");
  }
}

// ─── /help (cooldown 10s) ───
function handleHelp(caller: { id: string; name: string }) {
  const cd = checkCooldown(`help:${caller.id}`, 10_000);
  if (!cd.ok) return reply(`⏳ Подожди ${cd.retryAfter}с перед повторным вызовом.`);

  const embed = {
    title: "📖 Команды бота Hypnosia",
    color: 0x6bb7ff,
    description: "Все команды лучше всего работают в личке с ботом.",
    fields: [
      { name: "/profile", value: "Ваш профиль на сайте и краткая статистика. Кулдаун 10с." },
      { name: "/stats", value: "Подробная статистика: время за неделю/месяц/всё время и топ серверов. Кулдаун 10с." },
      { name: "/rank", value: "Ваше место в общем топе по времени игры. Кулдаун 10с." },
      { name: "/top `период`", value: "Топ-10 игроков по времени. Период: `alltime` или `month`. Кулдаун 3мин." },
      { name: "/online", value: "Сколько игроков сейчас онлайн. Кулдаун 30с." },
      { name: "/help", value: "Этот список команд. Кулдаун 10с." },
      { name: "/ticket `действие` `id` `текст`", value: "Только для администрации. Действия: `ответить`, `взять`, `закрыть`." },
      { name: "/gif `действие` `ключ` `статус`", value: "Только для администрации. Модерация GIF конфигов. Действия: `list`, `approve`, `deny`." },
    ],
    footer: { text: "127.0.0.1:3000" },
    timestamp: new Date().toISOString(),
  };
  return replyEmbed(embed);
}

// ─── /profile (cooldown 10s) ───
async function handleProfile(caller: { id: string; name: string }) {
  const cd = checkCooldown(`profile:${caller.id}`, 10_000);
  if (!cd.ok) return reply(`⏳ Подожди ${cd.retryAfter}с перед повторным вызовом.`);

  const db = getDb();
  const [acc] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.discordId, caller.id))
    .limit(1);

  if (!acc) {
    return reply(
      "❌ Ваш Discord не привязан к игровому аккаунту.\n" +
        `Привяжите его на сайте: ${SITE}`
    );
  }

  // Live aggregate from daily (source of truth) — never drifts.
  const [ptRow] = await db
    .select({ minutes: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
    .from(modPlaytimeDaily)
    .where(eq(modPlaytimeDaily.accountId, acc.id));
  const totalMinutes = ptRow?.minutes ?? 0;

  const [profile] = await db
    .select({ skinUrl: playerProfiles.skinUrl, customRoleName: playerProfiles.customRoleName, role: playerProfiles.role })
    .from(playerProfiles)
    .where(eq(playerProfiles.discordId, caller.id))
    .limit(1);

  const effectiveRole = computeEffectiveRole(acc.licenseRoles ?? [], profile?.role, undefined);
  const roleLabel = formatRoleName(effectiveRole, profile?.customRoleName ?? null);
  const displayName = acc.displayName && acc.displayName !== "None" && acc.displayName.trim() !== ""
    ? acc.displayName
    : `Account #${acc.accountId}`;

  const banned = acc.playtimeBanned === "true";
  const frozen = acc.playtimeFrozen === "true";

  const embed: any = {
    title: `👤 Профиль — ${displayName}`,
    color: 0x80ff97,
    url: `${SITE}/#/profile/${acc.id}`,
    fields: [
      { name: "Роль", value: roleLabel, inline: true },
      { name: "Время в игре", value: (banned || frozen) ? "—" : formatPlaytime(totalMinutes), inline: true },
      { name: "Статус", value: acc.isOnline === "true" ? "🟢 Онлайн" : "⚫ Оффлайн", inline: true },
      { name: "Профиль", value: `[Открыть на сайте](${SITE}/#/profile/${acc.id})` },
    ],
    timestamp: new Date().toISOString(),
  };
  if (profile?.skinUrl) embed.thumbnail = { url: profile.skinUrl };
  if (banned) embed.fields.push({ name: "⚠️", value: "Время игры заблокировано." });
  else if (frozen) embed.fields.push({ name: "❄️", value: "Время игры заморожено." });

  return replyEmbed(embed);
}

// ─── /top period:alltime|month (cooldown 3min) ───
async function handleTop(interaction: any, caller: { id: string; name: string }) {
  const cd = checkCooldown(`top:${caller.id}`, 180_000);
  if (!cd.ok) return reply(`⏳ Команда /top доступна раз в 3 минуты. Подожди ${cd.retryAfter}с.`);

  const period = (getOption(interaction, "период") as string) || "alltime";
  const isMonthly = period === "month" || period === "monthly";

  const db = getDb();

  // 1. Pre-aggregated playtime (minutes) keyed by mod_accounts.id
  let playtimeRows: { accountId: number; minutes: number }[] = [];
  if (isMonthly) {
    const now = new Date();
    const ym = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
    playtimeRows = await db
      .select({ accountId: modPlaytimeMonthly.accountId, minutes: modPlaytimeMonthly.minutes })
      .from(modPlaytimeMonthly)
      .where(eq(modPlaytimeMonthly.yearMonth, ym));
  } else {
    playtimeRows = await db
      .select({ accountId: modPlaytimeAlltime.accountId, minutes: modPlaytimeAlltime.minutes })
      .from(modPlaytimeAlltime);
  }
  const playtimeMap = new Map(playtimeRows.map((r) => [r.accountId, r.minutes]));

  // 2. Non-banned, non-frozen accounts
  const accounts = await db
    .select()
    .from(modAccounts)
    .where(and(eq(modAccounts.playtimeBanned, "false"), eq(modAccounts.playtimeFrozen, "false")))
    .limit(500);

  // 3. Profiles for custom role names
  const discordIds = accounts.map((a) => a.discordId).filter(Boolean) as string[];
  const profiles = discordIds.length
    ? await db.select().from(playerProfiles).where(inArray(playerProfiles.discordId, discordIds))
    : [];
  const profileMap = new Map(profiles.map((p) => [p.discordId, p]));

  // 4. Merge, sort, top 10
  const ranked = accounts
    .map((a) => {
      const profile = a.discordId ? profileMap.get(a.discordId) : undefined;
      const minutes = playtimeMap.get(a.id) || 0;
      const role = computeEffectiveRole(a.licenseRoles ?? [], profile?.role, undefined);
      const username = a.displayName && a.displayName !== "None" && a.displayName.trim() !== ""
        ? a.displayName
        : `Account #${a.accountId}`;
      return { username, minutes, role: formatRoleName(role, profile?.customRoleName ?? null) };
    })
    .filter((r) => r.minutes > 0)
    .sort((a, b) => b.minutes - a.minutes)
    .slice(0, 10);

  if (ranked.length === 0) {
    return reply("📊 Пока нет данных для топа.");
  }

  const medals = ["🥇", "🥈", "🥉"];
  const lines = ranked.map((r, i) => {
    const place = medals[i] ?? `**${i + 1}.**`;
    return `${place} **${r.username}** — ${r.role} — ${formatPlaytime(r.minutes)}`;
  });

  const embed = {
    title: isMonthly ? "🏆 Топ-10 за месяц" : "🏆 Топ-10 за всё время",
    description: lines.join("\n"),
    color: 0xffd700,
    footer: { text: `127.0.0.1:3000 • топ по времени игры` },
    timestamp: new Date().toISOString(),
  };
  return replyEmbed(embed);
}

// ─── /stats (cooldown 10s) — detailed personal playtime ───
async function handleStats(caller: { id: string; name: string }) {
  const cd = checkCooldown(`stats:${caller.id}`, 10_000);
  if (!cd.ok) return reply(`⏳ Подожди ${cd.retryAfter}с перед повторным вызовом.`);

  const db = getDb();
  const [acc] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.discordId, caller.id))
    .limit(1);
  if (!acc) {
    return reply(`❌ Ваш Discord не привязан к игровому аккаунту. Привяжите его на сайте: ${SITE}`);
  }

  if (acc.playtimeBanned === "true") return reply("⚠️ Ваше время игры заблокировано.");
  if (acc.playtimeFrozen === "true") return reply("❄️ Ваше время игры заморожено.");

  // All-time — live aggregate from daily (source of truth)
  const [allRow] = await db
    .select({ minutes: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
    .from(modPlaytimeDaily)
    .where(eq(modPlaytimeDaily.accountId, acc.id));
  const allMinutes = Number(allRow?.minutes ?? 0);

  // Current month — live aggregate from daily for this YYYY-MM
  const now = new Date();
  const ym = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
  const [monthRow] = await db
    .select({ minutes: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
    .from(modPlaytimeDaily)
    .where(and(eq(modPlaytimeDaily.accountId, acc.id), gte(modPlaytimeDaily.dateMsk, `${ym}-01`)));
  const monthMinutes = Number(monthRow?.minutes ?? 0);

  // Last 7 days
  const sevenDaysAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
    .toLocaleDateString("en-CA", { timeZone: "Europe/Moscow" });
  const [weekRow] = await db
    .select({ total: sql<number>`COALESCE(SUM(${modPlaytimeDaily.minutes}), 0)` })
    .from(modPlaytimeDaily)
    .where(and(eq(modPlaytimeDaily.accountId, acc.id), gte(modPlaytimeDaily.dateMsk, sevenDaysAgo)));
  const weekMinutes = Number(weekRow?.total ?? 0);

  // Top servers (alltime)
  const serverRows = await db
    .select({ serverIp: modPlaytimeServersAlltime.serverIp, totalMinutes: modPlaytimeServersAlltime.totalMinutes })
    .from(modPlaytimeServersAlltime)
    .where(eq(modPlaytimeServersAlltime.accountId, acc.id))
    .orderBy(desc(modPlaytimeServersAlltime.totalMinutes))
    .limit(5);

  let serverText = "Нет данных";
  if (serverRows.length > 0) {
    const ips = serverRows.map((r) => r.serverIp);
    const names = await db.select().from(serverNames).where(inArray(serverNames.serverIp, ips));
    const nameMap = new Map(names.map((n) => [n.serverIp, n.displayName]));
    serverText = serverRows
      .map((r) => `• ${nameMap.get(r.serverIp) || r.serverIp} — ${formatPlaytime(r.totalMinutes)}`)
      .join("\n");
  }

  const displayName = acc.displayName && acc.displayName !== "None" && acc.displayName.trim() !== ""
    ? acc.displayName
    : `Account #${acc.accountId}`;

  const embed = {
    title: `📊 Статистика — ${displayName}`,
    color: 0x6bb7ff,
    url: `${SITE}/#/profile/${acc.id}`,
    fields: [
      { name: "За неделю", value: formatPlaytime(weekMinutes), inline: true },
      { name: "За месяц", value: formatPlaytime(monthMinutes), inline: true },
      { name: "За всё время", value: formatPlaytime(allMinutes), inline: true },
      { name: "Топ серверов", value: serverText },
    ],
    footer: { text: "127.0.0.1:3000" },
    timestamp: new Date().toISOString(),
  };
  return replyEmbed(embed);
}

// ─── /rank (cooldown 10s) — caller's position in the alltime ranking ───
async function handleRank(caller: { id: string; name: string }) {
  const cd = checkCooldown(`rank:${caller.id}`, 10_000);
  if (!cd.ok) return reply(`⏳ Подожди ${cd.retryAfter}с перед повторным вызовом.`);

  const db = getDb();
  const [acc] = await db
    .select()
    .from(modAccounts)
    .where(eq(modAccounts.discordId, caller.id))
    .limit(1);
  if (!acc) {
    return reply(`❌ Ваш Discord не привязан к игровому аккаунту. Привяжите его на сайте: ${SITE}`);
  }
  if (acc.playtimeBanned === "true" || acc.playtimeFrozen === "true") {
    return reply("⚠️ Ваше время игры заблокировано или заморожено — вы не участвуете в рейтинге.");
  }

  // My alltime minutes
  const [myRow] = await db
    .select({ minutes: modPlaytimeAlltime.minutes })
    .from(modPlaytimeAlltime)
    .where(eq(modPlaytimeAlltime.accountId, acc.id))
    .limit(1);
  const myMinutes = myRow?.minutes ?? 0;
  if (myMinutes <= 0) {
    return reply("📊 У вас пока нет наигранного времени — вы ещё не в рейтинге.");
  }

  // Eligible (non-banned, non-frozen) account ids
  const eligible = await db
    .select({ id: modAccounts.id })
    .from(modAccounts)
    .where(and(eq(modAccounts.playtimeBanned, "false"), eq(modAccounts.playtimeFrozen, "false")))
    .limit(2000);
  const eligibleIds = new Set(eligible.map((e) => e.id));

  // All alltime rows, count how many eligible accounts have more minutes
  const allRows = await db
    .select({ accountId: modPlaytimeAlltime.accountId, minutes: modPlaytimeAlltime.minutes })
    .from(modPlaytimeAlltime);
  let higher = 0;
  let totalRanked = 0;
  for (const r of allRows) {
    if (!eligibleIds.has(r.accountId) || r.minutes <= 0) continue;
    totalRanked++;
    if (r.minutes > myMinutes) higher++;
  }
  const rank = higher + 1;

  const embed = {
    title: "🏅 Ваше место в рейтинге",
    color: 0xffd700,
    description: `Вы на **#${rank}** месте из **${totalRanked}** по времени игры за всё время.`,
    fields: [{ name: "Ваше время", value: formatPlaytime(myMinutes), inline: true }],
    footer: { text: "127.0.0.1:3000 • /top — полный список" },
    timestamp: new Date().toISOString(),
  };
  return replyEmbed(embed);
}

// ─── /online (cooldown 30s) — current online players ───
async function handleOnline(caller: { id: string; name: string }) {
  const cd = checkCooldown(`online:${caller.id}`, 30_000);
  if (!cd.ok) return reply(`⏳ Подожди ${cd.retryAfter}с перед повторным вызовом.`);

  const db = getDb();
  const onlineAccounts = await db
    .select({ displayName: modAccounts.displayName, accountId: modAccounts.accountId })
    .from(modAccounts)
    .where(eq(modAccounts.isOnline, "true"))
    .limit(50);

  const count = onlineAccounts.length;
  if (count === 0) {
    return replyEmbed({
      title: "🟢 Игроки онлайн",
      color: 0x80ff97,
      description: "Сейчас никого нет онлайн.",
      footer: { text: "127.0.0.1:3000" },
      timestamp: new Date().toISOString(),
    });
  }

  const names = onlineAccounts
    .slice(0, 20)
    .map((a) => {
      const n = a.displayName && a.displayName !== "None" && a.displayName.trim() !== ""
        ? a.displayName
        : `Account #${a.accountId}`;
      return `• ${n}`;
    })
    .join("\n");
  const more = count > 20 ? `\n…и ещё ${count - 20}` : "";

  const embed = {
    title: `🟢 Сейчас онлайн: ${count}`,
    color: 0x80ff97,
    description: names + more,
    footer: { text: "127.0.0.1:3000" },
    timestamp: new Date().toISOString(),
  };
  return replyEmbed(embed);
}

// ─── /ticket action:ответить|взять|закрыть id:<n> [текст] (staff only) ───
async function handleTicket(interaction: any, caller: { id: string; name: string }) {
  if (!(await isStaff(caller.id))) {
    return reply("❌ Эта команда доступна только администрации.");
  }

  const action = String(getOption(interaction, "действие") ?? "");
  const ticketId = Number(getOption(interaction, "id"));
  const text = (getOption(interaction, "сообщение") as string) || "";

  const db = getDb();

  // ── list (no ticket id needed) ──
  if (action === "список" || action === "list") {
    const openTickets = await db
      .select({ id: tickets.id, title: tickets.title, status: tickets.status, category: tickets.category })
      .from(tickets)
      .where(eq(tickets.status, "open"))
      .orderBy(desc(tickets.createdAt))
      .limit(20);

    if (openTickets.length === 0) {
      return reply("📋 Открытых тикетов нет.");
    }
    const lines = openTickets.map((t) => `**#${t.id}** — ${t.title} \`(${t.category})\``);
    return replyEmbed({
      title: `📋 Открытые тикеты (${openTickets.length})`,
      color: 0x6bb7ff,
      description: lines.join("\n"),
      footer: { text: "127.0.0.1:3000 • /ticket ответить id текст" },
      timestamp: new Date().toISOString(),
    });
  }

  if (!ticketId || Number.isNaN(ticketId)) {
    return reply("❌ Укажите корректный ID тикета.");
  }

  const [ticket] = await db.select().from(tickets).where(eq(tickets.id, ticketId)).limit(1);
  if (!ticket) return reply(`❌ Тикет #${ticketId} не найден.`);

  // ── reply ──
  if (action === "ответить" || action === "reply") {
    if (ticket.status === "closed") return reply("🔒 Тикет уже закрыт.");
    if (!text.trim()) return reply("❌ Укажите текст ответа в поле `сообщение`.");

    await db.insert(ticketMessages).values({
      ticketId,
      senderType: "admin",
      senderDiscordId: caller.id,
      senderName: caller.name,
      content: text,
    });

    if (ticket.discordChannelId) {
      await sendChannelMessage(ticket.discordChannelId, `👤 **${caller.name}** (Admin)\n${text}`);
    }
    if (ticket.discordUserId) {
      await sendDM(
        ticket.discordUserId,
        `🔔 **${caller.name}** ответил в вашем тикете #${ticketId} «${ticket.title}»:\n${text.slice(0, 800)}\n\nОткрыть: ${SITE}/support?ticket=${ticketId}`
      );
    }
    return reply(`✅ Ответ отправлен в тикет #${ticketId}.`);
  }

  // ── take ──
  if (action === "взять" || action === "take") {
    if (ticket.status === "closed") return reply("🔒 Тикет уже закрыт.");
    // Look up site admin id by discord id
    let adminSiteId: number | null = null;
    const [adminAcc] = await db.select({ id: modAccounts.id }).from(modAccounts).where(eq(modAccounts.discordId, caller.id)).limit(1);
    if (adminAcc) adminSiteId = adminAcc.id;
    await db.update(tickets).set({ assignedAdminId: adminSiteId }).where(eq(tickets.id, ticketId));
    await db.insert(ticketMessages).values({
      ticketId,
      senderType: "system",
      senderName: "System",
      content: `Администратор ${caller.name} взял тикет из Discord.`,
    });
    if (ticket.discordChannelId) {
      await sendChannelMessage(ticket.discordChannelId, `🎯 **${caller.name}** взял тикет.`);
    }
    if (ticket.discordUserId) {
      await sendDM(
        ticket.discordUserId,
        `🎯 Администратор **${caller.name}** взял ваш тикет #${ticketId} «${ticket.title}» и скоро ответит.`
      );
    }
    return reply(`🎯 Вы взяли тикет #${ticketId}.`);
  }

  // ── close ──
  if (action === "закрыть" || action === "close") {
    if (ticket.status === "closed") return reply("🔒 Тикет уже закрыт.");
    const closeReason = (getOption(interaction, "причина") as string) || "";
    await db.update(tickets).set({ status: "closed", closedAt: new Date(), closeReason }).where(eq(tickets.id, ticketId));
    const reasonLine = closeReason ? `\nПричина: ${closeReason}` : "";
    await db.insert(ticketMessages).values({
      ticketId,
      senderType: "system",
      senderName: "System",
      content: `Тикет закрыт из Discord администратором ${caller.name}.${reasonLine}`,
    });
    if (ticket.discordChannelId) {
      await sendChannelMessage(ticket.discordChannelId, `🔒 Тикет закрыт администратором ${caller.name}. Канал будет удалён.${reasonLine}`);
      await fetch(`https://discord.com/api/v10/channels/${ticket.discordChannelId}`, {
        method: "DELETE",
        headers: { Authorization: `Bot ${BOT_TOKEN}` },
      }).catch(() => {});
      await db.update(tickets).set({ discordChannelId: null }).where(eq(tickets.id, ticketId));
    }
    if (ticket.discordUserId) {
      const dmText = closeReason
        ? `🔒 Ваш тикет #${ticketId} «${ticket.title}» был закрыт администратором ${caller.name}.\n\nПричина: ${closeReason}`
        : `🔒 Ваш тикет #${ticketId} «${ticket.title}» был закрыт администратором ${caller.name}.`;
      await sendDM(ticket.discordUserId, dmText);
    }
    return reply(`🔒 Тикет #${ticketId} закрыт.${reasonLine}`);
  }

  return reply("❌ Неизвестное действие. Используйте: `ответить`, `взять` или `закрыть`.");
}

// ─── /gif (admin only) ───
async function handleGif(interaction: any, caller: { id: string; name: string }) {
  const staff = await isStaff(caller.id);
  if (!staff) return reply("❌ Только для администрации.");

  const action = (getOption(interaction, "действие") as string) || "";
  const configKey = (getOption(interaction, "ключ") as string) || "";
  const status = (getOption(interaction, "статус") as string) || "all";

  if (action === "list" || action === "список") {
    const configs = await getGifConfigs(status === "all" ? undefined : status);
    if (configs.length === 0) {
      return reply("📋 GIF конфиги не найдены.");
    }
    const lines = configs.map((c: any) => {
      const approved = c.gifApproved === true ? "✅" : c.gifApproved === false ? "❌" : "⏳";
      return `\`${c.configKey}\` — ${c.name} (${approved}) — #${c.accountId}`;
    });
    const chunks: string[] = [];
    let chunk = "";
    for (const line of lines) {
      if (chunk.length + line.length > 1800) {
        chunks.push(chunk);
        chunk = line;
      } else {
        chunk += (chunk ? "\n" : "") + line;
      }
    }
    if (chunk) chunks.push(chunk);
    if (chunks.length === 1) {
      return reply(`📋 GIF конфиги (${configs.length}):\n${chunks[0]}`);
    }
    return reply(`📋 GIF конфиги (${configs.length}):\n${chunks[0]}\n\n...и ещё ${configs.length - lines.slice(0, chunks[0].split("\n").length).length}`);
  }

  if (!configKey || configKey.length < 3) {
    return reply("❌ Укажите корректный config key.");
  }

  if (action === "approve" || action === "одобрить") {
    const ok = await approveGifConfig(configKey);
    if (!ok) return reply(`❌ Не удалось одобрить \`${configKey}\`.`);
    return reply(`✅ GIF конфиг \`${configKey}\` одобрен.`);
  }

  if (action === "deny" || action === "отклонить") {
    const ok = await denyGifConfig(configKey);
    if (!ok) return reply(`❌ Не удалось отклонить \`${configKey}\`.`);
    return reply(`❌ GIF конфиг \`${configKey}\` отклонён. Аккаунт заблокирован от Cloud Config.`);
  }

  return reply("❌ Неизвестное действие. Используйте: `list`, `approve` или `deny`.");
}
