import type { Metadata } from 'next';
import { TargetList } from '../../../features/targets/target-list';

export const metadata: Metadata = { title: 'Systems | Loadpilot' };

export default function TargetsPage() {
  return <TargetList />;
}
