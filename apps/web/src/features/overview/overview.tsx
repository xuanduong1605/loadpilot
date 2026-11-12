import { TargetList } from '../targets/target-list';
import { ExperimentRecords } from '../experiments/experiment-records';
import styles from './overview.module.css';

export function Overview() {
  return <div className={styles.dashboard}>
    <section aria-label="Systems"><TargetList dashboard /></section>
    <ExperimentRecords kind="RUNS" title="Recent runs" preview />
  </div>;
}
