import {
  mysqlTable,
  mysqlEnum,
  serial,
  int,
  bigint,
  varchar,
  text,
  timestamp,
  date,
  uniqueIndex,
  json,
} from "drizzle-orm/mysql-core";

export const users = mysqlTable("users", {
  id: serial("id").primaryKey(),
  unionId: varchar("unionId", { length: 255 }).notNull().unique(),
  discordId: varchar("discord_id", { length: 32 }).unique(),
  name: varchar("name", { length: 255 }),
  email: varchar("email", { length: 320 }),
  avatar: text("avatar"),
  role: mysqlEnum("role", ["user", "vip", "qa", "sponsor", "sponsor_plus", "sponsor_plusplus", "developer", "admin", "owner"]).default("user").notNull(),
  points: int("points").default(0).notNull(),
  createdAt: timestamp("createdAt").defaultNow().notNull(),
  updatedAt: timestamp("updatedAt")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
  lastSignInAt: timestamp("lastSignInAt").defaultNow().notNull(),
});

export type User = typeof users.$inferSelect;
export type InsertUser = typeof users.$inferInsert;

// Minecraft account linking tables
export const minecraftLinks = mysqlTable("minecraft_links", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull().unique(),
  minecraftUuid: varchar("minecraft_uuid", { length: 36 }).notNull().unique(),
  minecraftUsername: varchar("minecraft_username", { length: 16 }).notNull(),
  linkedAt: timestamp("linked_at").defaultNow().notNull(),
  isActive: mysqlEnum("is_active", ["true", "false"]).default("true").notNull(),
});

export type MinecraftLink = typeof minecraftLinks.$inferSelect;
export type InsertMinecraftLink = typeof minecraftLinks.$inferInsert;

export const linkCodes = mysqlTable("link_codes", {
  id: serial("id").primaryKey(),
  code: varchar("code", { length: 8 }).notNull().unique(),
  discordId: varchar("discord_id", { length: 32 }).unique(),
  minecraftUuid: varchar("minecraft_uuid", { length: 36 }),
  minecraftUsername: varchar("minecraft_username", { length: 16 }),
  type: mysqlEnum("type", ["site", "minecraft"]).notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  expiresAt: timestamp("expires_at").notNull(),
  used: mysqlEnum("used", ["true", "false"]).default("false").notNull(),
});

export type LinkCode = typeof linkCodes.$inferSelect;
export type InsertLinkCode = typeof linkCodes.$inferInsert;

// ─── Player Profiles (public, searchable via Tops) ───
export const playerProfiles = mysqlTable("player_profiles", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull().unique(),
  minecraftUuid: varchar("minecraft_uuid", { length: 36 }).notNull().unique(),
  displayName: varchar("display_name", { length: 16 }).notNull(),
  role: mysqlEnum("role", ["user", "vip", "developer", "sponsor", "sponsor_plus", "sponsor_plusplus", "qa", "admin", "owner"]).default("user").notNull(),
  hoursPlayed: int("hours_played").default(0),
  mcJoined: varchar("mc_joined", { length: 10 }),
  siteJoined: timestamp("site_joined").defaultNow().notNull(),
  isOnline: mysqlEnum("is_online", ["true", "false"]).default("false").notNull(),
  // Visibility settings
  showHours: mysqlEnum("show_hours", ["true", "false"]).default("true").notNull(),
  showMcJoined: mysqlEnum("show_mc_joined", ["true", "false"]).default("true").notNull(),
  showOnline: mysqlEnum("show_online", ["true", "false"]).default("true").notNull(),
  showRank: mysqlEnum("show_rank", ["true", "false"]).default("true").notNull(),
  // Gradient settings (stored as JSON-like comma-separated colors)
  nickGradientFrom: varchar("nick_gradient_from", { length: 7 }).default("#80FF97"),
  nickGradientTo: varchar("nick_gradient_to", { length: 7 }).default("#6BB7FF"),
  roleGradientFrom: varchar("role_gradient_from", { length: 7 }).default("#6BB7FF"),
  roleGradientTo: varchar("role_gradient_to", { length: 7 }).default("#FFD700"),
  // Discord bot integration
  discordBotEnabled: mysqlEnum("discord_bot_enabled", ["true", "false"]).default("false").notNull(),
  discordServerId: varchar("discord_server_id", { length: 32 }),
  // Stats
  configsUploaded: int("configs_uploaded").default(0),
  lastSeen: timestamp("last_seen").defaultNow(),
  // Custom skin
  skinUrl: varchar("skin_url", { length: 512 }),
  skinModel: mysqlEnum("skin_model", ["classic", "slim"]).default("classic"),
  customRoleName: varchar("custom_role_name", { length: 32 }),
  nickGradientEditedAt: timestamp("nick_gradient_edited_at"),
  roleGradientEditedAt: timestamp("role_gradient_edited_at"),
});

