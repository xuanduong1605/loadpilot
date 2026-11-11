import Link from 'next/link';
import { Icon } from './icon';
import styles from './brand-link.module.css';

export function BrandLink({ className }: { className?: string }) {
  return (
    <Link className={className ? `${styles.brand} ${className}` : styles.brand} href="/" aria-label="Loadpilot home">
      <span className={styles.mark}><Icon name="chart" width={21} height={21} /></span>
      <span>loadpilot<span className={styles.dot}>.</span></span>
    </Link>
  );
}
