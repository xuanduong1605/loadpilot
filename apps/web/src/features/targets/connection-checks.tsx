'use client';

import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { api } from '../../lib/api';
import type { ConnectionView, Inspection } from './connection-types';
import type { Target } from './types';

type Check = {
  revision: number;
  pending: boolean;
  unconfigured?: boolean;
  error?: string;
  snapshot?: Inspection;
};

const CheckContext = createContext<{
  checks: Readonly<Record<string, Check | undefined>>;
  inspect: (target: Target, connectionRevision?: number) => Promise<void>;
  invalidate: (id: string) => void;
} | null>(null);

export function ConnectionChecks({ children }: { children: ReactNode }) {
  const [checks, setChecks] = useState<Record<string, Check | undefined>>({});
  const requests = useRef(new Map<string, { revision: number }>());

  const invalidate = useCallback((id: string) => {
    requests.current.delete(id);
    setChecks(previous => ({ ...previous, [id]: undefined }));
  }, []);

  const inspect = useCallback(async (target: Target, connectionRevision?: number) => {
    const { id, revision } = target;
    if (target.archivedAt || requests.current.get(id)?.revision === revision) return;
    const request = { revision };
    requests.current.set(id, request);
    setChecks(previous => ({ ...previous, [id]: {
      revision, pending: true, snapshot: previous[id]?.revision === revision ? previous[id]?.snapshot : undefined,
    } }));
    function finish(value: Partial<Check>) {
      if (requests.current.get(id) !== request) return;
      requests.current.delete(id);
      setChecks(previous => ({ ...previous, [id]: { ...previous[id], ...value, revision, pending: false } }));
    }
    try {
      const expected = connectionRevision ?? (await api<ConnectionView>(`/api/integrations/targets/${id}/connection`)).revision;
      if (requests.current.get(id) !== request) return;
      if (!expected) { finish({ unconfigured: true, snapshot: undefined }); return; }
      const snapshot = await api<Inspection>(`/api/integrations/targets/${id}/status`, { headers: { 'X-Loadpilot-Interactive': 'true' } });
      if (snapshot.targetId !== id || snapshot.targetRevision !== revision || snapshot.connectionRevision !== expected)
        throw new Error('The system or connection changed. Refresh the system and check again.');
      finish({ snapshot });
    } catch (reason) {
      finish({ error: reason instanceof Error ? reason.message : 'Connection check failed.' });
    }
  }, []);

  return <CheckContext.Provider value={{ checks, inspect, invalidate }}>{children}</CheckContext.Provider>;
}

export function useConnectionChecks() {
  const context = useContext(CheckContext);
  if (!context) throw new Error('Connection checks require a workspace session.');
  return context;
}

export function connectionStatus(check: Check | undefined) {
  if (check?.pending) return { label: 'Connecting', tone: 'warning' };
  if (check?.error) return { label: 'Connection error', tone: 'danger' };
  if (check?.unconfigured) return { label: 'Not connected', tone: 'warning' };
  if (!check?.snapshot) return { label: 'Not checked', tone: 'warning' };
  const { api, kubernetes, prometheus } = check.snapshot;
  if (kubernetes.state === 'NOT_CONFIGURED') return { label: 'Not connected', tone: 'warning' };
  return api.state === 'READY' && kubernetes.state === 'READY' && ['READY', 'NOT_CONFIGURED'].includes(prometheus.state)
    ? { label: 'Connected', tone: 'success' }
    : { label: 'Connection error', tone: 'danger' };
}
