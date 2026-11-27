'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { ApiError, api, mutate } from '../../lib/api';
import { requestId } from '../../lib/request-id';
import { Button, Notice } from '../../components/form-controls';
import { PageHeading, RecordMetadata, StatusBadge } from '../../components/console-ui';
import guide from '../../components/task-guide.module.css';
import type { Target } from '../targets/types';
import styles from './settings.module.css';
import type { LoadProfile } from './load-profile';
import type { SloPolicy } from './slo-policy';
import { LoadSummary, SloSummary, SummaryRows } from './experiment-summary';
import summary from './experiment-summary.module.css';
import { ExperimentNavigation } from './experiment-navigation';

export type Draft = { id: string; targetId: string; settings: Record<string, number>; revision: number; updatedAt?: string; scenarioId: string | null; scenarioVersion: number | null; executionBounds: Record<string, number | null> | null; loadProfile: LoadProfile | null; sloPolicy: SloPolicy | null };
type Inputs = {
  schemaVersion: string; draftId: string; draftRevision: number; mode: string;
  target: Pick<Target, 'id' | 'name' | 'baseUrl' | 'environment' | 'namespace' | 'deployment' | 'container' | 'revision'>;
  scenario: { script: { scenarioId: string; name: string; version: number; sha256: string; source: string }; approvalId: string; approvedBy: string; approvedAt: string; policyVersion: string; parserVersion: string };
  settings: Record<string, number>; loadUnit: string; executor: string; measurementSeconds: number; sloOperator: string;
  executionBounds?: Record<string, number>; maximumAttemptSeconds?: number; reservedAttemptsPerLoad?: number; loadProfile?: LoadProfile; measurementRate?: number;
  sloPolicy?: SloPolicy;
};
type Preview = { inputs: Inputs; sha256: string };
type Snapshot = Preview & { id: string; createdBy: string; createdAt: string; runtimeCheckRequired: boolean; executionAvailable: boolean };
type StartReadiness = { gate: 'READY' | 'MONITOR_DISABLED' | 'RUNTIME_ADMISSION_UNCONFIGURED'; message: string; existingRunId: string | null };
type StartResponse = { id: string; snapshotId: string; requestKey: string };

function startRequestKey(snapshot: Snapshot) {
  const storageKey = `loadpilot:start:${snapshot.id}:${snapshot.sha256}`;
  const generated = requestId();
  try {
    const stored = localStorage.getItem(storageKey);
    if (stored && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(stored)) return stored;
    localStorage.setItem(storageKey, generated);
  } catch { /* This page can still retry with its in-memory key. */ }
  return generated;
}

