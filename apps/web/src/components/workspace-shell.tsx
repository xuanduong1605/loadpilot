'use client';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useRef, useState, useSyncExternalStore } from 'react';
import { BrandLink } from './brand-link';
import { Icon, type IconName } from './icon';
import styles from './workspace.module.css';
import guide from './task-guide.module.css';
const destinations: { href: string; label: string; icon: IconName }[] = [
  { href: '/', label: 'Dashboard', icon: 'grid' },
  { href: '/targets/', label: 'Systems', icon: 'server' },
  { href: '/scenarios/', label: 'Scripts', icon: 'scenarios' },
  { href: '/experiments/', label: 'Experiments', icon: 'experiments' },
  { href: '/settings/', label: 'Settings', icon: 'settings' },
];
const wideScreen = '(min-width: 1000px)';
function subscribeToScreen(callback: () => void) {
  const media = window.matchMedia(wideScreen);
  media.addEventListener('change', callback);
  return () => media.removeEventListener('change', callback);
}

export function WorkspaceShell({ children, account }: { children: React.ReactNode; account?: React.ReactNode }) {
  const pathname = usePathname();
  const wide = useSyncExternalStore(subscribeToScreen, () => window.matchMedia(wideScreen).matches, () => true);
  const [preference, setPreference] = useState<boolean>();
  const expanded = preference ?? wide;
  const toggle = useRef<HTMLButtonElement>(null);
  return <div className={`${styles.workspace} ${guide.theme}`} data-navigation={preference === undefined ? 'auto' : expanded ? 'expanded' : 'collapsed'}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <header className={styles.topbar}>
      <BrandLink />
      <div className={styles.accountArea}>{account}</div>
    </header>
    <div className={styles.body}>
      <aside className={styles.sidebar} onKeyDown={event => {
        if (event.key === 'Escape' && expanded) { setPreference(false); toggle.current?.focus(); }
      }}>
        <div className={styles.sidebarControls}>
          <button ref={toggle} type="button" className={styles.toggle} aria-label={expanded ? 'Collapse sidebar' : 'Expand sidebar'} aria-expanded={expanded} aria-controls="workspace-navigation" onClick={() => setPreference(!expanded)}>
            <Icon name="panel" />
          </button>
          <nav id="workspace-navigation" aria-label="Workspace">
            {destinations.map(({ href, label, icon }) => {
              const active = href === '/' ? pathname === '/' : pathname.startsWith(href) || (href === '/experiments/' && pathname.startsWith('/comparisons/'));
              return <Link key={href} href={href} aria-label={label} title={label} aria-current={active ? 'page' : undefined} onNavigate={() => setPreference(false)}>
                <Icon name={icon} /><span>{label}</span>
              </Link>;
            })}
          </nav>
        </div>
      </aside>
      <main id="main-content" className={styles.content} tabIndex={-1}>{children}</main>
    </div>
  </div>;
}
