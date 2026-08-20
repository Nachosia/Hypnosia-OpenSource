import { HttpClient } from "./http";
import { env } from "./env";
import { z } from "zod";
import { createHmac } from "crypto";

const LicenseAccountInfoSchema = z.object({
  id: z.number().optional(),
  accountId: z.number().optional(),
  accountKey: z.string().optional(),
  name: z.string().optional(),
  contact: z.string().nullable().optional(),
  roles: z.array(z.string()).optional(),
  hwidHash: z.string().optional(),
  createdAt: z.string().optional(),
  roleGradients: z.record(z.string(), z.string()).optional(),
  nickGradients: z.record(z.string(), z.string()).optional(),
  roleIcons: z.record(z.string(), z.string()).optional(),
  roleDisplayNames: z.record(z.string(), z.string()).optional(),
});

const CreateOrUpdateLicenseResultSchema = z.object({
  success: z.boolean(),
  keys: z.array(z.object({
    role: z.string(),
    key: z.string(),
    expiresAt: z.string(),
    isNewKey: z.boolean(),
  })).optional(),
  expiresAt: z.string().optional(),
  message: z.string().optional(),
});

function safeParse<T>(schema: z.ZodType<T>, data: unknown): T | null {
  const result = schema.safeParse(data);
  if (!result.success) {
    console.error("[LicenseClient] Schema validation failed:", result.error.format());
    return null;
  }
  return result.data;
}

// Use direct IP to license server with SSL verification disabled
// Nginx on license server routes by Host header
const client = new HttpClient(env.licenseServerUrl || "http://127.0.0.1:8080", {
  headers: {
    "Host": "127.0.0.1:3000",
  },
});

const adminClient = new HttpClient(env.licenseServerUrl || "http://127.0.0.1:8080", {
  headers: {
    "Host": "127.0.0.1:3000",
    "X-Admin-Key": env.licenseServerApiKey || "",
  },
});

export interface LicenseAccountInfo {
  id?: number;
  accountId?: number;
  accountKey?: string;
  name?: string;
  contact?: string | null;
  roles?: string[];
  hwidHash?: string;
  createdAt?: string;
  roleGradients?: Record<string, string>;
  nickGradients?: Record<string, string>;
  roleIcons?: Record<string, string>;
  roleDisplayNames?: Record<string, string>;
}

export interface LicenseAccountCreateResult {
  id: number;
  accountKey: string;
}

/**
 * Parse CSS linear-gradient string into {from, to} hex colors.
 * Supports: linear-gradient(90deg, #888888, #BBBBBB)
 * Returns null if parsing fails or string is empty.
 */
