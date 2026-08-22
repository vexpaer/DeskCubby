/** Central API client. 401 responses redirect to the login gate. */

export class ApiClientError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function handle(resp: Response): Promise<Response> {
  if (resp.status === 401 && !location.pathname.startsWith("/login")) {
    location.href = "/login";
    throw new ApiClientError(401, "unauthorized", "Authentication required");
  }
  return resp;
}

export async function apiGet<T>(path: string): Promise<T> {
  const resp = await handle(await fetch(path, { headers: { Accept: "application/json" } }));
  if (!resp.ok) throw await toError(resp);
  return (await resp.json()) as T;
}

export async function apiSend<T>(path: string, method: string, body?: unknown): Promise<T> {
  const resp = await handle(
    await fetch(path, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  );
  if (!resp.ok) throw await toError(resp);
  const text = await resp.text();
  return text ? JSON.parse(text) : ({} as T);
}

export async function apiUpload<T>(path: string, file: File | Blob, fileName?: string, extra?: Record<string, string>): Promise<T> {
  const form = new FormData();
  form.append("file", file, fileName ?? (file instanceof File ? file.name : "upload"));
  if (extra) for (const [k, v] of Object.entries(extra)) form.append(k, v);
  const resp = await handle(await fetch(path, { method: "POST", body: form }));
  if (!resp.ok) throw await toError(resp);
  return (await resp.json()) as T;
}

export async function apiDownload(path: string): Promise<Blob> {
  const resp = await handle(await fetch(path));
  if (!resp.ok) throw await toError(resp);
  return resp.blob();
}

async function toError(resp: Response): Promise<ApiClientError> {
  try {
    const data = await resp.json();
    if (data?.error) return new ApiClientError(resp.status, data.error.code ?? "error", data.error.message ?? "Request failed");
  } catch {
    /* fall through */
  }
  return new ApiClientError(resp.status, "http_" + resp.status, `Request failed (${resp.status})`);
}
