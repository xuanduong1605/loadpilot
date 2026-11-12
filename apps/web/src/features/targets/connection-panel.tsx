'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api } from '../../lib/api';
import { ConnectionForm } from './connection-form';
import { useConnectionChecks } from './connection-checks';
import { Icon } from '../../components/icon';
import { ResourceChart } from './resource-chart';
import { appendObservation, resourceSummary, validUsage, type ResourcePoint } from './resources';
import type { ConnectionPolicy, ConnectionView } from './connection-types';
import type { Target } from './types';
import styles from './system-detail.module.css';

const number = (value: number | null | undefined, divisor = 1) => value == null ? 'Unavailable' : new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(value / divisor);
const state = (value: string) => value.toLowerCase().replaceAll('_', ' ');
const label = (value: string) => ({ READY: 'Ready', NOT_CONFIGURED: 'Not configured', NOT_CHECKED: 'Not checked',
  UNAUTHORIZED: 'Authentication failed', FORBIDDEN: 'Permission denied', TLS_ERROR: 'TLS error',
  DESTINATION_DENIED: 'Destination denied', CONNECTION_UNAVAILABLE: 'Unavailable', CONTAINER_NOT_FOUND: 'Container missing',
  INVALID_RESPONSE: 'Invalid response', REDIRECT_BLOCKED: 'Redirect blocked', RESPONSE_TOO_LARGE: 'Response too large' }[value] ?? state(value));
const tone = (value: string) => value === 'READY' ? 'success' : ['NOT_CONFIGURED', 'NOT_CHECKED'].includes(value) ? 'warning' : 'danger';

type PanelProps = { target: Target } & ({ screen: 'overview'; inspectOnMount?: boolean } | { screen: 'settings'; onSaved: () => void });

