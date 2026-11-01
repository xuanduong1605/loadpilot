'use client';

import Link from 'next/link';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Icon } from '../../components/icon';
import styles from './session.module.css';

export function AccountMenu({ username, leaving, onLogout }: { username: string; leaving: boolean; onLogout: () => void }) {
  const [open, setOpen] = useState(false);
  const [compact, setCompact] = useState(false);
  const [storageAvailable, setStorageAvailable] = useState(true);
  const account = useRef<HTMLDivElement>(null);
  const avatar = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const preferences = useRef<HTMLDialogElement>(null);
  const firstFocus = useRef<'first' | 'last'>('first');

  useEffect(() => {
    try {
      const value = localStorage.getItem('loadpilot:compact-layout') === 'true';
      setCompact(value); document.documentElement.dataset.compactLayout = String(value);
    } catch { setStorageAvailable(false); }
  }, []);
  useEffect(() => {
    if (!open) return;
    const items = menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]');
    items?.[firstFocus.current === 'last' ? items.length - 1 : 0]?.focus();
    const dismiss = (event: PointerEvent) => {
      if (!account.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, [open]);

  function close(restoreFocus = false) {
    setOpen(false);
    if (restoreFocus) avatar.current?.focus();
  }
  function navigate(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') { event.preventDefault(); close(true); return; }
    if (event.key === 'Tab') { close(true); return; }
    const items = Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
    if (!items.length || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const current = items.indexOf(document.activeElement as HTMLElement);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
      : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next]?.focus();
  }
  function changeCompact(value: boolean) {
    setCompact(value); document.documentElement.dataset.compactLayout = String(value);
    try { localStorage.setItem('loadpilot:compact-layout', String(value)); }
    catch { setStorageAvailable(false); }
  }

  return <div className={styles.account} ref={account}>
    <button ref={avatar} className={styles.avatar} type="button" aria-label={`Account menu for ${username}`}
      aria-haspopup="menu" aria-expanded={open} aria-controls={open ? 'account-menu' : undefined} disabled={leaving} aria-busy={leaving}
      onClick={() => { firstFocus.current = 'first'; setOpen(value => !value); }}
      onKeyDown={event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault(); firstFocus.current = event.key === 'ArrowUp' ? 'last' : 'first'; setOpen(true);
        }
      }}><span aria-hidden="true">{Array.from(username)[0]?.toUpperCase()}</span></button>
    {open && <div ref={menu} id="account-menu" role="menu" aria-label="Account" className={styles.accountMenu} onKeyDown={navigate}>
      <div className={styles.menuIdentity} role="presentation">{username}</div>
      <Link role="menuitem" tabIndex={-1} href="/settings/" className={styles.menuItem} onClick={() => close()}><Icon name="settings" width={17} height={17} />Settings</Link>
      <button role="menuitem" tabIndex={-1} type="button" className={styles.menuItem} onClick={() => { close(); preferences.current?.showModal(); }}><Icon name="preferences" width={17} height={17} />Preferences</button>
      <div role="separator" className={styles.menuSeparator} />
      <button role="menuitem" tabIndex={-1} type="button" className={`${styles.menuItem} ${styles.signOut}`} onClick={() => { close(); onLogout(); }}><Icon name="logout" width={17} height={17} />Sign out</button>
    </div>}
    <dialog ref={preferences} className={styles.preferences} aria-labelledby="preferences-title" onClose={() => avatar.current?.focus()}>
      <div className={styles.preferencesHeader}><h2 id="preferences-title">Preferences</h2><button type="button" className={styles.closePreferences} aria-label="Close preferences" onClick={() => preferences.current?.close()}>×</button></div>
      <p className={styles.preferenceHint}>{storageAvailable ? 'Display choices apply to this browser.' : 'Browser storage is unavailable. Changes apply for this visit.'}</p>
      <label className={styles.preferenceOption}><input type="checkbox" checked={compact} onChange={event => changeCompact(event.target.checked)} />
        <span><strong>Compact layout</strong><span>Use less space around page content and navigation.</span></span>
      </label>
      <button type="button" className={styles.preferencesDone} onClick={() => preferences.current?.close()}>Done</button>
    </dialog>
  </div>;
}
