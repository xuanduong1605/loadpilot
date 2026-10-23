import type { Metadata } from 'next';
import Link from 'next/link';
import { Icon } from '../../components/icon';
import { LoginPreview } from '../../features/access/login-preview';
import styles from '../../features/access/login.module.css';

export const metadata: Metadata = { title: 'Sign in preview | Loadpilot' };

export default function LoginPage() {
  return (
    <div className={styles.page}>
      <a className="skip-link" href="#login-content">Skip to sign-in form</a>
      <aside className={styles.story} aria-label="About Loadpilot">
        <Link className={styles.brand} href="/"><span className={styles.brandMark}>↗</span> loadpilot.</Link>
        <div className={styles.storyBody}>
          <span className={styles.eyebrow}>CONFIDENCE, UNDER LOAD</span>
          <h2>Less guesswork.<br /><em>Better performance.</em></h2>
          <p>A place to ask better questions about your application, and find answers in the measurements.</p>
          <ul className={styles.principles}>
            <li><Icon name="check" width={16} height={16} /> Repeatable experiments</li>
            <li><Icon name="check" width={16} height={16} /> Clear performance goals</li>
            <li><Icon name="check" width={16} height={16} /> Evidence behind every decision</li>
          </ul>
        </div>
        <span className={styles.storyFooter}>Loadpilot · Performance laboratory</span>
      </aside>
      <main id="login-content" className={styles.formSide} tabIndex={-1}>
        <div className={styles.formContainer}><LoginPreview /></div>
      </main>
    </div>
  );
}
