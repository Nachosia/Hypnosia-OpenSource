import { Hono } from "hono";
import { writeFile, mkdir, readFile, access } from "fs/promises";
import path from "path";
import { authenticateRequest } from "./kimi/auth";
import { getDb } from "./queries/connection";
import { playerProfiles, ticketMessages } from "@db/schema";
import { eq, sql } from "drizzle-orm";
import sharp from "sharp";

const SKINS_DIR = process.env.SKINS_DIR || "/opt/nachosia/skins";
const SKINS_BASE_URL = process.env.SKINS_BASE_URL || "/skins";
const FACES_CACHE_DIR = path.join(SKINS_DIR, "faces");

const UPLOADS_DIR = process.env.UPLOADS_DIR || "/opt/nachosia/uploads";
const UPLOADS_BASE_URL = process.env.UPLOADS_BASE_URL || "/uploads";
const TICKET_UPLOADS_DIR = path.join(UPLOADS_DIR, "tickets");

export const uploadApi = new Hono();

// Ensure dirs exist
async function ensureSkinsDir() {
  try {
    await mkdir(SKINS_DIR, { recursive: true });
    await mkdir(FACES_CACHE_DIR, { recursive: true });
  } catch {
    // ignore
  }
}

// POST /api/upload/skin — upload skin PNG (64x64)
uploadApi.post("/skin", async (c) => {
  try {
    const user = await authenticateRequest(c.req.raw.headers);
    const discordId = String(user.unionId);

    const body = await c.req.parseBody({ all: false });
    const file = body.skin as File;

    if (!file || !(file instanceof File)) {
      return c.json({ error: "MISSING_FILE" }, 400);
    }

    // Validate PNG
    const bytes = new Uint8Array(await file.arrayBuffer());
    const isPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47;
    if (!isPng) {
      return c.json({ error: "INVALID_FORMAT" }, 400);
    }

    // Validate size (max 32KB)
    if (bytes.length > 32 * 1024) {
      return c.json({ error: "FILE_TOO_LARGE" }, 400);
    }

    await ensureSkinsDir();

    // Generate filename: {discordId}_{timestamp}.png
    const timestamp = Date.now();
    const filename = `${discordId}_${timestamp}.png`;
    const filepath = path.join(SKINS_DIR, filename);

    await writeFile(filepath, bytes);

    const skinUrl = `${SKINS_BASE_URL}/${filename}`;

    // Update profile
    const db = getDb();
    await db
      .update(playerProfiles)
      .set({ skinUrl })
      .where(eq(playerProfiles.discordId, discordId));

    return c.json({ success: true, skinUrl });
  } catch (e: any) {
    console.error("[Upload] Skin upload error:", e.message);
    if (e.message?.includes("authentication") || e.message?.includes("token")) {
      return c.json({ error: "UNAUTHORIZED" }, 401);
    }
    return c.json({ error: "UPLOAD_FAILED" }, 500);
  }
});

// Ensure ticket uploads dir exists
async function ensureTicketUploadsDir(ticketId: number) {
  const dir = path.join(TICKET_UPLOADS_DIR, String(ticketId));
  await mkdir(dir, { recursive: true });
  return dir;
}

const MAX_FILES_PER_MESSAGE = 3;
const MAX_FILES_PER_TICKET = 10;
const MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

function validateMagicBytes(bytes: Uint8Array): boolean {
  if (bytes.length < 8) return true; // too small to check
  // PNG
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47) return true;
  // JPEG
  if (bytes[0] === 0xFF && bytes[1] === 0xD8) return true;
  // GIF87a / GIF89a
  if (bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) return true;
  // WEBP
  if (bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return true;
  // MP4 (ftyp)
  if (bytes[4] === 0x66 && bytes[5] === 0x74 && bytes[6] === 0x79 && bytes[7] === 0x70) return true;
  // PDF
  if (bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46) return true;
  // TXT / plain text (allow ASCII printable)
  const printableCount = bytes.slice(0, 256).filter(b => b === 0x0A || b === 0x0D || (b >= 0x20 && b <= 0x7E)).length;
  if (printableCount > bytes.slice(0, 256).length * 0.9) return true;
  return false;
}

