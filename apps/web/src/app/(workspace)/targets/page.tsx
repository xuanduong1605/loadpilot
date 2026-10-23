import type { Metadata } from 'next';
import { WorkspaceSection } from '../../../components/workspace-section';

export const metadata: Metadata = { title: 'Targets | Loadpilot' };

export default function TargetsPage() {
  return <WorkspaceSection title="Targets" description="A home for the APIs and workloads you want to understand."
    icon="target" heading="Your testing starts with a target">
    Target management is coming next. You will be able to register an API and connect its workload and monitoring here.
  </WorkspaceSection>;
}
