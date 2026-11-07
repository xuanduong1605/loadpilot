'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import { api, mutate } from '../../lib/api';
import styles from './scenarios.module.css';
import CodeEditor from './code-editor';
import { Icon } from '../../components/icon';

type Version = { scenarioId: string; name: string; version: number; source: string; sha256: string; createdBy: string; createdAt: string; approved: boolean };
type ScenarioPage = { items: { id: string; name: string; latestVersion: number }[]; total: number };
type ScriptReview = { id: string; version: number; sha256: string; syntaxValid: boolean; policyAccepted: boolean; policyVersion: string; parserVersion: string; runtimeCheckRequired: boolean; findings: { code: string; message: string; line: number; column: number }[] };

export function ScenarioEditor() {
  const [name, setName] = useState('');
  const [source, setSource] = useState('');
  const [current, setCurrent] = useState<Version>();
  const [latest, setLatest] = useState<number>();
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [listError, setListError] = useState('');
  const [page, setPage] = useState<ScenarioPage>();
  const [offset, setOffset] = useState(0);
  const [refresh, setRefresh] = useState(0);
  const [saved, setSaved] = useState(false);
  const [review, setReview] = useState<ScriptReview>();
  const [acknowledged, setAcknowledged] = useState(false);
  const [prompt, setPrompt] = useState('');
  const sourceBytes = new TextEncoder().encode(source).length;
  function accept(value: Version, latestVersion: number) {
    setReview(undefined);
    setAcknowledged(false);
    setCurrent(value); setLatest(latestVersion); setName(value.name); setSource(value.source); setDirty(false);
  }
  useEffect(() => {
    let active = true;
    setPage(undefined); setListError('');
    void api<ScenarioPage>(`/api/scenarios?offset=${offset}&limit=12`)
      .then(value => { if (active) setPage(value); }).catch(reason => { if (active) setListError(reason.message); });
    return () => { active = false; };
  }, [offset, refresh]);
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
      window.history.replaceState(null, '', `/scenarios/?id=${id}&version=${value.version}`);
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
      accept(value, value.version); setSaved(true); setRefresh(value => value + 1);
      window.history.replaceState(null, '', `/scenarios/?id=${value.scenarioId}&version=${value.version}`);
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
  return <div className={styles.layout}>
    <aside className={styles.side}>
    <section className={styles.panel} aria-labelledby="ai-script-title"><div className={styles.panelTitle}><h2 id="ai-script-title">Script requirements</h2><span className={styles.planned}>AI unavailable</span></div>
      <label className={styles.promptLabel} htmlFor="script-prompt">Natural-language requirements</label><textarea id="script-prompt" value={prompt} onChange={event => setPrompt(event.target.value)} placeholder="Describe endpoints, user behavior and checks…" rows={6} maxLength={4000} />
      <p>Describe the script you need. AI is unavailable; this text is not submitted or saved automatically.</p><Button disabled title="AI is not implemented yet">Draft with AI</Button>
    </section>
    <section className={styles.panel} aria-labelledby="saved-scenarios"><h2 id="saved-scenarios">Saved scripts</h2>
      {!page && !listError && <p role="status">Loading scenarios…</p>}
      {listError && <><Notice tone="error" title="Cannot load scenarios">{listError}</Notice><Button onClick={() => setRefresh(value => value + 1)}>Retry list</Button></>}
      {page && <><ul className={styles.list}>{page.items.map(item => <li key={item.id}><button disabled={busy} onClick={() => void open(item.id)}>{item.name} · v{item.latestVersion}</button></li>)}</ul>
        {!page.items.length && <p>No saved scenarios on this page.</p>}
        <div className={styles.actions}><Button variant="secondary" disabled={busy || offset === 0} onClick={() => setOffset(value => Math.max(0, value - 12))}>Previous</Button>
        <Button variant="secondary" disabled={busy || offset + 12 >= page.total} onClick={() => setOffset(value => value + 12)}>Next</Button></div></>}
      <Button disabled={busy} variant="secondary" onClick={() => {
        if (dirty && !window.confirm('Discard unsaved script edits and create a new scenario?')) return;
        setCurrent(undefined); setLatest(undefined); setName(''); setSource(''); setDirty(false); setError(''); setSaved(false); setReview(undefined); setAcknowledged(false);
        window.history.replaceState(null, '', '/scenarios/');
      }}>New scenario</Button>
    </section>
    <section className={styles.approvalGuide}><Icon name="lock" width={16} height={16} /><div><strong>Approve the exact version</strong><p>Save, check syntax, verify SHA-256, then approve. Each edit creates a new version that needs approval again.</p></div></section>
    </aside>
    <form className={styles.panel} onSubmit={save}>
      <div className={styles.panelTitle}><h2>k6 Script Studio</h2><span className={styles.planned}>{current?.approved && !dirty ? 'Approved · bound to hash' : dirty ? 'Unsaved changes' : 'Unapproved'}</span></div><p>Save exact script versions for review. Keep credentials out of scripts; saved source is retained in history.</p>
      <fieldset disabled={busy} className={styles.fields}>
        <TextField id="scenario-name" label="Scenario name" value={name} required maxLength={120} readOnly={!!current} onChange={event => { setName(event.target.value); edit(); }} />
        <span className={styles.sourceLabel}>Script source</span>
        <CodeEditor value={source} disabled={busy} onChange={value => { setSource(value); edit(); }} />
        <p>{sourceBytes.toLocaleString()} / 65,536 UTF-8 bytes</p>
        {sourceBytes > 65536 && <Notice title="Script too large" tone="error">Reduce the source to 65,536 UTF-8 bytes before saving.</Notice>}
      </fieldset>
      <div className={styles.actions}><Button type="submit" busy={busy} disabled={!dirty || !name.trim() || !source.trim() || sourceBytes > 65536}>Save {current ? 'new version' : 'scenario'}</Button>
      <Button variant="secondary" disabled={busy || dirty || !current} onClick={() => void checkScript()}>Check saved script</Button>
      <Button disabled={busy || dirty || !review?.syntaxValid || !review?.policyAccepted || !acknowledged || current?.approved} onClick={() => void approve()}><Icon name="lock" width={13} height={13} />Approve version</Button></div>
      {error && <Notice title="Scenario action failed" tone="error">{error}</Notice>}
      {review && <section aria-label="Script check results">
        <Notice title={review.syntaxValid && review.policyAccepted ? 'Static checks passed' : 'Script checks need attention'} tone={review.syntaxValid && review.policyAccepted ? 'info' : 'error'}>
          Version {review.version} · {review.policyVersion} · parser {review.parserVersion}. These parse-only checks do not execute the script or confirm k6 runtime compatibility, destination safety or approval.
        </Notice>
        {review.findings.length > 0 && <ul>{review.findings.map((finding, index) => <li key={index}>Line {finding.line}, column {finding.column}: {finding.message}</li>)}</ul>}
        {review.syntaxValid && review.policyAccepted && !current?.approved && <label><input type="checkbox" checked={acknowledged} disabled={busy || dirty} onChange={event => setAcknowledged(event.target.checked)} /> I reviewed the exact source and SHA-256 of saved version {review.version}. Approval does not start a test.</label>}
      </section>}
      {saved && <Notice title="Version saved">Exact source retained. This version is unvalidated and unapproved.</Notice>}
      {dirty && <p role="status">Unsaved edits. Save a new version to retain them.</p>}
      {current && <section className={styles.version} aria-label="Saved version details"><h3>Saved version {current.version}</h3>
        <p>{current.approved ? 'Approved saved version — runtime checks still required' : 'Unapproved'} · saved by {current.createdBy} · {new Date(current.createdAt).toLocaleString('en-GB', { timeZone: 'UTC' })}</p>
        <p>SHA-256: <code>{current.sha256}</code></p>
        <div className={styles.actions}><Button disabled={busy || current.version <= 1} variant="secondary" onClick={() => void open(current.scenarioId, current.version - 1)}>Older version</Button>
        <Button disabled={busy || current.version >= (latest ?? current.version)} variant="secondary" onClick={() => void open(current.scenarioId, current.version + 1)}>Newer version</Button>
        <Button disabled={busy} variant="secondary" onClick={() => void open(current.scenarioId)}>Reload latest</Button></div>
      </section>}
    </form>
  </div>;
}
