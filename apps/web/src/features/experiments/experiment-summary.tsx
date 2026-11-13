import type { ReactNode } from 'react';
import { Icon } from '../../components/icon';
import { ProfilePreview } from './profile-preview';
import type { LoadProfile } from './load-profile';
import type { ResourceCondition } from './slo-policy';
import styles from './experiment-summary.module.css';

type Value = number | string | null | undefined;
type TargetValue = { value: Value; unit: string; operator?: string; empty?: string };
type SloSummary = {
  percentile?: string;
  latency: Value;
  errorRatio: Value;
  ceiling?: string;
  throughputKind?: string | null;
  throughput: Value;
  throughputOperator?: string;
  throughputEmpty?: string;
  resources?: ResourceCondition[] | null;
  resourceEmpty?: string;
  httpDefinition?: boolean;
  edit?: ReactNode;
};

function asNumber(value: Value) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return undefined;
  return Number(value);
}

function TargetValue({ value, unit, operator = '≤', empty = 'Not entered' }: TargetValue) {
  const amount = asNumber(value);
  return amount === undefined ? <span className={styles.unset}>{empty}</span> : <span className={styles.amount}>
    <span className={styles.operator}>{operator}</span> {String(amount)} <span className={styles.unit}>{unit}</span>
  </span>;
}

export function SloSummary({ percentile, latency, errorRatio, ceiling = '≤', throughputKind, throughput, throughputOperator = '≥', throughputEmpty, resources, resourceEmpty, httpDefinition, edit }: SloSummary) {
  const ratio = asNumber(errorRatio);
  return <section className={styles.section} aria-label="SLO targets">
    <div className={styles.sectionHead}><div><h3>SLO targets</h3><p>Thresholds for evaluating this test.</p></div>{edit}</div>
    <dl className={styles.targets}>
      <div className={styles.latency}>
        <dt><Icon name="clock" width={18} height={18} />{percentile ? `${percentile.toLowerCase()} latency` : 'Latency percentile'}</dt>
        <dd><TargetValue value={percentile ? latency : undefined} unit="ms" operator={ceiling} empty={percentile ? undefined : 'Choose a percentile'} /></dd>
      </div>
      <div><dt>Error rate</dt><dd><TargetValue value={ratio === undefined ? undefined : Number((ratio * 100).toPrecision(15))} unit="%" operator={ceiling} /></dd></div>
      <div><dt>{throughputKind === 'TOTAL' ? 'Total HTTP throughput' : throughputKind === 'SUCCESSFUL' ? 'Successful HTTP throughput' : 'HTTP throughput'}</dt>
        <dd><TargetValue value={throughputKind ? throughput : undefined} unit="req/s" operator={throughputOperator} empty={throughputEmpty} /></dd></div>
    </dl>
    {(!!resources?.length || resourceEmpty) && <dl className={styles.resourceTargets}>
      {resources?.length ? resources.map(({ metric, threshold, operator }) => {
        const cpu = metric === 'CPU_USAGE_MILLICORES';
        return <div key={metric}><dt>{cpu ? 'Target CPU usage' : 'Target memory working set'}</dt>
          <dd><TargetValue value={threshold} unit={cpu ? 'mCPU' : 'MiB'} operator={operator === 'LE' ? '≤' : operator} /></dd></div>;
      }) : resourceEmpty && <div><dt>Target resource SLO</dt><dd className={styles.unset}>{resourceEmpty}</dd></div>}
    </dl>}
    {(httpDefinition || !!resources?.length || ratio !== undefined) && <details className={styles.disclosure}>
      <summary>How thresholds are evaluated</summary>
      {ratio !== undefined && <p>Error ratio <code>{ratio}</code>.</p>}
      {httpDefinition && <p>HTTP success is a 200–399 response without a transport error. Total throughput counts successful and failed outcomes. Script checks are evaluated separately.</p>}
      {!!resources?.length && <p>Resource thresholds use the maximum of the selected container’s usage summed across Deployment pods during the measurement window.</p>}
      <p>Missing required evidence yields Insufficient data.</p>
    </details>}
  </section>;
}

export function SummaryRows({ rows }: { rows: [string, ReactNode][] }) {
  return <dl className={styles.rows}>{rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value ?? 'Not entered'}</dd></div>)}</dl>;
}

export function LoadSummary({ rows, profile, duration, warmup, edit }: { rows: [string, ReactNode][]; profile?: LoadProfile | null; duration?: number; warmup?: number; edit?: ReactNode }) {
  return <section className={styles.section} aria-label="Load and measurement">
    <div className={styles.sectionHead}><h3>Load and measurement</h3>{edit}</div>
    <div className={styles.load}>
      <SummaryRows rows={rows} />
      {profile && duration !== undefined && warmup !== undefined && <ProfilePreview profile={profile} durationSeconds={duration} warmupSeconds={warmup} />}
    </div>
    {!!profile?.stages.length && <details className={styles.disclosure}>
      <summary>Load stages</summary>
      <ol>{profile.stages.map((stage, index) => <li key={index}>{stage.durationSeconds} s to {stage.targetRate} iterations/s</li>)}</ol>
      <p>Stages ramp from the preceding rate. Equal consecutive rates hold a plateau.</p>
    </details>}
  </section>;
}