function StartExperiment({ snapshot }: { snapshot: Snapshot }) {
  const router = useRouter();
  const [readiness, setReadiness] = useState<StartReadiness>();
  const [checkError, setCheckError] = useState<ApiError | Error>();
  const [retry, setRetry] = useState(0);
  const [acknowledged, setAcknowledged] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const requestKey = useRef<string>(undefined);
  const pending = useRef(false);
  const mounted = useRef(false);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ snapshotId: snapshot.id });
    void api<StartReadiness>(`/api/experiments/runs/start-readiness?${params}`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setReadiness(value); })
      .catch(reason => { if (!controller.signal.aborted) setCheckError(reason); });
    return () => controller.abort();
  }, [snapshot.id, retry]);

  function recheck() {
    setReadiness(undefined); setCheckError(undefined); setRetry(value => value + 1);
  }

  async function start() {
    if (!acknowledged || readiness?.gate !== 'READY' || readiness.existingRunId || pending.current) return;
    pending.current = true;
    setStarting(true); setError('');
    try {
      requestKey.current ??= startRequestKey(snapshot);
      const run = await mutate<StartResponse>('/api/experiments/runs', 'POST', {
        snapshotId: snapshot.id, snapshotHash: snapshot.sha256, requestKey: requestKey.current, acknowledged,
      });
      if (!mounted.current) return;
      if (!run.id || run.snapshotId !== snapshot.id || run.requestKey !== requestKey.current) {
        throw new Error('The platform could not confirm which run was queued. Retry this start request.');
      }
      setReadiness({ ...readiness, existingRunId: run.id });
      router.push(`/experiments/monitor/?run=${encodeURIComponent(run.id)}`);
    } catch (reason) {
      if (!mounted.current) return;
      setError(reason instanceof Error ? reason.message : 'The platform could not confirm the start. Retry this start request.');
      if (reason instanceof ApiError && (reason.status === 409 || reason.code === 'EXECUTION_UNAVAILABLE')) recheck();
    } finally { pending.current = false; setStarting(false); }
  }

  const needsReview = checkError instanceof ApiError && [400, 409].includes(checkError.status);
  const startReady = readiness?.gate === 'READY' && !readiness.existingRunId;
  return <section className={`${summary.section} ${summary.stack}`} aria-labelledby="start-experiment-title">
    <div className={summary.sectionHead}><h3 id="start-experiment-title">Start experiment</h3></div>
    {!readiness && !checkError && <p role="status">Checking whether this snapshot can start…</p>}
    {checkError && <Notice title={needsReview ? 'Fresh input review required' : 'Cannot check execution readiness'} tone="error">{checkError.message}{needsReview && ' Save the draft again, then review and finalize its new revision.'}</Notice>}
    {needsReview && <div><Link className={guide.secondary} href={`/experiments/new/?draft=${encodeURIComponent(snapshot.inputs.draftId)}`}>Review a new draft revision</Link></div>}
    {readiness?.existingRunId && <div><Link className={guide.button} href={`/experiments/monitor/?run=${encodeURIComponent(readiness.existingRunId)}`}>Open run</Link></div>}
    {readiness && !readiness.existingRunId && !startReady && <Notice title={readiness.gate === 'MONITOR_DISABLED' ? 'Execution monitoring is disabled' : 'Runtime admission is unavailable'}>{readiness.message}</Notice>}
    {((checkError && !needsReview) || (readiness && !startReady && !readiness.existingRunId)) && <div><Button variant="secondary" onClick={recheck}>Check again</Button></div>}
    {startReady && <>
      <p>Starting queues these inputs for runtime admission. The worker can launch after the checks pass.</p>
      {error && <Notice title="Start request could not be confirmed" tone="error">{error}</Notice>}
      <label className={summary.acknowledge}><input type="checkbox" checked={acknowledged} disabled={starting} onChange={event => setAcknowledged(event.target.checked)} /> I approve running this exact target, script and effective settings.</label>
      <div className={summary.actions}><Button busy={starting} disabled={!acknowledged} onClick={() => void start()}>{starting ? 'Queuing start…' : error ? 'Retry start' : 'Start experiment'}</Button></div>
    </>}
  </section>;
}

