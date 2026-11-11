import Link from 'next/link';
import { Icon } from '../../components/icon';
import styles from '../../components/feature-workspace.module.css';
export function Overview() {
  return <>
    <div className={styles.intro}><h1>What are you working on?</h1><p>Choose a feature to open its workspace. Your applications, scripts and experiments each have their own place.</p></div>
    <nav className={styles.launcher} aria-label="Features">
      <Link href="/targets/" className={styles.launchRow}><h2>Applications</h2><p>Register a target, manage its connections and inspect workload resources.</p><span>Open<Icon name="arrow" width={20} height={20} /></span></Link>
      <Link href="/scenarios/" className={styles.launchRow}><h2>Scripts</h2><p>Write k6 JavaScript, save versions and review the exact source for approval.</p><span>Open<Icon name="arrow" width={20} height={20} /></span></Link>
      <Link href="/experiments/" className={styles.launchRow}><h2>Experiments</h2><p>Configure bounded tests, review saved inputs, monitor a run and compare snapshots.</p><span>Open<Icon name="arrow" width={20} height={20} /></span></Link>
    </nav>
    <footer className={styles.homeFooter}><p>Open a feature to continue saved work.</p><Link href="/settings/">Workspace settings</Link></footer>
  </>;
}
