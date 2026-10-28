import type { Metadata } from 'next';
import { Suspense } from 'react';
import { TargetDetail } from '../../../../features/targets/target-detail';

export const metadata: Metadata = { title: 'System details | Loadpilot' };
export default function TargetDetailPage() {
  return <Suspense fallback={<p>Loading system…</p>}><TargetDetail /></Suspense>;
}
