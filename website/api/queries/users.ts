import { eq } from "drizzle-orm";
import * as schema from "@db/schema";
import type { InsertUser } from "@db/schema";
import { getDb } from "./connection";
import { env } from "../lib/env";

export async function findUserByUnionId(unionId: string) {
  const rows = await getDb()
    .select()
    .from(schema.users)
    .where(eq(schema.users.unionId, unionId))
    .limit(1);
  return rows.at(0);
}

export async function upsertUser(data: InsertUser) {
  const db = getDb();
  const values = { ...data };
  const updateSet: Partial<InsertUser> = {
    lastSignInAt: new Date(),
    ...data,
  };

  if (
    values.role === undefined &&
    values.unionId &&
    values.unionId === env.ownerUnionId
  ) {
    values.role = "admin";
    updateSet.role = "admin";
  }

  // Check if user already exists
  const existing = await findUserByUnionId(data.unionId);

  await db
    .insert(schema.users)
    .values(values)
    .onDuplicateKeyUpdate({ set: updateSet });

  // Create player profile on first registration
  if (!existing && data.discordId) {
    try {
      await db.insert(schema.playerProfiles).values({
        discordId: data.discordId,
        displayName: data.name ?? "User",
        role: "user",
      });
    } catch {
      // Profile may already exist — ignore
    }
  }
}
