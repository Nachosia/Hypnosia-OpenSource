import { Hono } from "hono";
import path from "path";
import { readFile, access, writeFile, mkdir } from "fs/promises";
import sharp from "sharp";

const SKINS_DIR = process.env.SKINS_DIR || "/opt/nachosia/skins";
const FACES_CACHE_DIR = path.join(SKINS_DIR, "faces");

async function ensureFacesDir() {
  try {
    await mkdir(FACES_CACHE_DIR, { recursive: true });
  } catch {
    // ignore
  }
}

export const skinApi = new Hono();

// GET /api/skin/face/:filename — extract face (8x8 @ 8,8) and resize to 128x128
skinApi.get("/:filename", async (c) => {
  try {
    const filename = c.req.param("filename");
    if (!filename || filename.includes("/") || filename.includes("\\")) {
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
        const stevePath = path.join(SKINS_DIR, "steve.png");
        await access(stevePath);
        sourceFile = stevePath;
      } catch {
        return c.json({ error: "SKIN_NOT_FOUND" }, 404);
      }
    }

    await ensureFacesDir();

    const faceBuffer = await sharp(sourceFile)
      .extract({ left: 8, top: 8, width: 8, height: 8 })
      .resize(128, 128, { kernel: sharp.kernel.nearest })
      .png()
      .toBuffer();

    // Cache it
    await writeFile(cachePath, faceBuffer);

    c.header("Content-Type", "image/png");
    c.header("Cache-Control", "public, max-age=86400");
    return c.body(faceBuffer);
  } catch (e: any) {
    console.error("[SkinFace] Error:", e.message);
    return c.json({ error: "PROCESSING_FAILED" }, 500);
  }
});
