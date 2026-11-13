import type { ReactNode } from 'react';
import Link from 'next/link';
import { Icon } from './icon';
import styles from './console-ui.module.css';

export function PageHeading({ title, description, back, actions }: { title: string; description?: string; back?: { href: string; label: string }; actions?: ReactNode }) {
  return <header className={styles.heading}>
    <div className={styles.headingTitle}>
      {back && <Link className={styles.backLink} href={back.href} aria-label={back.label} title={back.label}><Icon name="arrowLeft" /></Link>}
      <div><h1>{title}</h1>{description && <p>{description}</p>}</div>
    </div>
    {actions && <div className={styles.headingActions}>{actions}</div>}
  </header>;
}

export function RecordMetadata({ name, children }: { name?: string; children?: ReactNode }) {
  return <div className={styles.recordMetadata}>{name && <strong>{name}</strong>}{children && <p>{children}</p>}</div>;
}

export function StatusBadge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'info' | 'success' | 'warning' | 'danger' }) {
  return <span className={styles.badge} data-tone={tone}>{children}</span>;
}
