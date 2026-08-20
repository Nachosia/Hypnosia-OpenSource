import { Hono } from "hono";
import { createContext } from "./context";
import { getDb } from "./queries/connection";
import { tickets, ticketMessages, modAccounts } from "@db/schema";
import { eq } from "drizzle-orm";
import crypto from "crypto";
import { handleSlashCommand } from "./discord-commands";

// ─── Coarse rate limiting for Discord interactions (per source IP).
// Discord delivers all interactions from its own IP ranges, so this is only a
// blunt DoS guard. Per-user, per-command cooldowns live in discord-commands.ts.
const interactionRateLimit = new Map<string, { count: number; resetAt: number }>();
const INTERACTION_RATE_LIMIT_WINDOW_MS = 60_000;
const INTERACTION_RATE_LIMIT_MAX = 120;

function checkInteractionRateLimit(ip: string): { allowed: boolean; retryAfter?: number } {
  const now = Date.now();
  const entry = interactionRateLimit.get(ip);
  if (!entry || now > entry.resetAt) {
    interactionRateLimit.set(ip, { count: 1, resetAt: now + INTERACTION_RATE_LIMIT_WINDOW_MS });
    return { allowed: true };
  }
  if (entry.count >= INTERACTION_RATE_LIMIT_MAX) {
    const retryAfter = Math.ceil((entry.resetAt - now) / 1000);
    return { allowed: false, retryAfter };
  }
  entry.count++;
  return { allowed: true };
}

const DISCORD_PUBLIC_KEY = process.env.DISCORD_PUBLIC_KEY || "";

function verifyDiscordSignature(signature: string, timestamp: string, body: string): boolean {
  try {
    const data = Buffer.from(timestamp + body);
    const sig = Buffer.from(signature, "hex");
    const rawKey = Buffer.from(DISCORD_PUBLIC_KEY, "hex");
    // Ed25519 SPKI DER prefix for 32-byte raw key
    const spkiPrefix = Buffer.from([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00]);
    const key = crypto.createPublicKey({
      key: Buffer.concat([spkiPrefix, rawKey]),
      format: "der",
      type: "spki",
    });
    return crypto.verify(null, data, key, sig);
  } catch {
    return false;
  }
}

export const discordInteractionApi = new Hono();

