import type { Metadata } from 'next';
import { WorkspaceSection } from '../../../components/workspace-section';

export const metadata: Metadata = { title: 'Settings | Loadpilot' };

export default function SettingsPage() {
  return <WorkspaceSection title="Settings" description="Bring your laboratory connections together."
    icon="settings" heading="Your laboratory, connected">
    Connection settings are not available yet. Kubernetes and monitoring connections will be configured and checked here.
  </WorkspaceSection>;
}
