import type { Metadata } from 'next';
import { BrandLink } from '../../components/brand-link';
import { LoginForm } from '../../features/access/login-form';
import styles from '../../features/access/login.module.css';
import guide from '../../components/task-guide.module.css';

export const metadata: Metadata = { title: 'Sign in | Loadpilot' };

export default function LoginPage() {
  return (
    <div className={`${guide.theme} ${styles.page}`}>
      <a className="skip-link" href="#login-content">Skip to sign-in form</a>
      <main id="login-content" className={styles.formSide} tabIndex={-1}>
        <BrandLink className={styles.brand} />
        <div className={styles.formContainer}><LoginForm /></div>
      </main>
    </div>
  );
}
