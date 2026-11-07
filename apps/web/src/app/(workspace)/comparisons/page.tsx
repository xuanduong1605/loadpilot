import type { Metadata } from 'next';
import { ComparisonWorkspace } from '../../../features/experiments/comparison-workspace';

export const metadata: Metadata = { title: 'Comparisons | Loadpilot' };

export default function ComparisonsPage() {
  return <ComparisonWorkspace />;
}
