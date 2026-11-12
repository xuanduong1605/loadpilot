'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Icon } from '../../components/icon';
import { Button, Notice } from '../../components/form-controls';
import { connectionStatus, useConnectionChecks } from './connection-checks';
import type { Target } from './types';
import styles from './system-table.module.css';

export function SystemTable({ systems, filterable = false }: { systems: Target[]; filterable?: boolean }) {
  const [query, setQuery] = useState('');
  const [environment, setEnvironment] = useState('');
  const { checks, inspect } = useConnectionChecks();
  const filtered = systems.filter(system => (!environment || system.environment === environment)
    && `${system.name} ${system.namespace} ${system.deployment} ${system.container} ${system.baseUrl}`.toLowerCase().includes(query.trim().toLowerCase()));
  function clear() { setQuery(''); setEnvironment(''); }

  return <>
    {filterable && <div className={styles.filters}>
      <label className={styles.search}><Icon name="search" width={16} height={16} /><span className={styles.srOnly}>Search systems on this page</span>
        <input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="Search systems on this page…" /></label>
      <label className={styles.select}><span className={styles.srOnly}>Filter this page by environment</span><select value={environment} onChange={event => setEnvironment(event.target.value)}>
        <option value="">All environments</option><option value="DEVELOPMENT">Development</option><option value="STAGING">Staging</option><option value="PRODUCTION">Production</option>
      </select></label>
      {(query || environment) && <button className={styles.clear} type="button" onClick={clear}>Clear filters</button>}
      {(query || environment) && filtered.length > 0 && <span className={styles.resultCount} role="status">{filtered.length} of {systems.length} on this page</span>}
    </div>}
    {filtered.length ? <div className={styles.scroll} role="region" aria-label="Systems table" tabIndex={0}>
      <table className={styles.table}>
        <thead><tr><th scope="col">System</th><th scope="col" className={styles.context}>Environment</th><th scope="col" className={styles.context}>Workload</th><th scope="col">Connection</th></tr></thead>
        <tbody>{filtered.map(system => {
          const check = checks[system.id]?.revision === system.revision ? checks[system.id] : undefined;
          const status = connectionStatus(check);
          return <tr key={system.id}>
          <th scope="row"><Link className={styles.identity} href={`/targets/detail/?id=${system.id}`} aria-label={system.name}><span className={styles.systemIcon}><Icon name="server" width={18} height={18} /></span><span><strong>{system.name}</strong><small>{system.baseUrl}</small></span></Link></th>
          <td className={styles.context}><span className={styles.environment} data-environment={system.environment}>{system.environment.toLowerCase()}</span></td>
          <td className={styles.context}><span className={styles.workload}>{system.deployment}</span><small>{system.namespace} / {system.container}</small></td>
          <td>{system.archivedAt ? <span className={styles.archived}>Archived</span> : <div className={styles.connection}>
            <div aria-live="polite"><span className={styles.status} data-tone={status.tone}>{status.label}</span>
              {check?.snapshot && !check.pending && !check.error && <small>Checked <time dateTime={check.snapshot.collectedAt}>{new Date(check.snapshot.collectedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</time></small>}</div>
            <Button variant="secondary" busy={check?.pending} aria-label={`Check ${system.name}`} onClick={() => void inspect(system)}>Check</Button>
          </div>}</td>
        </tr>;
        })}</tbody>
      </table>
    </div> : <div className={styles.noMatches}><Notice title="No matching systems on this page" tone="error">Try another name, workload or environment.</Notice></div>}
  </>;
}
