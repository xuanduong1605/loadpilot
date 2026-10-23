import type { Metadata } from 'next';
import { WorkspaceSection } from '../../../components/workspace-section';

export const metadata: Metadata = { title: 'Experiments | Loadpilot' };

export default function ExperimentsPage() {
  return <WorkspaceSection title="Experiments" description="Measure how your application behaves as demand changes."
    icon="experiments" heading="A space for your next experiment">
    Experiment execution is not available yet. Once connected, you will be able to configure a bounded test and follow its progress here.
  </WorkspaceSection>;
}
