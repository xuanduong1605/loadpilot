'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api } from '../../lib/api';
import { Icon } from '../../components/icon';
import { SystemTable } from './system-table';
import type { TargetPage } from './types';
import styles from './targets.module.css';

export function TargetList({ dashboard = false }: { dashboard?: boolean }) {
  const [page, setPage] = useState<TargetPage>();
  const [offset, setOffset] = useState(0);
  const [archived, setArchived] = useState(false);
  const [retry, setRetry] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    void api<TargetPage>(`/api/targets?offset=${offset}&limit=12&includeArchived=${archived}`)
      .then(value => { if (active) setPage(value); })
      .catch(reason => { if (active) setError(reason.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [offset, archived, retry]);

  return <>
    <div className={styles.heading}><h1>{dashboard ? 'Dashboard' : 'Systems'}</h1>
      <Link className={styles.action} href="/targets/detail/?new=1"><Icon name="plus" width={16} height={16} /> Register system</Link></div>
    <div className={styles.toolbar}>{dashboard ? <h2>System connections</h2> : <label><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setOffset(0); }} /> Include archived systems</label>}
      <span>{page && !loading && !error ? `${page.total} registered` : ''}</span></div>
    {loading && <p role="status">Loading systems…</p>}
    {error && <><Notice title="Cannot load systems" tone="error">{error}</Notice><div className={styles.actions}><Button onClick={() => setRetry(retry + 1)}>Try again</Button></div></>}
    {!loading && !error && page && <>
      {page.items.length === 0 ? <section className={styles.panel}><h2>{offset ? 'No systems on this page' : 'Your first system starts here'}</h2>
        <p>{offset ? 'Return to the previous page.' : 'Add an API URL and its Kubernetes workload.'}</p></section>
        : <section className={styles.listPanel} aria-label="Registered systems"><SystemTable key={`${offset}-${archived}-${retry}`} systems={page.items} filterable /></section>}
      {(offset > 0 || page.total > 12) && <div className={styles.pagination}><Button variant="secondary" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - 12))}>Previous</Button>
        <span>Page {Math.floor(offset / 12) + 1}</span><Button variant="secondary" disabled={offset + 12 >= page.total} onClick={() => setOffset(offset + 12)}>Next</Button></div>}
    </>}
  </>;
}
