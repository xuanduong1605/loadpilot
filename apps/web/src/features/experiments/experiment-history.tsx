'use client';

import { useState } from 'react';
import Link from 'next/link';
import { Icon } from '../../components/icon';
import { Button } from '../../components/form-controls';
import { StatusBadge } from '../../components/console-ui';
import type { Target } from '../targets/types';
import styles from '../../components/console-ui.module.css';

export function ExperimentHistory({ targets = [] }: { targets?: Target[] }) {
  const [target, setTarget] = useState('');
  const [period, setPeriod] = useState('all');
  const [state, setState] = useState('');
  const [verdict, setVerdict] = useState('');
  const filtered = target || period !== 'all' || state || verdict;
  return <section className={styles.panel} aria-labelledby="experiment-history-title">
    <div className={styles.panelHead}><div><h2 id="experiment-history-title">Experiment history</h2><p>Execution state and SLO verdicts are tracked independently.</p></div><StatusBadge>History unavailable</StatusBadge></div>
    <div className={styles.filters}>
      <label>Target Apps<select value={target} onChange={event => setTarget(event.target.value)}><option value="">All target apps</option>{targets.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <label>Time range · UTC<select value={period} onChange={event => setPeriod(event.target.value)}><option value="all">All time</option><option value="24h">Last 24 hours</option><option value="7d">Last 7 days</option><option value="30d">Last 30 days</option></select></label>
      <label>Execution state<select value={state} onChange={event => setState(event.target.value)}><option value="">All states</option><option value="QUEUED">Queued</option><option value="RUNNING">Running</option><option value="STOPPING">Stopping</option><option value="COMPLETED">Completed</option><option value="FAILED">Failed</option><option value="CANCELLED">Cancelled</option><option value="RECOVERY_REQUIRED">Recovery required</option></select></label>
      <label>SLO verdict<select value={verdict} onChange={event => setVerdict(event.target.value)}><option value="">All verdicts</option><option value="PASS">Pass</option><option value="FAIL">Fail</option><option value="INSUFFICIENT_DATA">Insufficient data</option><option value="UNEVALUATED">Unevaluated</option></select></label>
      <Button variant="secondary" disabled={!filtered} onClick={() => { setTarget(''); setPeriod('all'); setState(''); setVerdict(''); }}>Clear filters</Button>
    </div>
    <div className={styles.scroll} role="region" aria-label="Experiment history table" tabIndex={0}><table className={styles.table}><thead><tr><th scope="col">Run / target app</th><th scope="col">Time · UTC</th><th scope="col">Requested / achieved</th><th scope="col">Execution state</th><th scope="col">SLO</th><th scope="col">Restoration</th></tr></thead><tbody /></table></div>
    <div className={styles.empty}><Icon name="experiments" width={25} height={25} /><h3>{filtered ? 'No data for these filters' : 'No experiment history to display'}</h3><p>Saved experiment history is not available yet. Prepare and approve inputs before execution; no sample results are shown.</p><Link href="/experiments/">Experiment Setup <Icon name="arrow" width={13} height={13} /></Link></div>
    <div className={styles.footnote}><span role="status">{filtered ? 'Filters applied · history unavailable' : 'No saved measurements'}</span><span>Execution ≠ SLO verdict ≠ restoration</span></div>
  </section>;
}
