import "dotenv/config";
import { Hono } from "hono";

console.log('[boot] DATA_ENCRYPTION_KEY length from process.env:', process.env.DATA_ENCRYPTION_KEY?.length);
console.log('[boot] DATA_ENCRYPTION_KEY from env.ts:', (await import("./lib/env")).env.dataEncryptionKey.length);
import { bodyLimit } from "hono/body-limit";
import { cors } from "hono/cors";
import type { HttpBindings } from "@hono/node-server";
import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { appRouter } from "./router";
import { createContext } from "./context";
import { env } from "./lib/env";
import { createOAuthCallbackHandler, createOAuthStartHandler } from "./kimi/auth";
import { modApi } from "./mod-api";
import { discordApi } from "./discord-api";
import { uploadApi } from "./upload-router";
import { skinApi } from "./skin-router";
import { internalApi } from "./internal-router";
import { discordInteractionApi } from "./discord-interaction";
import { launcherApi } from "./launcher-router";
import { Paths } from "@contracts/constants";
import { startWorkers } from "./workers";
import { serveStatic } from "@hono/node-server/serve-static";

const app = new Hono<{ Bindings: HttpBindings }>();

app.use("/api/launcher/*", cors({
  origin: ["http://tauri.localhost", "https://tauri.localhost"],
  allowMethods: ["GET", "POST", "OPTIONS"],
  allowHeaders: ["Content-Type"],
  credentials: false,
}));

app.use(bodyLimit({ maxSize: 50 * 1024 * 1024 }));
app.get("/api/oauth/start", createOAuthStartHandler());
app.get(Paths.oauthCallback, createOAuthCallbackHandler());
app.route("/api/mod", modApi);
app.route("/api/interaction/discord", discordInteractionApi);
app.route("/api/discord", discordApi);
app.route("/api/upload", uploadApi);
app.route("/api/skin/face", skinApi);
app.route("/api/launcher", launcherApi);

// Internal API: restrict to loopback / server IP only, even with token.
app.use("/internal/*", async (c, next) => {
  const remoteAddress = (c.env as any)?.remote?.address ?? "unknown";
  const forwarded = c.req.header("x-forwarded-for")?.split(",")[0].trim();
  const clientIp = forwarded || remoteAddress;
  const allowed = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
  // Also allow the server's own public IP if needed
  const serverIp = process.env.SERVER_IP || "127.0.0.1";
  if (serverIp) allowed.add(serverIp);
  if (!allowed.has(clientIp)) {
    console.warn(`[internal] blocked request from ${clientIp}`);
    return c.json({ error: "FORBIDDEN" }, 403);
  }
  await next();
});
app.route("/internal", internalApi);

// Serve uploaded skins
app.use("/skins/*", serveStatic({ root: "/opt/nachosia/skins", rewriteRequestPath: (path) => path.replace(/^\/skins/, "") }));

app.use("/api/trpc/*", async (c) => {
  return fetchRequestHandler({
    endpoint: "/api/trpc",
    req: c.req.raw,
    router: appRouter,
    createContext,
  });
});
app.all("/api/*", (c) => c.json({ error: "Not Found" }, 404));

export default app;

if (env.isProduction) {
  startWorkers();

  const { serve } = await import("@hono/node-server");
  const { serveStaticFiles } = await import("./lib/vite");
  serveStaticFiles(app);

  const port = parseInt(process.env.PORT || "3000");
  serve({ fetch: app.fetch, port }, () => {
    console.log(`Server running on http://localhost:${port}/`);
  });
}
