'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Icon } from '../../components/icon';
import { Button, Notice } from '../../components/form-controls';
import { PageHeading, StatusBadge } from '../../components/console-ui';
import { api } from '../../lib/api';
import type { TargetPage } from '../../features/targets/types';
import { SystemTable } from '../../features/targets/system-table';
import { ExperimentHistory } from '../../features/experiments/experiment-history';
import styles from '../../components/console-ui.module.css';

export default function OverviewPage() {
  const [systems, setSystems] = useState<TargetPage>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true; setSystems(undefined); setError('');
    void api<TargetPage>('/api/targets?offset=0&limit=4&includeArchived=false').then(value => { if (active) setSystems(value); })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : 'Cannot load target apps.'); });
    return () => { active = false; };
  }, [retry]);
  return <>
    <PageHeading title="Overview" description="From approved configuration to measured evidence. Every conclusion requires valid observations." actions={<><Link className={styles.secondary} href="/targets/detail/?new=1"><Icon name="plus" width={14} height={14} />Register target app</Link><Link className={styles.action} href="/experiments/"><Icon name="experiments" width={14} height={14} />Experiment Setup</Link></>} />
    <dl className={styles.summary} aria-label="Workspace summary">
      <div><dt>Registered target apps</dt><dd>{systems ? String(systems.total).padStart(2, '0') : '—'}</dd><small>{error ? 'Cannot load' : systems ? 'Active systems' : 'Loading…'}</small></div>
      <div><dt>Active execution</dt><dd>— <StatusBadge>Unavailable</StatusBadge></dd><small>Run list unavailable</small></div>
      <div><dt>SLO verdict</dt><dd>— <StatusBadge>Unevaluated</StatusBadge></dd><small>Never inferred from Job state</small></div>
      <div><dt>Connection checks</dt><dd><Icon name="eye" width={22} height={22} /><StatusBadge tone="info">Read only</StatusBadge></dd><small>On demand · per target app</small></div>
    </dl>
    <ExperimentHistory targets={systems?.items} />
    <section className={styles.panel} aria-labelledby="systems-title"><div className={styles.panelHead}><div><h2 id="systems-title">Target Apps</h2><p>APIs, Kubernetes workloads and monitoring connections.</p></div><Link href="/targets/">View all <Icon name="arrow" width={13} height={13} /></Link></div>
      {error ? <div className={styles.body}><Notice title="Cannot load systems" tone="error">{error}</Notice><Button variant="secondary" onClick={() => setRetry(retry + 1)}>Try again</Button></div>
        : !systems ? <div className={styles.body}><div className={styles.skeleton} role="status" aria-label="Loading systems" /></div>
        : systems.items.length ? <SystemTable systems={systems.items} /> : <div className={styles.empty}><Icon name="server" width={26} height={26} /><h3>Connect your first target app</h3><p>Register an API and workload to configure connections and inspect readiness and resources.</p><Link href="/targets/detail/?new=1">Register target app</Link></div>}
      <div className={styles.footnote}><span>Health is checked on demand, never inferred from saved configuration.</span><span>CPU · Memory · Pod readiness</span></div>
    </section>
  </>;
}