function InputDetails({ inputs, sha256 }: Preview) {
  const { target, scenario, settings, sloPolicy, executionBounds, loadProfile } = inputs;
  const { warmupSeconds, durationSeconds } = settings;
  const ceiling = inputs.sloOperator === '<=' ? '≤' : inputs.sloOperator;
  return <div className={summary.stack}>
    <SloSummary
      percentile={sloPolicy?.latencyPercentile ?? 'P95'}
      latency={sloPolicy?.latencyThresholdMs ?? settings.p95LatencyMs}
      errorRatio={sloPolicy?.maximumErrorRatio ?? settings.maximumErrorRatio}
      ceiling={ceiling}
      throughputKind={sloPolicy?.throughputKind}
      throughput={sloPolicy?.minimumThroughputRps}
      throughputOperator={sloPolicy?.throughputOperator === 'GE' ? '≥' : sloPolicy?.throughputOperator ?? '≥'}
      throughputEmpty={sloPolicy ? 'Not configured' : 'Not recorded'}
      resources={sloPolicy?.resourceConditions}
      resourceEmpty={sloPolicy?.resourceConditions == null ? 'Not recorded' : 'Not configured'}
      httpDefinition={!!sloPolicy}
    />
    <LoadSummary profile={loadProfile} duration={durationSeconds} warmup={warmupSeconds} rows={[
      ['Test mode', inputs.mode === 'SINGLE_RUN' ? 'Single-run profile' : 'Adaptive load configuration'],
      ...(loadProfile ? [
        ['Load shape', loadProfile.shape.toLowerCase()],
        ['Starting rate', loadProfile.startRate === null ? 'Selected per trial' : `${loadProfile.startRate} ${inputs.loadUnit}`],
      ] as [string, string][] : []),
      ['Load bounds', `${settings.minimumRate}–${settings.maximumRate} ${inputs.loadUnit}`],
      ...(inputs.measurementRate !== undefined ? [['Requested plateau', `${inputs.measurementRate} ${inputs.loadUnit}`] as [string, string]] : []),
      ['Duration / warm-up', `${durationSeconds} s total / ${warmupSeconds} s excluded`],
      ['Measurement window', `${warmupSeconds}–${warmupSeconds + inputs.measurementSeconds} s (${inputs.measurementSeconds} s)`],
    ]} />
    <section className={summary.section} aria-label="System and script">
      <div className={summary.sectionHead}><h3>System and script</h3></div>
      <SummaryRows rows={[
        ['System', `${target.name} · ${target.environment} · revision ${target.revision}`],
        ['API URL', target.baseUrl],
        ['Workload', `${target.namespace} / ${target.deployment} / ${target.container}`],
        ['Script', `${scenario.script.name} · version ${scenario.script.version}`],
      ]} />
    </section>
    <details className={summary.more}><summary>Execution limits and budgets</summary>
      <SummaryRows rows={[
        ['Executor per load', inputs.executor],
        [`Repetitions / ${executionBounds ? 'attempt' : 'run'} budget`, `${settings.repetitions} per load / ${settings.maximumRuns} maximum ${executionBounds ? 'attempts' : 'runs'}`],
        ['Total time budget', `${settings.totalBudgetSeconds} s`],
      ]} />
      {executionBounds && <SummaryRows rows={[
        ...(inputs.mode === 'ADAPTIVE_LOAD' ? [['Search precision', `${executionBounds.precisionIterationsPerSecond} ${inputs.loadUnit}`] as [string, string]] : []),
        ['Generator VUs', `${executionBounds.preAllocatedVUs} preallocated / ${executionBounds.maximumVUs} maximum`],
        ['Workers', executionBounds.workerCount],
        ['Generator resources', `${executionBounds.generatorCpuMillicores} mCPU / ${executionBounds.generatorMemoryMiB} MiB`],
        ['Request timeout', `${executionBounds.requestTimeoutMs} ms`],
        ['Startup / graceful stop', `${executionBounds.startupTimeoutSeconds} s / ${executionBounds.gracefulStopSeconds} s`],
        ['Maximum attempts per run', executionBounds.maximumAttemptsPerRun],
        ['Maximum time per attempt', `${inputs.maximumAttemptSeconds} s including startup and graceful stop`],
        ['Attempt allowance per load', `${inputs.reservedAttemptsPerLoad} including repetitions and retries`],
      ]} />}
    </details>
    <details className={summary.more}><summary>Exact saved script source</summary>
      <SummaryRows rows={[
        ['Source approval', `${scenario.approvedBy} · ${new Date(scenario.approvedAt).toLocaleString('en-GB', { timeZone: 'UTC' })} UTC · ${scenario.policyVersion}`],
        ['Script SHA-256', <code>{scenario.script.sha256}</code>],
        ['Input SHA-256', <code>{sha256}</code>],
      ]} />
      <pre className={styles.source}>{scenario.script.source}</pre>
    </details>
    {(!loadProfile || !executionBounds || !sloPolicy) && <Notice title="Legacy input snapshot">Some current profile, limit or SLO choices were not recorded. Open the draft to review it with the current options.</Notice>}
  </div>;
}

