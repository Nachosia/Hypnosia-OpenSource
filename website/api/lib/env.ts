import "dotenv/config";

function required(name: string): string {
  const value = process.env[name];
  if (!value && process.env.NODE_ENV === "production") {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value ?? "";
}

export const env = {
  appId: required("APP_ID"),
  appSecret: required("APP_SECRET"),
  isProduction: process.env.NODE_ENV === "production",
  databaseUrl: required("DATABASE_URL"),
  discordAuthUrl: "https://discord.com",
  discordApiUrl: "https://discord.com/api",
  ownerUnionId: process.env.OWNER_UNION_ID ?? "",
  licenseServerUrl: process.env.LICENSE_SERVER_URL ?? "http://127.0.0.1:8080",
  licenseServerApiKey: process.env.LICENSE_SERVER_API_KEY ?? "",
  modApiKey: process.env.MOD_API_KEY || process.env.API_KEY || "",
  dataEncryptionKey: process.env.DATA_ENCRYPTION_KEY ?? "",
  hypnosiaResetSecret: process.env.HYPNOSIA_RESET_SECRET ?? "",
};
