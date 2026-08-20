import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, authedQuery, adminQuery } from "./middleware";
import { getDb } from "./queries/connection";
import { tickets, ticketMessages } from "@db/schema";
import { eq, desc, sql } from "drizzle-orm";
import { rm } from "fs/promises";
import path from "path";

const DISCORD_BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
const TICKET_CATEGORY_ID = "1510176942664712222";
const GUILD_ID = process.env.DISCORD_GUILD_ID; // optionally set for channel creation

// "Reply" button shown to the ticket owner in DM notifications, lets them
// answer the ticket straight from Discord (handled in discord-interaction.ts).
function ticketReplyComponents(ticketId: number): any[] {
  return [
    {
      type: 1,
      components: [
        { type: 2, style: 1, custom_id: `ticket_userreply:${ticketId}`, label: "Ответить", emoji: { name: "✍️" } },
      ],
    },
  ];
}

async function createDiscordTicketChannel(ticketId: number, title: string, _userName: string) {
  if (!DISCORD_BOT_TOKEN || !GUILD_ID) return null;
  try {
    const channelName = `ticket-${ticketId}`;
    const res = await fetch(`https://discord.com/api/v10/guilds/${GUILD_ID}/channels`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        name: channelName,
        type: 0, // GUILD_TEXT
        parent_id: TICKET_CATEGORY_ID,
        topic: `Ticket #${ticketId} | ${title} | Admin: http://127.0.0.1:3000/admin?tickets`,
        permission_overwrites: [
          { id: GUILD_ID, type: 0, deny: "1024" }, // @everyone deny VIEW_CHANNEL = 0x400
        ],
      }),
    });
    if (!res.ok) {
      console.error("[TicketDiscord] Failed to create channel:", await res.text());
      return null;
    }
    const data = await res.json() as { id: string };
    return data.id;
  } catch (e) {
    console.error("[TicketDiscord] Error creating channel:", e);
    return null;
  }
}

