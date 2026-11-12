'use client';

import { useState, type FormEvent } from 'react';
import { api } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import { PageHeading } from '../../components/console-ui';
import { Icon } from '../../components/icon';
import styles from '../../components/console-ui.module.css';
import { ExperimentNavigation } from './experiment-navigation';
import { snapshotOption, useExperimentRecords } from './experiment-records';

type Snapshot = { id: string; sha256: string; createdAt: string; inputs: { target: { id: string; name: string; namespace: string; deployment: string; container: string }; scenario: { script: { name: string; version: number; sha256: string } }; loadUnit: string; settings: Record<string, number>; executionBounds?: Record<string, number | null> } };
export function ComparisonWorkspace() {
  const records = useExperimentRecords('SNAPSHOTS');
  const [ids, setIds] = useState(['', '']);
  const [snapshots, setSnapshots] = useState<(Snapshot | null)[]>([null, null]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function load(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setSnapshots([null, null]);
    try {
      const result = await Promise.all(ids.map(async id => {
        if (!id) return null;
        const value = await api<Snapshot>(`/api/experiments/snapshots/${encodeURIComponent(id)}`);
        if (value.id !== id || !/^[a-f0-9]{64}$/.test(value.sha256)) throw new Error('Could not verify the selected snapshot.');
        return value;
      })); setSnapshots(result);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot open snapshot.'); }
    finally { setBusy(false); }
  }
  function download(index: number) {
    const snapshot = snapshots[index]; if (!snapshot) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(snapshot, null, 2) + '\n'], { type: 'application/json;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = `loadpilot-input-${snapshot.id}.json`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  const differentTargets = snapshots[0] && snapshots[1] && snapshots[0].inputs.target.id !== snapshots[1].inputs.target.id;
  const rows: [string, (snapshot: Snapshot) => string][] = [
    ['Saved at', item => new Date(item.createdAt).toLocaleString()],
    ['Target app', item => item.inputs.target.name], ['Namespace / workload', item => `${item.inputs.target.namespace} / ${item.inputs.target.deployment}`],
    ['Container', item => item.inputs.target.container], ['Script', item => `${item.inputs.scenario.script.name} · v${item.inputs.scenario.script.version}`],
    ['Load bounds', item => `${item.inputs.settings.minimumRate}–${item.inputs.settings.maximumRate} ${item.inputs.loadUnit}`],
    ['Generator CPU (mCPU)', item => String(item.inputs.executionBounds?.generatorCpuMillicores ?? '—')],
    ['Generator memory (MiB)', item => String(item.inputs.executionBounds?.generatorMemoryMiB ?? '—')],
  ];
  return <>
    <PageHeading title="Compare & export" />
    <ExperimentNavigation screen="compare" />
    <section className={styles.panel}><div className={styles.panelHead}><h2>Select saved inputs</h2></div><form className={styles.filters} onSubmit={load}>
      {ids.map((id, index) => <div className={styles.snapshotField} key={index}><label htmlFor={`comparison-input-${index}`}>{index === 0 ? 'Baseline' : 'Candidate'}</label><select id={`comparison-input-${index}`} value={id} disabled={busy || !records.items.length} onChange={event => { setIds(previous => previous.map((value, position) => position === index ? event.target.value : value)); setSnapshots([null, null]); setError(''); }}>
        <option value="">Choose saved inputs</option>
        {records.items.map(entry => <option key={entry.id} value={entry.id}>{snapshotOption(entry)}</option>)}
      </select></div>)}
      <Button type="submit" busy={busy} disabled={!ids.some(Boolean)}>Open selected inputs</Button>
    </form>
    {records.loading && <div className={styles.body} role="status">Loading saved inputs…</div>}
    {records.error && <div className={styles.body}><Notice title="Cannot load saved inputs" tone="error">{records.error}</Notice><Button variant="secondary" onClick={records.retry}>Try again</Button></div>}
    {!records.items.length && !records.loading && !records.error && <div className={styles.body}>Finalize an experiment’s inputs to select them here.</div>}
    {records.hasMore && !records.error && <div className={styles.body}><Button variant="secondary" busy={records.loading} onClick={records.more}>Load more saved inputs</Button></div>}
    </section>
    {error && <Notice title="Cannot open snapshot" tone="error">{error}</Notice>}
    {differentTargets && <Notice title="Different systems">These inputs describe different workloads.</Notice>}
    {snapshots.some(Boolean) && <section className={styles.panel} aria-labelledby="candidate-matrix-title"><div className={styles.panelHead}><h2 id="candidate-matrix-title">Saved configuration</h2><div className={styles.actions}>{snapshots.map((snapshot, index) => snapshot && <Button key={index} variant="secondary" onClick={() => download(index)}><Icon name="download" width={14} height={14} />{index === 0 ? 'Baseline' : 'Candidate'} JSON</Button>)}</div></div>
      <div className={styles.scroll} role="region" aria-label="Candidate comparison matrix" tabIndex={0}><table className={styles.table}><thead><tr><th scope="col">Parameter</th><th scope="col">Baseline</th><th scope="col">Candidate</th></tr></thead><tbody>{rows.map(([label, read]) => <tr key={label}><th scope="row">{label}</th>{snapshots.map((snapshot, index) => <td key={index}>{snapshot ? read(snapshot) : '—'}</td>)}</tr>)}</tbody></table></div>
      <div className={styles.footnote}>Configuration only. Open a run to see its measurements.</div>
    </section>}
  </>;
}
