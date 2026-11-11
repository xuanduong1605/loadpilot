'use client';
import { useRef, useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '../../components/form-controls';
import guide from '../../components/task-guide.module.css';
import styles from '../../components/feature-workspace.module.css';
export function ExperimentHistory() {
  const [runId, setRunId] = useState('');
  const [error, setError] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const router = useRouter();
  function open(event: FormEvent) {
    event.preventDefault(); const id = runId.trim();
    if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id)) { setError('Enter a valid run ID, including its hyphens.'); input.current?.focus(); return; }
    router.push('/experiments/monitor/?run=' + encodeURIComponent(id));
  }
  return <section className={styles.lookup} aria-labelledby="experiment-history-title"><h2 id="experiment-history-title">Open a run</h2><p>Automatic run history is unavailable. Enter a known run ID to inspect its execution and available observations.</p><form onSubmit={open} noValidate><label className={guide.field}>Run ID<input ref={input} value={runId} onChange={event => { setRunId(event.target.value); setError(''); }} autoComplete="off" spellCheck={false} placeholder="Paste a run ID" aria-invalid={!!error} aria-describedby={error ? 'run-id-error' : undefined} /></label>{error && <p id="run-id-error" role="alert">{error}</p>}<div><Button className={guide.button} type="submit">Open run</Button></div></form><p className={guide.hint} style={{ marginTop: 28 }}>Execution, SLO verdict and restoration are separate facts. A completed job does not establish an SLO pass.</p></section>;
}
