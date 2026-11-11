import Link from 'next/link';
import { Icon } from '../../components/icon';
import styles from '../../components/feature-workspace.module.css';
export function Overview() {
  return <>
    <h1 className={styles.intro}>What are you working on?</h1>
    <nav className={styles.launcher} aria-label="Features">
      <Link href="/targets/" className={styles.launchRow}>
        <h2><Icon name="computer" width={22} height={22} />Applications</h2>
        <p>Register a target, manage its connections and inspect workload resources.</p>
        <Icon name="arrow" width={20} height={20} />
      </Link>
      <Link href="/scenarios/" className={styles.launchRow}>
        <h2><Icon name="scroll" width={22} height={22} />Scripts</h2>
        <p>Write k6 JavaScript, save versions and review the exact source for approval.</p>
        <Icon name="arrow" width={20} height={20} />
      </Link>
      <Link href="/experiments/" className={styles.launchRow}>
        <h2><Icon name="experiments" width={22} height={22} />Experiments</h2>
        <p>Configure bounded tests, review saved inputs, monitor a run and compare snapshots.</p>
        <Icon name="arrow" width={20} height={20} />
      </Link>
    </nav>
    <footer className={styles.homeFooter}><Link href="/settings/">Workspace settings</Link></footer>
  </>;
}
