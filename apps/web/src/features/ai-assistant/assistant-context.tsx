'use client';

import { useEffect, useState } from 'react';
import { recordLabel, snapshotOption, useExperimentRecords } from '../experiments/experiment-records';
import { api } from '../../lib/api';
import type { Task } from './assistant-data';
import styles from './assistant.module.css';

const SIZE = 20;
type RecordOption = { id: string; label: string };
type PickerProps = { value: string | null; onChange: (id: string | null) => void; disabled: boolean };

export function AssistantContext({ task, ...props }: PickerProps & { task: Task }) {
  switch (task) {
    case 'SCRIPT': return <Scripts {...props} />;
    case 'STATUS':
    case 'RESULT': return <Experiments key="RUNS" kind="RUNS" {...props} />;
    case 'START': return <Experiments key="SNAPSHOTS" kind="SNAPSHOTS" {...props} />;
    case 'EDIT': return <Experiments key="DRAFTS" kind="DRAFTS" {...props} />;
    case 'ASK': return null;
  }
}

const labels = {
  RUNS: { label: 'Test run', fallback: 'Latest saved run' },
  SNAPSHOTS: { label: 'Finalized inputs', fallback: 'General run guidance' },
  DRAFTS: { label: 'Experiment draft', fallback: 'General setup guidance' },
};

function Experiments({ kind, ...props }: PickerProps & { kind: keyof typeof labels }) {
  const records = useExperimentRecords(kind, SIZE);
  const options = records.items.map(item => ({ id: item.id, label: kind === 'SNAPSHOTS' ? snapshotOption(item) :
    `${item.targetName ?? 'Unnamed system'} · ${recordLabel(item)} · ${new Date(item.listedAt).toLocaleString()}` }));
  return <Picker {...props} {...labels[kind]} {...records} options={options} />;
}

function Scripts(props: PickerProps) {
  const [options, setOptions] = useState<RecordOption[]>([]);
  const [offset, setOffset] = useState(0);
  const [retry, setRetry] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void api<{ items: { id: string; name: string; latestVersion: number }[]; total: number }>(`/api/scenarios?offset=${offset}&limit=${SIZE}`, { signal: controller.signal })
      .then(page => {
        if (!active) return;
        const next = page.items.map(item => ({ id: item.id, label: `${item.name} · v${item.latestVersion}` }));
        setOptions(current => offset ? [...current, ...next.filter(item => !current.some(entry => entry.id === item.id))] : next);
        setHasMore(offset + page.items.length < page.total);
      })
      .catch(reason => { if (active) setError(reason.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [offset, retry]);
  return <Picker {...props} label="Scenario script" fallback="General script guidance" options={options} hasMore={hasMore} loading={loading}
    error={error} retry={() => setRetry(count => count + 1)} more={() => setOffset(page => page + SIZE)} />;
}

function Picker({ value, onChange, disabled, label, fallback, options, hasMore, loading, error, retry, more }: PickerProps & {
  label: string; fallback: string; options: RecordOption[]; hasMore: boolean; loading: boolean; error: string;
  retry: () => void; more: () => void;
}) {
  return <div className={styles.picker}>
    <label><span>{label}</span><select value={value ?? ''} onChange={event => onChange(event.target.value || null)} disabled={disabled}>
      <option value="">{fallback}</option>
      {value && !options.some(item => item.id === value) && <option value={value}>Previously selected record</option>}
      {options.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
    </select></label>
    {loading && <span role="status">Loading records…</span>}
    {error && <><span role="alert" className={styles.error}>Could not load records.</span><button type="button" onClick={retry} disabled={disabled || loading}>Retry</button></>}
    {hasMore && !error && !loading && <button type="button" onClick={more} disabled={disabled}>Load more records</button>}
  </div>;
}
