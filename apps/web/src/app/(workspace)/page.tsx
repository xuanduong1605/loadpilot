import Link from 'next/link';
import { Icon, type IconName } from '../../components/icon';
import shared from '../../components/workspace.module.css';
import styles from './overview.module.css';

const steps: { title: string; description: string; href: string; icon: IconName }[] = [
  { title: 'Connect a target', description: 'Choose the API and workload you want to test.', href: '/targets/', icon: 'target' },
  { title: 'Shape your scenario', description: 'Define a user journey and the load it should handle.', href: '/scenarios/', icon: 'scenarios' },
  { title: 'Learn from a run', description: 'Review measured results against your performance goals.', href: '/experiments/', icon: 'chart' },
];

export default function OverviewPage() {
  return (
    <>
      <div className={shared.heading}>
        <span className={shared.eyebrow}>A little clarity, under load</span>
        <h1>Your performance workspace.</h1>
        <p>From the first request to a better resource decision.<br />Keep your targets, scenarios and experiments in one place.</p>
      </div>
      <section className={styles.welcome} aria-labelledby="welcome-title">
        <div>
          <span className={styles.welcomeLabel}>BUILT FOR BETTER QUESTIONS</span>
          <h2 id="welcome-title">Know your limits.<br /><em>Then find your balance.</em></h2>
          <p>Explore a workspace for repeatable load tests and evidence-backed resource decisions.</p>
          <Link className={styles.primaryLink} href="/targets/">Explore targets <Icon name="arrow" width={16} height={16} /></Link>
        </div>
        <div className={styles.diagram} aria-hidden="true">
          <span className={styles.diagramLabel}>THE EXPERIMENT LOOP</span>
          <div className={styles.diagramStep}><Icon name="target" /><span>Set a goal</span><span>01</span></div>
          <span className={styles.connector} />
          <div className={styles.diagramStep}><Icon name="experiments" /><span>Measure</span><span>02</span></div>
          <span className={styles.connector} />
          <div className={styles.diagramStep}><Icon name="chart" /><span>Refine</span><span>03</span></div>
        </div>
      </section>
      <section className={styles.gettingStarted} aria-labelledby="getting-started-title">
        <div className={styles.sectionTitle}><h2 id="getting-started-title">A simple path to better measurements</h2><span>THE WORKFLOW</span></div>
        <div className={styles.steps}>{steps.map((step, index) => (
          <Link href={step.href} className={styles.step} key={step.title}>
            <div className={styles.stepTop}><Icon name={step.icon} /><span>0{index + 1}</span></div>
            <h3>{step.title} <Icon name="arrow" width={15} height={15} /></h3>
            <p>{step.description}</p>
          </Link>
        ))}</div>
      </section>
      <section className={styles.recent} aria-labelledby="recent-title">
        <div className={styles.sectionTitle}><h2 id="recent-title">Recent experiments</h2><Link href="/experiments/">View experiments <Icon name="arrow" width={14} height={14} /></Link></div>
        <div className={styles.recentEmpty}><span className={styles.emptySymbol}><Icon name="experiments" width={25} height={25} /></span>
          <div><h3>No results to display yet</h3><p>This is a workspace preview. Live targets, runs and measurements are not connected.</p></div>
        </div>
      </section>
    </>
  );
}
