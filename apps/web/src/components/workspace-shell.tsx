'use client';

import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { Icon, type IconName } from './icon';
import { api } from '../lib/api';
import type { TargetPage } from '../features/targets/types';
import styles from './workspace.module.css';

const navigation: { href: string; label: string; icon: IconName }[] = [
  { href: '/', label: 'Overview', icon: 'grid' },
  { href: '/targets/', label: 'Target Apps', icon: 'server' },
  { href: '/scenarios/', label: 'Scripts', icon: 'scenarios' },
  { href: '/experiments/', label: 'Experiment Setup', icon: 'experiments' },
  { href: '/experiments/?view=live', label: 'Live Execution Monitor', icon: 'activity' },
  { href: '/comparisons/', label: 'Comparison & Reports', icon: 'compare' },
];
type Health = { targetId: string; targetRevision: number; kubernetes: string; prometheus: string; collectedAt: string };

function ClusterContext() {
  const query = useSearchParams();
  const [targets, setTargets] = useState<TargetPage>();
  const [selected, setSelected] = useState('');
  const [health, setHealth] = useState<Health>();
  const [now, setNow] = useState(0);
  const [error, setError] = useState(false);
  useEffect(() => { let active = true; void api<TargetPage>('/api/targets?offset=0&limit=12&includeArchived=false').then(value => { if (active) setTargets(value); }).catch(() => { if (active) setError(true); }); return () => { active = false; }; }, []);
  useEffect(() => { const id = query.get('id'); if (id && targets?.items.some(item => item.id === id)) setSelected(id); }, [query, targets]);
  useEffect(() => {
    const listener = (event: Event) => { setHealth((event as CustomEvent<Health>).detail); setNow(Date.now()); };
    window.addEventListener('loadpilot:inspection', listener);
    const timer = window.setInterval(() => setNow(Date.now()), 5000);
    return () => { window.removeEventListener('loadpilot:inspection', listener); window.clearInterval(timer); };
  }, []);
  const target = targets?.items.find(item => item.id === selected);
  const fresh = target && health?.targetId === target.id && health.targetRevision === target.revision && now - Date.parse(health.collectedAt) < 30_000;
  const href = target ? `/targets/detail/?id=${target.id}&check=1` : '/targets/';
  const state = (key: 'kubernetes' | 'prometheus') => fresh ? health[key] === 'READY' ? 'READY' : 'error' : 'unknown';
  const text = (key: 'kubernetes' | 'prometheus') => fresh ? health[key] === 'READY' ? 'Ready' : 'Needs attention' : 'Not checked';
  return <div className={styles.context}>
    <label><Icon name="server" width={14} height={14} /><select aria-label="App / cluster context" value={selected} onChange={event => { setSelected(event.target.value); setHealth(undefined); }}>
      <option value="">{error ? 'Cannot load clusters' : 'Select app / cluster'}</option>
      {targets?.items.map(item => <option key={item.id} value={item.id}>{item.name} / {item.namespace}</option>)}
    </select></label>
    <Link href={href} className={styles.health} data-state={state('kubernetes')} title="Check connections for the selected target">K8s <span>{text('kubernetes')}</span></Link>
    <Link href={href} className={styles.health} data-state={state('prometheus')}>Prometheus <span>{text('prometheus')}</span></Link>
  </div>;
}

export function WorkspaceShell({ children, account }: { children: React.ReactNode; account?: React.ReactNode }) {
  const pathname = usePathname();
  const query = useSearchParams();
  const [menuOpen, setMenuOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const live = query.get('view') === 'live';
  useEffect(() => { setMenuOpen(false); }, [pathname, query]);
  useEffect(() => {
    if (!menuOpen) return;
    const dismiss = (event: KeyboardEvent) => { if (event.key === 'Escape') { setMenuOpen(false); menuButton.current?.focus(); } };
    window.addEventListener('keydown', dismiss); return () => window.removeEventListener('keydown', dismiss);
  }, [menuOpen]);
  return <div className={styles.workspace} data-collapsed={collapsed}>
    <a className="skip-link" href="#main-content">Skip to content</a>
    <aside className={styles.sidebar} aria-label="Workspace sidebar">
      <div className={styles.brandRow}><Link className={styles.brand} href="/" aria-label="Loadpilot home"><span className={styles.brandMark}><Icon name="activity" width={18} height={18} /></span><span className={styles.brandName}>loadpilot<span className={styles.brandDot}>.</span></span></Link>
        <button className={styles.collapseButton} type="button" aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} aria-expanded={!collapsed} aria-controls="workspace-navigation" onClick={() => setCollapsed(!collapsed)}><Icon name="panel" width={15} height={15} /></button>
        <button ref={menuButton} className={styles.menuButton} type="button" aria-label="Toggle navigation" aria-expanded={menuOpen} aria-controls="workspace-navigation" onClick={() => setMenuOpen(!menuOpen)}><Icon name="menu" /></button></div>
      <nav id="workspace-navigation" className={`${styles.navigation} ${menuOpen ? styles.open : ''}`} aria-label="Main navigation">
        {navigation.map(({ href, label, icon }) => {
          const active = href.startsWith('/experiments/') ? pathname.startsWith('/experiments') && (href.includes('view=live') === live) : href === '/' ? pathname === '/' : pathname.startsWith(href);
          return <Link key={href} href={href} className={`${styles.navLink} ${active ? styles.active : ''}`} title={collapsed ? label : undefined} aria-label={label} aria-current={active ? 'page' : undefined}><Icon name={icon} width={17} height={17} /><span>{label}</span>{active && <span className={styles.activeDot} />}</Link>;
        })}
      </nav>
      <div className={styles.sidebarFoot}><Link className={styles.navLink} href="/settings/" aria-label="Settings" title={collapsed ? 'Settings' : undefined}><Icon name="settings" width={17} height={17} /><span>Connection settings</span></Link><p>LOADPILOT / WORKSPACE<span>Bounded tests. Measured evidence.</span></p></div>
    </aside>
    <div className={styles.body}><header className={styles.topbar}><ClusterContext /><div className={styles.accountArea}><span className={styles.utc}>UTC +00:00</span>{account}</div></header><main id="main-content" className={styles.content} tabIndex={-1}>{children}</main></div>
  </div>;
}
