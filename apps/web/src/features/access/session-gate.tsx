'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, accessPost } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import { WorkspaceShell } from '../../components/workspace-shell';
import styles from './session.module.css';

export function SessionGate({ children }: { children: React.ReactNode }) {
  const [username, setUsername] = useState<string>();
  const [error, setError] = useState('');
  const [leaving, setLeaving] = useState(false);
  const expiry = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const redirect = useCallback(() => {
    setUsername(undefined);
    window.location.replace(`/login/?next=${encodeURIComponent(window.location.pathname + window.location.search)}`);
  }, []);
  const check = useCallback(async () => {
    setError('');
    try {
      const identity = await api<{ username: string; idleTimeoutSeconds: number }>('/api/access/session');
      setUsername(identity.username);
      window.clearTimeout(expiry.current);
      // Check once at expiry; periodic polling would keep an idle session alive forever.
      expiry.current = setTimeout(() => void check(), (identity.idleTimeoutSeconds + 1) * 1000);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) redirect();
      else { setUsername(undefined); setError(reason instanceof Error ? reason.message : 'Cannot check your session.'); }
    }
  }, [redirect]);

  useEffect(() => {
    void check();
    const resume = () => { if (document.visibilityState === 'visible') void check(); };
    window.addEventListener('loadpilot:unauthenticated', redirect);
    window.addEventListener('pageshow', resume);
    document.addEventListener('visibilitychange', resume);
    return () => {
      window.clearTimeout(expiry.current);
      window.removeEventListener('loadpilot:unauthenticated', redirect);
      window.removeEventListener('pageshow', resume);
      document.removeEventListener('visibilitychange', resume);
    };
  }, [check, redirect]);

  async function logout() {
    setLeaving(true); setError('');
    try { await accessPost('logout'); window.location.replace('/login/'); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Sign out failed. Try again.'); setLeaving(false); }
  }

  if (!username) return <main className={styles.loading}><p role="status">{error || 'Checking your session…'}</p>
    {error && <Button onClick={() => void check()}>Try again</Button>}
    <noscript>Enable JavaScript to sign in to Loadpilot.</noscript></main>;
  return <WorkspaceShell account={<div className={styles.account}>
    <span className={styles.avatar} aria-hidden="true">{username.slice(0, 1).toUpperCase()}</span>
    <span className={styles.identity}>Signed in as {username}</span><Button variant="secondary" onClick={() => void logout()} disabled={leaving}>{leaving ? 'Signing out…' : 'Sign out'}</Button>
  </div>}>
    {error && <Notice title="Sign out failed" tone="error">{error}</Notice>}
    {children}
  </WorkspaceShell>;
}
