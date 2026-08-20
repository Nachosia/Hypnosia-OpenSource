import { z } from "zod";
import { TRPCError } from "@trpc/server";
import { createRouter, authedQuery } from "./middleware";
import { getDb } from "./queries/connection";
import { redemptionCodes, users, transactions, modAccounts } from "@db/schema";
import { eq, and, sql } from "drizzle-orm";

export const codeRouter = createRouter({
  redeem: authedQuery
    .input(z.object({ code: z.string().min(6).max(12) }))
    .mutation(async ({ ctx, input }) => {
      const db = getDb();
      const discordId = String(ctx.user.unionId);

      // Require a linked account (via /hypnosia link + License Server)
      const [account] = await db
        .select()
        .from(modAccounts)
        .where(eq(modAccounts.discordId, discordId))
        .limit(1);
      if (!account) {
        throw new TRPCError({ code: "FORBIDDEN", message: "MINECRAFT_NOT_LINKED" });
      }

      const [record] = await db
        .select()
        .from(redemptionCodes)
        .where(eq(redemptionCodes.code, input.code.toUpperCase()))
        .limit(1);

      if (!record) {
        throw new TRPCError({ code: "NOT_FOUND", message: "CODE_NOT_FOUND" });
      }
      if (record.used === "true") {
        throw new TRPCError({ code: "BAD_REQUEST", message: "CODE_ALREADY_USED" });
      }

      // Atomic redemption: all writes in one transaction. The code is claimed
      // with a conditional UPDATE (WHERE used='false') so concurrent requests
      // for the same code cannot both succeed (prevents double-spend).
      const points = await db.transaction(async (tx) => {
        const [claimed] = await tx
          .update(redemptionCodes)
          .set({
            used: "true",
            usedBy: discordId,
            usedAt: new Date(),
          })
          .where(and(eq(redemptionCodes.id, record.id), eq(redemptionCodes.used, "false")));

        // If no row was affected, another request already claimed this code.
        if (claimed.affectedRows === 0) {
          throw new TRPCError({ code: "BAD_REQUEST", message: "CODE_ALREADY_USED" });
        }

        // Add points to user balance
        await tx
          .update(users)
          .set({ points: sql`${users.points} + ${record.points}` })
          .where(eq(users.unionId, discordId));

        // Create transaction record
        await tx.insert(transactions).values({
          userId: ctx.user.id,
          type: "deposit",
          amount: record.points,
          description: `Redeemed code: ${record.code}`,
          relatedId: record.id,
        });

        return record.points;
      });

      console.log("[ADMIN] Code redeemed:", { code: record.code, userId: ctx.user.id, userName: ctx.user.name, points });

      return {
        success: true,
        points,
        message: `Activated! You received ${points} points.`,
      };
    }),
});
