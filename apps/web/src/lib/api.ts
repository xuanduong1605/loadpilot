export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { ...init, credentials: 'same-origin', cache: 'no-store' });
  } catch {
    throw new ApiError(0, 'UNAVAILABLE', 'Cannot reach the platform. Check your connection and try again.');
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    if (response.status === 401 && !path.endsWith('/login')) window.dispatchEvent(new Event('loadpilot:unauthenticated'));
    throw new ApiError(response.status, error.code ?? 'REQUEST_FAILED', error.message ?? (response.status === 413 ? 'The request is too large. Reduce the submitted data.' : 'The platform is unavailable. Try again later.'));
  }
  return response.status === 204 ? undefined as T : response.json();
}

export async function accessPost<T>(action: 'login' | 'logout', body?: URLSearchParams): Promise<T> {
  const csrf = await api<{ headerName: string; token: string }>('/api/access/csrf');
  return api<T>(`/api/access/${action}`, { method: 'POST', body, headers: { [csrf.headerName]: csrf.token } });
}

export async function mutate<T>(path: string, method: 'POST' | 'PUT', body: unknown): Promise<T> {
  const csrf = await api<{ headerName: string; token: string }>('/api/access/csrf');
  return api<T>(path, { method, body: JSON.stringify(body),
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token } });
}

// Only implemented static workspace paths can be return destinations.
export function returnPath(value: string | null): string {
  if (!value) return '/';
  const path = value.split('?')[0];
  return ['/', '/targets/', '/targets/detail/', '/scenarios/', '/experiments/', '/comparisons/', '/settings/'].includes(path) ? value : '/';
}
