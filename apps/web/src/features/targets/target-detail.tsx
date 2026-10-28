'use client';

import Link from 'next/link';
import { useSearchParams, useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api, mutate } from '../../lib/api';
import { TargetForm } from './target-form';
import type { Target } from './types';
import styles from './targets.module.css';

export function TargetDetail() {
  const query = useSearchParams();
  const router = useRouter();
  const id = query.get('id');
  const create = query.get('new') === '1';
  const [target, setTarget] = useState<Target>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [confirmArchive, setConfirmArchive] = useState(false);
  const [pending, setPending] = useState(false);
  const request = useRef(0);
  const valid = !!id && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id);
  const load = useCallback(async () => {
    const ticket = ++request.current;
    if (!valid || create) return;
    setLoading(true); setError('');
    try { const result = await api<Target>(`/api/targets/${id}`); if (request.current === ticket) { setTarget(result); setEditing(false); } }
    catch (reason) { if (request.current === ticket) { setTarget(undefined); setError(reason instanceof Error ? reason.message : 'Cannot load this system.'); } }
    finally { if (request.current === ticket) setLoading(false); }
  }, [id, valid, create]);
  useEffect(() => { void load(); return () => { request.current++; }; }, [load]);
  async function archive() {
    if (!target || pending) return;
    setPending(true); setError('');
    try { setTarget(await mutate<Target>(`/api/targets/${target.id}/archive`, 'POST', { revision: target.revision })); setConfirmArchive(false); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot archive this system.'); }
    finally { setPending(false); }
  }
  function saved(value: Target) { setTarget(value); setEditing(false); if (create) router.replace(`/targets/detail/?id=${value.id}`); }
  return <>
    <div className={styles.heading}><div><h1>{create ? 'Register system' : target?.id === id ? target.name : 'System details'}</h1><p>Keep each application&apos;s workload and connection details together.</p></div><Link className={styles.backAction} href="/targets/">All systems</Link></div>
    {error && <><Notice title="Cannot complete this action" tone="error">{error}</Notice><div className={styles.actions}><Button onClick={() => void load()}>Reload system</Button></div></>}
    {loading && <p role="status">Loading system…</p>}
    {!create && !valid && <Notice title="Choose a system">Open a system from the systems list to view its details.</Notice>}
    {create ? <TargetForm onSaved={saved} onCancel={() => router.push('/targets/')} /> : !loading && valid && target?.id === id && <>
      {editing ? <TargetForm key={`${target.id}:${target.revision}`} target={target} onSaved={saved} onCancel={() => setEditing(false)} /> : <section className={`${styles.panel} ${styles.metadata}`}>
        <span className={styles.badge}>{target.archivedAt ? 'Archived' : 'Registered'}</span>
        <dl className={styles.details}><dt>API</dt><dd>{target.baseUrl}</dd><dt>Environment</dt><dd>{target.environment}</dd><dt>Namespace</dt><dd>{target.namespace}</dd>
          <dt>Deployment</dt><dd>{target.deployment}</dd><dt>Container</dt><dd>{target.container}</dd><dt>Credentials</dt><dd>{target.credentialRef === target.id ? 'Saved for this system' : target.credentialRef || 'Not configured'}</dd>
          <dt>Monitoring</dt><dd>{target.monitoringRef === target.id ? 'Configured for this system' : target.monitoringRef || 'Not configured'}</dd><dt>Updated</dt><dd><time dateTime={target.updatedAt}>{new Date(target.updatedAt).toLocaleString()}</time></dd></dl>
        {!target.archivedAt && <div className={styles.actions}><Button onClick={() => setEditing(true)}>Edit system</Button><Button onClick={() => setConfirmArchive(true)}>Archive system</Button></div>}
        {target.archivedAt && <p className={styles.hint}>This system is archived. Its identity and history are retained.</p>}
      </section>}
      {confirmArchive && <section className={styles.panel} aria-label="Confirm archive"><h2>Archive this system?</h2><p>It will be hidden from the active list. Its identity and history will be retained.</p>
        <div className={styles.actions}><Button busy={pending} onClick={() => void archive()}>Confirm archive</Button><Button disabled={pending} onClick={() => setConfirmArchive(false)}>Cancel</Button></div></section>}
    </>}
  </>;
}
