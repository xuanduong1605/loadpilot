import Link from 'next/link';
import { WorkspaceShell } from '../components/workspace-shell';
import guide from '../components/task-guide.module.css';
import styles from '../components/workspace.module.css';

export default function NotFound() {
  return (
    <WorkspaceShell>
      <section className={styles.empty}>
        <h1>Page not found</h1>
        <p>Check the address for typos.</p>
        <Link href="/" className={guide.button}>Go to All features</Link>
      </section>
    </WorkspaceShell>
  );
}
