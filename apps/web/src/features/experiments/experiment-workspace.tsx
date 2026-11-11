'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { ExperimentSettingsForm } from './settings-form';
import { SavedExperimentInputs } from './experiment-review';
import { LiveMonitor } from './live-monitor';
import { GoalAssistant, OptimizationPlan } from './setup-assistance';
import { PageHeading } from '../../components/console-ui';
import { ExperimentHistory } from './experiment-history';
import { ExperimentNavigation, type ExperimentScreen } from './experiment-navigation';
import { SnapshotLookup } from './snapshot-lookup';
import styles from '../../components/feature-workspace.module.css';

export function ExperimentWorkspace({ screen = 'history' }: { screen?: ExperimentScreen }) {
  const query = useSearchParams();
  const router = useRouter();
  const snapshotId = query.get('snapshot');
  const current = query.get('view') === 'live' ? 'monitor' : snapshotId ? 'review' : query.get('draft') ? 'setup' : screen;
  const titles = { history: 'Experiments', setup: 'New experiment', review: 'Input review', monitor: 'Run monitor', compare: 'Compare & export', assistance: 'Planning tools' };
  return <>
    <PageHeading title={titles[current]} />
    <ExperimentNavigation screen={current} />
    {current === 'history' && <><div className={styles.actions}><Link href="/experiments/new/">Create experiment</Link><Link href="/experiments/review/">Open saved inputs</Link></div><ExperimentHistory /></>}
    {current === 'monitor' && <LiveMonitor />}
    {current === 'review' && (snapshotId ? <SavedExperimentInputs id={snapshotId} /> : <SnapshotLookup />)}
    {current === 'setup' && <div className={styles.narrow}><ExperimentSettingsForm onFinalized={id => router.push(`/experiments/review/?snapshot=${encodeURIComponent(id)}`)} /><p><Link href="/experiments/planning/">AI goals and optimization tools</Link> · Currently unavailable</p></div>}
    {current === 'assistance' && <><div className={styles.actions}><Link href="/experiments/new/">Back to manual setup</Link></div><GoalAssistant /><OptimizationPlan /></>}
  </>;
}
