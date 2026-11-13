'use client';

import { useSearchParams, useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { PageHeading, RecordMetadata, StatusBadge } from '../../components/console-ui';
import { api, mutate } from '../../lib/api';
import { FeatureNav } from '../../components/feature-nav';
import { TargetForm } from './target-form';
import { RegistrationForm } from './registration-form';
import { ConnectionPanel } from './connection-panel';
import { useConnectionChecks } from './connection-checks';
import type { Target } from './types';
import styles from './targets.module.css';
import detail from './system-detail.module.css';

export function TargetDetail() {
  const query = useSearchParams();
  const router = useRouter();
  const { invalidate } = useConnectionChecks();
  const id = query.get('id');
  const create = query.get('new') === '1';
  const requested = query.get('screen');
  const screen = requested === 'settings' || requested === 'connections' ? 'settings' : 'overview';
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
  useEffect(() => { setEditing(false); setConfirmArchive(false); }, [id, screen]);
  async function archive() {
    if (!target || pending) return;
    setPending(true); setError('');
    try { setTarget(await mutate<Target>(`/api/targets/${target.id}/archive`, 'POST', { revision: target.revision })); invalidate(target.id); setConfirmArchive(false); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot archive this system.'); }
    finally { setPending(false); }
  }
  function saved(value: Target, check = false) {
    invalidate(value.id); setTarget(value); setEditing(false);
    if (create) router.replace(`/targets/detail/?id=${value.id}${check ? '&check=1' : ''}`);
  }
  const record = !create && target?.id === id ? target : undefined;
  return <>
    <PageHeading title={create ? 'Register system' : 'System'} back={{ href: '/targets/', label: 'Back to systems' }} actions={record && <>
      <RecordMetadata name={record.name}>Revision {record.revision}{record.updatedAt && <> · Updated <time dateTime={record.updatedAt}>{new Date(record.updatedAt).toLocaleString('en-GB', { timeZone: 'UTC' })} UTC</time></>}</RecordMetadata>
      <span className={detail.environment}>{record.environment.toLowerCase()}</span>
      {record.archivedAt && <StatusBadge>Archived</StatusBadge>}
    </>} />
    {error && <><Notice title="Cannot complete this action" tone="error">{error}</Notice><div className={styles.actions}><Button onClick={() => void load()}>Reload system</Button></div></>}
    {loading && <p role="status">Loading system…</p>}
    {!create && !valid && <Notice title="Choose a system">Open a system from the systems list to view its details.</Notice>}
    {create ? <RegistrationForm onSaved={saved} onCancel={() => router.push('/targets/')} /> : !loading && valid && target?.id === id && <>
      <FeatureNav label="Application" items={[
        { label: 'Overview', href: '/targets/detail/?id=' + target.id, active: screen === 'overview' },
        { label: 'Settings', href: '/targets/detail/?id=' + target.id + '&screen=settings', active: screen === 'settings' },
      ]} />
      {screen === 'overview' && <div className={detail.overview}>
        <section className={styles.panel}><h2>System details</h2><dl className={styles.details}><dt>API base URL</dt><dd>{target.baseUrl}</dd><dt>Namespace</dt><dd>{target.namespace}</dd><dt>Deployment</dt><dd>{target.deployment}</dd><dt>Container</dt><dd>{target.container}</dd></dl></section>
        {target.archivedAt ? <p className={detail.archived}>This system is archived. Its identity and history are retained.</p> :
          <ConnectionPanel key={`${target.id}:${target.revision}:overview`} target={target} screen="overview" inspectOnMount={query.get('check') === '1'} />}
      </div>}
      {screen === 'settings' && <div className={detail.overview}>
        {!target.archivedAt && <ConnectionPanel key={`${target.id}:${target.revision}:settings`} target={target} screen="settings" onSaved={() => {
          invalidate(target.id); router.replace(`/targets/detail/?id=${target.id}&check=1`);
        }} />}
        {editing ? <TargetForm key={`${target.id}:${target.revision}`} target={target} onSaved={saved} onCancel={() => setEditing(false)} /> :
          <section className={styles.panel}><h2>System settings</h2><dl className={detail.settings}><dt>Updated</dt><dd><time dateTime={target.updatedAt}>{new Date(target.updatedAt).toLocaleString('en-GB', { timeZone: 'UTC' })}</time></dd></dl>
            {!target.archivedAt && <div className={styles.actions}><Button variant="secondary" onClick={() => setEditing(true)}>Edit system</Button><Button variant="danger" onClick={() => setConfirmArchive(true)}>Archive system</Button></div>}
          </section>}
      </div>}
      {confirmArchive && <section className={styles.panel} aria-label="Confirm archive"><h2>Archive this system?</h2><p>It will be hidden from the active list. Its identity and history will be retained.</p>
        <div className={styles.actions}><Button variant="danger" busy={pending} onClick={() => void archive()}>Confirm archive</Button><Button variant="secondary" disabled={pending} onClick={() => setConfirmArchive(false)}>Cancel</Button></div></section>}
    </>}
  </>;
}
