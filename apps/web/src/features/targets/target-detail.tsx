'use client';

import Link from 'next/link';
import { useSearchParams, useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api, mutate } from '../../lib/api';
import { FeatureNav } from '../../components/feature-nav';
import { TargetForm } from './target-form';
import { RegistrationForm } from './registration-form';
import { ConnectionPanel } from './connection-panel';
import type { Target } from './types';
import styles from './targets.module.css';
import detail from './system-detail.module.css';

export function TargetDetail() {
  const query = useSearchParams();
  const router = useRouter();
  const id = query.get('id');
  const create = query.get('new') === '1';
  const screen = query.get('check') === '1' ? 'connections' : query.get('screen') ?? 'details';
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
  function saved(value: Target, check = false) { setTarget(value); setEditing(false); if (create) router.replace(`/targets/detail/?id=${value.id}${check ? '&check=1' : ''}`); }
  return <>
    <div className={create ? styles.heading : detail.heading}><div><h1>{create ? 'Register system' : target?.id === id ? target.name : 'System details'}</h1>
      {!create && target?.id === id && <div className={detail.subtitle}><span className={detail.environment}>{target.environment.toLowerCase()}</span>{screen !== 'details' && <><span>{target.namespace} / {target.deployment}</span><span>Container {target.container}</span></>}{target.archivedAt && <span>Archived</span>}</div>}</div>
      <div className={detail.headingActions}>{!create && target?.id === id && !target.archivedAt && !editing && <Button variant="secondary" onClick={() => setEditing(true)}>Edit system</Button>}<Link className={styles.backAction} href="/targets/">All systems</Link></div>
    </div>
    {error && <><Notice title="Cannot complete this action" tone="error">{error}</Notice><div className={styles.actions}><Button onClick={() => void load()}>Reload system</Button></div></>}
    {loading && <p role="status">Loading system…</p>}
    {!create && !valid && <Notice title="Choose a system">Open a system from the systems list to view its details.</Notice>}
    {create ? <RegistrationForm onSaved={saved} onCancel={() => router.push('/targets/')} /> : !loading && valid && target?.id === id && <>
      <FeatureNav label="Application" items={[
        { label: 'Details', href: '/targets/detail/?id=' + target.id, active: screen === 'details' },
        { label: 'Connections', href: '/targets/detail/?id=' + target.id + '&screen=connections', active: screen === 'connections' },
        { label: 'Resources', href: '/targets/detail/?id=' + target.id + '&screen=resources', active: screen === 'resources' },
      ]} />
      {screen === 'details' && !editing && <section className={styles.panel}><h2>Application details</h2><dl className={styles.details}><dt>API base URL</dt><dd>{target.baseUrl}</dd><dt>Namespace</dt><dd>{target.namespace}</dd><dt>Deployment</dt><dd>{target.deployment}</dd><dt>Container</dt><dd>{target.container}</dd></dl><p className={styles.hint}>Saved metadata does not establish connectivity. Open Connections to check endpoints, or Resources to inspect the workload.</p></section>}
      {editing && <TargetForm key={`${target.id}:${target.revision}`} target={target} onSaved={saved} onCancel={() => setEditing(false)} />}
      {!editing && target.archivedAt && <p className={detail.archived}>This system is archived. Its identity and history are retained.</p>}
      {!editing && !target.archivedAt && screen !== 'details' && <ConnectionPanel key={`${target.id}:${target.revision}`} target={target} screen={screen === 'resources' ? 'resources' : 'connections'} inspectOnMount={query.get('check') === '1'} onSaved={() => void load()} />}
      {!editing && screen === 'details' && <details className={`${detail.disclosure} ${detail.metadata}`}><summary>System settings</summary><dl className={detail.settings}><dt>Updated</dt><dd><time dateTime={target.updatedAt}>{new Date(target.updatedAt).toLocaleString('en-GB', { timeZone: 'UTC' })}</time></dd></dl>
        {!target.archivedAt && <div className={styles.actions}><Button variant="danger" onClick={() => setConfirmArchive(true)}>Archive system</Button></div>}
      </details>}
      {confirmArchive && <section className={styles.panel} aria-label="Confirm archive"><h2>Archive this system?</h2><p>It will be hidden from the active list. Its identity and history will be retained.</p>
        <div className={styles.actions}><Button variant="danger" busy={pending} onClick={() => void archive()}>Confirm archive</Button><Button variant="secondary" disabled={pending} onClick={() => setConfirmArchive(false)}>Cancel</Button></div></section>}
    </>}
  </>;
}
