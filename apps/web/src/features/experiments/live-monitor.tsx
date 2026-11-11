'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, mutate } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import { Icon } from '../../components/icon';
import { StatusBadge } from '../../components/console-ui';
import { TelemetryChart, type TelemetryPoint } from './telemetry-chart';
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
  const router = useRouter();
  const runId = query.get('run') ?? '';
  const [input, setInput] = useState(runId);
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
  useEffect(() => { setInput(runId); setRun(undefined); setMetrics(undefined); setHttpPoints([]); setCpuPoints([]); setConfirmStop(false); setStopError(''); setStopping(false); }, [runId]);
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
  function open(event: FormEvent) { event.preventDefault(); if (uuid.test(input.trim())) router.replace(`/experiments/?view=live&run=${encodeURIComponent(input.trim())}`); }
  const httpReady = !error && metrics?.http.state === 'READY' && fresh(metrics.http.collectedAt, 30000) && metrics.http.identity?.runId === run?.id && metrics.http.identity?.attemptId === run?.attemptId && metrics.http.identity?.shardId === run?.shardId && metrics.http.identity?.jobUid === run?.workerUid;
  const cpuReady = !error && metrics?.resources.state === 'READY' && fresh(metrics.resources.collectedAt, 30000) && fresh(metrics.resources.oldestSampleAt, 120000);
  const stale = metrics && (now || Date.now()) - Date.parse(metrics.http.collectedAt) > 30000;
  return <>
    <section className={styles.panel}><div className={styles.panelHead}><h2>Execution session</h2><StatusBadge tone={run?.status === 'RUNNING' ? 'info' : run?.status === 'RECOVERY_REQUIRED' ? 'danger' : 'neutral'}>{run?.status ?? 'No run selected'}</StatusBadge></div><form className={styles.filters} onSubmit={open}>
      <label style={{ flex: '1 1 400px' }}>Run ID<input value={input} onChange={event => setInput(event.target.value)} placeholder="UUID of an existing run" required pattern="[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}" /></label><Button type="submit" disabled={!uuid.test(input.trim())}>Monitor run</Button><Button variant="secondary" disabled={!runId || !uuid.test(runId) || loading} onClick={() => setRetry(value => value + 1)}><Icon name="refresh" width={13} height={13} />Reload</Button>
    </form>{runId && !uuid.test(runId) && <div className={styles.body}><Notice title="Invalid run ID" tone="error">Enter the complete run UUID to monitor execution.</Notice></div>}{error && <div className={styles.body}><Notice title="Cannot update run" tone="error">{error} Previously collected data is not a new observation.</Notice></div>}
      <div className={styles.footnote}><span role="status">{loading ? 'Updating…' : run ? 'Auto-updates for this run · paused while the tab is hidden' : 'Select a run to monitor it. Monitoring does not generate load.'}</span><span>UTC · {stale ? 'Data is stale' : 'Best-effort live / not an SLO result'}</span></div>
    </section>
    {run && <><div className={styles.actions} role="group" aria-label="Run sections">{(['metrics', 'evidence', 'stop'] as const).map(value => <Button key={value} variant="secondary" aria-pressed={panel === value} onClick={() => setPanel(value)}>{value === 'metrics' ? 'Metrics' : value === 'evidence' ? 'Evidence & logs' : 'Stop run'}</Button>)}</div><div hidden={panel !== 'evidence'}><ol className={styles.stepper} aria-label="Expected experiment phases">{[['Warm-up', 'Phase data unavailable'], ['Measuring', 'Phase data unavailable'], ['Applying Candidate', 'Optimization unavailable'], ['Rollback', 'Restoration not verified']].map(([title, subtitle], index) => <li key={title}><span>{index + 1}</span><div>{title}<small>{subtitle}</small></div></li>)}</ol>
    <p className={styles.note}>A RUNNING Job does not establish its phase. Execution, SLO and restoration are verified separately.</p>
    </div><div hidden={panel !== 'metrics'}><dl className={styles.summary} style={{ marginTop: 20 }}><div><dt>Requested load</dt><dd>{number(metrics?.requestedMeasurementRate)}</dd><small>{metrics?.requestedLoadUnit ?? 'Unit unavailable'}</small></div><div><dt>Achieved HTTP rate</dt><dd>{number(httpReady ? metrics?.http.requestsPerSecond : null)}</dd><small>HTTP requests/s · recent window</small></div><div><dt>p95 HTTP duration</dt><dd>{number(httpReady ? metrics?.http.p95LatencyMs : null)}</dd><small>ms · SLO unevaluated</small></div><div><dt>Actual CPU usage</dt><dd>{number(cpuReady ? metrics?.resources.cpuMillicores : null)}</dd><small>millicores · selected container</small></div></dl>
    <div className={styles.grid}>
      <section className={styles.panel}><div className={styles.panelHead}><h2>Requested vs. Achieved RPS</h2><StatusBadge>{httpReady ? 'Latest observation' : 'No data yet'}</StatusBadge></div><TelemetryChart points={error || stale ? [] : httpPoints} unit="HTTP requests/s" /><div className={styles.footnote}>Iterations/s cannot be converted to RPS without the request count per iteration.</div></section>
      <section className={styles.panel}><div className={styles.panelHead}><h2>Allocated vs. Actual CPU</h2><StatusBadge>{cpuReady ? 'Latest observation' : 'No data yet'}</StatusBadge></div><TelemetryChart points={error || !cpuReady ? [] : cpuPoints} unit="millicores" requestedLabel="Allocated CPU request" actualLabel="Actual CPU usage" /><div className={styles.footnote}>CPU requests describe allocation, not consumption or cloud cost.</div></section>
    </div>
    </div><div hidden={panel !== 'evidence'}><div className={styles.grid}><section className={styles.panel}><div className={styles.panelHead}><h2>k6 Job logs</h2><StatusBadge>Log stream unavailable</StatusBadge></div><div className={styles.terminal}><p>No k6 log stream is available to display.</p><p>Logs are not reconstructed from metrics. Job state alone does not establish success.</p>{run?.workerName && <p>Job: {run.workerName}<br />Worker state: {run.workerState ?? 'Not observed'}</p>}</div></section>
      <section className={styles.panel}><div className={styles.panelHead}><h2>Scope & provenance</h2><StatusBadge>UNEVALUATED</StatusBadge></div><div className={styles.body}><dl className={styles.meta}><dt>Run</dt><dd>{run?.id ?? '—'}</dd><dt>Attempt</dt><dd>{run?.attemptId ?? '—'}</dd><dt>Shard</dt><dd>{run?.shardId ?? '—'}</dd><dt>HTTP</dt><dd>{metrics?.http.source ?? 'No source available'}</dd><dt>Window · UTC</dt><dd>{metrics?.http.windowFrom ?? '—'} → {metrics?.http.windowUntil ?? '—'}</dd><dt>CPU</dt><dd>{metrics?.resources.source ?? 'No source available'}</dd><dt>Restoration</dt><dd>No verified evidence</dd></dl></div></section></div>
    </div><div hidden={panel !== 'stop'}><section className={styles.stopbar} aria-labelledby="stop-title"><div><h2 id="stop-title">Stop & restore</h2><p>A stop request does not confirm worker termination. Rollback control is unavailable; restoration remains unverified.</p></div><div className={styles.actions}><Button variant="danger" disabled={!run || terminal(run.status) || stopping} onClick={() => setConfirmStop(true)}><Icon name="stop" width={14} height={14} />Request stop</Button><Button variant="secondary" disabled>Rollback unavailable</Button></div></section>
    {confirmStop && <section className={styles.panel} style={{ marginTop: 16 }} aria-label="Confirm run stop"><div className={styles.body}><h2>Stop this run?</h2><p className={styles.muted}>This request applies only to <code>{run?.id}</code>. Keep monitoring STOPPING until termination is confirmed.</p><div className={styles.actions}><Button variant="danger" busy={stopping} onClick={() => void stop()}>Confirm stop</Button><Button variant="secondary" disabled={stopping} onClick={() => setConfirmStop(false)}>Cancel</Button></div></div></section>}
    {stopError && <Notice title="Stop request not confirmed" tone="error">{stopError} Check the run status before retrying.</Notice>}
    </div></>}
  </>;
}
