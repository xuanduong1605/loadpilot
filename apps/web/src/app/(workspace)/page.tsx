'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Icon, type IconName } from '../../components/icon';
import { Button, Notice } from '../../components/form-controls';
import { api } from '../../lib/api';
import type { TargetPage } from '../../features/targets/types';
import styles from './overview.module.css';

const steps: { title: string; description: string; href: string; icon: IconName; available: boolean }[] = [
  { title: 'Register a system', description: 'Add an API and its Kubernetes workload.', href: '/targets/detail/?new=1', icon: 'target', available: true },
  { title: 'Inspect connections', description: 'Check access, readiness and resource samples.', href: '/targets/', icon: 'chart', available: true },
  { title: 'Prepare a scenario', description: 'Script editing and approval are coming next.', href: '/scenarios/', icon: 'scenarios', available: false },
];

export default function OverviewPage() {
  const [systems, setSystems] = useState<TargetPage>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let active = true;
    setSystems(undefined); setError('');
    void api<TargetPage>('/api/targets?offset=0&limit=4&includeArchived=false')
      .then(value => { if (active) setSystems(value); })
      .catch(reason => { if (active) setError(reason instanceof Error ? reason.message : 'Systems are unavailable.'); });
    return () => { active = false; };
  }, [retry]);
  return <>
    <div className={styles.heading}>
      <div><span className={styles.eyebrow}>PERFORMANCE WORKSPACE</span><h1>Overview</h1><p>Your systems, workload connections and performance evidence.</p></div>
      <Link className={styles.primaryLink} href="/targets/detail/?new=1"><span aria-hidden="true">+</span> Register system</Link>
    </div>
    <section className={styles.summary} aria-label="Workspace summary">
      <div><span>Active systems</span><strong>{systems ? systems.total : error ? 'Unavailable' : 'Loading…'}</strong><p>Registered applications</p></div>
      <div><span>Workload inspection</span><strong className={styles.summaryLabel}><Icon name="eye" width={18} height={18} /> Read only</strong><p>Run a check from a system&apos;s details</p></div>
      <div><span>Experiment execution</span><strong className={styles.summaryLabel}>Not available yet</strong><p>No run measurements to display</p></div>
    </section>
    <div className={styles.grid}>
      <section className={styles.panel} aria-labelledby="systems-title">
        <div className={styles.sectionTitle}><h2 id="systems-title">Registered systems</h2><Link href="/targets/">View all <Icon name="arrow" width={14} height={14} /></Link></div>
        {error ? <div className={styles.feedback}><Notice title="Cannot load systems" tone="error">{error}</Notice><Button variant="secondary" onClick={() => setRetry(retry + 1)}>Try again</Button></div>
          : !systems ? <p className={styles.feedback} role="status">Loading systems…</p>
          : systems.items.length ? <div className={styles.systems}>{systems.items.map(target => <Link className={styles.system} key={target.id} href={`/targets/detail/?id=${target.id}`}>
            <span className={styles.systemIcon}><Icon name="target" width={18} height={18} /></span><div><strong>{target.name}</strong><span>{target.namespace} / {target.deployment}</span></div><span className={styles.environment}>{target.environment.toLowerCase()}</span><Icon name="arrow" width={15} height={15} />
          </Link>)}</div> : <div className={styles.empty}><span className={styles.emptyIcon}><Icon name="target" width={24} height={24} /></span><h3>Connect your first system</h3><p>Register an application to configure its connection and inspect its workload.</p><Link href="/targets/detail/?new=1">Register system <Icon name="arrow" width={14} height={14} /></Link></div>}
        <div className={styles.panelFoot}><Icon name="eye" width={14} height={14} /> Connection health is checked on demand, per system.</div>
      </section>
      <section className={styles.panel} aria-labelledby="workflow-title">
        <div className={styles.sectionTitle}><h2 id="workflow-title">Getting started</h2><span>WORKFLOW</span></div>
        <ol className={styles.steps}>{steps.map((step, index) => <li key={step.title}><Link href={step.href}>
          <span className={styles.stepNumber}>{index + 1}</span><div><strong>{step.title}{!step.available && <span className={styles.soon}>Planned</span>}</strong><p>{step.description}</p></div><Icon name="arrow" width={15} height={15} />
        </Link></li>)}</ol>
      </section>
      <section className={`${styles.panel} ${styles.recent}`} aria-labelledby="recent-title">
        <div className={styles.sectionTitle}><h2 id="recent-title">Recent experiments</h2><Link href="/experiments/">View experiments <Icon name="arrow" width={14} height={14} /></Link></div>
        <div className={styles.recentEmpty}><span className={styles.emptyIcon}><Icon name="experiments" width={23} height={23} /></span><div><h3>No experiment results yet</h3><p>Execution is not available yet. Measured results will appear here when experiments are supported.</p></div></div>
      </section>
    </div>
  </>;
}
