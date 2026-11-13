'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import { api, mutate } from '../../lib/api';
import styles from './scenarios.module.css';
import CodeEditor from './code-editor';
import { Icon } from '../../components/icon';
import { PageHeading, RecordMetadata } from '../../components/console-ui';

type Version = { scenarioId: string; name: string; version: number; source: string; sha256: string; createdBy: string; createdAt: string; approved: boolean };
type ScriptReview = { id: string; version: number; sha256: string; syntaxValid: boolean; policyAccepted: boolean; policyVersion: string; parserVersion: string; runtimeCheckRequired: boolean; findings: { code: string; message: string; line: number; column: number }[] };

export function ScenarioEditor() {
  const [name, setName] = useState('');
  const [source, setSource] = useState('');
  const [current, setCurrent] = useState<Version>();
  const [latest, setLatest] = useState<number>();
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [review, setReview] = useState<ScriptReview>();
  const [acknowledged, setAcknowledged] = useState(false);
  const sourceBytes = new TextEncoder().encode(source).length;
  function accept(value: Version, latestVersion: number) {
    setReview(undefined);
    setAcknowledged(false);
    setCurrent(value); setLatest(latestVersion); setName(value.name); setSource(value.source); setDirty(false);
  }
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const id = params.get('id');
    const requestedVersion = params.get('version');
    if (!id) return;
    let active = true;
    setBusy(true);
    void api<Version>(`/api/scenarios/${encodeURIComponent(id)}`)
      .then(async newest => {
        const value = requestedVersion ? await api<Version>(`/api/scenarios/${encodeURIComponent(id)}/versions/${encodeURIComponent(requestedVersion)}`) : newest;
        if (active) accept(value, newest.version);
      }).catch(reason => { if (active) setError(reason.message); })
      .finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, []);
  async function open(id: string, version?: number) {
    if (dirty && !window.confirm('Discard unsaved script edits and open this saved version?')) return;
    setBusy(true); setError(''); setSaved(false);
    try {
      const newest = await api<Version>(`/api/scenarios/${id}`);
      const value = version ? await api<Version>(`/api/scenarios/${id}/versions/${version}`) : newest;
      accept(value, newest.version);
      window.history.replaceState(null, '', `/scenarios/editor/?id=${id}&version=${value.version}`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot open scenario.'); }
    finally { setBusy(false); }
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (sourceBytes > 65536) { setError('Script source exceeds the 64 KiB UTF-8 limit.'); return; }
    setBusy(true); setError(''); setSaved(false);
    try {
      const value = await mutate<Version>(current ? `/api/scenarios/${current.scenarioId}/versions` : '/api/scenarios', 'POST',
        { name, source, expectedVersion: current ? latest : undefined });
      accept(value, value.version); setSaved(true);
      window.history.replaceState(null, '', `/scenarios/editor/?id=${value.scenarioId}&version=${value.version}`);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot save scenario.'); }
    finally { setBusy(false); }
  }
  async function checkScript() {
    if (!current || dirty) return;
    setBusy(true); setError(''); setReview(undefined); setAcknowledged(false); setSaved(false);
    try { setReview(await mutate<ScriptReview>(`/api/scenarios/${current.scenarioId}/versions/${current.version}/validation`, 'POST', { sha256: current.sha256 })); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot check script.'); }
    finally { setBusy(false); }
  }
  async function approve() {
    if (!current || !review || dirty || !acknowledged) return;
    setBusy(true); setError('');
    try {
      await mutate(`/api/scenarios/${current.scenarioId}/versions/${current.version}/approval`, 'POST', { validationId: review.id, sha256: current.sha256, acknowledged });
      setCurrent({ ...current, approved: true }); setAcknowledged(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot approve this version.'); }
    finally { setBusy(false); }
  }
  function edit() { setDirty(true); setSaved(false); setReview(undefined); setAcknowledged(false); }
  return <>
    <PageHeading title="Script editor" back={{ href: '/scenarios/', label: 'Back to scripts' }} actions={current && <RecordMetadata name={current.name}>
      Version {current.version} · Saved by {current.createdBy} · <time dateTime={current.createdAt}>{new Date(current.createdAt).toLocaleString('en-GB', { timeZone: 'UTC' })} UTC</time>
    </RecordMetadata>} />
    <form className={styles.layout} data-script-editor onSubmit={save} aria-busy={busy}>
      <section className={styles.panel} aria-labelledby="script-settings-title" tabIndex={0}>
        <h2 id="script-settings-title">Script settings</h2>
        <TextField id="scenario-name" label="Script name" value={name} required maxLength={120} disabled={busy} readOnly={!!current} onChange={event => { setName(event.target.value); edit(); }} />
        <p className={styles.status} role="status" data-approved={current?.approved && !dirty}>{dirty ? 'Unsaved changes' : current?.approved ? 'Approved' : saved ? 'Version saved · Check before approval' : current ? 'Unapproved' : 'New script'}</p>
        <div className={styles.actions}>
          <Button className={styles.compactButton} type="submit" busy={busy} disabled={!dirty || !name.trim() || !source.trim() || sourceBytes > 65536}>Save {current ? 'new version' : 'scenario'}</Button>
          <Button className={styles.compactButton} variant="secondary" disabled={busy || dirty || !current} onClick={() => void checkScript()}>Check saved script</Button>
        </div>
        {sourceBytes > 65536 && <Notice title="Script too large" tone="error">Reduce the source to 65,536 UTF-8 bytes before saving.</Notice>}
        {error && <Notice title="Script action failed" tone="error">{error}</Notice>}
        {review && <section className={styles.review} aria-label="Script check results">
          <Notice title={review.syntaxValid && review.policyAccepted ? 'Static checks passed' : 'Script checks need attention'} tone={review.syntaxValid && review.policyAccepted ? 'info' : 'error'}>
            {review.syntaxValid && review.policyAccepted ? 'k6 runtime checks are still required.' : 'Review the findings and save a corrected version.'}
          </Notice>
          {review.findings.length > 0 && <ul>{review.findings.map((finding, index) => <li key={index}>Line {finding.line}, column {finding.column}: {finding.message}</li>)}</ul>}
          {review.syntaxValid && review.policyAccepted && !current?.approved && <>
            <label className={styles.acknowledgment}><input type="checkbox" checked={acknowledged} disabled={busy || dirty} onChange={event => setAcknowledged(event.target.checked)} /> I reviewed the exact source of saved version {review.version}.</label>
            <Button className={styles.compactButton} disabled={busy || dirty || !acknowledged} onClick={() => void approve()}><Icon name="lock" width={13} height={13} />Approve version</Button>
          </>}
        </section>}
        {current && <details className={styles.version}>
          <summary>Version details</summary>
          <dl>
            <dt>SHA-256</dt><dd><code>{current.sha256}</code></dd>
            {review && <><dt>Static check versions</dt><dd>{review.policyVersion} · {review.parserVersion}</dd></>}
          </dl>
          {current.approved && <p className={styles.status}>k6 runtime checks are still required.</p>}
          <div className={styles.actions}>
            <Button className={styles.compactButton} disabled={busy || current.version <= 1} variant="secondary" onClick={() => void open(current.scenarioId, current.version - 1)}>Older version</Button>
            <Button className={styles.compactButton} disabled={busy || current.version >= (latest ?? current.version)} variant="secondary" onClick={() => void open(current.scenarioId, current.version + 1)}>Newer version</Button>
            <Button className={styles.compactButton} disabled={busy} variant="secondary" onClick={() => void open(current.scenarioId)}>Reload latest</Button>
          </div>
        </details>}
      </section>
      <CodeEditor value={source} disabled={busy} sourceSize={`${sourceBytes.toLocaleString()} / 65,536 UTF-8 bytes`} onChange={value => { setSource(value); edit(); }} />
    </form>
  </>;
}
