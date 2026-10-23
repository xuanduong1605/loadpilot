import type { Metadata } from 'next';
import { WorkspaceSection } from '../../../components/workspace-section';

export const metadata: Metadata = { title: 'Scenarios | Loadpilot' };

export default function ScenariosPage() {
  return <WorkspaceSection title="Scenarios" description="Turn a real user journey into a repeatable load test."
    icon="scenarios" heading="Every test needs a clear scenario">
    Script editing is not available yet. This space will hold your k6 scripts, saved versions and approval history.
  </WorkspaceSection>;
}
