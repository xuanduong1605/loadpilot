'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import { api, mutate } from '../../lib/api';
import type { Target, TargetPage } from '../targets/types';
import styles from './settings.module.css';
import { ExperimentReview, type Draft } from './experiment-review';
import { LoadProfileEditor, encodeProfile, profileValues } from './load-profile';
import { SloPolicyEditor, encodeSlo, sloValues } from './slo-policy';

const fields = [
  ['minimumRate', 'Minimum load (iterations/s)', 1, 10000, 1],
  ['maximumRate', 'Maximum load (iterations/s)', 1, 10000, 1],
  ['durationSeconds', 'Run duration including warm-up (seconds)', 1, 3600, 1],
  ['warmupSeconds', 'Warm-up excluded from measurement (seconds)', 0, 3599, 1],
  ['p95LatencyMs', 'Maximum p95 latency (milliseconds)', 1, 600000, 1],
  ['maximumErrorRatio', 'Maximum error ratio (0–1)', 0, 1, 0.001],
  ['repetitions', 'Repetitions per tested load', 1, 20, 1],
  ['maximumRuns', 'Maximum total attempts (including retries)', 1, 100, 1],
  ['totalBudgetSeconds', 'Total time budget (seconds)', 1, 86400, 1],
] as const;
const executionFields = [
  ['precisionIterationsPerSecond', 'Search precision (iterations/s)', 1, 10000],
  ['preAllocatedVUs', 'Preallocated virtual users', 1, 1000],
  ['maximumVUs', 'Maximum virtual users', 1, 1000],
  ['workerCount', 'Worker count (single worker supported)', 1, 1],
  ['generatorCpuMillicores', 'Generator CPU limit (millicores)', 50, 4000],
  ['generatorMemoryMiB', 'Generator memory limit (MiB)', 64, 4096],
  ['requestTimeoutMs', 'Per-request timeout (milliseconds)', 1, 120000],
  ['startupTimeoutSeconds', 'Worker startup timeout (seconds)', 1, 300],
  ['gracefulStopSeconds', 'Graceful stop allowance (seconds)', 0, 60],
  ['maximumAttemptsPerRun', 'Maximum attempts per run (including first)', 1, 3],
] as const;

type Review = { valid: boolean; errors: string[]; maximumAttemptSeconds: number; reservedAttemptsPerLoad: number };
type ScenarioPage = { items: { id: string; name: string; latestVersion: number }[]; total: number };
type ScriptVersion = { scenarioId: string; name: string; version: number; sha256: string; approved: boolean };

