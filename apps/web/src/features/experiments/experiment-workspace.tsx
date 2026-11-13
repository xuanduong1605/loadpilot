'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { ExperimentSettingsForm } from './settings-form';
import { SavedExperimentInputs } from './experiment-review';
import { LiveMonitor } from './live-monitor';
import { GoalAssistant, OptimizationPlan } from './setup-assistance';
import { PageHeading } from '../../components/console-ui';
import { ExperimentRecords } from './experiment-records';
import { ExperimentNavigation, type ExperimentScreen } from './experiment-navigation';
import guide from '../../components/task-guide.module.css';

export function ExperimentWorkspace({ screen = 'history' }: { screen?: ExperimentScreen }) {
  const query = useSearchParams();
  const router = useRouter();
  const snapshotId = query.get('snapshot');
  const current = query.get('view') === 'live' ? 'monitor' : snapshotId ? 'review' : query.get('draft') ? 'setup' : screen;
  if (current === 'review' && snapshotId) return <SavedExperimentInputs key={snapshotId} id={snapshotId} />;
  const titles = { history: 'Experiments', setup: 'New experiment', review: 'Input review', monitor: 'Run monitor', compare: 'Compare & export', assistance: 'Planning tools' };
  const back = current === 'monitor' && query.get('run') ? { href: '/experiments/monitor/', label: 'Choose another run' }
    : current === 'assistance' ? { href: '/experiments/new/', label: 'Back to manual setup' } : undefined;
  return <>
    {current !== 'setup' && <>
      <PageHeading title={titles[current]} back={back} actions={current === 'history' ? <Link className={guide.button} href="/experiments/new/">Create experiment</Link> : undefined} />
      <ExperimentNavigation screen={current} />
    </>}
    {current === 'history' && <><ExperimentRecords kind="RUNS" title="Runs" /><ExperimentRecords kind="DRAFTS" title="Saved drafts" /><ExperimentRecords kind="SNAPSHOTS" title="Finalized inputs" /></>}
    {current === 'monitor' && <LiveMonitor />}
    {current === 'review' && <ExperimentRecords kind="SNAPSHOTS" title="Saved inputs" />}
    {current === 'setup' && <><ExperimentSettingsForm onFinalized={id => router.push(`/experiments/review/?snapshot=${encodeURIComponent(id)}`)} /><p><Link href="/experiments/planning/">AI goals and optimization tools</Link> · Currently unavailable</p></>}
    {current === 'assistance' && <><GoalAssistant /><OptimizationPlan /></>}
  </>;
}
