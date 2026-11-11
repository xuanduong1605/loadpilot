import type { Metadata } from 'next';
import Link from 'next/link';
import { Icon } from '../../../components/icon';
import styles from '../../../components/workspace.module.css';

export const metadata: Metadata = { title: 'Settings | Loadpilot' };

export default function SettingsPage() {
  return <>
    <div className={styles.heading}><h1>Settings</h1></div>
    <section className={styles.empty} aria-labelledby="connection-settings-title">
      <h2 id="connection-settings-title">System connections</h2>
      <p>Open a system to configure its Kubernetes endpoint, read-only credentials and optional monitoring connection.</p>
      <Link className={styles.textLink} href="/targets/">Manage system connections <Icon name="arrow" width={16} height={16} /></Link>
    </section>
  </>;
}
