'use client';

import { useEffect, useState } from 'react';
import { ApiError, api, mutate } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import type { Target } from '../targets/types';
import styles from './settings.module.css';
import type { LoadProfile } from './load-profile';
import { ProfilePreview } from './profile-preview';
import type { SloPolicy } from './slo-policy';

export type Draft = { id: string; targetId: string; settings: Record<string, number>; revision: number; scenarioId: string | null; scenarioVersion: number | null; executionBounds: Record<string, number | null> | null; loadProfile: LoadProfile | null; sloPolicy: SloPolicy | null };
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

function InputDetails({ inputs, sha256 }: Preview) {
  const { target, scenario, settings } = inputs;
  return <>
    <dl className={styles.details}>
      <dt>System</dt><dd>{target.name} · {target.environment} · revision {target.revision}</dd>
      <dt>API URL</dt><dd>{target.baseUrl}</dd>
      <dt>Workload</dt><dd>{target.namespace} / {target.deployment} / {target.container}</dd>
      <dt>Script</dt><dd>{scenario.script.name} · version {scenario.script.version}</dd>
      <dt>Script SHA-256</dt><dd><code>{scenario.script.sha256}</code></dd>
      <dt>Source approval</dt><dd>{scenario.approvedBy} · {new Date(scenario.approvedAt).toLocaleString('en-GB', { timeZone: 'UTC' })} · {scenario.policyVersion}</dd>
      <dt>Load bounds</dt><dd>{settings.minimumRate}–{settings.maximumRate} {inputs.loadUnit}</dd>
      <dt>Mode</dt><dd>{inputs.mode === 'SINGLE_RUN' ? 'Single-run profile' : 'Adaptive load configuration'}</dd>
      {inputs.loadProfile && <><dt>Load shape</dt><dd>{inputs.loadProfile.shape}</dd><dt>Starting rate</dt><dd>{inputs.loadProfile.startRate === null ? 'Selected per adaptive trial' : `${inputs.loadProfile.startRate} iterations/s`}</dd></>}
      <dt>Executor per load</dt><dd>{inputs.executor}</dd>
      <dt>Duration / warm-up</dt><dd>{settings.durationSeconds} s total / {settings.warmupSeconds} s excluded</dd>
      <dt>Measurement window</dt><dd>{settings.warmupSeconds}–{settings.warmupSeconds + inputs.measurementSeconds} s ({inputs.measurementSeconds} s)</dd>
      {inputs.measurementRate !== undefined && <><dt>Requested plateau rate</dt><dd>{inputs.measurementRate} iterations/s</dd></>}
      <dt>{inputs.sloPolicy?.latencyPercentile.toLowerCase() ?? 'p95'} latency SLO</dt><dd>{inputs.sloOperator} {inputs.sloPolicy?.latencyThresholdMs ?? settings.p95LatencyMs} ms</dd>
      <dt>Error ratio SLO</dt><dd>{inputs.sloOperator} {inputs.sloPolicy?.maximumErrorRatio ?? settings.maximumErrorRatio} ({Number(((inputs.sloPolicy?.maximumErrorRatio ?? settings.maximumErrorRatio) * 100).toPrecision(6))}%)</dd>
      <dt>Throughput SLO</dt><dd>{!inputs.sloPolicy ? 'Not recorded in this legacy snapshot' : inputs.sloPolicy.throughputKind ? `>= ${inputs.sloPolicy.minimumThroughputRps} ${inputs.sloPolicy.throughputKind === 'TOTAL' ? 'total' : 'successful'} HTTP requests/s` : 'Not configured'}</dd>
      <dt>Target resource SLO</dt><dd>{inputs.sloPolicy?.resourceConditions == null ? 'Not recorded in this legacy snapshot' : !inputs.sloPolicy.resourceConditions.length ? 'Not configured' : inputs.sloPolicy.resourceConditions.map(item => <p key={item.metric}>{item.metric === 'CPU_USAGE_MILLICORES' ? 'CPU usage' : 'Memory working set'} &lt;= {item.threshold} {item.metric === 'CPU_USAGE_MILLICORES' ? 'millicores' : 'MiB'}; maximum of the selected container’s usage summed across all Deployment pods during the measurement window.</p>)}</dd>
      {inputs.sloPolicy && <><dt>HTTP outcome definition</dt><dd>Success: HTTP 200–399 without transport errors. Script checks are separate.</dd></>}
      <dt>Repetitions / {inputs.executionBounds ? 'attempt' : 'run'} budget</dt><dd>{settings.repetitions} per load / {settings.maximumRuns} maximum {inputs.executionBounds ? 'attempts' : 'runs'}</dd>
      <dt>Total time budget</dt><dd>{settings.totalBudgetSeconds} s</dd>
      {inputs.executionBounds && <>
        {inputs.mode === 'ADAPTIVE_LOAD' && <><dt>Search precision</dt><dd>{inputs.executionBounds.precisionIterationsPerSecond} iterations/s</dd></>}
        <dt>Generator VUs</dt><dd>{inputs.executionBounds.preAllocatedVUs} preallocated / {inputs.executionBounds.maximumVUs} maximum</dd>
        <dt>Workers</dt><dd>{inputs.executionBounds.workerCount}</dd>
        <dt>Generator resources</dt><dd>{inputs.executionBounds.generatorCpuMillicores} millicores / {inputs.executionBounds.generatorMemoryMiB} MiB</dd>
        <dt>Request timeout</dt><dd>{inputs.executionBounds.requestTimeoutMs} ms</dd>
        <dt>Startup / graceful stop</dt><dd>{inputs.executionBounds.startupTimeoutSeconds} s / {inputs.executionBounds.gracefulStopSeconds} s</dd>
        <dt>Maximum attempts per run</dt><dd>{inputs.executionBounds.maximumAttemptsPerRun}</dd>
        <dt>Maximum time per attempt</dt><dd>{inputs.maximumAttemptSeconds} s including startup and graceful stop</dd>
        <dt>Attempt allowance per load</dt><dd>{inputs.reservedAttemptsPerLoad} including repetitions and retries</dd>
      </>}
      <dt>Input SHA-256</dt><dd><code>{sha256}</code></dd>
    </dl>
    {inputs.loadProfile?.mode === 'SINGLE_RUN' && <ProfilePreview profile={inputs.loadProfile} durationSeconds={settings.durationSeconds} warmupSeconds={settings.warmupSeconds} />}
    {inputs.loadProfile && inputs.loadProfile.stages.length > 0 && <><h3>Load stages</h3><ol>{inputs.loadProfile.stages.map((stage, index) => <li key={index}>{stage.durationSeconds} s to {stage.targetRate} iterations/s</li>)}</ol><p>Each stage ramps from the preceding target; matching rates hold a plateau. Only the declared measurement window is used for SLO evaluation.</p></>}
    {(!inputs.loadProfile || !inputs.executionBounds || !inputs.sloPolicy) && <Notice title="Legacy input snapshot">Some current profile, limit or SLO choices were not recorded in this older snapshot. Open its draft and explicitly complete them for a new review.</Notice>}
    <details><summary>Exact saved script source</summary><pre className={styles.source}>{scenario.script.source}</pre></details>
    <p>Iterations/s may differ from HTTP requests/s. Missing required measurement evidence must yield Insufficient data. Finalization records these inputs; it does not confirm connectivity, runtime compatibility or execution readiness.</p>
  </>;
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
  return <section className={styles.form} aria-labelledby="review-inputs-title">
    <h2 id="review-inputs-title">Review experiment inputs</h2>
    {!dirty && <p>Choose an approved script version before reviewing.</p>}
    <Button variant="secondary" disabled={busy || dirty || !draft.scenarioId} onClick={() => void review()}>Review saved inputs</Button>
    {dirty && <p role="status">Save your changes before reviewing or finalizing.</p>}
    {error && <Notice title="Input review unavailable" tone="error">{error}</Notice>}
    {preview && <><InputDetails {...preview} />
      <label><input type="checkbox" disabled={busy || dirty} checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} /> I reviewed this exact target, script and effective settings.</label>
      <div className={styles.actions}><Button busy={busy} disabled={dirty || !acknowledged} onClick={() => void finalize()}>Finalize input snapshot</Button></div>
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
  return <section className={styles.form} aria-labelledby="finalized-inputs-title">
    <h2 id="finalized-inputs-title">Finalized experiment inputs</h2>
    {!snapshot && !error && <p role="status">Loading input snapshot…</p>}
    {error && <><Notice title="Cannot load snapshot" tone="error">{error}</Notice><Button onClick={() => setRetry(value => value + 1)}>Try again</Button></>}
    {snapshot && <><Notice title="Input snapshot saved">These inputs are immutable. Runtime and execution safety checks are still required before a load test can start.</Notice>
      <p>Finalized by {snapshot.createdBy} · {new Date(snapshot.createdAt).toLocaleString('en-GB', { timeZone: 'UTC' })}</p>
      <InputDetails {...snapshot} />
      <div className={styles.actions}><Button disabled>Start experiment</Button><a href={`/experiments/new/?draft=${encodeURIComponent(snapshot.inputs.draftId)}`}>Open editable draft</a><a href="/experiments/new/">New draft</a></div>
    </>}
  </section>;
}
