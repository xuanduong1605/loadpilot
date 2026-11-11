'use client';
import { FeatureNav } from '../../components/feature-nav';
export type ExperimentScreen = 'history' | 'setup' | 'review' | 'monitor' | 'compare' | 'assistance';
export function ExperimentNavigation({ screen }: { screen: ExperimentScreen }) {
  return <FeatureNav label="Experiments" items={[
    { label: 'Runs', href: '/experiments/', active: screen === 'history' },
    { label: 'Setup', href: '/experiments/new/', active: screen === 'setup' },
    { label: 'Input review', href: '/experiments/review/', active: screen === 'review' },
    { label: 'Monitor', href: '/experiments/monitor/', active: screen === 'monitor' },
    { label: 'Compare & export', href: '/comparisons/', active: screen === 'compare' },
  ]} />;
}