export function ExperimentReview({ draft, dirty, onFinalized, onBusyChange }: { draft: Draft; dirty: boolean; onFinalized: (id: string) => void; onBusyChange: (busy: boolean) => void }) {
  const [preview, setPreview] = useState<Preview>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  useEffect(() => { setPreview(undefined); setAcknowledged(false); setError(''); }, [draft.id, draft.revision, dirty]);
  async function review() {
    setBusy(true); onBusyChange(true); setError(''); setPreview(undefined); setAcknowledged(false);
    try {
      const value = await api<Preview>(`/api/experiments/drafts/${draft.id}/review`);
      if (value.inputs.draftRevision !== draft.revision) throw new Error('This draft changed. Reload the saved draft before reviewing inputs.');
      setPreview(value);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot review inputs.'); }
    finally { setBusy(false); onBusyChange(false); }
  }
  async function finalize() {
    if (!preview || !acknowledged || dirty) return;
    setBusy(true); onBusyChange(true); setError('');
    try {
      const value = await mutate<Snapshot>(`/api/experiments/drafts/${draft.id}/finalize`, 'POST', { revision: preview.inputs.draftRevision, reviewHash: preview.sha256, acknowledged });
      onFinalized(value.id);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Cannot finalize inputs.');
      setAcknowledged(false);
      if (reason instanceof ApiError && reason.status === 409) setPreview(undefined);
    } finally { setBusy(false); onBusyChange(false); }
  }
  return <section className={`${styles.form} ${summary.stack}`} aria-labelledby="review-inputs-title">
    <div className={summary.sectionHead}>
      <div><h2 id="review-inputs-title">Review experiment inputs</h2>
        {dirty ? <p role="status">Save your changes before reviewing or finalizing.</p> : <p>Review the saved draft and approved script before finalizing.</p>}
      </div>
      <Button variant="secondary" busy={busy && !preview} disabled={busy || dirty || !draft.scenarioId} onClick={() => void review()}>Review saved inputs</Button>
    </div>
    {error && <Notice title="Input review unavailable" tone="error">{error}</Notice>}
    {preview && <><InputDetails {...preview} />
      <label className={summary.acknowledge}><input type="checkbox" disabled={busy || dirty} checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} /> I reviewed this exact target, script and effective settings.</label>
      <div className={summary.actions}><Button busy={busy} disabled={dirty || !acknowledged} onClick={() => void finalize()}>Finalize input snapshot</Button></div>
    </>}
  </section>;
}

export function SavedExperimentInputs({ id }: { id: string }) {
  const [snapshot, setSnapshot] = useState<Snapshot>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true; setSnapshot(undefined); setError('');
    void api<Snapshot>(`/api/experiments/snapshots/${encodeURIComponent(id)}`).then(value => { if (active) setSnapshot(value); })
      .catch(reason => { if (active) setError(reason.message); });
    return () => { active = false; };
  }, [id, retry]);
  return <>
    <PageHeading title="Input review" back={{ href: '/experiments/review/', label: 'Choose saved inputs' }} actions={snapshot && <>
      <RecordMetadata name={snapshot.inputs.target.name}>
        Finalized by {snapshot.createdBy} · <time dateTime={snapshot.createdAt}>{new Date(snapshot.createdAt).toLocaleString('en-GB', { timeZone: 'UTC' })} UTC</time> · Draft revision {snapshot.inputs.draftRevision}
      </RecordMetadata>
      <Link className={guide.secondary} href={`/experiments/new/?draft=${encodeURIComponent(snapshot.inputs.draftId)}`}>Edit draft</Link>
    </>} />
    <ExperimentNavigation screen="review" />
    <section className={summary.stack} aria-labelledby="finalized-inputs-title">
      <div className={summary.sectionHead}><h2 id="finalized-inputs-title">Finalized experiment inputs</h2>{snapshot && <StatusBadge tone="success">Finalized</StatusBadge>}</div>
      {!snapshot && !error && <p role="status">Loading input snapshot…</p>}
      {error && <><Notice title="Cannot load snapshot" tone="error">{error}</Notice><div><Button onClick={() => setRetry(value => value + 1)}>Try again</Button></div></>}
      {snapshot?.id === id && <><InputDetails {...snapshot} /><StartExperiment key={`${snapshot.id}:${snapshot.sha256}`} snapshot={snapshot} /></>}
    </section>
  </>;
}
