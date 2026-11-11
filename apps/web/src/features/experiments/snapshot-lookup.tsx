'use client';
import { useRef, useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '../../components/form-controls';
import styles from '../../components/feature-workspace.module.css';
import guide from '../../components/task-guide.module.css';
export function SnapshotLookup() {
  const [id, setId] = useState('');
  const [error, setError] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const router = useRouter();
  function open(event: FormEvent) {
    event.preventDefault();
    if (!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id.trim())) { setError('Enter a valid snapshot ID, including its hyphens.'); input.current?.focus(); return; }
    router.push(`/experiments/review/?snapshot=${encodeURIComponent(id.trim())}`);
  }
  return <section className={styles.lookup}><h2>Open saved inputs</h2><p>Review the exact script and parameters in an immutable snapshot. Opening a snapshot does not start a run.</p><form onSubmit={open} noValidate><label className={guide.field}>Input snapshot ID<input ref={input} value={id} onChange={event => { setId(event.target.value); setError(''); }} autoComplete="off" spellCheck={false} placeholder="Paste an input snapshot ID" aria-invalid={!!error} aria-describedby={error ? 'snapshot-id-error' : undefined} /></label>{error && <p id="snapshot-id-error" role="alert">{error}</p>}<div><Button className={guide.button} type="submit">Open input review</Button></div></form></section>;
}
