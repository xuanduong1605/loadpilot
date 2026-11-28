'use client';

import { useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import controls from '../../components/form-controls.module.css';
import { mutate } from '../../lib/api';
import type { LoadProfile, ProfileValues } from './load-profile';
import { ProfilePreview } from './profile-preview';
import type { SloPolicy } from './slo-policy';
import summary from './experiment-summary.module.css';
import styles from './settings.module.css';

export type EffectiveSettings = {
  settings: Record<string, number>;
  executionBounds: Record<string, number | null>;
  loadProfile: LoadProfile;
  sloPolicy: SloPolicy;
};

export type SettingsChange = { field: string; value: number };
export type SettingsSnapshot = { current: EffectiveSettings | null; fields: Record<string, string>; profile: ProfileValues; revision: string };
type Proposal = { changes: SettingsChange[]; preview: EffectiveSettings | null; errors: string[]; loadProfile?: LoadProfile };

function currentValue(snapshot: SettingsSnapshot, field: string) {
  const value = snapshot.fields[field];
  return value ? Number(field === 'latencyPercentile' ? value.slice(1) : value) : undefined;
}

function displayValue(field: string, value: number | null | undefined) {
  if (value == null) return 'Not set';
  return field === 'latencyPercentile' ? `p${value}` : String(value);
}

export function SettingsAssistant({ read, apply, labels, disabled }: {
  read: () => SettingsSnapshot;
  apply: (changes: SettingsChange[], profile?: LoadProfile) => void;
  labels: Readonly<Record<string, string>>;
  disabled: boolean;
}) {
  const [goal, setGoal] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [result, setResult] = useState<{ snapshot: SettingsSnapshot; changes: SettingsChange[]; stageDuration?: number;
    loadProfile?: LoadProfile; warmupSeconds?: number }>();
  const disclosure = useRef<HTMLElement>(null);

  async function requestPreview() {
    const snapshot = read();
    setError(''); setNotice(''); setResult(undefined);
    setBusy(true);
    try {
      const proposal = await mutate<Proposal>('/api/assistant/settings-draft', 'POST', { goal: goal.trim(), current: snapshot.current });
      if (proposal.errors.length || !proposal.changes.length || (snapshot.current && !proposal.preview)) {
        setError(proposal.errors.join(' ') || 'Gemini did not return a settings preview.');
        return;
      }
      const changes = proposal.changes.filter(change => change.value !== currentValue(snapshot, change.field));
      if (!changes.length && !proposal.loadProfile) setNotice('The proposed values already match the form.');
      else {
        const before = snapshot.current?.loadProfile;
        const after = proposal.preview?.loadProfile;
        const stageDuration = !proposal.loadProfile && before?.shape === 'STEADY' && before.stages.length === 1 && after?.stages.length === 1
          && before.stages[0].durationSeconds !== after.stages[0].durationSeconds ? after.stages[0].durationSeconds : undefined;
        setResult({ snapshot, changes, stageDuration, loadProfile: proposal.loadProfile,
          warmupSeconds: proposal.changes.find(change => change.field === 'warmupSeconds')?.value });
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'AI settings suggestions are unavailable.');
    } finally {
      setBusy(false);
    }
  }

  function applyPreview() {
    if (!result) return;
    if (read().revision !== result.snapshot.revision) {
      setResult(undefined);
      setError('The form changed after this preview. Request a new preview to review the current values.');
      return;
    }
    apply(result.changes, result.loadProfile);
    setResult(undefined);
    setNotice(result.snapshot.current ? 'Changes applied to the form. Review and save the draft when ready.'
      : 'Changes applied to the form. Complete the remaining settings before validating or saving.');
    disclosure.current?.focus();
  }

  return <details className={summary.more}>
    <summary ref={disclosure}>Suggest settings with Gemini</summary>
    <div className={styles.settingsAssistant}>
      <div className={controls.field}>
        <label htmlFor="settings-goal">Describe your goal or changes</label>
        <textarea id="settings-goal" className={controls.input} rows={3} maxLength={2000} value={goal} readOnly={busy || disabled}
          placeholder="Example: Set the maximum load to 20 iterations/s and p99 to 175 ms."
          aria-describedby="settings-goal-hint" onChange={event => {
            event.stopPropagation(); setGoal(event.target.value); setResult(undefined); setError(''); setNotice('');
          }} />
        <span id="settings-goal-hint" className={controls.hint}>Gemini receives your goal text. Keep credentials and private URLs out of it.</span>
        <details className={summary.disclosure}>
          <summary>Load profile example</summary>
          <p>Start at 1 iteration/s. Ramp to 5 iterations/s over 5 seconds, then hold at 5 for 25 seconds. Warm up for 5 seconds and measure for 25 seconds.</p>
        </details>
      </div>
      <div className={summary.actions}>
        <Button variant="secondary" busy={busy} disabled={disabled || !goal.trim()} onClick={() => void requestPreview()}>
          {busy ? 'Preparing preview…' : 'Preview changes'}
        </Button>
      </div>
      {error && <Notice title="Settings preview unavailable" tone="error">{error}</Notice>}
      {notice && <p role="status">{notice}</p>}
      {result && <>
        {!result.snapshot.current && <p>Review the proposed values. Complete the remaining settings to validate the full form.</p>}
        {result.stageDuration !== undefined && <p>The steady stage uses the run duration. Both will be {result.stageDuration} seconds.</p>}
        {result.loadProfile && <section className={styles.proposedProfile} aria-label="Proposed load profile">
          <h3>Proposed {result.loadProfile.shape.toLowerCase()} profile</h3>
          <p>Single run starting at {result.loadProfile.startRate} iterations/s.</p>
          {result.snapshot.profile.mode === 'ADAPTIVE_LOAD' && <p>This replaces adaptive trials and their search precision with one fixed profile.</p>}
          {result.warmupSeconds !== undefined && <ProfilePreview profile={result.loadProfile} warmupSeconds={result.warmupSeconds}
            durationSeconds={result.loadProfile.stages.reduce((total, stage) => total + stage.durationSeconds, 0)} />}
          <ol aria-label="Proposed load stages">{result.loadProfile.stages.map((stage, index) =>
            <li key={index}>{stage.durationSeconds} s to {stage.targetRate} iterations/s</li>)}</ol>
        </section>}
        {result.changes.length > 0 && <table className={styles.settingsChanges} aria-label="Proposed settings">
          <thead><tr><th scope="col">Setting</th>
            <th scope="col" aria-label="Current"><span className={styles.fullHeading}>Current</span><span className={styles.shortHeading}>Now</span></th>
            <th scope="col" aria-label="Proposed"><span className={styles.fullHeading}>Proposed</span><span className={styles.shortHeading}>New</span></th>
          </tr></thead>
          <tbody>{result.changes.map(change => <tr key={change.field}>
            <th scope="row">{labels[change.field] ?? change.field}</th>
            <td>{displayValue(change.field, currentValue(result.snapshot, change.field))}</td>
            <td>{displayValue(change.field, change.value)}</td>
          </tr>)}</tbody>
        </table>}
        <div className={summary.actions}>
          <Button disabled={disabled} onClick={applyPreview}>Apply to form</Button>
          <Button variant="secondary" onClick={() => { setResult(undefined); disclosure.current?.focus(); }}>Discard preview</Button>
        </div>
      </>}
    </div>
  </details>;
}