export function ExperimentSettingsForm({ onFinalized }: { onFinalized: (id: string) => void }) {
  const [step, setStep] = useState(0);
  const stepHeading = useRef<HTMLHeadingElement>(null);
  const stepNames = ['Target & script', 'SLO policy', 'Load & budgets', 'Execution limits', 'Review & save'];
  function goToStep(next: number) { setStep(next); requestAnimationFrame(() => { stepHeading.current?.focus(); stepHeading.current?.scrollIntoView({ block: 'start' }); }); }
  const [review, setReview] = useState<Review>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState<Draft>();
  const [values, setValues] = useState<Record<string, string>>({});
  const [executionValues, setExecutionValues] = useState<Record<string, string>>({});
  const [profile, setProfile] = useState(profileValues());
  const [slo, setSlo] = useState(sloValues());
  const [targetId, setTargetId] = useState('');
  const [targets, setTargets] = useState<TargetPage>();
  const [offset, setOffset] = useState(0);
  const [targetRetry, setTargetRetry] = useState(0);
  const [targetError, setTargetError] = useState('');
  const [saved, setSaved] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [scenarioId, setScenarioId] = useState('');
  const [scenarioVersion, setScenarioVersion] = useState('');
  const [scenarios, setScenarios] = useState<ScenarioPage>();
  const [scenarioOffset, setScenarioOffset] = useState(0);
  const [scenarioRetry, setScenarioRetry] = useState(0);
  const [scenarioError, setScenarioError] = useState('');
  const [script, setScript] = useState<ScriptVersion>();
  const [scriptError, setScriptError] = useState('');
  const [selectedTarget, setSelectedTarget] = useState<Target>();
  const [latestScript, setLatestScript] = useState<ScriptVersion>();
  const [versionLimit, setVersionLimit] = useState(25);
  const listedTarget = targets?.items.find(item => item.id === targetId);
  const listedScript = scenarios?.items.find(item => item.id === scenarioId);
  const targetName = listedTarget?.name ?? (selectedTarget?.id === targetId ? selectedTarget.name : undefined);
  const latest = latestScript?.scenarioId === scenarioId ? latestScript : undefined;
  const scriptName = listedScript?.name ?? latest?.name ?? (script?.scenarioId === scenarioId ? script.name : undefined);
  const newestVersion = Math.max(listedScript?.latestVersion ?? latest?.version ?? 0, Number(scenarioVersion));
  const versions = Array.from({ length: Math.min(versionLimit, newestVersion) }, (_, index) => newestVersion - index);
  useEffect(() => {
    let active = true; setScenarios(undefined); setScenarioError('');
    void api<ScenarioPage>(`/api/scenarios?offset=${scenarioOffset}&limit=12`).then(value => { if (active) setScenarios(value); })
      .catch(reason => { if (active) setScenarioError(reason.message); });
    return () => { active = false; };
  }, [scenarioOffset, scenarioRetry]);
  useEffect(() => {
    let active = true; setScript(undefined); setScriptError('');
    if (scenarioId && Number.isInteger(Number(scenarioVersion)) && Number(scenarioVersion) > 0) {
      void api<ScriptVersion>(`/api/scenarios/${encodeURIComponent(scenarioId)}/versions/${encodeURIComponent(scenarioVersion)}`)
        .then(value => { if (active) setScript(value); }).catch(reason => { if (active) setScriptError(reason.message); });
    }
    return () => { active = false; };
  }, [scenarioId, scenarioVersion]);
  useEffect(() => {
    let active = true;
    setTargets(undefined); setTargetError('');
    void api<TargetPage>(`/api/targets?offset=${offset}&limit=12`)
      .then(value => { if (active) setTargets(value); })
      .catch(reason => { if (active) setTargetError(reason.message); });
    return () => { active = false; };
  }, [offset, targetRetry]);
  useEffect(() => {
    if (!targetId || !targets || listedTarget) return;
    let active = true;
    void api<Target>(`/api/targets/${encodeURIComponent(targetId)}`).then(value => { if (active && value.id === targetId) setSelectedTarget(value); }).catch(() => {});
    return () => { active = false; };
  }, [targetId, targets, listedTarget]);
  useEffect(() => {
    if (!scenarioId || !scenarios || listedScript) return;
    let active = true;
    void api<ScriptVersion>(`/api/scenarios/${encodeURIComponent(scenarioId)}`).then(value => { if (active && value.scenarioId === scenarioId) setLatestScript(value); }).catch(() => {});
    return () => { active = false; };
  }, [scenarioId, scenarios, listedScript]);
  useEffect(() => {
    const id = new URLSearchParams(window.location.search).get('draft');
    if (!id) return;
    let active = true;
    setBusy(true);
    void api<Draft>(`/api/experiments/drafts/${encodeURIComponent(id)}`)
      .then(value => {
        if (!active) return;
        setDraft(value); setTargetId(value.targetId);
        setScenarioId(value.scenarioId ?? ''); setScenarioVersion(value.scenarioVersion ? String(value.scenarioVersion) : ''); setDirty(false);
        setValues(Object.fromEntries(Object.entries(value.settings).map(([key, number]) => [key, String(number)])));
        setExecutionValues(Object.fromEntries(Object.entries(value.executionBounds ?? {}).map(([key, number]) => [key, number == null ? '' : String(number)])));
        setProfile(profileValues(value.loadProfile));
        setSlo(sloValues(value.sloPolicy));
      }).catch(reason => { if (active) setError(reason.message); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (reviewBusy) return;
    const invalid = Array.from(event.currentTarget.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input, select, textarea')).find(control => !control.checkValidity());
    if (invalid) {
      const section = invalid.closest<HTMLElement>('[data-setup-step]');
      setError('Complete the highlighted field before validating or saving.');
      if (section) goToStep(Number(section.dataset.setupStep));
      requestAnimationFrame(() => invalid.reportValidity());
      return;
    }
    const data = new FormData(event.currentTarget);
    const settings = Object.fromEntries(fields.map(([key]) => [key, Number(data.get(key))]));
    const executionBounds = Object.fromEntries(executionFields.map(([key]) => [key, key === 'precisionIterationsPerSecond' && profile.mode === 'SINGLE_RUN' ? null : Number(data.get(key))]));
    const loadProfile = encodeProfile(profile, settings.durationSeconds);
    const sloPolicy = encodeSlo(slo, settings);
    const save = (event.nativeEvent as SubmitEvent).submitter?.getAttribute('value') === 'save';
    setBusy(true); setError(''); setReview(undefined); setSaved(false);
    try {
      if (save) {
        const value = await mutate<Draft>(draft ? `/api/experiments/drafts/${draft.id}` : '/api/experiments/drafts', draft ? 'PUT' : 'POST',
          { targetId, settings, executionBounds, loadProfile, sloPolicy, revision: draft?.revision, scenarioId: scenarioId || null, scenarioVersion: scenarioVersion ? Number(scenarioVersion) : null });
        setDraft(value); setSaved(true); setDirty(false);
        window.history.replaceState(null, '', `/experiments/new/?draft=${encodeURIComponent(value.id)}`);
      } else setReview(await mutate<Review>('/api/experiments/settings/validate-effective', 'POST', { settings, executionBounds, loadProfile, sloPolicy }));
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot validate settings.'); }
    finally { setBusy(false); }
  }
  return <><nav className={styles.steps} aria-label="Setup steps">{stepNames.map((name, index) => <button key={name} type="button" aria-current={step === index ? 'step' : undefined} onClick={() => goToStep(index)}>{index + 1}. {name}</button>)}</nav><form noValidate className={styles.form} onSubmit={event => { if (step !== 4) { event.preventDefault(); goToStep(step + 1); } else void submit(event); }} onChange={() => { setReview(undefined); setError(''); setSaved(false); setDirty(true); }}>
    <h2 ref={stepHeading} tabIndex={-1} className={styles.stepHeading}>{stepNames[step]}</h2>
    <div data-setup-step={0} hidden={step !== 0}>    <fieldset disabled={busy || reviewBusy} className={styles.fields}>
      <legend>Choose a target and approved script</legend>
      <label htmlFor="experiment-system">System</label>
      <select id="experiment-system" value={targetId} onChange={event => setTargetId(event.target.value)}>
        <option value="">Choose a registered system</option>
        {targetId && !listedTarget && <option value={targetId}>{targetName ?? 'Selected system'}</option>}
        {targets?.items.map(target => <option key={target.id} value={target.id}>{target.name} ({target.environment})</option>)}
      </select>
      {!targets && !targetError && <p role="status">Loading systems…</p>}
      {targetError && <><Notice title="Cannot load systems" tone="error">{targetError}</Notice><Button onClick={() => setTargetRetry(value => value + 1)}>Retry systems</Button></>}
      {targets && targets.total > 12 && <div><Button variant="secondary" disabled={!offset} onClick={() => setOffset(value => Math.max(0, value - 12))}>Previous systems</Button>{' '}
        <Button variant="secondary" disabled={offset + 12 >= targets.total} onClick={() => setOffset(value => value + 12)}>Next systems</Button>
        <p>{targets.total ? `Systems ${offset + 1}–${Math.min(offset + 12, targets.total)} of ${targets.total}` : 'Register a system before saving a draft.'}</p></div>}
      <label htmlFor="experiment-scenario">Scenario</label>
      <select id="experiment-scenario" value={scenarioId} onChange={event => {
        setScenarioId(event.target.value); setScenarioVersion(String(scenarios?.items.find(item => item.id === event.target.value)?.latestVersion ?? '')); setVersionLimit(25);
      }}>
        <option value="">Choose a scenario (required for finalization)</option>
        {scenarioId && !listedScript && <option value={scenarioId}>{scriptName ?? 'Selected script'}</option>}
        {scenarios?.items.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
      </select>
      {!scenarios && !scenarioError && <p role="status">Loading scenarios…</p>}
      {scenarioError && <><Notice title="Cannot load scenarios" tone="error">{scenarioError}</Notice><Button onClick={() => setScenarioRetry(value => value + 1)}>Retry scenarios</Button></>}
      {scenarios && scenarios.total > 12 && <div><Button variant="secondary" disabled={!scenarioOffset} onClick={() => setScenarioOffset(value => Math.max(0, value - 12))}>Previous scenarios</Button>{' '}
        <Button variant="secondary" disabled={scenarioOffset + 12 >= scenarios.total} onClick={() => setScenarioOffset(value => value + 12)}>Next scenarios</Button></div>}
      <label htmlFor="experiment-scenario-version">Exact script version</label>
      <select id="experiment-scenario-version" required={!!scenarioId} disabled={!scenarioId} value={scenarioVersion} onChange={event => setScenarioVersion(event.target.value)}>
        <option value="">Choose a version</option>
        {scenarioVersion && !versions.includes(Number(scenarioVersion)) && <option value={scenarioVersion}>Version {scenarioVersion}</option>}
        {versions.map(version => <option key={version} value={version}>Version {version}</option>)}
      </select>
      {newestVersion > versionLimit && <div><Button variant="secondary" onClick={() => setVersionLimit(value => value + 25)}>Show earlier versions</Button></div>}
      {scriptError && <Notice title="Cannot load script version" tone="error">{scriptError}</Notice>}
      {script && <p>{script.name} · {script.approved ? 'Approved saved version' : 'Unapproved saved version'}<br />SHA-256: <code>{script.sha256}</code></p>}
      <a href={scenarioId ? `/scenarios/?id=${encodeURIComponent(scenarioId)}&version=${encodeURIComponent(scenarioVersion)}` : '/scenarios/'}>Edit or approve scenario</a>
    </fieldset>
    </div>
    <div data-setup-step={1} hidden={step !== 1}>    <fieldset disabled={busy || reviewBusy} className={styles.fields}>
      <legend>SLO policy</legend>
      <SloPolicyEditor value={slo} onChange={value => { setSlo(value); setDirty(true); setSaved(false); setReview(undefined); }}
        latencyThreshold={values.p95LatencyMs ?? ''} errorRatio={values.maximumErrorRatio ?? ''}
        onLatency={value => setValues(previous => ({ ...previous, p95LatencyMs: value }))} onError={value => setValues(previous => ({ ...previous, maximumErrorRatio: value }))} />
      {draft && !draft.sloPolicy && <Notice title="SLO review needed">Choose an explicit percentile and throughput condition before creating a new review of this older draft.</Notice>}
      {draft?.sloPolicy && draft.sloPolicy.resourceConditions == null && <Notice title="Resource SLO review needed">Choose target resource conditions or explicitly leave them unconfigured before creating a new review.</Notice>}
    </fieldset>
    </div>
    <div data-setup-step={2} hidden={step !== 2}>    <fieldset disabled={busy || reviewBusy} className={styles.fields}>
      <legend>Load profile and measurement</legend>
      <p>Iterations/s equals HTTP requests/s only when each iteration sends exactly one request.</p>
      <LoadProfileEditor value={profile} onChange={value => { setProfile(value); setDirty(true); setSaved(false); setReview(undefined); }} />
      {draft && !draft.loadProfile && <Notice title="Load profile needed">This older draft has no explicit load profile. Choose and save one before creating a new review.</Notice>}
      <div className={styles.boundFields}>{fields.filter(([key]) => key !== 'p95LatencyMs' && key !== 'maximumErrorRatio').map(([key, label, min, max, step]) => <TextField key={key} id={key} name={key} label={label} type="number" required min={min} max={max} step={step}
        value={values[key] ?? ''} onChange={event => setValues(previous => ({ ...previous, [key]: event.target.value }))} />)}</div>
    </fieldset>
    </div>
    <div data-setup-step={3} hidden={step !== 3}>    <fieldset disabled={busy || reviewBusy} className={styles.fields}>
      <legend>Generator and execution limits</legend>
      <p>Resources apply to the load generator. Startup, shutdown and retries consume the attempt and time budgets.</p>
      <div className={styles.boundFields}>{executionFields.filter(([key]) => key !== 'precisionIterationsPerSecond' || profile.mode !== 'SINGLE_RUN').map(([key, label, min, max]) => <TextField key={key} id={key} name={key} label={label} type="number" required min={min} max={max} step={1}
        value={executionValues[key] ?? ''} onChange={event => setExecutionValues(previous => ({ ...previous, [key]: event.target.value }))} />)}</div>
      {draft && !draft.executionBounds && <Notice title="Execution limits needed">This older draft has no recorded execution limits. Complete these fields and save a new revision before reviewing it.</Notice>}
    </fieldset>
    </div>
    <div hidden={step !== 4}><h3>Review your configuration</h3><dl className={styles.details}><dt>Target</dt><dd>{targetName ?? (targetId ? 'Selected system' : 'Not selected')}</dd><dt>Script</dt><dd>{scriptName && scenarioVersion ? `${scriptName} · version ${scenarioVersion}` : 'Not selected'}</dd>{fields.map(([key, label]) => <div key={key} className={styles.reviewRow}><dt>{label}</dt><dd>{values[key] || 'Not entered'}</dd></div>)}{executionFields.map(([key, label]) => <div key={key} className={styles.reviewRow}><dt>{label}</dt><dd>{executionValues[key] || (key === 'precisionIterationsPerSecond' && profile.mode === 'SINGLE_RUN' ? 'Not applicable' : 'Not entered')}</dd></div>)}</dl>
    <div className={styles.actions}><Button type="submit" busy={busy} disabled={reviewBusy}>Validate settings</Button>
    <Button type="submit" name="action" value="save" disabled={busy || reviewBusy || !targetId}>Save draft</Button>
    <Button disabled title="Runtime checks and execution are not implemented">Start experiment</Button>
    </div>
    </div>
    {error && <Notice title="Validation unavailable" tone="error">{error}</Notice>}
    {saved && <Notice title="Draft saved">Bookmark this page to reopen the saved settings. Saving does not approve or start a load test.</Notice>}
    {draft && <p>Draft revision {draft.revision}. <a href={`/experiments/new/?draft=${encodeURIComponent(draft.id)}`}>Reload saved draft</a></p>}
    {review && (review.valid ? <Notice title="Settings validated">Each attempt has a {review.maximumAttemptSeconds} s ceiling. The budget reserves {review.reservedAttemptsPerLoad} attempts per tested load. Validation does not save or approve inputs.</Notice>
      : <Notice title="Review settings" tone="error">{review.errors.join(' ')}</Notice>)}
    <div className={styles.stepActions}><Button variant="secondary" disabled={step === 0 || busy || reviewBusy} onClick={() => goToStep(step - 1)}>Back</Button><span>Step {step + 1} of {stepNames.length}</span>{step < 4 && <Button disabled={busy || reviewBusy} onClick={() => goToStep(step + 1)}>Continue</Button>}</div>
  </form><div hidden={step !== 4}>{draft && <ExperimentReview draft={draft} dirty={dirty || busy} onFinalized={onFinalized} onBusyChange={setReviewBusy} />}</div></>;
}
