'use client';
import Link from 'next/link';
import styles from './feature-workspace.module.css';
export function FeatureNav({ label, items }: { label: string; items: { label: string; href: string; active: boolean }[] }) {
  return <nav className={styles.navigation} aria-label={label}>{items.map(item => <Link key={item.href} href={item.href} aria-current={item.active ? 'page' : undefined}>{item.label}</Link>)}</nav>;
}
