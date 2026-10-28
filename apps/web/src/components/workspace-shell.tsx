'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import { Icon, type IconName } from './icon';
import styles from './workspace.module.css';

const navigation: { href: string; label: string; icon: IconName }[] = [
  { href: '/', label: 'Overview', icon: 'grid' },
  { href: '/targets/', label: 'Targets', icon: 'target' },
  { href: '/scenarios/', label: 'Scenarios', icon: 'scenarios' },
  { href: '/experiments/', label: 'Experiments', icon: 'experiments' },
  { href: '/comparisons/', label: 'Comparisons', icon: 'compare' },
  { href: '/settings/', label: 'Settings', icon: 'settings' },
];

export function WorkspaceShell({ children, account }: { children: React.ReactNode; account?: React.ReactNode }) {
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const current = navigation.find(({ href }) => href === pathname || href === `${pathname}/`);

  return (
    <div className={styles.workspace}>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <aside className={styles.sidebar} aria-label="Workspace sidebar">
        <div className={styles.brandRow}>
          <Link className={styles.brand} href="/" onClick={() => setMenuOpen(false)}>
            <span className={styles.brandMark}>↗</span> loadpilot<span className={styles.brandDot}>.</span>
          </Link>
          <button className={styles.menuButton} type="button" aria-label="Toggle navigation"
            aria-expanded={menuOpen} aria-controls="workspace-navigation" onClick={() => setMenuOpen(!menuOpen)}>
            <Icon name="menu" />
          </button>
        </div>
        <nav id="workspace-navigation" className={`${styles.navigation} ${menuOpen ? styles.open : ''}`} aria-label="Main navigation">
          <p className={styles.navLabel}>WORKSPACE</p>
          {navigation.map(({ href, label, icon }) => (
            <Link key={href} href={href} className={`${styles.navLink} ${current?.href === href ? styles.active : ''}`}
              aria-current={current?.href === href ? 'page' : undefined} onClick={() => setMenuOpen(false)}>
              <Icon name={icon} /><span>{label}</span>
            </Link>
          ))}
        </nav>
        <div className={styles.sidebarFooter}>
          <span className={styles.workspaceAvatar}>L</span>
          <div><strong>Performance lab</strong><span>Operator workspace</span></div>
        </div>
      </aside>
      <div className={styles.body}>
        <header className={styles.topbar}>
          <p>Workspace <span>/</span> <strong>{current?.label ?? 'Overview'}</strong></p>
          {account}
        </header>
        <main id="main-content" className={styles.content} tabIndex={-1}>{children}</main>
        <footer className={styles.footer}><span>Loadpilot · Performance laboratory</span><span>Measure. Learn. Refine.</span></footer>
      </div>
    </div>
  );
}
