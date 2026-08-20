import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from "crypto";

const ALGORITHM = "aes-256-gcm";
const IV_LENGTH = 16;
const SALT_LENGTH = 16;
const TAG_LENGTH = 16;
const KEY_LENGTH = 32;

function getKey(): Buffer {
  const envKey = process.env.DATA_ENCRYPTION_KEY;
  if (!envKey || envKey.length < 32) {
    throw new Error("DATA_ENCRYPTION_KEY is not configured or too short (min 32 chars).");
  }
  return scryptSync(envKey, "hypnosia-static-salt", KEY_LENGTH);
}

let cachedKey: Buffer | null = null;
function key(): Buffer {
  if (!cachedKey) cachedKey = getKey();
  return cachedKey;
}

/**
 * Encrypt a plaintext string using AES-256-GCM.
 * Returns a base64-encoded string containing: salt + iv + ciphertext + authTag.
 */
export function encrypt(plaintext: string): string {
  const salt = randomBytes(SALT_LENGTH);
  const iv = randomBytes(IV_LENGTH);
  const cipher = createCipheriv(ALGORITHM, key(), iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  const result = Buffer.concat([salt, iv, encrypted, tag]);
  return result.toString("base64");
}

/**
 * Decrypt a base64-encoded string produced by encrypt().
 * Returns the original plaintext, or null if decryption fails.
 */
export function decrypt(ciphertext: string): string | null {
  try {
    const data = Buffer.from(ciphertext, "base64");
    if (data.length < SALT_LENGTH + IV_LENGTH + TAG_LENGTH) {
      return null;
    }
    const salt = data.subarray(0, SALT_LENGTH);
    const iv = data.subarray(SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
    const tag = data.subarray(data.length - TAG_LENGTH);
    const encrypted = data.subarray(SALT_LENGTH + IV_LENGTH, data.length - TAG_LENGTH);
    const decipher = createDecipheriv(ALGORITHM, key(), iv);
    decipher.setAuthTag(tag);
    const decrypted = Buffer.concat([decipher.update(encrypted), decipher.final()]);
    return decrypted.toString("utf8");
  } catch {
    return null;
  }
}

/**
 * Check whether a value looks like it is already encrypted (base64, minimum length).
 * Used during migrations to avoid double-encryption.
 */
export function looksEncrypted(value: string): boolean {
  if (value.length < 44) return false;
  try {
    const buf = Buffer.from(value, "base64");
    return buf.length >= SALT_LENGTH + IV_LENGTH + TAG_LENGTH + 1;
  } catch {
    return false;
  }
}
