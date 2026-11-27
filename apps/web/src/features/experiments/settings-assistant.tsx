'use client';

import { useRef, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import controls from '../../components/form-controls.module.css';
import { mutate } from '../../lib/api';
import type { LoadProfile } from './load-profile';
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
export type SettingsSnapshot = { current: EffectiveSettings | null; fields: Record<string, string>; revision: string };
type Proposal = { changes: SettingsChange[]; preview: EffectiveSettings | null; errors: string[] };

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
  apply: (changes: SettingsChange[]) => void;
  labels: Readonly<Record<string, string>>;
  disabled: boolean;
}) {
  const [goal, setGoal] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [result, setResult] = useState<{ snapshot: SettingsSnapshot; changes: SettingsChange[] }>();
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
      if (!changes.length) setNotice('The proposed values already match the form.');
      else setResult({ snapshot, changes });
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
    apply(result.changes);
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
        <table className={styles.settingsChanges} aria-label="Proposed settings">
          <thead><tr><th scope="col">Setting</th>
            <th scope="col" aria-label="Current"><span className={styles.fullHeading}>Current</span><span className={styles.shortHeading}>Now</span></th>
            <th scope="col" aria-label="Proposed"><span className={styles.fullHeading}>Proposed</span><span className={styles.shortHeading}>New</span></th>
          </tr></thead>
          <tbody>{result.changes.map(change => <tr key={change.field}>
            <th scope="row">{labels[change.field] ?? change.field}</th>
            <td>{displayValue(change.field, currentValue(result.snapshot, change.field))}</td>
            <td>{displayValue(change.field, change.value)}</td>
          </tr>)}</tbody>
        </table>
        <div className={summary.actions}>
          <Button disabled={disabled} onClick={applyPreview}>Apply to form</Button>
          <Button variant="secondary" onClick={() => { setResult(undefined); disclosure.current?.focus(); }}>Discard preview</Button>
        </div>
      </>}
    </div>
  </details>;
}
