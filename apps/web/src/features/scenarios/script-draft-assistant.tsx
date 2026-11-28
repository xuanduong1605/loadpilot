'use client';

import { useEffect, useRef, useState } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import controls from '../../components/form-controls.module.css';
import { api, mutate } from '../../lib/api';
import type { TargetPage } from '../targets/types';
import styles from './scenarios.module.css';

type Check = { method: 'GET' | 'HEAD'; expectedStatus: number | null };
type ScriptDraft = { source: string | null; method: Check['method'] | null; expectedStatus: number | null;
  errors: string[]; steps?: (Check & { path: string })[] };
const PAGE_SIZE = 20;

export function ScriptDraftAssistant({ source, version, disabled, apply }: {
  source: string; version: string; disabled: boolean; apply: (source: string) => void;
}) {
  const [opened, setOpened] = useState(false);
  const [targets, setTargets] = useState<TargetPage>();
  const [offset, setOffset] = useState(0);
  const [retry, setRetry] = useState(0);
  const [targetsError, setTargetsError] = useState('');
  const [targetId, setTargetId] = useState('');
  const [timeout, setTimeoutValue] = useState('');
  const [pathText, setPathText] = useState('');
  const [goal, setGoal] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [applied, setApplied] = useState(false);
  const [preview, setPreview] = useState<{ draft: ScriptDraft; source: string; version: string }>();
  const summary = useRef<HTMLElement>(null);
  const timeoutMs = Number(timeout);
  const validTimeout = /^\d+$/.test(timeout) && timeoutMs >= 1 && timeoutMs <= 120000;
  const paths = pathText.split(/\r?\n/).map(path => path.trim()).filter(Boolean);
  const invalidPaths = paths.length > 4 || paths.some(path => path.length > 512 || !/^\/(?!\/)[^?#\\\s]*$/.test(path));

  useEffect(() => {
    if (!opened) return;
    const controller = new AbortController();
    let active = true;
    setTargetsError('');
    void api<TargetPage>(`/api/targets?offset=${offset}&limit=${PAGE_SIZE}`, { signal: controller.signal })
      .then(page => {
        if (active) setTargets(prior => ({ ...page, items: offset && prior ? [...prior.items, ...page.items] : page.items }));
      }).catch(reason => { if (active) setTargetsError(reason.message); });
    return () => { active = false; controller.abort(); };
  }, [opened, offset, retry]);

  function clearPreview() { setPreview(undefined); setError(''); setApplied(false); }

  async function requestDraft() {
    if (busy || disabled || !targetId || !goal.trim() || !validTimeout || invalidPaths) return;
    clearPreview(); setBusy(true);
    try {
      const draft = await mutate<ScriptDraft>('/api/assistant/script-draft', 'POST', {
        targetId, goal: goal.trim(), requestTimeoutMs: timeoutMs, paths: paths.length ? paths : undefined,
      });
      if (!draft.source || draft.errors.length) {
        setError(draft.errors.join(' ') || 'Gemini did not return a script draft.');
        return;
      }
      setPreview({ draft, source, version });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Could not request a script draft.');
    } finally { setBusy(false); }
  }

  function useDraft() {
    if (!preview?.draft.source) return;
    if (preview.source !== source || preview.version !== version) {
      setPreview(undefined);
      setError('The editor changed after this preview. Request a new draft to review it against your current work.');
      return;
    }
    apply(preview.draft.source);
    setPreview(undefined); setApplied(true);
    summary.current?.focus();
  }

  return <details className={styles.version} onToggle={event => { if (event.currentTarget.open) setOpened(true); }}>
    <summary ref={summary}>Draft with Gemini</summary>
    <div className={styles.scriptDraft}>
      <div className={controls.field}>
        <label htmlFor="script-draft-system">Registered system</label>
        <select id="script-draft-system" className={controls.input} value={targetId} disabled={disabled || busy}
          onChange={event => { setTargetId(event.target.value); clearPreview(); }}>
          <option value="">Choose a system</option>
          {targets?.items.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
        {opened && !targets && !targetsError && <span role="status">Loading systems…</span>}
        {targets?.total === 0 && <span className={controls.hint}>Register a system before drafting.</span>}
        {targetsError ? <><Notice title="Cannot load systems" tone="error">{targetsError}</Notice>
          <div><Button variant="secondary" onClick={() => setRetry(value => value + 1)}>Retry systems</Button></div></>
          : targets && targets.items.length < targets.total && <div><Button variant="secondary" disabled={busy || offset >= targets.items.length}
            onClick={() => setOffset(targets.items.length)}>Load more systems</Button></div>}
      </div>
      <TextField id="script-draft-timeout" label="Request timeout (ms)" inputMode="numeric" value={timeout} maxLength={6}
        disabled={disabled || busy} error={timeout && !validTimeout ? 'Enter a whole number from 1 to 120000.' : undefined}
        onKeyDown={event => { if (event.key === 'Enter') event.preventDefault(); }}
        onChange={event => { setTimeoutValue(event.target.value); clearPreview(); }} />
      <div className={controls.field}>
        <label htmlFor="script-draft-goal">Describe GET or HEAD checks</label>
        <textarea id="script-draft-goal" className={controls.input} value={goal} readOnly={disabled || busy} rows={3} maxLength={2000}
          placeholder="Example: GET and check for HTTP 200." aria-describedby="script-draft-sharing"
          onChange={event => { setGoal(event.target.value); clearPreview(); }} />
        <span id="script-draft-sharing" className={controls.hint}>Gemini receives your goal text. Enter request paths separately and keep URLs and credentials out of the goal. Set the load shape in Experiment Setup.</span>
      </div>
      <details className={styles.version}>
        <summary>Request paths{paths.length ? ` (${paths.length})` : ''}</summary>
        <div className={controls.field}>
          <label htmlFor="script-draft-paths">Paths on the selected origin</label>
          <textarea id="script-draft-paths" className={controls.input} value={pathText} readOnly={disabled || busy} rows={2} maxLength={2051}
            placeholder={'/health\n/catalog'} aria-invalid={invalidPaths || undefined} aria-describedby="script-draft-paths-help"
            onChange={event => { setPathText(event.target.value); clearPreview(); }} />
          <span id="script-draft-paths-help" className={controls.hint}>{invalidPaths
            ? 'Enter up to four paths starting with a single slash, with no query strings or fragments.'
            : 'Leave blank for the registered URL. For multiple checks, enter one path per line and describe each request in order.'}</span>
        </div>
      </details>
      <div className={styles.actions}><Button className={styles.compactButton} variant="secondary" busy={busy}
        disabled={disabled || !targetId || !validTimeout || !goal.trim() || invalidPaths} onClick={() => void requestDraft()}>
        {busy ? 'Drafting script…' : 'Preview script'}
      </Button></div>
      {error && <Notice title="Script preview unavailable" tone="error">{error}</Notice>}
      {preview && <>
        {preview.draft.steps?.length ? <div className={styles.scriptDraftSteps}>
          <p className={styles.status}>{preview.draft.steps.length} {preview.draft.steps.length === 1 ? 'request' : 'requests'} per iteration · Draft checks passed</p>
          <ol aria-label="Proposed request order">{preview.draft.steps.map((step, index) => <li key={index}>
            <strong>{step.method}</strong> <code>{step.path}</code> · HTTP {step.expectedStatus ?? '2xx or 3xx'}
          </li>)}</ol>
        </div> : <p className={styles.status}>{preview.draft.method} · HTTP {preview.draft.expectedStatus ?? '2xx or 3xx'} · Draft checks passed</p>}
        <pre className={styles.scriptDraftSource} aria-label="Proposed script source" tabIndex={0}>{preview.draft.source}</pre>
        <p className={styles.status}>{source ? 'Using this draft replaces the source in the editor.' : 'Using this draft fills the empty editor.'}</p>
        <div className={styles.actions}>
          <Button className={styles.compactButton} disabled={disabled} onClick={useDraft}>Use draft in editor</Button>
          <Button className={styles.compactButton} variant="secondary" onClick={() => { clearPreview(); summary.current?.focus(); }}>Discard preview</Button>
        </div>
      </>}
      {applied && <p className={styles.status} role="status">Draft added to the editor. Review and save the source before checking the saved version.</p>}
    </div>
  </details>;
}