// POST /api/upload/ticket — upload files for a ticket (max 5MB each, max 3 per request, max 10 per ticket)
uploadApi.post("/ticket", async (c) => {
  try {
    const user = await authenticateRequest(c.req.raw.headers);
    const isAdmin = user.role === "admin" || user.role === "owner";
    const body = await c.req.parseBody({ all: true });
    const filesRaw = body.file;
    const ticketId = Number(body.ticketId);

    const files: File[] = [];
    if (filesRaw) {
      if (Array.isArray(filesRaw)) {
        files.push(...filesRaw.filter((f): f is File => f instanceof File));
      } else if (filesRaw instanceof File) {
        files.push(filesRaw);
      }
    }

    if (files.length === 0) {
      return c.json({ error: "MISSING_FILE" }, 400);
    }
    if (!ticketId || isNaN(ticketId)) {
      return c.json({ error: "MISSING_TICKET_ID" }, 400);
    }

    // Max 3 files per message (admin bypass available)
    if (!isAdmin && files.length > MAX_FILES_PER_MESSAGE) {
      return c.json({ error: "TOO_MANY_FILES", max: MAX_FILES_PER_MESSAGE }, 400);
    }

    // Check total files in ticket (admin bypass available)
    if (!isAdmin) {
      const db = getDb();
      const msgs = await db.select({ attachments: ticketMessages.attachments }).from(ticketMessages).where(eq(ticketMessages.ticketId, ticketId));
      let totalFiles = 0;
      for (const m of msgs) {
        const att = m.attachments as Array<{url: string; name: string; size: number}> | null;
        if (att) totalFiles += att.length;
      }
      if (totalFiles + files.length > MAX_FILES_PER_TICKET) {
        return c.json({ error: "TICKET_FILE_LIMIT", max: MAX_FILES_PER_TICKET, current: totalFiles }, 400);
      }
    }

    // Validate each file size
    for (const file of files) {
      if (file.size > MAX_FILE_SIZE) {
        return c.json({ error: "FILE_TOO_LARGE", file: file.name, maxSize: "5MB" }, 400);
      }
    }

    const dir = await ensureTicketUploadsDir(ticketId);
    const results: { url: string; name: string; size: number }[] = [];

    for (const file of files) {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (!validateMagicBytes(bytes)) {
        return c.json({ error: "INVALID_FILE_FORMAT", file: file.name }, 400);
      }
      const originalName = file.name.replace(/[^a-zA-Z0-9._-]/g, "_");
      const timestamp = Date.now();
      const filename = `${timestamp}_${originalName}`;
      const filepath = path.join(dir, filename);
      await writeFile(filepath, bytes);
      const fileUrl = `${UPLOADS_BASE_URL}/tickets/${ticketId}/${filename}`;
      results.push({ url: fileUrl, name: originalName, size: bytes.length });
    }

    return c.json({ success: true, files: results });
  } catch (e: any) {
    console.error("[Upload] Ticket upload error:", e.message);
    if (e.message?.includes("authentication") || e.message?.includes("token")) {
      return c.json({ error: "UNAUTHORIZED" }, 401);
    }
    return c.json({ error: "UPLOAD_FAILED" }, 500);
  }
});

// Serve uploaded ticket files statically
uploadApi.get("/ticket/file/*", async (c) => {
  try {
    const subpath = c.req.param("*");
    if (!subpath || subpath.includes("..") || subpath.includes("//")) {
      return c.json({ error: "INVALID_PATH" }, 400);
    }
    const filepath = path.join(TICKET_UPLOADS_DIR, subpath);
    // Security: ensure it's inside TICKET_UPLOADS_DIR
    const resolved = path.resolve(filepath);
    const baseResolved = path.resolve(TICKET_UPLOADS_DIR);
    if (!resolved.startsWith(baseResolved)) {
      return c.json({ error: "INVALID_PATH" }, 400);
    }
    const data = await readFile(resolved);
    // Guess content type from extension
    const ext = path.extname(resolved).toLowerCase();
    const mimeTypes: Record<string, string> = {
      ".png": "image/png",
      ".jpg": "image/jpeg",
      ".jpeg": "image/jpeg",
      ".gif": "image/gif",
      ".webp": "image/webp",
      ".mp4": "video/mp4",
      ".webm": "video/webm",
      ".txt": "text/plain",
      ".pdf": "application/pdf",
      ".zip": "application/zip",
    };
    const filename = path.basename(resolved);
    c.header("Content-Type", mimeTypes[ext] || "application/octet-stream");
    c.header("Content-Disposition", `attachment; filename="${filename}"`);
    c.header("X-Content-Type-Options", "nosniff");
    c.header("Cache-Control", "public, max-age=86400");
    return c.body(data);
  } catch (e: any) {
    console.error("[Upload] Ticket file serve error:", e.message);
    return c.json({ error: "NOT_FOUND" }, 404);
  }
});

// GET /api/skin/face/:filename — extract face (8x8 @ 8,8) and resize to 128x128
uploadApi.get("/face/*", async (c) => {
  try {
    const filename = c.req.param("*");
    if (!filename || !filename.endsWith(".png") || filename.includes("/") || filename.includes("\\")) {
      return c.json({ error: "INVALID_FILENAME" }, 400);
    }

    const sourcePath = path.join(SKINS_DIR, filename);
    const cachePath = path.join(FACES_CACHE_DIR, filename);

    // Return cached face if exists
    try {
      await access(cachePath);
      const cached = await readFile(cachePath);
      c.header("Content-Type", "image/png");
      c.header("Cache-Control", "public, max-age=86400");
      return c.body(cached);
    } catch {
      // not cached, generate
    }

    // Use source skin or fallback to Steve
    let sourceFile = sourcePath;
    try {
      await access(sourcePath);
    } catch {
      try {
        await access(path.join(SKINS_DIR, "steve.png"));
        sourceFile = path.join(SKINS_DIR, "steve.png");
      } catch {
        return c.json({ error: "SKIN_NOT_FOUND" }, 404);
      }
    }

    const faceBuffer = await sharp(sourceFile)
      .extract({ left: 8, top: 8, width: 8, height: 8 })
      .resize(128, 128, { kernel: sharp.kernel.nearest })
      .png()
      .toBuffer();

    // Cache it
    await writeFile(cachePath, faceBuffer);

    c.header("Content-Type", "image/png");
    c.header("Cache-Control", "public, max-age=86400");
    return c.body(new Uint8Array(faceBuffer));
  } catch (e: any) {
    console.error("[SkinFace] Error:", e.message);
    return c.json({ error: "PROCESSING_FAILED" }, 500);
  }
});
