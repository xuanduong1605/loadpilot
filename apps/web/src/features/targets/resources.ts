import type { Inspection, Usage } from './connection-types';

export type ResourcePoint = { collectedAt: string; cpuMillicores: number | null; memoryBytes: number | null };
export type ResourceSummary = ResourcePoint & { readyPods: number | null; totalPods: number | null; restarts: number | null };

const valid = (value: number | null): value is number => value !== null && Number.isFinite(value) && value >= 0;
export function validUsage(usage: Usage, collectedAt: string): boolean {
    const captured = Date.parse(collectedAt);
    const timestamp = Date.parse(usage.timestamp ?? '');
    const window = /^(?=.+)(?:([0-9]{1,4})h)?(?:([0-9]{1,4})m)?(?:([0-9]{1,4}(?:\.[0-9]{1,9})?)s)?$/.exec(usage.window ?? '');
    const duration = window ? Number(window[1] ?? 0) * 3600 + Number(window[2] ?? 0) * 60 + Number(window[3] ?? 0) : 0;
    return usage.state === 'READY' && valid(usage.cpuMillicores) && valid(usage.memoryBytes) && Number.isFinite(captured)
      && Number.isFinite(timestamp) && timestamp <= captured + 5000 && timestamp >= captured - 120000 && duration > 0;
}
export function resourceSummary(snapshot: Inspection): ResourceSummary {
  const scoped = snapshot.kubernetes.state === 'READY';
  const samples = scoped && snapshot.metrics.state === 'READY' && snapshot.pods.length > 0 && snapshot.pods.every(({ usage }) => validUsage(usage, snapshot.collectedAt));
  const sum = (field: 'cpuMillicores' | 'memoryBytes') => {
    if (!samples) return null;
    const total = snapshot.pods.reduce((value, pod) => value + (pod.usage[field] ?? 0), 0);
    return Number.isFinite(total) ? total : null;
  };
  const restartSum = scoped && snapshot.pods.every(pod => valid(pod.restarts) && Number.isInteger(pod.restarts))
    ? snapshot.pods.reduce((value, pod) => value + (pod.restarts ?? 0), 0) : null;
  return { collectedAt: snapshot.collectedAt, cpuMillicores: sum('cpuMillicores'), memoryBytes: sum('memoryBytes'),
    readyPods: scoped ? snapshot.pods.filter(pod => pod.ready === true).length : null,
    totalPods: scoped ? snapshot.pods.length : null, restarts: restartSum !== null && Number.isFinite(restartSum) ? restartSum : null };
}

/** Page-local observations only; invalid points remain gaps instead of fabricated zero usage. */
export function appendObservation(history: ResourcePoint[], snapshot: Inspection): ResourcePoint[] {
  const point = resourceSummary(snapshot), time = Date.parse(point.collectedAt);
  if (!Number.isFinite(time)) return history;
  const previous = history.at(-1);
  if (previous && time < Date.parse(previous.collectedAt)) return history;
  const next = previous && Date.parse(previous.collectedAt) === time ? history.slice(0, -1) : history;
  return [...next, { collectedAt: point.collectedAt, cpuMillicores: point.cpuMillicores, memoryBytes: point.memoryBytes }].slice(-40);
}
