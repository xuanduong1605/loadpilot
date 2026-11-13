'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { api, mutate } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import { Icon } from '../../components/icon';
import { StatusBadge } from '../../components/console-ui';
import { TelemetryChart, type TelemetryPoint } from './telemetry-chart';
import { ExperimentRecords } from './experiment-records';
import styles from '../../components/console-ui.module.css';

type Run = { id: string; status: string; revision: number; targetId: string; snapshotId: string; attemptId: string; shardId: string; workerName: string; workerUid: string | null; workerState: string | null; verdict: string; deadlineAt: string; operations: { id: string; kind: string; status: string }[] };
type Metric = { runId: string; runRevision: number; status: string; verdict: string; pollAfterSeconds: number; requestedLoadUnit: string; requestedMeasurementRate: number | null;
  resources: { state: string; message: string; collectedAt: string; oldestSampleAt: string | null; newestSampleAt: string | null; cpuMillicores: number | null; memoryBytes: number | null; source: string; namespace: string; deployment: string; container: string; pods: { cpuRequestMillicores: number | null }[] };
  http: { state: string; message: string; requestsPerSecond: number | null; p95LatencyMs: number | null; errorRatio: number | null; collectedAt: string; windowFrom: string | null; windowUntil: string | null; source: string; coverage: string; identity: { runId: string; attemptId: string; shardId: string; jobUid: string } | null } };
const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const terminal = (status: string) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status);
const number = (value: number | null | undefined, unit = '') => value == null || !Number.isFinite(value) ? '—' : `${value.toLocaleString('en', { maximumFractionDigits: 2 })}${unit}`;
const fresh = (at: string | null, age: number) => at !== null && Number.isFinite(Date.parse(at)) && Date.now() - Date.parse(at) <= age && Date.parse(at) <= Date.now() + 5000;

