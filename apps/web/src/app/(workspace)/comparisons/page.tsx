import type { Metadata } from 'next';
import { WorkspaceSection } from '../../../components/workspace-section';

export const metadata: Metadata = { title: 'Comparisons | Loadpilot' };

export default function ComparisonsPage() {
  return <WorkspaceSection title="Comparisons" description="Put performance and resource decisions in context."
    icon="compare" heading="Good comparisons start with evidence">
    Run comparison is not available yet. Completed experiments will let you compare results at a common load and review the evidence.
  </WorkspaceSection>;
}
