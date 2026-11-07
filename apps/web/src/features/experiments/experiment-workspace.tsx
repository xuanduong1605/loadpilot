'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { ExperimentSettingsForm } from './settings-form';
import { SavedExperimentInputs } from './experiment-review';
import { LiveMonitor } from './live-monitor';
import { GoalAssistant, OptimizationPlan } from './setup-assistance';
import { PageHeading, StatusBadge } from '../../components/console-ui';
import styles from '../../components/console-ui.module.css';
import setup from './settings.module.css';

export function ExperimentWorkspace() {
  const query = useSearchParams();
  const snapshotId = query.get('snapshot');
  const live = query.get('view') === 'live';
  return <>
    <PageHeading title={live ? 'Live Execution Monitor' : 'Experiment Setup'} description={live ? 'Monitor run status and observations, and request a stop using its run ID.' : 'Define goals, load and budgets before approving an immutable input snapshot.'} actions={<StatusBadge tone="warning">Start unavailable</StatusBadge>} />
    <nav className={styles.tabs} aria-label="Experiment views"><Link href="/experiments/" aria-current={!live ? 'page' : undefined}>Setup & review</Link><Link href="/experiments/?view=live" aria-current={live ? 'page' : undefined}>Live Execution Monitor</Link></nav>
    {live ? <LiveMonitor /> : snapshotId ? <SavedExperimentInputs id={snapshotId} /> : <div className={setup.workbench}><div><GoalAssistant /><ExperimentSettingsForm onFinalized={id => { window.history.replaceState(null, '', `/experiments/?snapshot=${encodeURIComponent(id)}`); }} /></div><OptimizationPlan /></div>}
  </>;
}
