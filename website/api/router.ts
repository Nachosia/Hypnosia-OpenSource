import { authRouter } from "./auth-router";
import { minecraftRouter } from "./minecraft-router";
import { adminRouter } from "./admin-router";
import { codeRouter } from "./code-router";
import { profileRouter, topsRouter, statsRouter } from "./profile-router";
import { storeRouter } from "./store-router";
import { accountRouter } from "./account-router";
import { transactionRouter } from "./transaction-router";
import { roadmapRouter } from "./roadmap-router";
import { ticketRouter } from "./ticket-router";
import { createRouter, publicQuery } from "./middleware";

export const appRouter = createRouter({
  ping: publicQuery.query(() => ({ ok: true, ts: Date.now() })),
  auth: authRouter,
  minecraft: minecraftRouter,
  admin: adminRouter,
  code: codeRouter,
  profile: profileRouter,
  tops: topsRouter,
  stats: statsRouter,
  store: storeRouter,
  account: accountRouter,
  transaction: transactionRouter,
  roadmap: roadmapRouter,
  ticket: ticketRouter,
});

export type AppRouter = typeof appRouter;
