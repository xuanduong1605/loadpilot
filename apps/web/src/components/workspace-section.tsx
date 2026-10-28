import Link from 'next/link';
import { Icon, type IconName } from './icon';
import styles from './workspace.module.css';

export function WorkspaceSection({ title, description, icon, heading, children }: {
  title: string;
  description: string;
  icon: IconName;
  heading: string;
  children: React.ReactNode;
}) {
  return (
    <>
      <div className={styles.heading}>
        <span className={styles.eyebrow}>Workspace / {title}</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <section className={styles.empty} aria-label={`${title} preview`}>
        <span className={styles.emptyIcon}><Icon name={icon} width={26} height={26} /></span>
        <h2>{heading}</h2>
        <p>{children}</p>
        <Link className={styles.textLink} href="/">Back to overview <Icon name="arrow" width={16} height={16} /></Link>
      </section>
    </>
  );
}
