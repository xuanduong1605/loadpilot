'use client';

import { TextField } from '../../components/form-controls';
import styles from './settings.module.css';

export type ResourceCondition = { metric: string; threshold: number; scope: string; aggregation: string; operator: string };
export type SloPolicy = { latencyPercentile: string; latencyThresholdMs: number; maximumErrorRatio: number; successDefinition: string; throughputKind?: string | null; minimumThroughputRps?: number | null; throughputOperator?: string | null; resourceConditions?: ResourceCondition[] | null };
export type SloValues = { latencyPercentile: string; throughputChoice: string; minimumThroughputRps: string; resourceChoice: string; cpu: string; memory: string };
export function sloValues(policy?: SloPolicy | null): SloValues {
  return policy ? { latencyPercentile: policy.latencyPercentile, throughputChoice: policy.throughputKind ?? 'NONE', minimumThroughputRps: policy.minimumThroughputRps == null ? '' : String(policy.minimumThroughputRps),
    resourceChoice: policy.resourceConditions == null ? '' : policy.resourceConditions.length ? 'CUSTOM' : 'NONE',
    cpu: String(policy.resourceConditions?.find(item => item.metric === 'CPU_USAGE_MILLICORES')?.threshold ?? ''),
    memory: String(policy.resourceConditions?.find(item => item.metric === 'MEMORY_WORKING_SET_MIB')?.threshold ?? '') }
    : { latencyPercentile: '', throughputChoice: '', minimumThroughputRps: '', resourceChoice: '', cpu: '', memory: '' };
}
export function encodeSlo(value: SloValues, settings: Record<string, number>): SloPolicy {
  const throughput = value.throughputChoice !== 'NONE';
  return { latencyPercentile: value.latencyPercentile, latencyThresholdMs: settings.p95LatencyMs, maximumErrorRatio: settings.maximumErrorRatio,
    successDefinition: 'HTTP_200_399_WITHOUT_TRANSPORT_ERROR', throughputKind: throughput ? value.throughputChoice : null,
    minimumThroughputRps: throughput ? Number(value.minimumThroughputRps) : null, throughputOperator: throughput ? 'GE' : null,
    resourceConditions: !value.resourceChoice ? null : value.resourceChoice === 'NONE' ? [] : [
      ...(value.cpu ? [{ metric: 'CPU_USAGE_MILLICORES', threshold: Number(value.cpu), scope: 'SELECTED_CONTAINER_ALL_DEPLOYMENT_PODS', aggregation: 'MAXIMUM_OF_POD_SUM_OVER_MEASUREMENT_WINDOW', operator: 'LE' }] : []),
      ...(value.memory ? [{ metric: 'MEMORY_WORKING_SET_MIB', threshold: Number(value.memory), scope: 'SELECTED_CONTAINER_ALL_DEPLOYMENT_PODS', aggregation: 'MAXIMUM_OF_POD_SUM_OVER_MEASUREMENT_WINDOW', operator: 'LE' }] : []),
    ] };
}
export function SloPolicyEditor({ value, onChange, latencyThreshold, errorRatio, onLatency, onError }: {
  value: SloValues; onChange: (value: SloValues) => void; latencyThreshold: string; errorRatio: string; onLatency: (value: string) => void; onError: (value: string) => void;
}) {
  return <div className={styles.boundFields}>
    <div className={styles.selectField}><label htmlFor="slo-percentile">Latency percentile</label>
    <select id="slo-percentile" required value={value.latencyPercentile} onChange={event => onChange({ ...value, latencyPercentile: event.target.value })}>
      <option value="">Choose percentile</option><option value="P90">p90</option><option value="P95">p95</option><option value="P99">p99</option>
    </select></div>
    <TextField id="p95LatencyMs" name="p95LatencyMs" label="Maximum latency (milliseconds, <=)" type="number" min={1} max={600000} step={1} required value={latencyThreshold} onChange={event => onLatency(event.target.value)} />
    <TextField id="maximumErrorRatio" name="maximumErrorRatio" label="Maximum error ratio (0–1, <=)" type="number" min={0} max={1} step="any" required value={errorRatio} onChange={event => onError(event.target.value)} />
    <div className={styles.selectField}><label htmlFor="slo-throughput">Throughput SLO</label>
    <select id="slo-throughput" required value={value.throughputChoice} onChange={event => onChange({ ...value, throughputChoice: event.target.value })}>
      <option value="">Choose throughput condition</option><option value="NONE">Not configured</option><option value="TOTAL">Total HTTP throughput</option><option value="SUCCESSFUL">Successful HTTP throughput</option>
    </select></div>
    {value.throughputChoice && value.throughputChoice !== 'NONE' && <TextField id="slo-minimum-throughput" label="Minimum HTTP throughput (requests/s, >=)" type="number" min={0} max={1000000} step="any" required value={value.minimumThroughputRps}
      onChange={event => onChange({ ...value, minimumThroughputRps: event.target.value })} hint="Enter a value greater than zero. HTTP requests/s may differ from iterations/s." />}
    <div className={styles.selectField}><label htmlFor="slo-resources">Target resource SLO</label>
    <select id="slo-resources" required value={value.resourceChoice} onChange={event => onChange({ ...value, resourceChoice: event.target.value })}>
      <option value="">Choose resource condition</option><option value="NONE">Not configured</option><option value="CUSTOM">CPU and/or memory usage</option>
    </select></div>
    {value.resourceChoice === 'CUSTOM' && <>
      <TextField id="slo-cpu" label="Maximum target CPU usage (millicores, <=)" type="number" min={0} max={1000000000} step="any" required={!value.memory} value={value.cpu} onChange={event => onChange({ ...value, cpu: event.target.value })} />
      <TextField id="slo-memory" label="Maximum target memory working set (MiB, <=)" type="number" min={0} max={1000000000} step="any" required={!value.cpu} value={value.memory} onChange={event => onChange({ ...value, memory: event.target.value })} />
      <p>Enter at least one positive limit. At each sample, sum usage of the selected container across all pods of the target Deployment; compare the maximum sum in the measurement window. CPU usage differs from CPU requests and limits. These conditions require complete target telemetry.</p>
    </>}
    <p>Success means HTTP 200–399 without transport errors. Total throughput counts successful and failed outcomes, including transport failures; successful throughput counts only successes. Script checks are separate. Missing required evidence means Insufficient data.</p>
  </div>;
}
