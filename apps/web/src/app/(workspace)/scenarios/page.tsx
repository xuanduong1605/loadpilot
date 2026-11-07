import type { Metadata } from 'next';
import { ScenarioEditor } from '../../../features/scenarios/scenario-editor';
import { PageHeading } from '../../../components/console-ui';

export const metadata: Metadata = { title: 'Scenarios | Loadpilot' };

export default function ScenariosPage() {
  return <><PageHeading title="Scripts" description="Edit JavaScript, check syntax and approve each exact k6 script version." /><ScenarioEditor /></>;
}
