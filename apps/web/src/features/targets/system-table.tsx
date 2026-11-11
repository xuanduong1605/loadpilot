'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Icon } from '../../components/icon';
import { Button } from '../../components/form-controls';
import type { Target } from './types';
import styles from './system-table.module.css';

export function SystemTable({ systems, filterable = false }: { systems: Target[]; filterable?: boolean }) {
  const [query, setQuery] = useState('');
  const [environment, setEnvironment] = useState('');
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
      <span className={styles.resultCount} role="status">{filtered.length} of {systems.length} on this page</span>
    </div>}
    {filtered.length ? <div className={styles.scroll} role="region" aria-label="Systems table" tabIndex={0}>
      <table className={styles.table}>
        <thead><tr><th scope="col">System</th><th scope="col">Environment</th><th scope="col">Workload</th><th scope="col"><span className={styles.srOnly}>Details</span></th></tr></thead>
        <tbody>{filtered.map(system => <tr key={system.id}>
          <th scope="row"><Link className={styles.identity} href={`/targets/detail/?id=${system.id}`} aria-label={system.name}><span className={styles.systemIcon}><Icon name="server" width={18} height={18} /></span><span><strong>{system.name}</strong><small>{system.baseUrl}</small></span></Link></th>
          <td><span className={styles.environment} data-environment={system.environment}>{system.environment.toLowerCase()}</span>{system.archivedAt && <span className={styles.archived}>Archived</span>}</td>
          <td><span className={styles.workload}>{system.deployment}</span><small>{system.namespace} / {system.container}</small></td>
          <td><Link className={styles.open} href={`/targets/detail/?id=${system.id}`} aria-label={`Inspect ${system.name}`}><Icon name="chevron" width={16} height={16} /></Link></td>
        </tr>)}</tbody>
      </table>
    </div> : <div className={styles.noMatches}><Icon name="search" width={24} height={24} /><h3>No matching systems on this page</h3><p>Try another name, workload or environment.</p><Button variant="secondary" onClick={clear}>Clear filters</Button></div>}
  </>;
}
