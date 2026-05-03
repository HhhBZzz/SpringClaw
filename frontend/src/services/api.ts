import type { ApiEnvelope, AuthProfileResponse, AuthTokenResponse, ChatResponse } from '../types';

const TOKEN_KEY = 'springclaw.frontend.token';

export function backendOrigin(): string {
  if (typeof window === 'undefined') return '';
  const { protocol, hostname, port } = window.location;
  if (port === '5173' || port === '4173' || port === '3000') {
    return `${protocol}//${hostname}:18080`;
  }
  return '';
}

export function adminConsoleUrl(): string {
  return `${backendOrigin()}/admin`;
}

export function readToken(): string {
  return localStorage.getItem(TOKEN_KEY) || localStorage.getItem('springclaw.admin.token') || '';
}

export function writeToken(token: string) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

async function request<T>(url: string, options: RequestInit & { auth?: boolean } = {}): Promise<T> {
  const token = readToken();
  const headers = new Headers(options.headers);
  headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'application/json');
  if (options.auth !== false && token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(url, { ...options, headers });
  const raw = await response.text();
  let payload: ApiEnvelope<T>;
  try {
    payload = raw ? JSON.parse(raw) as ApiEnvelope<T> : { code: response.status, message: '响应为空', data: null as T };
  } catch {
    const contentType = response.headers.get('Content-Type') || 'unknown';
    const method = options.method || 'GET';
    const snippet = raw.replace(/\s+/g, ' ').slice(0, 120);
    throw new Error(`接口返回非 JSON：${method} ${url} -> HTTP ${response.status} (${contentType})。请确认后端 18080 已启动，且当前页面通过 Vite 5173 或 Spring Boot 静态页访问。响应片段：${snippet || '空响应'}`);
  }
  if (!response.ok || payload.code !== 0) {
    throw new Error(payload.message || `请求失败 (${response.status})`);
  }
  return payload.data;
}

export function login(username: string, password: string) {
  return request<AuthTokenResponse>('/api/auth/login', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ username, password })
  });
}

export function register(username: string, password: string) {
  return request<AuthTokenResponse>('/api/auth/register', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ username, password })
  });
}

export function me() {
  return request<AuthProfileResponse>('/api/auth/me');
}

export function sendChat(input: { sessionKey: string; userId: string; message: string; channel?: string }) {
  return request<ChatResponse>('/api/chat/send', {
    method: 'POST',
    body: JSON.stringify({ ...input, channel: input.channel || 'api' })
  });
}

export interface ChatStreamHandlers {
  onToken?: (token: string) => void;
  onStatus?: (status: string) => void;
  onError?: (message: string) => void;
  onDone?: () => void;
}

export async function streamChat(
  input: { sessionKey: string; userId: string; message: string; channel?: string },
  handlers: ChatStreamHandlers = {}
) {
  const token = readToken();
  const headers = new Headers();
  headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'text/event-stream');
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ ...input, channel: input.channel || 'api' })
  });

  if (!response.ok) {
    const raw = await response.text();
    throw new Error(extractHttpError(raw, response.status));
  }
  if (!response.body) {
    throw new Error('浏览器不支持流式响应。');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = consumeSseBuffer(buffer, handlers);
  }
  buffer += decoder.decode();
  consumeSseBuffer(buffer + '\n\n', handlers);
}

function consumeSseBuffer(buffer: string, handlers: ChatStreamHandlers) {
  const parts = buffer.split(/\r?\n\r?\n/);
  const rest = parts.pop() || '';
  for (const part of parts) {
    dispatchSseEvent(part, handlers);
  }
  return rest;
}

function dispatchSseEvent(raw: string, handlers: ChatStreamHandlers) {
  let eventName = 'message';
  const dataLines: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).replace(/^ /, ''));
    }
  }
  const data = dataLines.join('\n');
  if (eventName === 'token' || eventName === 'message') {
    handlers.onToken?.(data);
  } else if (eventName === 'status') {
    handlers.onStatus?.(data);
  } else if (eventName === 'error') {
    handlers.onError?.(data);
  } else if (eventName === 'done') {
    handlers.onDone?.();
  }
}

function extractHttpError(raw: string, status: number) {
  try {
    const payload = JSON.parse(raw) as ApiEnvelope<unknown>;
    return payload.message || `请求失败 (${status})`;
  } catch {
    return raw.replace(/\s+/g, ' ').slice(0, 160) || `请求失败 (${status})`;
  }
}
