import type { Metadata } from 'next';
import { ExperimentWorkspace } from '../../../features/experiments/experiment-workspace';

export const metadata: Metadata = { title: 'Experiments | Loadpilot' };

export default function ExperimentsPage() {
  return <ExperimentWorkspace />;
}
