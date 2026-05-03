export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

export interface AuthTokenResponse {
  token: string;
  username: string;
  roleCode: string;
  expireAt: number;
}

export interface AuthProfileResponse {
  username: string;
  roleCode: string;
  expireAt: number;
}

export interface ChatResponse {
  sessionKey: string;
  answer: string;
  model: string;
  timestamp: number;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'agent' | 'system';
  content: string;
  model?: string;
  createdAt: number;
}
