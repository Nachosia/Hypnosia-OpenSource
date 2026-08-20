import https from "https";
import http from "http";
import { URL } from "url";

interface RequestConfig {
  method?: string;
  params?: Record<string, string | number>;
  body?: any;
  headers?: Record<string, string>;
  timeout?: number;
  rejectUnauthorized?: boolean;
}

function requestPromise(
  url: URL,
  options: https.RequestOptions,
  body?: string
): Promise<{ statusCode: number; body: string }> {
  return new Promise((resolve, reject) => {
    const isHttps = url.protocol === "https:";
    const client = isHttps ? https : http;

    // Build request options from URL parts to preserve custom Host header
    const reqOptions: https.RequestOptions = {
      hostname: url.hostname,
      port: url.port || (isHttps ? 443 : 80),
      path: url.pathname + url.search,
      method: options.method,
      headers: options.headers,
      timeout: options.timeout,
    };

    if (isHttps) {
      // Use Host header for SNI when connecting by IP address
      // This allows TLS verification to succeed against the certificate CN/SAN
      reqOptions.servername = options.headers?.Host || url.hostname;
      if (options.rejectUnauthorized === false) {
        reqOptions.agent = new https.Agent({ rejectUnauthorized: false });
      }
    }

    const req = client.request(reqOptions, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => resolve({ statusCode: res.statusCode || 0, body: data }));
    });
    req.on("error", reject);
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Request timeout"));
    });
    if (body) req.write(body);
    req.end();
  });
}

export class HttpClient {
  private baseUrl: string;
  private defaultHeaders: Record<string, string>;
  private rejectUnauthorized: boolean;

  constructor(baseURL: string, opts?: { headers?: Record<string, string>; rejectUnauthorized?: boolean }) {
    this.baseUrl = baseURL;
    this.defaultHeaders = {
      "Content-Type": "application/json",
      ...opts?.headers,
    };
    this.rejectUnauthorized = opts?.rejectUnauthorized !== false;
  }

  async requestRaw(endpoint: string, config: RequestConfig = {}): Promise<string> {
    const {
      method = "GET",
      params,
      body,
      headers,
      timeout = 30000,
      rejectUnauthorized,
    } = config;

    const url = new URL(`${this.baseUrl}${endpoint}`);
    if (params) {
      Object.entries(params).forEach(([key, value]) =>
        url.searchParams.append(key, value.toString()),
      );
    }

    const bodyStr = body ? JSON.stringify(body) : undefined;
    const options: https.RequestOptions = {
      method,
      headers: { ...this.defaultHeaders, ...headers },
      timeout,
      rejectUnauthorized: rejectUnauthorized !== undefined ? rejectUnauthorized : this.rejectUnauthorized,
    };

    const response = await requestPromise(url, options, bodyStr);

    if (response.statusCode < 200 || response.statusCode >= 300) {
      let errorData: Record<string, string> = {};
      try {
        errorData = JSON.parse(response.body);
      } catch {}
      throw new Error(errorData.message || `HTTP Error: ${response.statusCode}`);
    }

    return response.body;
  }

  async request<T>(endpoint: string, config: RequestConfig = {}): Promise<T> {
    const text = await this.requestRaw(endpoint, config);
    return JSON.parse(text) as T;
  }

  get<T>(url: string, params?: RequestConfig["params"], config?: RequestConfig) {
    return this.request<T>(url, { ...config, method: "GET", params });
  }

  post<T>(url: string, body?: any, config?: RequestConfig) {
    return this.request<T>(url, { ...config, method: "POST", body });
  }

  postRaw(url: string, body?: any, config?: RequestConfig) {
    return this.requestRaw(url, { ...config, method: "POST", body });
  }
}