export function ConnectionPanel(props: PanelProps) {
  const { target, screen } = props;
  const inspectOnMount = screen === 'overview' && props.inspectOnMount;
  const { checks: systemChecks, inspect } = useConnectionChecks();
  const check = systemChecks[target.id]?.revision === target.revision ? systemChecks[target.id] : undefined;
  const [connection, setConnection] = useState<ConnectionView>();
  const [policy, setPolicy] = useState<ConnectionPolicy>();
  const [editing, setEditing] = useState(false);
  const snapshot = check?.snapshot?.connectionRevision === connection?.revision ? check?.snapshot : undefined;
  const pending = check?.pending ?? false;
  const [error, setError] = useState('');
  const [auto, setAuto] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [history, setHistory] = useState<ResourcePoint[]>([]);
  const [metric, setMetric] = useState<'cpuMillicores' | 'memoryBytes'>('cpuMillicores');
  const mounted = useRef(true);
  const initialInspection = useRef(false);
  const load = useCallback(async () => {
    setError('');
    try {
      const [saved, allowed] = await Promise.all([api<ConnectionView>(`/api/integrations/targets/${target.id}/connection`), screen === 'settings' ? api<ConnectionPolicy>('/api/integrations/config') : undefined]);
      if (mounted.current) { setConnection(saved); setPolicy(allowed); }
    } catch (reason) { if (mounted.current) setError(reason instanceof Error ? reason.message : 'Cannot load connection settings.'); }
  }, [target.id, screen]);
  useEffect(() => {
    mounted.current = true; void load();
    const clock = window.setInterval(() => setNow(Date.now()), 5000);
    return () => { mounted.current = false; window.clearInterval(clock); };
  }, [load]);
  const refresh = useCallback(() => inspect(target, connection?.revision), [inspect, target, connection?.revision]);
  useEffect(() => {
    if (screen === 'overview' && connection && !initialInspection.current) {
      initialInspection.current = true;
      if (inspectOnMount || (!pending && !snapshot && !check?.error)) void refresh();
    }
  }, [screen, inspectOnMount, connection, pending, snapshot, check?.error, refresh]);
  useEffect(() => {
    if (snapshot) {
      setHistory(previous => appendObservation(previous, snapshot));
      setNow(Date.now());
      if (snapshot.kubernetes.state !== 'READY') setAuto(false);
    }
  }, [snapshot]);
  useEffect(() => { if (check?.error) setAuto(false); }, [check?.error]);
  useEffect(() => {
    if (!auto || screen !== 'overview') return;
    const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void refresh(); }, 15_000);
    return () => window.clearInterval(interval);
  }, [auto, screen, refresh]);
  const stale = !!snapshot && now - Date.parse(snapshot.collectedAt) > 30_000;
  if (screen === 'settings') {
    if (editing && connection && policy) return <ConnectionForm target={target} connection={connection} policy={policy} onCancel={() => setEditing(false)} onSaved={props.onSaved} />;
    return <section className={styles.resourcePanel} aria-labelledby="connection-settings-title">
      <h2 id="connection-settings-title">Connection</h2>
      {error ? <><Notice title="Cannot load connection settings" tone="error">{error}</Notice><Button onClick={() => void load()}>Retry</Button></> :
        !connection || !policy ? <p role="status">Loading connection settings…</p> : <>
          <p>{connection.revision ? 'Manage the saved Kubernetes endpoint, monitoring and credentials.' : 'Add Kubernetes connection settings to inspect this workload.'}</p>
          <Button variant="secondary" onClick={() => setEditing(true)}>{connection.revision ? 'Edit connection' : 'Set up connection'}</Button>
        </>}
    </section>;
  }
  if (connection?.revision === 0) return <Notice title="Connect this system">Add a Kubernetes connection in <Link href={`/targets/detail/?id=${target.id}&screen=settings`}>Settings</Link> to see workload metrics and diagnostics.</Notice>;
  const summary = snapshot ? resourceSummary(snapshot) : null;
  const checks = [
    ['API', snapshot?.api], ['Kubernetes', snapshot?.kubernetes], ['Metrics', snapshot?.metrics], ['Monitoring', snapshot?.prometheus],
  ] as const;
  const statuses = checks.map(([name, check]) => ({ name, check, value: check?.state ?? (name === 'Monitoring' && !connection?.prometheusUrl ? 'NOT_CONFIGURED' : 'NOT_CHECKED') }));
  const problems = statuses.filter(item => item.check && !['READY', 'NOT_CONFIGURED'].includes(item.value));
  const unavailable = <span aria-label="Unavailable" title="No valid observation">—</span>;
  return <div className={styles.overview}>
    <div className={styles.controls}>
      <p className={styles.checked}>{snapshot ? <>Last checked <time dateTime={snapshot.collectedAt}>{new Date(snapshot.collectedAt).toLocaleTimeString('en-GB', { timeZone: 'UTC' })}</time>{stale ? ' · Stale snapshot' : ''}</> : 'Connection not checked yet'}</p>
      <div className={styles.controlActions}>
        <Button busy={pending} disabled={!connection?.revision} aria-label="Refresh system data" onClick={() => void refresh()}><Icon name="refresh" width={14} height={14} />Refresh</Button>
        <label className={styles.auto}><input type="checkbox" checked={auto} disabled={!connection?.revision} aria-label="Auto-refresh every 15 seconds" onChange={event => { setAuto(event.target.checked); if (event.target.checked) void refresh(); }} /> Auto-refresh · 15s</label>
      </div>
    </div>
    {(error || check?.error) && <Notice title="Cannot complete the connection check" tone="error">{error || check?.error}{snapshot ? ' Previously collected values are not a new observation.' : ''}</Notice>}
    {!connection && <div><p role="status" className={styles.checked}>{error ? 'Connection settings are unavailable.' : 'Loading connection settings…'}</p>{error && <Button onClick={() => void load()}>Retry connection settings</Button>}</div>}
    {pending && <p role="status" className={styles.checked}>Inspecting endpoints from the platform…</p>}
    {stale && <Notice title="Snapshot is stale">These values are more than 30 seconds old. Refresh to get a current observation.</Notice>}
    {problems.length > 0 && <Notice title="Some data is unavailable" tone={problems.some(item => tone(item.value) === 'danger') ? 'error' : 'info'}>{problems.map(item => `${item.name}: ${label(item.value)}`).join(' · ')}. {snapshot?.kubernetes.state !== 'READY' ? <Link href={`/targets/detail/?id=${target.id}&screen=settings`}>Review connection settings</Link> : 'Open diagnostics for the source.'}</Notice>}
    <dl className={styles.metrics} data-stale={stale} aria-label="Current selected-container resources">
      <div className={styles.metric}><dt><Icon name="grid" width={14} height={14} />Ready Pods</dt><dd>{summary?.totalPods != null ? <>{summary.readyPods}<small>/ {summary.totalPods}</small></> : unavailable}</dd></div>
      <div className={styles.metric}><dt><Icon name="chart" width={14} height={14} />CPU usage</dt><dd>{summary?.cpuMillicores != null ? <>{number(summary.cpuMillicores)}<small>mCPU</small></> : unavailable}</dd></div>
      <div className={styles.metric}><dt><Icon name="memory" width={14} height={14} />Memory usage</dt><dd>{summary?.memoryBytes != null ? <>{number(summary.memoryBytes, 1048576)}<small>MiB</small></> : unavailable}</dd></div>
      <div className={styles.metric}><dt><Icon name="refresh" width={14} height={14} />Restarts</dt><dd>{summary?.restarts != null ? number(summary.restarts) : unavailable}</dd></div>
    </dl>
    <div className={styles.statusRow} aria-label="Connection status">
      {statuses.map(({ name, value }) => <span key={name} className={styles.status} data-tone={pending ? 'warning' : tone(value)}>{name === 'Monitoring' ? 'Prometheus' : name === 'Kubernetes' ? 'K8s API' : name}<strong>{pending ? 'Connecting' : label(value)}</strong></span>)}
      <span className={styles.status} data-tone="neutral">Grafana <strong>Health check unavailable</strong></span>
    </div>
    <section className={styles.resourcePanel} aria-labelledby="resource-usage-title">
      <div className={styles.panelHeading}><div><h2 id="resource-usage-title">Resource usage</h2><p className={styles.chartScope}>Selected container · {metric === 'cpuMillicores' ? 'mCPU' : 'MiB'} · {history.length} observation{history.length === 1 ? '' : 's'} this visit</p></div>
        <div className={styles.metricSwitch} role="group" aria-label="Resource chart metric"><button type="button" aria-pressed={metric === 'cpuMillicores'} onClick={() => setMetric('cpuMillicores')}>CPU</button><button type="button" aria-pressed={metric === 'memoryBytes'} onClick={() => setMetric('memoryBytes')}>Memory</button></div>
      </div>
      <ResourceChart history={history} metric={metric} />
      <div className={styles.podsHeading}><h3>Pods</h3><span className={styles.podCount}>{snapshot ? `${snapshot.pods.length} in this snapshot` : 'Not inspected'}</span></div>
      {!!snapshot?.pods.length && <div className={styles.tableWrap} role="region" aria-label="Owned Pod resources" tabIndex={0}><table className={styles.table}><caption>Selected-container resources</caption>
        <thead><tr><th>Pod / readiness</th><th>CPU usage (mCPU)</th><th>Memory usage (MiB)</th><th>Restarts</th><th>Allocation (request / limit)</th><th>Sample</th></tr></thead>
        <tbody>{snapshot.pods.map(pod => { const measured = validUsage(pod.usage, snapshot.collectedAt); return <tr key={pod.name}><th>{pod.name}<small className={styles.readiness} data-ready={pod.ready === true}>{pod.phase ?? 'Unknown'} · {pod.ready == null ? 'Readiness unavailable' : pod.ready ? 'Ready' : 'Not ready'}</small></th>
          <td>{number(measured ? pod.usage.cpuMillicores : null)}</td><td>{number(measured ? pod.usage.memoryBytes : null, 1048576)}</td><td>{number(pod.restarts)}</td>
          <td><small>CPU {number(pod.cpuRequestMillicores)} / {number(pod.cpuLimitMillicores)} mCPU</small><small>Memory {number(pod.memoryRequestBytes, 1048576)} / {number(pod.memoryLimitBytes, 1048576)} MiB</small></td>
          <td>{state(pod.usage.state)}<small>{pod.usage.timestamp ? new Date(pod.usage.timestamp).toLocaleTimeString('en-GB', { timeZone: 'UTC' }) : 'No sample time'} · {pod.usage.window ?? 'No window'}</small></td></tr>; })}</tbody></table></div>}
      {!snapshot?.pods.length && <p className={styles.emptyPods}>{snapshot ? 'No owned selected-container Pods are available in this inspection.' : 'Pod readiness and resources appear after a connection check.'}</p>}
    </section>
    <details className={styles.disclosure}>
      <summary>Connection diagnostics</summary>
      {!snapshot && <p className={styles.checked}>No inspection has run. Saving a connection does not prove that it is reachable.</p>}
      {!!connection?.revision && <p className={styles.chartScope}>Kubernetes endpoint: {connection.kubernetesUrl}</p>}
      <div className={styles.diagnostics}>{checks.map(([name, check]) => check && <article key={name} data-tone={tone(check.state)}><h3>{name}: {label(check.state)}</h3><p>{check.message}</p><small>Source: {check.source} · <time dateTime={check.checkedAt}>{new Date(check.checkedAt).toLocaleString('en-GB', { timeZone: 'UTC' })}</time></small></article>)}</div>
      {snapshot?.workload && <div className={styles.rollout}><span>Replicas: {number(snapshot.workload.readyReplicas)} ready / {number(snapshot.workload.desiredReplicas)} desired / {number(snapshot.workload.availableReplicas)} available</span><span>Updated replicas: {number(snapshot.workload.updatedReplicas)}</span><span>Generation: {number(snapshot.workload.observedGeneration)} observed / {number(snapshot.workload.generation)} desired</span></div>}
    </details>
  </div>;
}