discordInteractionApi.post("/", async (c) => {
  const clientIp = c.req.header("x-forwarded-for")?.split(",")[0].trim()
    || c.req.header("x-real-ip")
    || "unknown";
  const rateLimit = checkInteractionRateLimit(clientIp);
  if (!rateLimit.allowed) {
    return c.json({ type: 4, data: { content: `⏳ Rate limited. Retry after ${rateLimit.retryAfter}s.`, flags: 64 } });
  }

  const signature = c.req.header("X-Signature-Ed25519") || "";
  const timestamp = c.req.header("X-Signature-Timestamp") || "";
  const body = await c.req.text();

  if (!verifyDiscordSignature(signature, timestamp, body)) {
    return c.text("Invalid signature", 401);
  }

  const interaction = JSON.parse(body);

  // PING
  if (interaction.type === 1) {
    return c.json({ type: 1 });
  }

  // APPLICATION_COMMAND (slash command)
  if (interaction.type === 2) {
    try {
      return c.json(await handleSlashCommand(interaction));
    } catch (e) {
      console.error("[DiscordInteraction] slash command error:", e);
      return c.json({ type: 4, data: { content: "❌ Произошла ошибка при выполнении команды.", flags: 64 } });
    }
  }

  // MESSAGE_COMPONENT (button click)
  if (interaction.type === 3) {
    const customId = interaction.data?.custom_id || "";

    // Reply button (admin, in ticket channel): ticket_reply:{ticketId}
    if (customId.startsWith("ticket_reply:")) {
      const ticketId = customId.split(":")[1];
      return c.json({
        type: 9, // MODAL
        data: {
          custom_id: `ticket_reply_modal:${ticketId}`,
          title: "Ответить на тикет",
          components: [
            {
              type: 1,
              components: [
                {
                  type: 4,
                  custom_id: "reply_content",
                  label: "Ваш ответ",
                  style: 2, // PARAGRAPH
                  min_length: 1,
                  max_length: 2000,
                  placeholder: "Введите текст ответа...",
                  required: true,
                },
              ],
            },
          ],
        },
      });
    }

    // Reply button (ticket owner, from DM): ticket_userreply:{ticketId}
    if (customId.startsWith("ticket_userreply:")) {
      const ticketId = customId.split(":")[1];
      return c.json({
        type: 9, // MODAL
        data: {
          custom_id: `ticket_userreply_modal:${ticketId}`,
          title: `Ответить на тикет #${ticketId}`,
          components: [
            {
              type: 1,
              components: [
                {
                  type: 4,
                  custom_id: "reply_content",
                  label: "Ваш ответ",
                  style: 2, // PARAGRAPH
                  min_length: 1,
                  max_length: 2000,
                  placeholder: "Введите текст ответа...",
                  required: true,
                },
              ],
            },
          ],
        },
      });
    }

    // Take ticket button: ticket_take:{ticketId}
    if (customId.startsWith("ticket_take:")) {
      const ticketId = Number(customId.split(":")[1]);
      const adminDiscordId = interaction.member?.user?.id || interaction.user?.id;
      const adminName = interaction.member?.user?.username || interaction.user?.username;

      const db = getDb();
      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, ticketId)).limit(1);
      if (!ticket) {
        return c.json({ type: 4, data: { content: "❌ Тикет не найден.", flags: 64 } });
      }
      if (ticket.status === "closed") {
        return c.json({ type: 4, data: { content: "🔒 Тикет уже закрыт.", flags: 64 } });
      }

      // Look up site admin id by discord id
      let adminSiteId: number | null = null;
      if (adminDiscordId) {
        const [adminAcc] = await db.select({ id: modAccounts.id }).from(modAccounts).where(eq(modAccounts.discordId, String(adminDiscordId))).limit(1);
        if (adminAcc) adminSiteId = adminAcc.id;
      }

      await db.update(tickets).set({ assignedAdminId: adminSiteId }).where(eq(tickets.id, ticketId));
      await db.insert(ticketMessages).values({
        ticketId,
        senderType: "system",
        senderName: "System",
        content: `Администратор ${adminName} взял тикет из Discord.`,
      });

      // Send DM to user
      if (ticket.discordUserId) {
        await sendDiscordDM(ticket.discordUserId, ``, {
          title: `🎯 Администратор назначен на тикет #${ticketId}`,
          description: `Администратор **${adminName}** взял ваш тикет **${ticket.title}** и скоро ответит.`,
          color: 0xffd700,
          fields: [{ name: "Ссылка", value: `[Открыть тикет](http://127.0.0.1:3000/support?ticket=${ticketId})` }],
          timestamp: new Date().toISOString(),
        }, ticketReplyComponents(ticketId));
      }

      return c.json({ type: 4, data: { content: `🎯 **${adminName}** взял тикет #${ticketId}.` } });
    }

    // Close ticket button: ticket_close:{ticketId} → open modal for reason
    if (customId.startsWith("ticket_close:")) {
      const ticketId = customId.split(":")[1];
      return c.json({
        type: 9, // MODAL
        data: {
          custom_id: `ticket_close_modal:${ticketId}`,
          title: "Закрыть тикет",
          components: [
            {
              type: 1,
              components: [
                {
                  type: 4,
                  custom_id: "close_reason",
                  label: "Причина закрытия",
                  style: 2, // PARAGRAPH
                  min_length: 1,
                  max_length: 500,
                  placeholder: "Укажите причину закрытия тикета...",
                  required: true,
                },
              ],
            },
          ],
        },
      });
    }
  }

  // MODAL_SUBMIT
  if (interaction.type === 5) {
    const customId = interaction.data?.custom_id || "";

    if (customId.startsWith("ticket_reply_modal:")) {
      const ticketId = Number(customId.split(":")[1]);
      const content = interaction.data?.components?.[0]?.components?.[0]?.value || "";
      const senderDiscordId = interaction.member?.user?.id || interaction.user?.id;
      const senderName = interaction.member?.user?.username || interaction.user?.username;

      const db = getDb();
      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, ticketId)).limit(1);
      if (!ticket || ticket.status === "closed") {
        return c.json({ type: 4, data: { content: "❌ Тикет не найден или закрыт.", flags: 64 } });
      }

      await db.insert(ticketMessages).values({
        ticketId,
        senderType: "admin",
        senderDiscordId,
        senderName,
        content,
      });

      // Notify user via DM
      if (ticket.discordUserId) {
        await sendDiscordDM(ticket.discordUserId, ``, {
          title: `🔔 Новый ответ в тикете #${ticketId}`,
          description: `**${senderName}** ответил в вашем тикете **${ticket.title}**:\n\n${content.slice(0, 500)}${content.length > 500 ? "..." : ""}`,
          color: 0x6bb7ff,
          fields: [{ name: "Ссылка", value: `[Открыть тикет](http://127.0.0.1:3000/support?ticket=${ticketId})` }],
          timestamp: new Date().toISOString(),
        }, ticketReplyComponents(ticketId));
      }

      return c.json({ type: 4, data: { content: `✅ Ответ отправлен в тикет #${ticketId}.` } });
    }

    // User (ticket owner) reply from DM: ticket_userreply_modal:{ticketId}
    if (customId.startsWith("ticket_userreply_modal:")) {
      const ticketId = Number(customId.split(":")[1]);
      const content = interaction.data?.components?.[0]?.components?.[0]?.value || "";
      const senderDiscordId = interaction.member?.user?.id || interaction.user?.id;
      const senderName = interaction.member?.user?.username || interaction.user?.username;

      const db = getDb();
      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, ticketId)).limit(1);
      if (!ticket || ticket.status === "closed") {
        return c.json({ type: 4, data: { content: "❌ Тикет не найден или закрыт.", flags: 64 } });
      }

      // Authorization: only the ticket owner may reply as the user.
      if (!ticket.discordUserId || ticket.discordUserId !== String(senderDiscordId)) {
        return c.json({ type: 4, data: { content: "❌ Это не ваш тикет.", flags: 64 } });
      }

      await db.insert(ticketMessages).values({
        ticketId,
        senderType: "user",
        senderDiscordId,
        senderName,
        content,
      });

      // Mirror the reply into the ticket's Discord channel so admins see it.
      if (ticket.discordChannelId) {
        await sendDiscordChannelMessage(
          ticket.discordChannelId,
          `👤 **${senderName}** (через DM)\n${content}`
        );
      }

      return c.json({ type: 4, data: { content: `✅ Ваш ответ отправлен в тикет #${ticketId}.`, flags: 64 } });
    }

    // Close ticket modal: ticket_close_modal:{ticketId}
    if (customId.startsWith("ticket_close_modal:")) {
      const ticketId = Number(customId.split(":")[1]);
      const closeReason = interaction.data?.components?.[0]?.components?.[0]?.value || "";
      const adminName = interaction.member?.user?.username || interaction.user?.username;

      const db = getDb();
      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, ticketId)).limit(1);
      if (!ticket) {
        return c.json({ type: 4, data: { content: "❌ Тикет не найден.", flags: 64 } });
      }
      if (ticket.status === "closed") {
        return c.json({ type: 4, data: { content: "🔒 Тикет уже закрыт.", flags: 64 } });
      }

      await db.update(tickets).set({ status: "closed", closedAt: new Date(), closeReason }).where(eq(tickets.id, ticketId));
      await db.insert(ticketMessages).values({
        ticketId,
        senderType: "system",
        senderName: "System",
        content: `Тикет закрыт из Discord пользователем ${adminName}.\nПричина: ${closeReason}`,
      });

      // Delete channel
      const channelId = interaction.channel_id;
      if (channelId) {
        await fetch(`https://discord.com/api/v10/channels/${channelId}`, {
          method: "DELETE",
          headers: { Authorization: `Bot ${process.env.DISCORD_BOT_TOKEN}` },
        });
      }

      // DM user
      if (ticket.discordUserId) {
        await sendDiscordDM(ticket.discordUserId, ``, {
          title: `🔒 Тикет #${ticketId} закрыт`,
          description: `Ваш тикет **${ticket.title}** был закрыт администратором ${adminName}.\n\n**Причина:** ${closeReason}`,
          color: 0x80ff97,
          fields: [{ name: "История", value: `[Мои тикеты](http://127.0.0.1:3000/support?ticket=${ticketId})` }],
          timestamp: new Date().toISOString(),
        });
      }

      return c.json({ type: 4, data: { content: `🔒 Тикет #${ticketId} закрыт.\nПричина: ${closeReason}` } });
    }
  }

  return c.json({ type: 4, data: { content: "❌ Неизвестное действие.", flags: 64 } });
});

