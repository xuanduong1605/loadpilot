import { SessionGate } from '../../features/access/session-gate';
import { Suspense } from 'react';

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<main><p role="status">Loading workspace…</p></main>}><SessionGate>{children}</SessionGate></Suspense>;
}
