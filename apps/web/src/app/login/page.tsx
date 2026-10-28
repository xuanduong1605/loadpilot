import type { Metadata } from 'next';
import Link from 'next/link';
import { Icon } from '../../components/icon';
import { LoginForm } from '../../features/access/login-form';
import styles from '../../features/access/login.module.css';

export const metadata: Metadata = { title: 'Sign in | Loadpilot' };

export default function LoginPage() {
  return (
    <div className={styles.page}>
      <a className="skip-link" href="#login-content">Skip to sign-in form</a>
      <main id="login-content" className={styles.formSide} tabIndex={-1}>
        <Link className={styles.brand} href="/" aria-label="Loadpilot home"><span className={styles.brandMark}><Icon name="chart" width={21} height={21} /></span> loadpilot.</Link>
        <div className={styles.formContainer}><LoginForm /></div>
      </main>
    </div>
  );
}