export function parseGradientColors(cssGradient?: string): { from: string; to: string } | null {
  if (!cssGradient || cssGradient.trim() === "") return null;
  // Match hex colors in the gradient string
  const matches = cssGradient.match(/#[0-9A-Fa-f]{3,8}/g);
  if (!matches || matches.length < 2) return null;
  return { from: matches[0], to: matches[1] };
}

/**
 * Get gradient colors for a specific role from License Server response.
 */
export function getRoleGradient(
  licenseInfo: LicenseAccountInfo,
  type: "role" | "nick"
): { from: string; to: string } | null {
  const roles = licenseInfo.roles ?? [];
  if (roles.length === 0) return null;
  const primaryRole = roles[0];
  const gradients = type === "role" ? licenseInfo.roleGradients : licenseInfo.nickGradients;
  const css = gradients?.[primaryRole];
  return parseGradientColors(css);
}

/**
 * Build a normalized map of role -> icon URL from License Server response.
 * Keys are normalized to site role names (e.g. "sponsor_plus").
 */
export function getRoleIconsMap(licenseInfo: LicenseAccountInfo): Record<string, string> {
  const map: Record<string, string> = {};
  const icons = licenseInfo.roleIcons ?? {};
  for (const [rawRole, url] of Object.entries(icons)) {
    const norm = rawRole.toLowerCase().replace(/\+\+/g, "_plusplus").replace(/\+/g, "_plus");
    map[norm] = url;
  }
  return map;
}

/**
 * Build a normalized map of role -> display name from License Server response.
 * Keys are normalized to site role names (e.g. "sponsor_plus").
 */
export function getRoleDisplayNamesMap(licenseInfo: LicenseAccountInfo): Record<string, string> {
  const map: Record<string, string> = {};
  const names = licenseInfo.roleDisplayNames ?? {};
  for (const [rawRole, name] of Object.entries(names)) {
    const norm = rawRole.toLowerCase().replace(/\+\+/g, "_plusplus").replace(/\+/g, "_plus");
    map[norm] = name;
  }
  return map;
}

export async function getLicenseAccountInfo(
  accountKey: string,
  hwidHash?: string
): Promise<LicenseAccountInfo | null> {
  try {
    const response = await client.postRaw("/api/account/info", {
      accountKey,
      hwidHash,
    });
    const body = typeof response === "string" ? response : JSON.stringify(response);
    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch (parseErr) {
      // License Server returned non-JSON / malformed JSON. Degrade gracefully
      // instead of throwing, so the caller falls back to local data.
      console.error("[LicenseClient] getLicenseAccountInfo: invalid JSON from License Server");
      return null;
    }
    const ok = typeof parsed === "object" && parsed !== null && (parsed as any).ok === true;
    if (!ok) return null;
    return safeParse(LicenseAccountInfoSchema, parsed);
  } catch (e: any) {
    if (e.message?.includes("404") || e.message?.includes("not found") || e.message?.includes("ACCOUNT_NOT_FOUND")) {
      return null;
    }
    throw e;
  }
}

export async function recoverLicenseAccountByKey(
  accountKey: string,
  hwidHash: string
): Promise<LicenseAccountInfo> {
  const response = await client.postRaw("/api/account/create", { accountKey, hwidHash });
  const body = typeof response === "string" ? response : JSON.stringify(response);
  const parsed: Record<string, unknown> = JSON.parse(body);
  if (parsed.ok !== true) {
    throw new Error(typeof parsed.status === "string" ? parsed.status : "RECOVERY_FAILED");
  }
  const result: LicenseAccountInfo = {
    id: typeof parsed.id === "number" ? parsed.id : undefined,
    accountId: typeof parsed.accountId === "number" ? parsed.accountId : undefined,
    accountKey: typeof parsed.accountKey === "string" ? parsed.accountKey : undefined,
    name: typeof parsed.displayName === "string" ? parsed.displayName : undefined,
    contact: typeof parsed.contact === "string" ? parsed.contact : undefined,
    roles: Array.isArray(parsed.roles) ? parsed.roles.filter((r): r is string => typeof r === "string") : undefined,
    hwidHash: typeof parsed.hwidHash === "string" ? parsed.hwidHash : undefined,
    createdAt: typeof parsed.createdAt === "string" ? parsed.createdAt : undefined,
  };
  return result;
}

export async function createLicenseAccount(
  hwidHash: string
): Promise<LicenseAccountCreateResult | null> {
  try {
    const result = await client.post<LicenseAccountCreateResult>("/api/account/create", {
      hwidHash,
    });
    return result;
  } catch (e: any) {
    console.error("Failed to create license account:", e.message);
    return null;
  }
}

/**
 * Find or create license account by HWID. Returns full account info including roles/gradients.
 * License Server returns existing account if HWID already registered, or creates new one.
 */
export async function findOrCreateLicenseAccountByHwid(
  hwidHash: string
): Promise<LicenseAccountInfo | null> {
  try {
    const response = await client.postRaw("/api/account/create", { hwidHash });
    const body = typeof response === "string" ? response : JSON.stringify(response);
    const result: LicenseAccountInfo = JSON.parse(body);
    return result;
  } catch (e: any) {
    console.error("[LicenseClient] findOrCreateLicenseAccountByHwid failed:", e.message);
    return null;
  }
}

/**
 * Recover/bind an existing license account to a new HWID using the account key.
 * License Server allows this only when the previous HWID has been reset.
 * Throws on License Server rejection so the caller can decide the error code.
 */
export async function reportOnlineToLicenseServer(
  accountKey: string,
  hwidHash: string
): Promise<boolean> {
  try {
    await client.post("/api/session/online", { accountKey, hwidHash });
    return true;
  } catch (e: any) {
    console.error("Failed to report online:", e.message);
    return false;
  }
}

export async function reportOfflineToLicenseServer(
  accountKey: string,
  hwidHash: string
): Promise<boolean> {
  try {
    await client.post("/api/session/offline", { accountKey, hwidHash });
    return true;
  } catch (e: any) {
    console.error("Failed to report offline:", e.message);
    return false;
  }
}

export interface LicenseAdminAccount {
  id: number;
  accountKey: string;
  hwidHash: string;
  displayName?: string;
  contact?: string;
  createdAt: string;
  disabled: boolean;
  isOnline: boolean;
  lastSeenAt: string;
  roles: string[];
  roleGradients: Record<string, string>;
  nickGradients: Record<string, string>;
  roleIcons: Record<string, string>;
}

export async function getAllLicenseAccounts(): Promise<LicenseAdminAccount[]> {
  try {
    const response = await adminClient.requestRaw("/api/admin/accounts", { method: "GET", timeout: 30000 });
    const accounts: LicenseAdminAccount[] = JSON.parse(response);
    return accounts;
  } catch (e: any) {
    console.error("[LicenseClient] getAllLicenseAccounts failed:", e.message);
    return [];
  }
}

export async function applyKeyOnLicenseServer(
  accountKey: string,
  key: string
): Promise<boolean> {
  try {
    await client.post("/api/account/apply-key", { accountKey, key });
    return true;
  } catch (e: any) {
    console.error("Failed to apply key:", e.message);
    return false;
  }
}

export interface HwidResetResult {
  success: boolean;
  newAccountKey?: string;
  message?: string;
}

export interface LicenseKeyResult {
  role: string;
  key: string;
  expiresAt: string;
  isNewKey: boolean;
}

export interface CreateOrUpdateLicenseResult {
  success: boolean;
  keys?: LicenseKeyResult[];
  expiresAt?: string;
  message?: string;
}

export async function createOrUpdateLicenseOnServer(
  accountKey: string,
  role: string,
  durationDays: number,
  limits?: { slots?: number; maxGifSize?: number; maxConfigsWithGif?: number },
  targetExpiresAt?: string
): Promise<CreateOrUpdateLicenseResult> {
  try {
    const raw = await adminClient.request<unknown>("/api/admin/license/create-or-update", {
      method: "POST",
      body: { accountKey, role, durationDays, limits, targetExpiresAt },
      timeout: 30000,
    });
    const validated = safeParse(CreateOrUpdateLicenseResultSchema, raw);
    if (!validated) {
      return { success: false, message: "INVALID_LICENSE_SERVER_RESPONSE" };
    }
    return validated;
  } catch (e: any) {
    console.error("[LicenseClient] createOrUpdateLicense failed:", e.message);
    return { success: false, message: e.message };
  }
}

function generateHwidResetToken(accountKey: string, secret: string): { token: string; timestamp: number } {
  const timestamp = Date.now();
  const message = `${accountKey}:${timestamp}`;
  const token = createHmac("sha256", secret).update(message).digest("base64");
  return { token, timestamp };
}

export async function resetHwidOnLicenseServer(accountKey: string): Promise<HwidResetResult> {
  try {
    if (!env.hypnosiaResetSecret) {
      console.error("[LicenseClient] HYPNOSIA_RESET_SECRET is not configured");
      return { success: false, message: "RESET_NOT_CONFIGURED" };
    }
    const { token, timestamp } = generateHwidResetToken(accountKey, env.hypnosiaResetSecret);
    const payload = { accountKey, resetToken: token, resetTimestamp: timestamp };
    console.log("[LicenseClient] resetHwid request:", payload);
    const response = await client.postRaw("/api/account/reset-hwid", payload);
    const body = typeof response === "string" ? response : JSON.stringify(response);
    console.log("[LicenseClient] resetHwid response:", body);
    const parsed = JSON.parse(body);
    return {
      success: parsed.ok === true,
      newAccountKey: parsed.newAccountKey,
      message: parsed.status || parsed.error || parsed.message,
    };
  } catch (e: any) {
    console.error("[LicenseClient] resetHwid failed:", e.message);
    return { success: false, message: e.message };
  }
}

export interface GifConfig {
  configKey: string;
  name: string;
  accountId: number;
  accountName: string;
  gifFileName: string;
  gifFileSize: number;
  gifApproved: boolean | null;
  updatedAt: string;
}

export interface GifConfigsResponse {
  ok: boolean;
  configs: GifConfig[];
}

export async function getGifConfigs(status?: string): Promise<GifConfig[]> {
  try {
    const result = await adminClient.request<GifConfigsResponse>("/api/admin/gif-configs", {
      method: "POST",
      body: { status },
      timeout: 30000,
    });
    return result.ok ? result.configs : [];
  } catch (e: any) {
    console.error("[LicenseClient] getGifConfigs failed:", e.message);
    return [];
  }
}

export async function approveGifConfig(configKey: string): Promise<boolean> {
  try {
    await adminClient.request("/api/admin/gif-configs/approve", {
      method: "POST",
      body: { configKey },
      timeout: 30000,
    });
    return true;
  } catch (e: any) {
    console.error("[LicenseClient] approveGifConfig failed:", e.message);
    return false;
  }
}

export async function denyGifConfig(configKey: string): Promise<boolean> {
  try {
    await adminClient.request("/api/admin/gif-configs/deny", {
      method: "POST",
      body: { configKey },
      timeout: 30000,
    });
    return true;
  } catch (e: any) {
    console.error("[LicenseClient] denyGifConfig failed:", e.message);
    return false;
  }
}