async function sendDiscordChannelMessage(channelId: string, content: string, embed?: any, components?: any[]) {
  if (!DISCORD_BOT_TOKEN) return;
  try {
    const body: any = { content };
    if (embed) body.embeds = [embed];
    if (components) body.components = components;
    await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch (e) {
    console.error("[TicketDiscord] Error sending message:", e);
  }
}

async function deleteDiscordChannel(channelId: string) {
  if (!DISCORD_BOT_TOKEN) return;
  try {
    await fetch(`https://discord.com/api/v10/channels/${channelId}`, {
      method: "DELETE",
      headers: { Authorization: `Bot ${DISCORD_BOT_TOKEN}` },
    });
  } catch (e) {
    console.error("[TicketDiscord] Error deleting channel:", e);
  }
}

async function sendDiscordDM(discordUserId: string, content: string, embed?: any, components?: any[]) {
  if (!DISCORD_BOT_TOKEN || !discordUserId) return;
  try {
    // 1. Create DM channel
    const channelRes = await fetch("https://discord.com/api/v10/users/@me/channels", {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ recipient_id: discordUserId }),
    });
    if (!channelRes.ok) {
      console.error("[TicketDiscordDM] Failed to create DM:", await channelRes.text());
      return;
    }
    const channel = await channelRes.json() as { id: string };
    // 2. Send message
    const body: any = { content };
    if (embed) body.embeds = [embed];
    if (components) body.components = components;
    const msgRes = await fetch(`https://discord.com/api/v10/channels/${channel.id}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    if (!msgRes.ok) {
      console.error("[TicketDiscordDM] Failed to send DM:", await msgRes.text());
    }
  } catch (e) {
    console.error("[TicketDiscordDM] Error:", e);
  }
}

async function deleteTicketFiles(ticketId: number) {
  try {
    const UPLOADS_DIR = process.env.UPLOADS_DIR || "/opt/nachosia/uploads";
    const ticketDir = path.join(UPLOADS_DIR, "tickets", String(ticketId));
    await rm(ticketDir, { recursive: true, force: true });
    console.log(`[TicketFiles] Deleted files for ticket ${ticketId}`);
  } catch (e) {
    console.error("[TicketFiles] Error deleting files:", e);
  }
}

export const ticketRouter = createRouter({
  // ─── Create ticket ───
  create: authedQuery
    .input(
      z.object({
        title: z.string().min(1).max(255),
        description: z.string().min(1).max(4000),
        category: z.string().max(32).default("other"),
        attachments: z.array(z.object({ url: z.string(), name: z.string(), size: z.number() })).optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const userId = ctx.user.id;
      const userName = ctx.user.name ?? "User";
      const role = (ctx.user as any)?.effectiveRole ?? ctx.user.role;
      const isAdmin = role === "admin" || role === "owner";

      // Max 3 active tickets per user (admin bypass)
      if (!isAdmin) {
        const activeCount = await db
          .select({ count: sql<number>`count(*)` })
          .from(tickets)
          .where(eq(tickets.userId, userId))
          .then(rows => rows[0]?.count ?? 0);
        if (activeCount >= 3) {
          throw new TRPCError({ code: "FORBIDDEN", message: "ACTIVE_TICKET_LIMIT: У вас уже 3 активных тикета. Дождитесь ответа или закройте существующие." });
        }
      }

      const [ticket] = await db.insert(tickets).values({
        userId,
        discordUserId: ctx.user.discordId ?? undefined,
        title: input.title,
        description: input.description,
        category: input.category,
        status: "open",
      });

      const ticketId = Number(ticket.insertId);

      // Create initial message
      const hasAttachments = input.attachments && input.attachments.length > 0;
      await db.insert(ticketMessages).values({
        ticketId,
        senderType: "user",
        senderId: userId,
        senderName: userName,
        content: input.description,
        hasAttachment: hasAttachments ? "true" : "false",
        attachments: hasAttachments ? input.attachments : undefined,
      });

      // Create Discord channel
      const channelId = await createDiscordTicketChannel(ticketId, input.title, userName);
      if (channelId) {
        await db.update(tickets).set({ discordChannelId: channelId }).where(eq(tickets.id, ticketId));
        const embed: any = {
          title: `🎫 Ticket #${ticketId}`,
          description: input.description,
          color: 0x6bb7ff,
          fields: [
            { name: "Author", value: userName, inline: true },
            { name: "Category", value: input.category, inline: true },
            { name: "Link", value: `[Admin Panel](http://127.0.0.1:3000/admin?tickets)` },
          ],
          timestamp: new Date().toISOString(),
        };
        if (hasAttachments) {
          embed.fields.push({
            name: "Вложения",
            value: input.attachments!.map(a => `[${a.name}](${a.url})`).join("\n"),
          });
        }
        const components = [
          {
            type: 1,
            components: [
              { type: 2, style: 1, custom_id: `ticket_reply:${ticketId}`, label: "Ответить", emoji: { name: "✍️" } },
              { type: 2, style: 3, custom_id: `ticket_take:${ticketId}`, label: "Взять тикет", emoji: { name: "🎯" } },
              { type: 2, style: 4, custom_id: `ticket_close:${ticketId}`, label: "Закрыть", emoji: { name: "🔒" } },
            ],
          },
        ];
        await sendDiscordChannelMessage(channelId, ``, embed, components);
      }

      // DM confirmation to user
      if (ctx.user.discordId) {
        await sendDiscordDM(
          ctx.user.discordId,
          ``,
          {
            title: `🎫 Тикет #${ticketId} создан`,
            description: `Ваш тикет **${input.title}** создан и отправлен команде поддержки.`,
            color: 0x80ff97,
            fields: [
              { name: "Категория", value: input.category, inline: true },
              { name: "Ссылка", value: `[Открыть тикет #${ticketId}](http://127.0.0.1:3000/support?ticket=${ticketId})` },
            ],
            timestamp: new Date().toISOString(),
          }
        );
      }

      return { id: ticketId };
    }),

  // ─── List my tickets ───
  list: authedQuery.query(async ({ ctx }) => {
    const db = getDb();
    const userId = ctx.user.id;
    const list = await db
      .select()
      .from(tickets)
      .where(eq(tickets.userId, userId))
      .orderBy(desc(tickets.createdAt));
    return list;
  }),

  // ─── Get ticket with messages ───
  getById: authedQuery
    .input(z.object({ id: z.number() }))
    .query(async ({ ctx, input }) => {
      const db = getDb();
      const userId = ctx.user.id;
      const role = (ctx.user as any)?.effectiveRole ?? ctx.user.role;
      const isAdmin = role === "admin" || role === "owner";

      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, input.id)).limit(1);
      if (!ticket) throw new TRPCError({ code: "NOT_FOUND", message: "Ticket not found" });
      if (!isAdmin && ticket.userId !== userId) {
        throw new TRPCError({ code: "FORBIDDEN", message: "Not your ticket" });
      }

      const messages = await db
        .select()
        .from(ticketMessages)
        .where(eq(ticketMessages.ticketId, input.id))
        .orderBy(ticketMessages.createdAt);

      return { ticket, messages };
    }),

  // ─── Send message ───
  message: authedQuery
    .input(
      z.object({
        ticketId: z.number(),
        content: z.string().min(1).max(4000),
        attachments: z.array(z.object({ url: z.string(), name: z.string(), size: z.number() })).optional(),
      })
    )
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const userId = ctx.user.id;
      const role = (ctx.user as any)?.effectiveRole ?? ctx.user.role;
      const isAdmin = role === "admin" || role === "owner";

      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, input.ticketId)).limit(1);
      if (!ticket) throw new TRPCError({ code: "NOT_FOUND", message: "Ticket not found" });
      if (ticket.status === "closed") {
        throw new TRPCError({ code: "FORBIDDEN", message: "Ticket is closed" });
      }
      if (!isAdmin && ticket.userId !== userId) {
        throw new TRPCError({ code: "FORBIDDEN", message: "Not your ticket" });
      }

      const senderType = isAdmin ? "admin" : "user";
      const senderName = ctx.user.name ?? "User";
      const hasAttachments = input.attachments && input.attachments.length > 0;

      const [msg] = await db.insert(ticketMessages).values({
        ticketId: input.ticketId,
        senderType,
        senderId: userId,
        senderName,
        content: input.content,
        hasAttachment: hasAttachments ? "true" : "false",
        attachments: hasAttachments ? input.attachments : undefined,
      });

      // Sync to Discord channel
      if (ticket.discordChannelId) {
        const prefix = isAdmin ? `👤 **${senderName}** (Admin)` : `👤 **${senderName}**`;
        let discordContent = `${prefix}\n${input.content}`;
        const firstImage = input.attachments?.find(a => /\.(png|jpe?g|gif|webp|bmp)$/i.test(a.name));
        const embed = firstImage ? { image: { url: firstImage.url } } : undefined;
        if (hasAttachments) {
          discordContent += "\n" + input.attachments!.map(a => `📎 [${a.name}](${a.url})`).join("\n");
        }
        await sendDiscordChannelMessage(ticket.discordChannelId, discordContent, embed);
      }

      // DM notification
      if (isAdmin && ticket.discordUserId) {
        // Admin replied → notify user
        await sendDiscordDM(
          ticket.discordUserId,
          ``,
          {
            title: `🔔 Новый ответ в тикете #${ticket.id}`,
            description: `**${senderName}** ответил в вашем тикете **${ticket.title}**:\n\n${input.content.slice(0, 500)}${input.content.length > 500 ? '...' : ''}`,
            color: 0x6bb7ff,
            fields: [
              { name: "Ссылка", value: `[Открыть тикет #${ticket.id}](http://127.0.0.1:3000/support?ticket=${ticket.id})` },
            ],
            timestamp: new Date().toISOString(),
          },
          ticketReplyComponents(ticket.id)
        );
      } else if (!isAdmin && ticket.assignedAdminId) {
        // User replied and ticket is assigned → notify admin via DM (we need admin's discordId, skip for now)
      }

      return { id: Number(msg.insertId) };
    }),

  // ─── Close ticket (admin only) ───
  close: adminQuery
    .input(z.object({ id: z.number(), reason: z.string().max(500).optional() }))
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const adminId = ctx.user.id;

      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, input.id)).limit(1);
      if (!ticket) throw new TRPCError({ code: "NOT_FOUND", message: "Ticket not found" });
      if (ticket.status === "closed") {
        throw new TRPCError({ code: "BAD_REQUEST", message: "Already closed" });
      }

      await db
        .update(tickets)
        .set({ status: "closed", closedAt: new Date(), closedBy: adminId, closeReason: input.reason ?? null })
        .where(eq(tickets.id, input.id));

      const reasonText = input.reason ? `\nПричина: ${input.reason}` : "";
      await db.insert(ticketMessages).values({
        ticketId: input.id,
        senderType: "system",
        senderId: adminId,
        senderName: "System",
        content: `Тикет закрыт администратором ${ctx.user.name ?? ""}.${reasonText}`,
      });

      if (ticket.discordChannelId) {
        await sendDiscordChannelMessage(ticket.discordChannelId, `🔒 **Тикет закрыт** администратором ${ctx.user.name ?? ""}. Канал будет удалён.`);
        await deleteDiscordChannel(ticket.discordChannelId);
        await db.update(tickets).set({ discordChannelId: null }).where(eq(tickets.id, input.id));
      }

      // DM user about closure
      if (ticket.discordUserId) {
        const closeDesc = input.reason
          ? `Ваш тикет **${ticket.title}** был закрыт администратором ${ctx.user.name ?? ""}.\n\n**Причина:** ${input.reason}`
          : `Ваш тикет **${ticket.title}** был закрыт администратором ${ctx.user.name ?? ""}.`;
        await sendDiscordDM(
          ticket.discordUserId,
          ``,
          {
            title: `🔒 Тикет #${ticket.id} закрыт`,
            description: closeDesc,
            color: 0x80ff97,
            fields: [
              { name: "История", value: `[Тикет #${ticket.id}](http://127.0.0.1:3000/support?ticket=${ticket.id})` }
            ],
            timestamp: new Date().toISOString(),
          }
        );
      }

      // Delete uploaded files, EXCEPT for payment tickets (keep receipts)
      if (ticket.category !== "payment") {
        await deleteTicketFiles(input.id);
      }

      return { success: true };
    }),

  // ─── Assign admin ───
  assign: adminQuery
    .input(z.object({ id: z.number() }))
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const adminId = ctx.user.id;

      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, input.id)).limit(1);
      if (!ticket) throw new TRPCError({ code: "NOT_FOUND", message: "Ticket not found" });

      await db.update(tickets).set({ assignedAdminId: adminId }).where(eq(tickets.id, input.id));

      await db.insert(ticketMessages).values({
        ticketId: input.id,
        senderType: "system",
        senderId: adminId,
        senderName: "System",
        content: `Администратор ${ctx.user.name ?? ""} взял тикет.`,
      });

      if (ticket.discordChannelId) {
        await sendDiscordChannelMessage(ticket.discordChannelId, `🎯 **Администратор ${ctx.user.name ?? ""} взял тикет.**`);
      }

      // DM user about assignment
      if (ticket.discordUserId) {
        await sendDiscordDM(
          ticket.discordUserId,
          ``,
          {
            title: `🎯 Администратор назначен на тикет #${ticket.id}`,
            description: `Администратор **${ctx.user.name ?? ""}** взял ваш тикет **${ticket.title}** и скоро ответит.`,
            color: 0xffd700,
            fields: [
              { name: "Ссылка", value: `[Открыть тикет #${ticket.id}](http://127.0.0.1:3000/support?ticket=${ticket.id})` },
            ],
            timestamp: new Date().toISOString(),
          },
          ticketReplyComponents(ticket.id)
        );
      }

      return { success: true };
    }),

  // ─── Admin: list all tickets ───
  adminList: adminQuery.query(async () => {
    const db = getDb();
    const list = await db.select().from(tickets).orderBy(desc(tickets.createdAt));
    return list;
  }),

  // ─── Admin: get ticket with messages ───
  adminGetById: adminQuery
    .input(z.object({ id: z.number() }))
    .query(async ({ input }) => {
      const db = getDb();
      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, input.id)).limit(1);
      if (!ticket) throw new TRPCError({ code: "NOT_FOUND", message: "Ticket not found" });
      const messages = await db
        .select()
        .from(ticketMessages)
        .where(eq(ticketMessages.ticketId, input.id))
        .orderBy(ticketMessages.createdAt);
      return { ticket, messages };
    }),

  // ─── Admin: reply as admin ───
  adminReply: adminQuery
    .input(z.object({
      ticketId: z.number(),
      content: z.string().min(1).max(4000),
      attachments: z.array(z.object({ url: z.string(), name: z.string(), size: z.number() })).optional(),
    }))
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const adminId = ctx.user.id;

      const [ticket] = await db.select().from(tickets).where(eq(tickets.id, input.ticketId)).limit(1);
      if (!ticket) throw new TRPCError({ code: "NOT_FOUND", message: "Ticket not found" });
      if (ticket.status === "closed") throw new TRPCError({ code: "FORBIDDEN", message: "Ticket is closed" });

      const senderName = ctx.user.name ?? "Admin";
      const hasAttachments = input.attachments && input.attachments.length > 0;
      const [msg] = await db.insert(ticketMessages).values({
        ticketId: input.ticketId,
        senderType: "admin",
        senderId: adminId,
        senderName,
        content: input.content,
        hasAttachment: hasAttachments ? "true" : "false",
        attachments: hasAttachments ? input.attachments : undefined,
      });

      if (ticket.discordChannelId) {
        let discordContent = `👤 **${senderName}** (Admin)\n${input.content}`;
        const firstImage = input.attachments?.find(a => /\.(png|jpe?g|gif|webp|bmp)$/i.test(a.name));
        const embed = firstImage ? { image: { url: firstImage.url } } : undefined;
        if (hasAttachments) {
          discordContent += "\n" + input.attachments!.map(a => `📎 [${a.name}](${a.url})`).join("\n");
        }
        await sendDiscordChannelMessage(ticket.discordChannelId, discordContent, embed);
      }

      // Notify the ticket owner in DM with a reply button
      if (ticket.discordUserId) {
        await sendDiscordDM(
          ticket.discordUserId,
          ``,
          {
            title: `🔔 Новый ответ в тикете #${ticket.id}`,
            description: `**${senderName}** ответил в вашем тикете **${ticket.title}**:\n\n${input.content.slice(0, 500)}${input.content.length > 500 ? '...' : ''}`,
            color: 0x6bb7ff,
            fields: [
              { name: "Ссылка", value: `[Открыть тикет #${ticket.id}](http://127.0.0.1:3000/support?ticket=${ticket.id})` },
            ],
            timestamp: new Date().toISOString(),
          },
          ticketReplyComponents(ticket.id)
        );
      }

      return { id: Number(msg.insertId) };
    }),
});
