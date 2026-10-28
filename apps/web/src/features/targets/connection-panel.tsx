'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api } from '../../lib/api';
import { ConnectionForm } from './connection-form';
import type { ConnectionPolicy, ConnectionView, Inspection } from './connection-types';
import type { Target } from './types';
import styles from './targets.module.css';

const number = (value: number | null | undefined, divisor = 1) => value == null ? 'Unavailable' : new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(value / divisor);
const state = (value: string) => value.toLowerCase().replaceAll('_', ' ');

export function ConnectionPanel({ target, onSaved }: { target: Target; onSaved: () => void }) {
  const [connection, setConnection] = useState<ConnectionView>();
  const [policy, setPolicy] = useState<ConnectionPolicy>();
  const [editing, setEditing] = useState(false);
  const [snapshot, setSnapshot] = useState<Inspection>();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [auto, setAuto] = useState(false);
  const [now, setNow] = useState(Date.now());
  const working = useRef(false);
  const mounted = useRef(true);
  const load = useCallback(async () => {
    setError('');
    try {
      const [saved, allowed] = await Promise.all([api<ConnectionView>(`/api/integrations/targets/${target.id}/connection`), api<ConnectionPolicy>('/api/integrations/config')]);
      if (mounted.current) { setConnection(saved); setPolicy(allowed); }
    } catch (reason) { if (mounted.current) setError(reason instanceof Error ? reason.message : 'Cannot load connection settings.'); }
  }, [target.id]);
  useEffect(() => {
    mounted.current = true; void load();
    const clock = window.setInterval(() => setNow(Date.now()), 5000);
    return () => { mounted.current = false; window.clearInterval(clock); };
  }, [load]);
  const inspect = useCallback(async (interactive = true) => {
    if (working.current) return;
    working.current = true; setPending(true); setError('');
    try {
      const result = await api<Inspection>(`/api/integrations/targets/${target.id}/status`, { headers: interactive ? { 'X-Loadpilot-Interactive': 'true' } : {} });
      if (!mounted.current) return;
      if (result.targetRevision !== target.revision || result.connectionRevision !== connection?.revision)
        throw new Error('This system or connection changed. Reload its details before inspecting again.');
      setSnapshot(result); setNow(Date.now());
      if (result.kubernetes.state !== 'READY') setAuto(false);
    } catch (reason) { if (mounted.current) { setError(reason instanceof Error ? reason.message : 'Inspection failed.'); setAuto(false); } }
    finally { working.current = false; if (mounted.current) setPending(false); }
  }, [target.id, target.revision, connection?.revision]);
  useEffect(() => {
    if (!auto) return;
    const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void inspect(false); }, 15_000);
    return () => window.clearInterval(interval);
  }, [auto, inspect]);
  const stale = !!snapshot && now - Date.parse(snapshot.collectedAt) > 30_000;
  if (editing && connection && policy) return <ConnectionForm target={target} connection={connection} policy={policy} onCancel={() => setEditing(false)} onSaved={onSaved} />;
  return <section className={styles.panel} aria-label="System connection and live status">
    <h2>Connection &amp; live status</h2>
    <p>{target.namespace} / {target.deployment} · container {target.container}</p>
    {error && <Notice title="Cannot complete the connection check" tone="error">{error}{snapshot ? ' Previously collected values below are not a new observation.' : ''}</Notice>}
    {!connection || !policy ? <><p role="status">{error ? 'Connection settings are unavailable.' : 'Loading connection settings…'}</p>{error && <Button onClick={() => void load()}>Retry connection settings</Button>}</>
      : <><p className={styles.hint}>{connection.revision ? `Kubernetes endpoint: ${connection.kubernetesUrl}` : 'Connection not checked. Set up a connection to inspect this system.'}</p>
        <div className={styles.actions}><Button onClick={() => { setAuto(false); setEditing(true); }}>{connection.revision ? 'Edit connection' : 'Set up connection'}</Button>
          <Button busy={pending} disabled={!connection.revision} onClick={() => void inspect()}>Check connection &amp; refresh</Button>
          <label className={styles.confirm}><input type="checkbox" checked={auto} disabled={!connection.revision} onChange={event => { setAuto(event.target.checked); if (event.target.checked) void inspect(); }} /> Auto-refresh every 15 seconds</label></div></>}
    {pending && <p role="status">Inspecting endpoints from the platform…</p>}
    {snapshot && <>
      <p className={styles.hint}>Collected <time dateTime={snapshot.collectedAt}>{new Date(snapshot.collectedAt).toLocaleString()}</time> {stale ? '· Stale snapshot — refresh for a current observation.' : '· Snapshot; resource sample times are listed below.'}</p>
      <div className={styles.checks}>{([['API', snapshot.api], ['Kubernetes API', snapshot.kubernetes], ['Resource metrics', snapshot.metrics], ['Prometheus', snapshot.prometheus]] as const).map(([label, check]) =>
        <article key={label} data-state={check.state} data-tone={check.state === 'READY' ? 'success' : ['UNAVAILABLE', 'STALE', 'NOT_CONFIGURED'].includes(check.state) ? 'warning' : 'error'}><h3>{label}: <span>{state(check.state)}</span></h3><p>{check.message}</p><small>Source: {check.source} · {new Date(check.checkedAt).toLocaleTimeString()}</small></article>)}</div>
      {snapshot.workload && <dl className={styles.details}><dt>Replicas desired</dt><dd>{number(snapshot.workload.desiredReplicas)}</dd><dt>Ready / available</dt><dd>{number(snapshot.workload.readyReplicas)} / {number(snapshot.workload.availableReplicas)}</dd>
        <dt>Updated replicas</dt><dd>{number(snapshot.workload.updatedReplicas)}</dd><dt>Generation</dt><dd>{number(snapshot.workload.observedGeneration)} observed / {number(snapshot.workload.generation)} desired</dd></dl>}
      {snapshot.pods.length > 0 && <div className={styles.tableWrap} role="region" aria-label="Owned Pod resources" tabIndex={0}><table className={styles.table}><caption>Owned Pods · selected-container resources. Allocation and usage are separate.</caption>
        <thead><tr><th>Pod / readiness</th><th>Restarts</th><th>CPU usage (mCPU)</th><th>CPU request / limit (mCPU)</th><th>Memory usage (MiB)</th><th>Memory request / limit (MiB)</th><th>Sample</th></tr></thead>
        <tbody>{snapshot.pods.map(pod => <tr key={pod.name}><th>{pod.name}<small>{pod.phase ?? 'Unknown'} · {pod.ready == null ? 'Readiness unavailable' : pod.ready ? 'Ready' : 'Not ready'}</small></th><td>{number(pod.restarts)}</td>
          <td>{number(pod.usage.cpuMillicores)}</td><td>{number(pod.cpuRequestMillicores)} / {number(pod.cpuLimitMillicores)}</td><td>{number(pod.usage.memoryBytes, 1048576)}</td>
          <td>{number(pod.memoryRequestBytes, 1048576)} / {number(pod.memoryLimitBytes, 1048576)}</td><td>{state(pod.usage.state)}<small>{pod.usage.timestamp ? new Date(pod.usage.timestamp).toLocaleTimeString() : 'No sample time'} · {pod.usage.window ?? 'No window'}</small></td></tr>)}</tbody></table></div>}
      {!snapshot.pods.length && <p className={styles.hint}>No owned selected-container Pods are available in this inspection.</p>}
    </>}
  </section>;
}
