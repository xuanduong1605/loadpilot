'use client';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Icon } from './icon';
import styles from './workspace.module.css';
import guide from './task-guide.module.css';
export function WorkspaceShell({ children, account }: { children: React.ReactNode; account?: React.ReactNode }) {
  const pathname = usePathname();
  const feature = pathname.startsWith('/targets') ? 'Applications' : pathname.startsWith('/scenarios') ? 'Scripts' : pathname.startsWith('/experiments') || pathname.startsWith('/comparisons') ? 'Experiments' : pathname.startsWith('/settings') ? 'Settings' : '';
  return <div className={`${styles.workspace} ${guide.theme}`}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <header className={styles.topbar}>
      <Link className={styles.brand} href="/" aria-label="Loadpilot home">loadpilot<span className={styles.brandDot}>.</span></Link>
      {feature && <nav className={styles.breadcrumb} aria-label="Workspace"><Link href="/"><Icon name="grid" width={16} height={16} /><span>All features</span></Link><span aria-hidden="true">/</span><span>{feature}</span></nav>}
      <div className={styles.accountArea}>{account}</div>
    </header>
    <main id="main-content" className={styles.content} tabIndex={-1}>{children}</main>
  </div>;
}