async function sendDiscordDM(discordUserId: string, content: string, embed?: any, components?: any[]) {
  const token = process.env.DISCORD_BOT_TOKEN;
  if (!token) return;
  try {
    const channelRes = await fetch("https://discord.com/api/v10/users/@me/channels", {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ recipient_id: discordUserId }),
    });
    if (!channelRes.ok) return;
    const channel = (await channelRes.json()) as { id: string };
    const body: any = { content };
    if (embed) body.embeds = [embed];
    if (components) body.components = components;
    await fetch(`https://discord.com/api/v10/channels/${channel.id}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch (e) {
    console.error("[DiscordInteractionDM] Error:", e);
  }
}

// Build the "Reply" button shown to the ticket owner in DMs.
function ticketReplyComponents(ticketId: number | string): any[] {
  return [
    {
      type: 1,
      components: [
        { type: 2, style: 1, custom_id: `ticket_userreply:${ticketId}`, label: "Ответить", emoji: { name: "✍️" } },
      ],
    },
  ];
}

async function sendDiscordChannelMessage(channelId: string, content: string) {
  const token = process.env.DISCORD_BOT_TOKEN;
  if (!token || !channelId) return;
  try {
    await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ content }),
    });
  } catch (e) {
    console.error("[DiscordInteractionChannel] Error:", e);
  }
}
