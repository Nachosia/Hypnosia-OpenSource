import { Hono } from "hono";
import { z } from "zod";
import { getDb } from "./queries/connection";
import { tickets, ticketMessages } from "@db/schema";
import { eq } from "drizzle-orm";

const SERVICE_TOKEN = process.env.SERVICE_TOKEN;

function requireServiceToken(c: any) {
  const auth = c.req.header("Authorization") || "";
  const token = auth.replace(/^Bearer\s+/i, "");
  if (!SERVICE_TOKEN || token !== SERVICE_TOKEN) {
    return c.json({ error: "Unauthorized" }, 401);
  }
  return null;
}

// Discord REST helpers (same as ticket-router)
const DISCORD_BOT_TOKEN = process.env.DISCORD_BOT_TOKEN;
async function sendDiscordChannelMessage(channelId: string, content: string, embed?: any) {
  if (!DISCORD_BOT_TOKEN) return;
  try {
    const body: any = { content };
    if (embed) body.embeds = [embed];
    await fetch(`https://discord.com/api/v10/channels/${channelId}/messages`, {
      method: "POST",
      headers: {
        Authorization: `Bot ${DISCORD_BOT_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  } catch (e) {
    console.error("[InternalDiscord] Error sending message:", e);
  }
}

export const internalApi = new Hono();

// POST /internal/discord/ticket-create
internalApi.post("/discord/ticket-create", async (c) => {
  const err = requireServiceToken(c);
  if (err) return err;

  const body = await c.req.json();
  const parsed = z.object({
    discordUserId: z.string(),
    title: z.string().min(1).max(255),
    description: z.string().min(1).max(4000),
    category: z.string().max(32).default("other"),
    discordChannelId: z.string(),
  }).safeParse(body);

  if (!parsed.success) return c.json({ error: "Invalid input" }, 400);

  const db = getDb();
  const [ticket] = await db.insert(tickets).values({
    userId: 0, // system placeholder
    discordUserId: parsed.data.discordUserId,
    title: parsed.data.title,
    description: parsed.data.description,
    category: parsed.data.category,
    status: "open",
    discordChannelId: parsed.data.discordChannelId,
  });

  const ticketId = Number(ticket.insertId);
  await db.insert(ticketMessages).values({
    ticketId,
    senderType: "user",
    senderDiscordId: parsed.data.discordUserId,
    senderName: "Discord User",
    content: parsed.data.description,
  });

  return c.json({ id: ticketId, discordChannelId: parsed.data.discordChannelId });
});

// POST /internal/discord/ticket-message
internalApi.post("/discord/ticket-message", async (c) => {
  const err = requireServiceToken(c);
  if (err) return err;

  const body = await c.req.json();
  const parsed = z.object({
    ticketId: z.number(),
    senderDiscordId: z.string(),
    senderName: z.string(),
    content: z.string().min(1).max(4000),
    attachments: z.array(z.object({ url: z.string(), name: z.string(), size: z.number() })).optional(),
  }).safeParse(body);

  if (!parsed.success) return c.json({ error: "Invalid input" }, 400);

  const db = getDb();
  const [ticket] = await db.select().from(tickets).where(eq(tickets.id, parsed.data.ticketId)).limit(1);
  if (!ticket) return c.json({ error: "Ticket not found" }, 404);
  if (ticket.status === "closed") return c.json({ error: "Ticket closed" }, 403);

  const hasAttachment = parsed.data.attachments && parsed.data.attachments.length > 0 ? "true" : "false";
  const firstAttachment = parsed.data.attachments?.[0];

  const [msg] = await db.insert(ticketMessages).values({
    ticketId: parsed.data.ticketId,
    senderType: "user",
    senderDiscordId: parsed.data.senderDiscordId,
    senderName: parsed.data.senderName,
    content: parsed.data.content,
    hasAttachment,
    attachmentUrl: firstAttachment?.url,
    attachmentName: firstAttachment?.name,
  });

  // Notify assigned admin in Discord if exists
  if (ticket.discordChannelId && ticket.assignedAdminId) {
    await sendDiscordChannelMessage(ticket.discordChannelId, `📩 **${parsed.data.senderName}** (из Discord)\n${parsed.data.content}`);
  }

  return c.json({ id: Number(msg.insertId) });
});

// POST /internal/discord/ticket-close
internalApi.post("/discord/ticket-close", async (c) => {
  const err = requireServiceToken(c);
  if (err) return err;

  const body = await c.req.json();
  const parsed = z.object({
    ticketId: z.number(),
    closedByDiscordId: z.string(),
    reason: z.string().max(500).optional(),
  }).safeParse(body);

  if (!parsed.success) return c.json({ error: "Invalid input" }, 400);

  const db = getDb();
  const [ticket] = await db.select().from(tickets).where(eq(tickets.id, parsed.data.ticketId)).limit(1);
  if (!ticket) return c.json({ error: "Ticket not found" }, 404);
  if (ticket.status === "closed") return c.json({ error: "Already closed" }, 400);

  const reason = parsed.data.reason;
  await db.update(tickets).set({ status: "closed", closedAt: new Date(), closeReason: reason ?? null }).where(eq(tickets.id, parsed.data.ticketId));
  const reasonLine = reason ? `\nПричина: ${reason}` : "";
  await db.insert(ticketMessages).values({
    ticketId: parsed.data.ticketId,
    senderType: "system",
    senderName: "System",
    content: `Тикет закрыт из Discord пользователем ${parsed.data.closedByDiscordId}.${reasonLine}`,
  });

  return c.json({ success: true });
});

// POST /internal/discord/ticket-assign
internalApi.post("/discord/ticket-assign", async (c) => {
  const err = requireServiceToken(c);
  if (err) return err;

  const body = await c.req.json();
  const parsed = z.object({
    ticketId: z.number(),
    adminDiscordId: z.string(),
    adminName: z.string(),
  }).safeParse(body);

  if (!parsed.success) return c.json({ error: "Invalid input" }, 400);

  const db = getDb();
  const [ticket] = await db.select().from(tickets).where(eq(tickets.id, parsed.data.ticketId)).limit(1);
  if (!ticket) return c.json({ error: "Ticket not found" }, 404);

  // We don't have site admin ID from Discord ID, so we store 0 and rely on discord mapping
  await db.update(tickets).set({ assignedAdminId: 0 }).where(eq(tickets.id, parsed.data.ticketId));
  await db.insert(ticketMessages).values({
    ticketId: parsed.data.ticketId,
    senderType: "system",
    senderName: "System",
    content: `Администратор ${parsed.data.adminName} взял тикет из Discord.`,
  });

  return c.json({ success: true });
});

// GET /internal/discord/ticket-by-channel/:channelId
internalApi.get("/discord/ticket-by-channel/:channelId", async (c) => {
  const err = requireServiceToken(c);
  if (err) return err;

  const channelId = c.req.param("channelId");
  const db = getDb();
  const [ticket] = await db.select().from(tickets).where(eq(tickets.discordChannelId, channelId)).limit(1);
  if (!ticket) return c.json({ error: "Not found" }, 404);
  return c.json(ticket);
});
