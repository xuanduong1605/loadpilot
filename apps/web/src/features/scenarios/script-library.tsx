'use client';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import guide from '../../components/task-guide.module.css';
import styles from '../../components/feature-workspace.module.css';
type Scripts = { items: { id: string; name: string; latestVersion: number }[]; total: number };
export function ScriptLibrary() {
  const [data, setData] = useState<Scripts>();
  const [offset, setOffset] = useState(0);
  const [retry, setRetry] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true; const controller = new AbortController(); setData(undefined); setError('');
    void api<Scripts>('/api/scenarios?offset=' + offset + '&limit=12', { signal: controller.signal }).then(value => { if (active) setData(value); }).catch(reason => { if (active) setError(reason instanceof Error ? reason.message : 'Cannot load saved scripts.'); });
    return () => { active = false; controller.abort(); };
  }, [offset, retry]);
  return <>
    <div className={styles.heading}><div><h1>Scripts</h1><p>Open a saved script to edit, check or approve its exact version.</p></div><Link href="/scenarios/editor/" className={guide.button}>New script</Link></div>
    {!data && !error && <p role="status">Loading saved scripts…</p>}
    {error && <><Notice title="Cannot load scripts" tone="error">{error}</Notice><Button onClick={() => setRetry(value => value + 1)}>Retry list</Button></>}
    {data && <>
      {data.items.length ? <ul className={styles.library}>{data.items.map(item => <li key={item.id}><Link href={'/scenarios/editor/?id=' + encodeURIComponent(item.id) + '&version=' + item.latestVersion}><strong>{item.name}</strong><span>Version {item.latestVersion}</span></Link></li>)}</ul> : <section className={styles.quiet}><h2>{offset ? 'No scripts on this page' : 'Write your first k6 script'}</h2><p>Save JavaScript source, check it and approve the exact version before using it in an experiment.</p><Link href="/scenarios/editor/">Create a script</Link></section>}
      <div className={styles.actions}><Button variant="secondary" disabled={!offset} onClick={() => setOffset(value => Math.max(0, value - 12))}>Previous</Button><span>{data.total} saved · Page {Math.floor(offset / 12) + 1}</span><Button variant="secondary" disabled={offset + 12 >= data.total} onClick={() => setOffset(value => value + 12)}>Next</Button></div>
    </>}
    <p><Link href="/scenarios/assistant/">AI script requirements</Link> · Currently unavailable</p>
  </>;
}
