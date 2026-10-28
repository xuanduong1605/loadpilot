import { SessionGate } from '../../features/access/session-gate';

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  return <SessionGate>{children}</SessionGate>;
}
