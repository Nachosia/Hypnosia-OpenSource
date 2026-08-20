import { z } from "zod";
import { createRouter, publicQuery, adminQuery } from "./middleware";
import { getDb } from "./queries/connection";
import { roadmapItems, roadmapVersions } from "@db/schema";
import { eq, desc, asc, sql, inArray } from "drizzle-orm";

export const roadmapRouter = createRouter({
  list: publicQuery.query(async () => {
    const db = getDb();
    const versions = await db
      .select()
      .from(roadmapVersions)
      .orderBy(asc(roadmapVersions.orderIndex), desc(roadmapVersions.createdAt));
    const items = await db
      .select()
      .from(roadmapItems)
      .orderBy(asc(roadmapItems.orderIndex));
    return { versions, items };
  }),

  create: adminQuery
    .input(
      z.object({
        title: z.string().min(1).max(255),
        description: z.string().optional(),
        version: z.string().min(1).max(32),
        status: z.enum(["planned", "in_progress", "completed", "cancelled"]).default("planned"),
      })
    )
    .mutation(async ({ input }) => {
      const db = getDb();
      // Ensure version exists
      const [existingVersion] = await db.select().from(roadmapVersions).where(eq(roadmapVersions.name, input.version)).limit(1);
      if (!existingVersion) {
        const [maxV] = await db.select({ max: sql<number>`COALESCE(MAX(${roadmapVersions.orderIndex}), -1)` }).from(roadmapVersions);
        await db.insert(roadmapVersions).values({ name: input.version, orderIndex: (maxV?.max ?? -1) + 1 });
      }
      const [maxOrder] = await db
        .select({ max: sql<number>`COALESCE(MAX(${roadmapItems.orderIndex}), -1)` })
        .from(roadmapItems)
        .where(eq(roadmapItems.version, input.version));
      const [item] = await db
        .insert(roadmapItems)
        .values({
          title: input.title,
          description: input.description || null,
          version: input.version,
          status: input.status,
          orderIndex: (maxOrder?.max ?? -1) + 1,
        })
        .$returningId();
      return { success: true, id: item.id };
    }),

  update: adminQuery
    .input(
      z.object({
        id: z.number().int().positive(),
        title: z.string().min(1).max(255).optional(),
        description: z.string().optional(),
        version: z.string().min(1).max(32).optional(),
        status: z.enum(["planned", "in_progress", "completed", "cancelled"]).optional(),
      })
    )
    .mutation(async ({ input }) => {
      const db = getDb();
      const { id, ...updates } = input;

      // Ensure new version exists if version is changing
      if (updates.version) {
        const [existingVersion] = await db.select().from(roadmapVersions).where(eq(roadmapVersions.name, updates.version)).limit(1);
        if (!existingVersion) {
          const [maxV] = await db.select({ max: sql<number>`COALESCE(MAX(${roadmapVersions.orderIndex}), -1)` }).from(roadmapVersions);
          await db.insert(roadmapVersions).values({ name: updates.version, orderIndex: (maxV?.max ?? -1) + 1 });
        }
      }

      const setData: any = { ...updates, updatedAt: new Date() };
      if (updates.status) {
        const [existing] = await db.select({ status: roadmapItems.status }).from(roadmapItems).where(eq(roadmapItems.id, id)).limit(1);
        if (existing && existing.status !== updates.status) {
          setData.statusChangedAt = new Date();
        }
      }

      await db.update(roadmapItems).set(setData).where(eq(roadmapItems.id, id));
      return { success: true };
    }),

  delete: adminQuery
    .input(z.object({ id: z.number().int().positive() }))
    .mutation(async ({ input }) => {
      const db = getDb();
      await db.delete(roadmapItems).where(eq(roadmapItems.id, input.id));
      return { success: true };
    }),

  reorder: adminQuery
    .input(
      z.object({
        items: z.array(
          z.object({
            id: z.number().int().positive(),
            orderIndex: z.number().int().min(0),
          })
        ),
      })
    )
    .mutation(async ({ input }) => {
      const db = getDb();
      for (const item of input.items) {
        await db.update(roadmapItems).set({ orderIndex: item.orderIndex }).where(eq(roadmapItems.id, item.id));
      }
      return { success: true };
    }),

  // Version management
  versionList: publicQuery.query(async () => {
    const db = getDb();
    return db.select().from(roadmapVersions).orderBy(asc(roadmapVersions.orderIndex));
  }),

  versionCreate: adminQuery
    .input(z.object({ name: z.string().min(1).max(32) }))
    .mutation(async ({ input }) => {
      const db = getDb();
      const [maxV] = await db.select({ max: sql<number>`COALESCE(MAX(${roadmapVersions.orderIndex}), -1)` }).from(roadmapVersions);
      await db.insert(roadmapVersions).values({ name: input.name, orderIndex: (maxV?.max ?? -1) + 1 });
      return { success: true };
    }),

  versionReorder: adminQuery
    .input(
      z.object({
        items: z.array(
          z.object({
            id: z.number().int().positive(),
            orderIndex: z.number().int().min(0),
          })
        ),
      })
    )
    .mutation(async ({ input }) => {
      const db = getDb();
      for (const item of input.items) {
        await db.update(roadmapVersions).set({ orderIndex: item.orderIndex }).where(eq(roadmapVersions.id, item.id));
      }
      return { success: true };
    }),

  versionDelete: adminQuery
    .input(z.object({ id: z.number().int().positive() }))
    .mutation(async ({ input }) => {
      const db = getDb();
      const [version] = await db.select().from(roadmapVersions).where(eq(roadmapVersions.id, input.id)).limit(1);
      if (version) {
        const itemsWithVersion = await db.select().from(roadmapItems).where(eq(roadmapItems.version, version.name)).limit(1);
        if (itemsWithVersion.length > 0) {
          throw new Error("Cannot delete version with items");
        }
        await db.delete(roadmapVersions).where(eq(roadmapVersions.id, input.id));
      }
      return { success: true };
    }),
});
