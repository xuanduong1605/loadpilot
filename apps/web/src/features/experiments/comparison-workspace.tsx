'use client';

import { useState, type FormEvent } from 'react';
import Link from 'next/link';
import { api } from '../../lib/api';
import { Button, Notice } from '../../components/form-controls';
import { PageHeading, StatusBadge } from '../../components/console-ui';
import { Icon } from '../../components/icon';
import styles from '../../components/console-ui.module.css';
import { ExperimentNavigation } from './experiment-navigation';

type Snapshot = { id: string; sha256: string; createdAt: string; inputs: { target: { id: string; name: string; namespace: string; deployment: string; container: string }; scenario: { script: { version: number; sha256: string } }; loadUnit: string; settings: Record<string, number>; executionBounds?: Record<string, number | null> } };
const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

export function ComparisonWorkspace() {
  const [ids, setIds] = useState(['', '']);
  const [snapshots, setSnapshots] = useState<(Snapshot | null)[]>([null, null]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function load(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setSnapshots([null, null]);
    try {
      const result = await Promise.all(ids.map(async id => {
        if (!id.trim()) return null;
        const value = await api<Snapshot>(`/api/experiments/snapshots/${encodeURIComponent(id.trim())}`);
        if (value.id !== id.trim() || !/^[a-f0-9]{64}$/.test(value.sha256)) throw new Error('Could not verify the selected snapshot.');
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
    ['Target app', item => item.inputs.target.name], ['Namespace / workload', item => `${item.inputs.target.namespace} / ${item.inputs.target.deployment}`],
    ['Container', item => item.inputs.target.container], ['Script version', item => `v${item.inputs.scenario.script.version}`],
    ['Load bounds', item => `${item.inputs.settings.minimumRate}–${item.inputs.settings.maximumRate} ${item.inputs.loadUnit}`],
    ['Target CPU (mCPU)', () => 'No measured evidence'], ['Target replicas', () => 'No measured evidence'],
    ['Capacity interval', () => 'Not measured'], ['SLO at required load', () => 'UNEVALUATED'], ['Candidate ranking', () => 'Insufficient evidence'],
  ];
  return <>
    <PageHeading title="Comparison & Reports" description="Compare configurations at the same required load. Rank only candidates with valid SLO evidence." actions={<Button disabled><Icon name="download" width={14} height={14} />Export result report</Button>} />
    <ExperimentNavigation screen="compare" />
    <section className={styles.panel}><div className={styles.panelHead}><h2>Input snapshots</h2><StatusBadge tone="info">Immutable · SHA-256</StatusBadge></div><form className={styles.filters} onSubmit={load}>
      {ids.map((id, index) => <label key={index}>{index === 0 ? 'Baseline · snapshot ID' : 'Candidate · snapshot ID'}<input value={id} disabled={busy} onChange={event => { setIds(previous => previous.map((value, position) => position === index ? event.target.value : value)); setSnapshots([null, null]); setError(''); }} placeholder="Saved snapshot UUID" pattern="[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}" /></label>)}
      <Button type="submit" busy={busy} disabled={!ids.some(Boolean) || ids.some(id => id && !uuid.test(id.trim()))}>Open snapshots</Button>
    </form><div className={styles.footnote}>Snapshots contain approved inputs, not measured results or proof of candidate feasibility.</div></section>
    {error && <Notice title="Cannot open snapshot" tone="error">{error}</Notice>}
    {differentTargets && <Notice title="Different targets" tone="error">These snapshots belong to different targets. They cannot establish resource rankings or capacity at a common required load.</Notice>}
    {snapshots.some(Boolean) && <><section className={styles.panel} aria-labelledby="candidate-matrix-title"><div className={styles.panelHead}><div><h2 id="candidate-matrix-title">Configuration & evidence matrix</h2><p>Input configurations and measured results remain separate.</p></div><StatusBadge tone="warning">Measured ranking unavailable</StatusBadge></div>
      <div className={styles.scroll} role="region" aria-label="Candidate comparison matrix" tabIndex={0}><table className={styles.table}><thead><tr><th scope="col">Parameter</th><th scope="col">Baseline</th><th scope="col">Candidate</th></tr></thead><tbody>{rows.map(([label, read]) => <tr key={label}><th scope="row">{label}</th>{snapshots.map((snapshot, index) => <td key={index}>{snapshot ? read(snapshot) : '—'}</td>)}</tr>)}</tbody></table></div>
      <div className={styles.footnote}><span>No saved measurements establish Pass, Fail or a capacity interval.</span><span>Recommendations ≠ measurements</span></div>
    </section>
    <details><summary>Measurement requirements and AI availability</summary><div className={styles.grid}><section className={styles.panel}><div className={styles.panelHead}><h2>Verified evidence</h2><StatusBadge>No results yet</StatusBadge></div><div className={styles.body}><p className={styles.muted}>CPU/replica comparisons require valid measurements at the same required load, with a defined window and source. Input snapshots do not provide that evidence.</p><dl className={styles.meta}><dt>Telemetry</dt><dd>No saved measurements</dd><dt>Capacity bounds</dt><dd>Not determined</dd><dt>Restoration</dt><dd>Not verified</dd></dl></div></section>
    <section className={styles.panel}><div className={styles.panelHead}><h2>AI bottleneck hypotheses</h2><StatusBadge tone="warning">AI unavailable</StatusBadge></div><div className={styles.body}><p className={styles.muted}>AI analysis is not available yet. Hypotheses will cite their sources and verification steps separately; they do not replace SLO verdicts.</p><Button disabled>Analyze evidence with AI</Button></div></section></div></details>
    <section className={styles.panel}><div className={styles.panelHead}><h2>Export snapshots</h2><Icon name="download" width={16} height={16} /></div><div className={styles.body}><p className={styles.muted}>Download the opened input snapshot as JSON. Result reports and telemetry exports are not available yet.</p><div className={styles.actions}>{snapshots.map((snapshot, index) => <Button key={index} variant="secondary" disabled={!snapshot || busy} onClick={() => download(index)}>Export {index === 0 ? 'baseline' : 'candidate'} · JSON</Button>)}<Link className={styles.secondary} href="/experiments/new/">Open experiment setup</Link></div>{snapshots.map((snapshot, index) => snapshot && <p className={styles.note} key={index}>SHA-256 {index === 0 ? 'baseline' : 'candidate'}: <code>{snapshot.sha256}</code></p>)}</div></section>
    </>}
  </>;
}