export function LiveMonitor() {
  const [panel, setPanel] = useState<'metrics' | 'evidence' | 'stop'>('metrics');
  const query = useSearchParams();
  const runId = query.get('run') ?? '';
  const [run, setRun] = useState<Run>();
  const [metrics, setMetrics] = useState<Metric>();
  const [httpPoints, setHttpPoints] = useState<TelemetryPoint[]>([]);
  const [cpuPoints, setCpuPoints] = useState<TelemetryPoint[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [retry, setRetry] = useState(0);
  const [confirmStop, setConfirmStop] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [stopError, setStopError] = useState('');
  const [now, setNow] = useState(0);
  const currentId = useRef(runId); currentId.current = runId;
  useEffect(() => { setRun(undefined); setMetrics(undefined); setHttpPoints([]); setCpuPoints([]); setError(''); setConfirmStop(false); setStopError(''); setStopping(false); }, [runId]);
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 5000); return () => window.clearInterval(timer); }, []);
  useEffect(() => {
    if (!runId || !uuid.test(runId)) return;
    let active = true; let timer: ReturnType<typeof setTimeout>; const controller = new AbortController();
    async function load() {
      if (document.visibilityState === 'hidden') { timer = setTimeout(load, 5000); return; }
      setLoading(true);
      let delay = 5000;
      try {
        const current = await api<Run>(`/api/experiments/runs/${runId}`, { signal: controller.signal });
        const observed = await api<Metric>(`/api/experiments/runs/${runId}/metrics`, { signal: controller.signal });
        if (!active) return;
        if (current.id !== runId || observed.runId !== runId || observed.runRevision !== current.revision || observed.status !== current.status) throw new Error('The run changed while loading. These metrics cannot be attributed to its current state. Reload to try again.');
        setRun(current); setMetrics(observed); setError(''); setNow(Date.now());
        const http = observed.http;
        const httpValid = http.state === 'READY' && http.identity?.runId === current.id && http.identity.attemptId === current.attemptId && http.identity.shardId === current.shardId && http.identity.jobUid === current.workerUid && fresh(http.collectedAt, 30000);
        const resource = observed.resources;
        const cpuValid = resource.state === 'READY' && fresh(resource.collectedAt, 30000) && fresh(resource.oldestSampleAt, 120000);
        const requestedRps = ['requests/s', 'HTTP_REQUESTS_PER_SECOND'].includes(observed.requestedLoadUnit) ? observed.requestedMeasurementRate : null;
        const allocation = cpuValid && resource.pods.length > 0 && resource.pods.every(pod => pod.cpuRequestMillicores !== null && Number.isFinite(pod.cpuRequestMillicores) && pod.cpuRequestMillicores >= 0) ? resource.pods.reduce((sum, pod) => sum + pod.cpuRequestMillicores!, 0) : null;
        const nonnegative = (value: number | null) => value !== null && Number.isFinite(value) && value >= 0 ? value : null;
        const append = (previous: TelemetryPoint[], point: TelemetryPoint) => previous.length && Date.parse(previous[previous.length - 1].at) >= Date.parse(point.at) ? previous : [...previous.slice(-59), point];
        if (fresh(http.collectedAt, 30000)) setHttpPoints(previous => append(previous, { at: http.collectedAt, requested: httpValid ? nonnegative(requestedRps) : null, actual: httpValid ? nonnegative(http.requestsPerSecond) : null }));
        if (fresh(resource.collectedAt, 30000)) setCpuPoints(previous => append(previous, { at: resource.collectedAt, requested: allocation, actual: cpuValid ? nonnegative(resource.cpuMillicores) : null }));
        if (terminal(current.status) || observed.pollAfterSeconds === 0) delay = 0;
        else delay = Math.max(5, Math.min(60, observed.pollAfterSeconds || 5)) * 1000;
      } catch (reason) { if (active) { setError(reason instanceof Error ? reason.message : 'Cannot load run.'); setMetrics(undefined); } }
      finally { if (active) { setLoading(false); if (delay) timer = setTimeout(load, delay); } }
    }
    void load(); return () => { active = false; clearTimeout(timer); controller.abort(); };
  }, [runId, retry]);
  async function stop() {
    if (!run || !confirmStop || stopping) return;
    setStopping(true); setStopError('');
    try { const value = await mutate<Run>(`/api/experiments/runs/${run.id}/stop`, 'POST', {}); if (currentId.current !== run.id) return; if (value.id !== run.id) throw new Error('Could not verify the stop response for this run.'); setRun(value); setMetrics(undefined); setConfirmStop(false); setRetry(value => value + 1); }
    catch (reason) { if (currentId.current === run.id) setStopError(reason instanceof Error ? reason.message : 'Could not send the stop request.'); }
    finally { if (currentId.current === run.id) setStopping(false); }
  }
  const httpReady = !error && metrics?.http.state === 'READY' && fresh(metrics.http.collectedAt, 30000) && metrics.http.identity?.runId === run?.id && metrics.http.identity?.attemptId === run?.attemptId && metrics.http.identity?.shardId === run?.shardId && metrics.http.identity?.jobUid === run?.workerUid;
  const cpuReady = !error && metrics?.resources.state === 'READY' && fresh(metrics.resources.collectedAt, 30000) && fresh(metrics.resources.oldestSampleAt, 120000);
  const stale = metrics && (now || Date.now()) - Date.parse(metrics.http.collectedAt) > 30000;
  const requestedRps = metrics && ['requests/s', 'HTTP_REQUESTS_PER_SECOND'].includes(metrics.requestedLoadUnit);
  if (!uuid.test(runId)) return <>
    {runId && <Notice title="Cannot open this run" tone="error">Choose a run from the list below.</Notice>}
    <ExperimentRecords kind="RUNS" title="Choose a run" />
  </>;
  return <>
    <div className={styles.monitorToolbar}>
      {run && <div className={styles.actions} role="group" aria-label="Run sections">{(['metrics', 'evidence', 'stop'] as const).map(value => <Button key={value} variant="secondary" aria-pressed={panel === value} onClick={() => setPanel(value)}>{value === 'metrics' ? 'Metrics' : value === 'evidence' ? 'Evidence' : 'Stop run'}</Button>)}</div>}
      <div className={styles.actions}>
        <span role="status"><StatusBadge tone={run?.status === 'RUNNING' ? 'info' : run && ['FAILED', 'RECOVERY_REQUIRED'].includes(run.status) ? 'danger' : 'neutral'}>{run?.status ?? 'Awaiting run status'}</StatusBadge></span>
        <Button variant="secondary" disabled={loading} onClick={() => setRetry(value => value + 1)}><Icon name="refresh" width={13} height={13} />Reload</Button>
      </div>
    </div>
    {error && <Notice title="Cannot update run" tone="error">{error}</Notice>}
    {run && <>
      <div hidden={panel !== 'metrics'}>
        {stale && <Notice title="HTTP observations are stale">A current measurement is unavailable.</Notice>}
        <dl className={styles.summary}><div><dt>Requested load</dt><dd>{number(metrics?.requestedMeasurementRate)}</dd><small>{metrics?.requestedLoadUnit ?? 'Unit unavailable'}</small></div><div><dt>Achieved HTTP rate</dt><dd>{number(httpReady ? metrics?.http.requestsPerSecond : null)}</dd><small>HTTP requests/s</small></div><div><dt>p95 HTTP duration</dt><dd>{number(httpReady ? metrics?.http.p95LatencyMs : null)}</dd><small>ms</small></div><div><dt>CPU usage</dt><dd>{number(cpuReady ? metrics?.resources.cpuMillicores : null)}</dd><small>millicores · selected container</small></div></dl>
        <div className={styles.grid}>
          <section className={styles.panel}><div className={styles.panelHead}><h2>HTTP throughput</h2></div><TelemetryChart points={error || stale ? [] : httpPoints} unit="HTTP requests/s" requestedLabel={requestedRps ? 'Requested' : null} /></section>
          <section className={styles.panel}><div className={styles.panelHead}><h2>CPU usage</h2></div><TelemetryChart points={error || !cpuReady ? [] : cpuPoints} unit="millicores" requestedLabel="CPU request" actualLabel="Measured usage" /></section>
        </div>
      </div>
      <div hidden={panel !== 'evidence'}>
        <section className={styles.panel}>
          <div className={styles.panelHead}><h2>Run evidence</h2><Link href={`/experiments/review/?snapshot=${encodeURIComponent(run.snapshotId)}`}>Open saved inputs</Link></div>
          <div className={styles.body}><dl className={styles.meta}><dt>Worker</dt><dd>{run.workerName} · {run.workerState ?? 'Not observed'}</dd><dt>HTTP source</dt><dd>{metrics?.http.source ?? 'Unavailable'}</dd><dt>Window (UTC)</dt><dd>{metrics?.http.windowFrom && metrics.http.windowUntil ? `${metrics.http.windowFrom} → ${metrics.http.windowUntil}` : 'Unavailable'}</dd><dt>CPU source</dt><dd>{metrics?.resources.source ?? 'Unavailable'}</dd><dt>SLO verdict</dt><dd>{run.verdict}</dd><dt>Restoration</dt><dd>Unverified</dd></dl></div>
          <div className={styles.body}><details><summary>Run identifiers</summary><dl className={styles.meta}><dt>Run</dt><dd>{run.id}</dd><dt>Attempt</dt><dd>{run.attemptId}</dd><dt>Shard</dt><dd>{run.shardId}</dd></dl></details></div>
        </section>
      </div>
      <div hidden={panel !== 'stop'}>
        <section className={styles.stopbar} aria-labelledby="stop-title">
          <div><h2 id="stop-title">{confirmStop ? 'Stop this run?' : 'Stop run'}</h2><p>{terminal(run.status) ? 'This run has ended.' : 'Keep monitoring until worker termination is confirmed.'}</p></div>
          <div className={styles.actions}>{confirmStop ? <><Button variant="danger" busy={stopping} onClick={() => void stop()}>Confirm stop</Button><Button variant="secondary" disabled={stopping} onClick={() => setConfirmStop(false)}>Cancel</Button></> : <Button variant="danger" disabled={terminal(run.status) || stopping} onClick={() => setConfirmStop(true)}><Icon name="stop" width={14} height={14} />Request stop</Button>}</div>
        </section>
        {stopError && <Notice title="Stop request not confirmed" tone="error">{stopError}</Notice>}
      </div>
    </>}
  </>;
}
