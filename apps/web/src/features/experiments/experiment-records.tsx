'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { StatusBadge } from '../../components/console-ui';
import { api } from '../../lib/api';
import styles from './experiment-records.module.css';

type Kind = 'DRAFTS' | 'SNAPSHOTS' | 'RUNS';
type Entry = { id: string; targetName: string | null; scriptName: string | null; scriptVersion: number | null; revision: number; status: string; listedAt: string };
type Page = { items: Entry[]; hasMore: boolean };

const dates = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
const route = { DRAFTS: '/experiments/new/?draft=', SNAPSHOTS: '/experiments/review/?snapshot=', RUNS: '/experiments/monitor/?run=' };
const empty = { DRAFTS: 'Saved drafts appear here after you save an experiment setup.', SNAPSHOTS: 'Finalized inputs appear here after you review and save them.', RUNS: 'Runs appear here after they are created.' };
const statusTones: Partial<Record<string, 'warning' | 'success' | 'danger'>> = {
  DRAFT: 'warning', FINALIZED: 'success', COMPLETED: 'success', FAILED: 'danger', RECOVERY_REQUIRED: 'danger',
};

export function recordLabel(entry: Entry) {
  return entry.scriptName ? `${entry.scriptName}${entry.scriptVersion === null ? '' : ` · v${entry.scriptVersion}`}` : 'Script not selected';
}

export function snapshotOption(entry: Entry) {
  return `${dates.format(new Date(entry.listedAt))} · ${entry.targetName ?? 'Unnamed system'} · ${recordLabel(entry)} · draft revision ${entry.revision}`;
}

export function useExperimentRecords(kind: Kind, limit = 20) {
  const [items, setItems] = useState<Entry[]>([]);
  const [offset, setOffset] = useState(0);
  const [retry, setRetry] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true); setError('');
    void api<Page>(`/api/experiments/library?kind=${kind}&offset=${offset}&limit=${limit}`, { signal: controller.signal })
      .then(page => {
        if (!active) return;
        setItems(previous => offset === 0 ? page.items : [...previous, ...page.items.filter(item => !previous.some(entry => entry.id === item.id))]);
        setHasMore(page.hasMore);
      })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : 'Cannot load these experiments.'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [kind, limit, offset, retry]);

  return { items, hasMore, loading, error, more: () => setOffset(value => value + limit), retry: () => setRetry(value => value + 1) };
}

export function ExperimentRecords({ kind, title, preview = false }: { kind: Kind; title: string; preview?: boolean }) {
  const records = useExperimentRecords(kind, preview ? 5 : 20);
  return <section className={styles.panel} aria-label={title}>
    <header className={styles.header}><h2>{title}</h2>{preview && <Link href="/experiments/">View experiments</Link>}</header>
    {records.items.length > 0 && <ul className={styles.records}>{records.items.map(entry => <li key={entry.id}>
      <Link href={route[kind] + encodeURIComponent(entry.id)} className={styles.record}>
        <span className={styles.identity}><strong>{entry.targetName ?? 'Unnamed system'}</strong><span>{recordLabel(entry)}</span></span>
        <span className={styles.meta}><StatusBadge tone={statusTones[entry.status]}>{entry.status.toLowerCase().replaceAll('_', ' ')}</StatusBadge>
          <time dateTime={entry.listedAt}>{dates.format(new Date(entry.listedAt))}</time></span>
      </Link>
    </li>)}</ul>}
    {!records.items.length && !records.loading && !records.error && <p className={styles.message}>{empty[kind]}</p>}
    {records.loading && <p className={styles.message} role="status">Loading {title.toLowerCase()}…</p>}
    {records.error && <div className={styles.message}><Notice title={`Cannot load ${title.toLowerCase()}`} tone="error">{records.error}</Notice><Button onClick={records.retry}>Try again</Button></div>}
    {!preview && records.hasMore && !records.error && <div className={styles.message}><Button variant="secondary" busy={records.loading} onClick={records.more}>Load more</Button></div>}
  </section>;
}
