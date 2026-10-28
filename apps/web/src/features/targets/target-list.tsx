'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api } from '../../lib/api';
import type { TargetPage } from './types';
import styles from './targets.module.css';

export function TargetList() {
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
    <div className={styles.heading}><div><h1>Systems</h1><p>Register your applications, then inspect their workloads and connections.</p></div>
      <Link className={styles.action} href="/targets/detail/?new=1">Register system</Link></div>
    <div className={styles.toolbar}><label><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setOffset(0); }} /> Include archived systems</label>
      <span>{page && !loading && !error ? `${page.total} registered` : ''}</span></div>
    {loading && <p role="status">Loading systems…</p>}
    {error && <><Notice title="Cannot load systems" tone="error">{error}</Notice><div className={styles.actions}><Button onClick={() => setRetry(retry + 1)}>Try again</Button></div></>}
    {!loading && !error && page && <>
      {page.items.length === 0 ? <section className={styles.panel}><h2>{offset ? 'No systems on this page' : 'Your first system starts here'}</h2>
        <p>{offset ? 'Return to the previous page or register another system.' : 'Add an API and its Kubernetes workload. Each system keeps its own connection and monitoring details.'}</p></section>
        : <div className={styles.cards}>{page.items.map(target => <article className={styles.panel} key={target.id}>
          <h2><Link href={`/targets/detail/?id=${target.id}`}>{target.name}</Link></h2>
          <span className={styles.badge}>{target.archivedAt ? 'Archived' : 'Open to inspect live status'}</span>
          <dl className={styles.details}><dt>Environment</dt><dd>{target.environment}</dd><dt>API</dt><dd>{target.baseUrl}</dd>
            <dt>Workload</dt><dd>{target.namespace} / {target.deployment}</dd><dt>Container</dt><dd>{target.container}</dd></dl>
        </article>)}</div>}
      <div className={styles.pagination}><Button variant="secondary" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - 12))}>Previous</Button>
        <span>Page {Math.floor(offset / 12) + 1}</span><Button variant="secondary" disabled={offset + 12 >= page.total} onClick={() => setOffset(offset + 12)}>Next</Button></div>
    </>}
  </>;
}