export type PlayerProfile = typeof playerProfiles.$inferSelect;
export type InsertPlayerProfile = typeof playerProfiles.$inferInsert;

// ─── Role Change Logs (managed by Discord bot) ───
export const roleLogs = mysqlTable("role_logs", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  action: mysqlEnum("action", ["assign", "remove", "upgrade"]).notNull(),
  oldRole: mysqlEnum("old_role", ["user", "vip", "developer", "sponsor", "sponsor_plus", "sponsor_plusplus", "qa", "admin", "owner"]),
  newRole: mysqlEnum("new_role", ["user", "vip", "developer", "sponsor", "sponsor_plus", "sponsor_plusplus", "qa", "admin", "owner"]).notNull(),
  performedBy: varchar("performed_by", { length: 32 }).notNull(), // Discord ID of bot/admin
  reason: varchar("reason", { length: 255 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type RoleLog = typeof roleLogs.$inferSelect;

// ─── HWID Reset Logs ───
export const hwidLogs = mysqlTable("hwid_logs", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

// ─── Player Configs (metadata only — files on Server 1) ───
export const playerConfigs = mysqlTable("player_configs", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  configKey: varchar("config_key", { length: 64 }).notNull(), // unique key from Server 1
  name: varchar("name", { length: 32 }).notNull(),
  sizeKb: int("size_kb").default(0),
  hasGif: mysqlEnum("has_gif", ["true", "false"]).default("false").notNull(),
  gifSizeKb: int("gif_size_kb").default(0),
  uploadedAt: timestamp("uploaded_at").defaultNow().notNull(),
});

export type PlayerConfig = typeof playerConfigs.$inferSelect;

// ─── Site Stats (global counters synced from external servers) ───
export const siteStats = mysqlTable("site_stats", {
  id: serial("id").primaryKey(),
  statKey: varchar("stat_key", { length: 64 }).notNull().unique(),
  statValue: int("stat_value").default(0).notNull(),
  updatedAt: timestamp("updated_at").defaultNow().notNull(),
});

export type SiteStat = typeof siteStats.$inferSelect;

// ─── Redemption Codes (created in admin panel) ───
export const redemptionCodes = mysqlTable("redemption_codes", {
  id: serial("id").primaryKey(),
  code: varchar("code", { length: 12 }).notNull().unique(),
  points: int("points").notNull(), // 1000, 1500, 2000, etc.
  used: mysqlEnum("used", ["true", "false"]).default("false").notNull(),
  forSale: mysqlEnum("for_sale", ["true", "false"]).default("false").notNull(),
  usedBy: varchar("used_by", { length: 32 }), // discord_id
  createdBy: varchar("created_by", { length: 32 }).notNull(), // admin discord_id
  createdAt: timestamp("created_at").defaultNow().notNull(),
  usedAt: timestamp("used_at"),
});

export type RedemptionCode = typeof redemptionCodes.$inferSelect;

// ─── Payment Sessions (FunPay, etc.) ───
export const paymentSessions = mysqlTable("payment_sessions", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  amount: int("amount").notNull(), // rubles
  pointsGiven: int("points_given").notNull(),
  provider: mysqlEnum("provider", ["funpay", "cryptomus", "freekassa"]).default("funpay").notNull(),
  status: mysqlEnum("status", ["pending", "paid", "cancelled", "failed"]).default("pending").notNull(),
  externalId: varchar("external_id", { length: 128 }), // payment provider transaction ID
  createdAt: timestamp("created_at").defaultNow().notNull(),
  paidAt: timestamp("paid_at"),
});

// ─── Subscription Purchases (auto-role on Server 1) ───
export const subscriptionPurchases = mysqlTable("subscription_purchases", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  tier: mysqlEnum("tier", ["sponsor", "sponsor_plus", "sponsor_plusplus"]).notNull(),
  days: int("days").notNull(), // 30, 60, 90
  pricePoints: int("price_points").notNull(),
  // Role assignment on Server 1
  roleAssigned: mysqlEnum("role_assigned", ["true", "false"]).default("false").notNull(),
  roleAssignedAt: timestamp("role_assigned_at"),
  // Expiry
  expiresAt: timestamp("expires_at").notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

// ─── Admin 2FA ───
export const admin2FA = mysqlTable("admin_2fa", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull().unique(),
  secret: varchar("secret", { length: 256 }).notNull(), // TOTP secret
  enabled: mysqlEnum("enabled", ["true", "false"]).default("false").notNull(),
  verifiedAt: timestamp("verified_at"),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

// ─── Admin audit logs ───
export const adminAuditLogs = mysqlTable("admin_audit_logs", {
  id: serial("id").primaryKey(),
  action: varchar("action", { length: 64 }).notNull(),
  performedBy: varchar("performed_by", { length: 32 }).notNull(),
  targetAccountKey: varchar("target_account_key", { length: 128 }),
  targetRole: varchar("target_role", { length: 32 }),
  metadata: json("metadata").$type<Record<string, unknown>>(),
  ipAddress: varchar("ip_address", { length: 64 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type AdminAuditLog = typeof adminAuditLogs.$inferSelect;
export type InsertAdminAuditLog = typeof adminAuditLogs.$inferInsert;

// ─── Weekly Stats (activity tracking) ───
export const weeklyStats = mysqlTable("weekly_stats", {
  id: serial("id").primaryKey(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  date: varchar("date", { length: 10 }).notNull(), // YYYY-MM-DD
  hoursPlayed: int("hours_played").notNull(),
  configsUploaded: int("configs_uploaded").notNull(),
  uniqueKey: varchar("unique_key", { length: 64 }).notNull().unique(), // discordId_date for upsert
});

// ─── Server 1 Sync Queue (for cross-server communication) ───
export const syncQueue = mysqlTable("sync_queue", {
  id: serial("id").primaryKey(),
  action: mysqlEnum("action", ["role_assign", "role_remove", "hwid_reset", "config_update"]).notNull(),
  discordId: varchar("discord_id", { length: 32 }).notNull(),
  payload: text("payload"), // JSON payload for Server 1
  status: mysqlEnum("status", ["pending", "sent", "acknowledged", "failed"]).default("pending").notNull(),
  retryCount: int("retry_count").notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  processedAt: timestamp("processed_at"),
});

// ═══════════════════════════════════════════════════════════════════
// ═══ NEW TABLES: Mod Accounts, Sessions, Store, Inventory ═════════
// ═══════════════════════════════════════════════════════════════════

// ─── Mod Accounts (cache from License Server + site extras) ───
export const modAccounts = mysqlTable("mod_accounts", {
  id: serial("id").primaryKey(),
  accountId: int("account_id").notNull().unique(), // License Server account ID
  accountKey: varchar("account_key", { length: 64 }),
  accountKeyHash: varchar("account_key_hash", { length: 128 }).notNull().unique(),
  accountKeyEnc: text("account_key_enc"), // encrypted if refresh needed
  hwidHash: varchar("hwid_hash", { length: 128 }),
  displayName: varchar("display_name", { length: 32 }),
  contact: varchar("contact", { length: 96 }),
  licenseRoles: json("license_roles").$type<string[]>(),
  discordId: varchar("discord_id", { length: 32 }).unique(),
  isOnline: mysqlEnum("is_online", ["true", "false"]).default("false").notNull(),
  lastSeenAt: timestamp("last_seen_at"),
  lastSyncedAt: timestamp("last_synced_at"),
  lastPlaytimeCountedAt: timestamp("last_playtime_counted_at"),
  playtimeBanned: mysqlEnum("playtime_banned", ["true", "false"]).default("false").notNull(),
  playtimeBanReason: varchar("playtime_ban_reason", { length: 255 }),
  playtimeFrozen: mysqlEnum("playtime_frozen", ["true", "false"]).default("false").notNull(),
  playtimeFreezeReason: varchar("playtime_freeze_reason", { length: 255 }),
  playtimeFrozenAt: timestamp("playtime_frozen_at"),
  playtimeFrozenSessionId: bigint("playtime_frozen_session_id", { mode: "number", unsigned: true }),
  currentServerIp: varchar("current_server_ip", { length: 64 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
});

export type ModAccount = typeof modAccounts.$inferSelect;
export type InsertModAccount = typeof modAccounts.$inferInsert;

// ─── Mod Sessions (game sessions from mod client) ───
export const modSessions = mysqlTable("mod_sessions", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  accountKey: varchar("account_key", { length: 64 }),
  sessionToken: varchar("session_token", { length: 64 }).notNull().unique(),
  startedAt: timestamp("started_at").notNull(),
  endedAt: timestamp("ended_at"),
  lastHeartbeatAt: timestamp("last_heartbeat_at").notNull(),
  lastCountedAt: timestamp("last_counted_at").notNull(),
  durationSeconds: int("duration_seconds").default(0),
  closeReason: mysqlEnum("close_reason", ["normal", "timeout", "replaced", "admin"]),
  graceEndAt: timestamp("grace_end_at"),
  clientIp: varchar("client_ip", { length: 64 }),
  minecraftVersion: varchar("minecraft_version", { length: 32 }),
  modVersion: varchar("mod_version", { length: 32 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type ModSession = typeof modSessions.$inferSelect;
export type InsertModSession = typeof modSessions.$inferInsert;

// ─── Mod Playtime Daily (aggregated per Moscow date) ───
export const modPlaytimeDaily = mysqlTable("mod_playtime_daily", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  dateMsk: date("date_msk").notNull(),
  seconds: int("seconds").default(0).notNull(),
  minutes: int("minutes").default(0).notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
}, (t) => [
  uniqueIndex("mod_playtime_daily_account_date").on(t.accountId, t.dateMsk),
]);

export type ModPlaytimeDaily = typeof modPlaytimeDaily.$inferSelect;
export type InsertModPlaytimeDaily = typeof modPlaytimeDaily.$inferInsert;

// ─── Mod Playtime Monthly (rollup) ───
export const modPlaytimeMonthly = mysqlTable("mod_playtime_monthly", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  yearMonth: varchar("year_month", { length: 7 }).notNull(), // YYYY-MM
  minutes: int("minutes").default(0).notNull(),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
}, (t) => [
  uniqueIndex("mod_playtime_monthly_account_ym").on(t.accountId, t.yearMonth),
]);

export type ModPlaytimeMonthly = typeof modPlaytimeMonthly.$inferSelect;
export type InsertModPlaytimeMonthly = typeof modPlaytimeMonthly.$inferInsert;

// ─── Mod Playtime Alltime (rollup) ───
export const modPlaytimeAlltime = mysqlTable("mod_playtime_alltime", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull().unique(),
  minutes: int("minutes").default(0).notNull(),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
});

// ─── Mod Link Codes (for Discord binding, stores hash only) ───
export const modLinkCodes = mysqlTable("mod_link_codes", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  accountKey: varchar("account_key", { length: 64 }),
  codeHash: varchar("code_hash", { length: 128 }).notNull(),
  expiresAt: timestamp("expires_at").notNull(),
  usedAt: timestamp("used_at"),
  createdIp: varchar("created_ip", { length: 64 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type ModLinkCode = typeof modLinkCodes.$inferSelect;
export type InsertModLinkCode = typeof modLinkCodes.$inferInsert;

// ─── Mod Notifications (in-game messages from admin) ───
export const modNotifications = mysqlTable("mod_notifications", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }), // null = broadcast to all
  message: text("message").notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  expiresAt: timestamp("expires_at"),
  readAt: timestamp("read_at"),
  createdBy: varchar("created_by", { length: 32 }), // discordId of admin
});

export type ModNotification = typeof modNotifications.$inferSelect;
export type InsertModNotification = typeof modNotifications.$inferInsert;

// ─── Mod Freeze Logs (audit trail for freeze/unfreeze) ───
export const modFreezeLogs = mysqlTable("mod_freeze_logs", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  action: mysqlEnum("action", ["freeze", "unfreeze", "ban", "unban"]).notNull(),
  reason: varchar("reason", { length: 255 }),
  performedBy: mysqlEnum("performed_by", ["auto", "admin"]).default("auto").notNull(),
  adminDiscordId: varchar("admin_discord_id", { length: 32 }),
  sessionId: bigint("session_id", { mode: "number", unsigned: true }),
  playtimeMinutesLost: int("playtime_minutes_lost").default(0),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type ModFreezeLog = typeof modFreezeLogs.$inferSelect;
export type InsertModFreezeLog = typeof modFreezeLogs.$inferInsert;

// ─── Store Items (products in the shop) ───
export const storeItems = mysqlTable("store_items", {
  id: serial("id").primaryKey(),
  sku: varchar("sku", { length: 64 }).notNull().unique(),
  type: mysqlEnum("type", [
    "subscription_key",
    "nickname_gradient",
    "role_gradient",
    "profile_style",
    "profile_badge",
    "profile_background",
    "cosmetic",
    "hwid_reset",
    "custom_role",
  ]).notNull(),
  name: varchar("name", { length: 96 }).notNull(),
  description: text("description"),
  priceCents: int("price_cents").notNull(),
  currency: varchar("currency", { length: 8 }).default("USD").notNull(),
  durationDays: int("duration_days"),
  metadata: json("metadata").$type<Record<string, unknown>>(),
  isActive: mysqlEnum("is_active", ["true", "false"]).default("true").notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type StoreItem = typeof storeItems.$inferSelect;
export type InsertStoreItem = typeof storeItems.$inferInsert;

// ─── Orders (purchases) ───
export const orders = mysqlTable("orders", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }),
  discordId: varchar("discord_id", { length: 32 }).unique(),
  status: mysqlEnum("status", ["pending", "paid", "cancelled", "failed", "refunded"]).default("pending").notNull(),
  totalCents: int("total_cents").notNull(),
  currency: varchar("currency", { length: 8 }).default("USD").notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  paidAt: timestamp("paid_at"),
});

export type Order = typeof orders.$inferSelect;
export type InsertOrder = typeof orders.$inferInsert;

// ─── Order Items (line items) ───
export const orderItems = mysqlTable("order_items", {
  id: serial("id").primaryKey(),
  orderId: bigint("order_id", { mode: "number", unsigned: true }).notNull(),
  storeItemId: bigint("store_item_id", { mode: "number", unsigned: true }).notNull(),
  quantity: int("quantity").default(1).notNull(),
  priceCents: int("price_cents").notNull(),
  metadata: json("metadata").$type<Record<string, unknown>>(),
});

export type OrderItem = typeof orderItems.$inferSelect;
export type InsertOrderItem = typeof orderItems.$inferInsert;

// ─── Subscription Keys (redeemable keys, hash only) ───
export const subscriptionKeys = mysqlTable("subscription_keys", {
  id: serial("id").primaryKey(),
  keyHash: varchar("key_hash", { length: 128 }).notNull().unique(),
  keyPrefix: varchar("key_prefix", { length: 12 }).notNull(),
  keyValue: varchar("key_value", { length: 256 }),
  storeItemId: bigint("store_item_id", { mode: "number", unsigned: true }).notNull(),
  orderId: bigint("order_id", { mode: "number", unsigned: true }),
  durationDays: int("duration_days").notNull(),
  status: mysqlEnum("status", ["created", "sold", "redeemed", "revoked"]).default("created").notNull(),
  buyerAccountId: bigint("buyer_account_id", { mode: "number", unsigned: true }),
  redeemedByAccountId: bigint("redeemed_by_account_id", { mode: "number", unsigned: true }),
  soldAt: timestamp("sold_at"),
  redeemedAt: timestamp("redeemed_at"),
  expiresAfterRedeemAt: timestamp("expires_after_redeem_at"),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type SubscriptionKey = typeof subscriptionKeys.$inferSelect;
export type InsertSubscriptionKey = typeof subscriptionKeys.$inferInsert;

// ─── User Entitlements (active rights/subscriptions) ───
export const userEntitlements = mysqlTable("user_entitlements", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  type: mysqlEnum("type", [
    "subscription",
    "nickname_gradient",
    "role_gradient",
    "profile_style",
    "profile_badge",
    "profile_background",
    "cosmetic",
    "hwid_reset",
    "custom_role",
  ]).notNull(),
  storeItemId: bigint("store_item_id", { mode: "number", unsigned: true }),
  source: mysqlEnum("source", ["purchase", "key", "admin", "gift"]).notNull(),
  startsAt: timestamp("starts_at").notNull(),
  endsAt: timestamp("ends_at"),
  isActive: mysqlEnum("is_active", ["true", "false"]).default("true").notNull(),
  metadata: json("metadata").$type<Record<string, unknown>>(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type UserEntitlement = typeof userEntitlements.$inferSelect;
export type InsertUserEntitlement = typeof userEntitlements.$inferInsert;

// ─── User Inventory (owned items) ───
export const userInventory = mysqlTable("user_inventory", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  storeItemId: bigint("store_item_id", { mode: "number", unsigned: true }).notNull(),
  acquiredFrom: mysqlEnum("acquired_from", ["purchase", "key", "admin", "gift"]).notNull(),
  acquiredAt: timestamp("acquired_at").defaultNow().notNull(),
  expiresAt: timestamp("expires_at"),
  isActive: mysqlEnum("is_active", ["true", "false"]).default("true").notNull(),
}, (t) => [
  uniqueIndex("user_inventory_account_item").on(t.accountId, t.storeItemId),
]);

export type UserInventory = typeof userInventory.$inferSelect;
export type InsertUserInventory = typeof userInventory.$inferInsert;

// ─── User Profile Settings (cosmetic selections + visibility) ───
export const userProfileSettings = mysqlTable("user_profile_settings", {
  accountId: bigint("account_id", { mode: "number", unsigned: true }).primaryKey(),
  selectedNicknameGradientId: bigint("selected_nickname_gradient_id", { mode: "number", unsigned: true }),
  selectedRoleGradientId: bigint("selected_role_gradient_id", { mode: "number", unsigned: true }),
  selectedProfileStyleId: bigint("selected_profile_style_id", { mode: "number", unsigned: true }),
  selectedProfileBackgroundId: bigint("selected_profile_background_id", { mode: "number", unsigned: true }),
  selectedBadgeId: bigint("selected_badge_id", { mode: "number", unsigned: true }),
  showOnline: mysqlEnum("show_online", ["true", "false"]).default("true").notNull(),
  showPlaytime: mysqlEnum("show_playtime", ["true", "false"]).default("true").notNull(),
  showDiscord: mysqlEnum("show_discord", ["true", "false"]).default("false").notNull(),
  customStatus: varchar("custom_status", { length: 128 }),
  profileBio: text("profile_bio"),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
});

export type UserProfileSettings = typeof userProfileSettings.$inferSelect;
export type InsertUserProfileSettings = typeof userProfileSettings.$inferInsert;

// ─── Site Role Settings (overrides License Server gradients for standard roles) ───
export const siteRoleSettings = mysqlTable("site_role_settings", {
  id: serial("id").primaryKey(),
  roleName: varchar("role_name", { length: 32 }).notNull().unique(), // e.g. "OWNER", "ADMIN", "SPONSOR"
  nickGradientFrom: varchar("nick_gradient_from", { length: 7 }),
  nickGradientTo: varchar("nick_gradient_to", { length: 7 }),
  roleGradientFrom: varchar("role_gradient_from", { length: 7 }),
  roleGradientTo: varchar("role_gradient_to", { length: 7 }),
  iconUrl: varchar("icon_url", { length: 512 }),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
});

export type SiteRoleSetting = typeof siteRoleSettings.$inferSelect;
export type InsertSiteRoleSetting = typeof siteRoleSettings.$inferInsert;

// ─── Transactions (balance history) ───
export const transactions = mysqlTable("transactions", {
  id: serial("id").primaryKey(),
  userId: int("user_id").notNull(),
  type: mysqlEnum("type", ["deposit", "withdraw", "purchase", "refund"]).notNull(),
  amount: int("amount").notNull(),
  description: varchar("description", { length: 255 }),
  relatedId: bigint("related_id", { mode: "number", unsigned: true }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type Transaction = typeof transactions.$inferSelect;
export type InsertTransaction = typeof transactions.$inferInsert;

// ─── Server Names (canonical server reference) ───
export const serverNames = mysqlTable("server_names", {
  serverIp: varchar("server_ip", { length: 64 }).primaryKey(),
  displayName: varchar("display_name", { length: 64 }).notNull(),
  category: varchar("category", { length: 32 }),
  isOfficial: mysqlEnum("is_official", ["true", "false"]).default("false").notNull(),
  iconUrl: varchar("icon_url", { length: 255 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type ServerName = typeof serverNames.$inferSelect;
export type InsertServerName = typeof serverNames.$inferInsert;

// ─── Mod Playtime Servers Daily (per-server daily stats) ───
export const modPlaytimeServersDaily = mysqlTable("mod_playtime_servers_daily", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  serverIp: varchar("server_ip", { length: 64 }).notNull(),
  dateMsk: date("date_msk").notNull(),
  minutes: int("minutes").default(0).notNull(),
}, (t) => [
  uniqueIndex("mod_pt_servers_daily_account_server_date").on(t.accountId, t.serverIp, t.dateMsk),
]);

export type ModPlaytimeServersDaily = typeof modPlaytimeServersDaily.$inferSelect;
export type InsertModPlaytimeServersDaily = typeof modPlaytimeServersDaily.$inferInsert;

// ─── Mod Playtime Servers Alltime (per-server rollup) ───
export const modPlaytimeServersAlltime = mysqlTable("mod_playtime_servers_alltime", {
  id: serial("id").primaryKey(),
  accountId: bigint("account_id", { mode: "number", unsigned: true }).notNull(),
  serverIp: varchar("server_ip", { length: 64 }).notNull(),
  totalMinutes: int("total_minutes").default(0).notNull(),
  lastUpdated: timestamp("last_updated")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
}, (t) => [
  uniqueIndex("mod_pt_servers_alltime_account_server").on(t.accountId, t.serverIp),
]);

export type ModPlaytimeServersAlltime = typeof modPlaytimeServersAlltime.$inferSelect;
export type InsertModPlaytimeServersAlltime = typeof modPlaytimeServersAlltime.$inferInsert;

// ─── Roadmap Items ───
export const roadmapItems = mysqlTable("roadmap_items", {
  id: serial("id").primaryKey(),
  title: varchar("title", { length: 255 }).notNull(),
  description: text("description"),
  version: varchar("version", { length: 32 }).notNull(),
  status: mysqlEnum("status", ["planned", "in_progress", "completed", "cancelled"]).default("planned").notNull(),
  orderIndex: int("order_index").default(0).notNull(),
  statusChangedAt: timestamp("status_changed_at").defaultNow(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  updatedAt: timestamp("updated_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
});

export type RoadmapItem = typeof roadmapItems.$inferSelect;
export type InsertRoadmapItem = typeof roadmapItems.$inferInsert;

export const roadmapVersions = mysqlTable("roadmap_versions", {
  id: serial("id").primaryKey(),
  name: varchar("name", { length: 32 }).notNull().unique(),
  orderIndex: int("order_index").default(0).notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type RoadmapVersion = typeof roadmapVersions.$inferSelect;
export type InsertRoadmapVersion = typeof roadmapVersions.$inferInsert;

// ─── Support Tickets ───
export const tickets = mysqlTable("tickets", {
  id: serial("id").primaryKey(),
  userId: int("user_id").notNull(), // site user id
  discordUserId: varchar("discord_user_id", { length: 32 }),
  title: varchar("title", { length: 255 }).notNull(),
  description: text("description").notNull(),
  category: varchar("category", { length: 32 }).notNull().default("other"),
  status: mysqlEnum("status", ["open", "closed"]).default("open").notNull(),
  assignedAdminId: int("assigned_admin_id"),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  closedAt: timestamp("closed_at"),
  closedBy: int("closed_by"),
  closeReason: varchar("close_reason", { length: 500 }),
  discordChannelId: varchar("discord_channel_id", { length: 64 }),
});

export type Ticket = typeof tickets.$inferSelect;
export type InsertTicket = typeof tickets.$inferInsert;

export const ticketMessages = mysqlTable("ticket_messages", {
  id: serial("id").primaryKey(),
  ticketId: int("ticket_id").notNull(),
  senderType: mysqlEnum("sender_type", ["user", "admin", "system"]).notNull(),
  senderId: int("sender_id"), // site user id
  senderDiscordId: varchar("sender_discord_id", { length: 32 }),
  senderName: varchar("sender_name", { length: 255 }),
  content: text("content").notNull(),
  hasAttachment: mysqlEnum("has_attachment", ["true", "false"]).default("false").notNull(),
  attachmentUrl: varchar("attachment_url", { length: 500 }),
  attachmentName: varchar("attachment_name", { length: 255 }),
  attachments: json("attachments").$type<{url: string, name: string, size: number}[]>(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  discordMessageId: varchar("discord_message_id", { length: 64 }),
});

export type TicketMessage = typeof ticketMessages.$inferSelect;
export type InsertTicketMessage = typeof ticketMessages.$inferInsert;

export const ticketAttachments = mysqlTable("ticket_attachments", {
  id: serial("id").primaryKey(),
  messageId: int("message_id").notNull(),
  fileUrl: varchar("file_url", { length: 500 }).notNull(),
  fileName: varchar("file_name", { length: 255 }).notNull(),
  fileSize: int("file_size").notNull(),
  createdAt: timestamp("created_at").defaultNow().notNull(),
});

export type TicketAttachment = typeof ticketAttachments.$inferSelect;
export type InsertTicketAttachment = typeof ticketAttachments.$inferInsert;


// ─── Launcher Devices (desktop launcher HWID binding) ───
export const launcherDevices = mysqlTable("launcher_devices", {
  id: serial("id").primaryKey(),
  hwidHash: varchar("hwid_hash", { length: 128 }).notNull().unique(),
  discordId: varchar("discord_id", { length: 32 }).unique(),
  firstIp: varchar("first_ip", { length: 64 }),
  lastIp: varchar("last_ip", { length: 64 }),
  createdAt: timestamp("created_at").defaultNow().notNull(),
  lastUsedAt: timestamp("last_used_at")
    .defaultNow()
    .notNull()
    .$onUpdate(() => new Date()),
  // Set by POST /api/launcher/logout. While non-null, /me treats the device
  // as not authenticated until an explicit login clears it.
  loggedOutAt: timestamp("logged_out_at"),
});

export type LauncherDevice = typeof launcherDevices.$inferSelect;
export type InsertLauncherDevice = typeof launcherDevices.$inferInsert;
