'use client';

import { useEffect, useRef, useState } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import controls from '../../components/form-controls.module.css';
import { api, mutate } from '../../lib/api';
import type { TargetPage } from '../targets/types';
import styles from './scenarios.module.css';

type ScriptDraft = { source: string | null; method: 'GET' | 'HEAD' | null; expectedStatus: number | null; errors: string[] };
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
  const [goal, setGoal] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [applied, setApplied] = useState(false);
  const [preview, setPreview] = useState<{ draft: ScriptDraft; source: string; version: string }>();
  const summary = useRef<HTMLElement>(null);
  const timeoutMs = Number(timeout);
  const validTimeout = /^\d+$/.test(timeout) && timeoutMs >= 1 && timeoutMs <= 120000;

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
    if (busy || disabled || !targetId || !goal.trim() || !validTimeout) return;
    clearPreview(); setBusy(true);
    try {
      const draft = await mutate<ScriptDraft>('/api/assistant/script-draft', 'POST', { targetId, goal: goal.trim(), requestTimeoutMs: timeoutMs });
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
        <label htmlFor="script-draft-goal">Describe one GET or HEAD request</label>
        <textarea id="script-draft-goal" className={controls.input} value={goal} readOnly={disabled || busy} rows={3} maxLength={2000}
          placeholder="Example: GET and check for HTTP 200." aria-describedby="script-draft-sharing"
          onChange={event => { setGoal(event.target.value); clearPreview(); }} />
        <span id="script-draft-sharing" className={controls.hint}>Gemini receives your goal text. Loadpilot adds the registered URL locally. Keep URLs and credentials out of the goal.</span>
      </div>
      <div className={styles.actions}><Button className={styles.compactButton} variant="secondary" busy={busy}
        disabled={disabled || !targetId || !validTimeout || !goal.trim()} onClick={() => void requestDraft()}>
        {busy ? 'Drafting script…' : 'Preview script'}
      </Button></div>
      {error && <Notice title="Script preview unavailable" tone="error">{error}</Notice>}
      {preview && <>
        <p className={styles.status}>{preview.draft.method} · HTTP {preview.draft.expectedStatus ?? '2xx or 3xx'} · Draft checks passed</p>
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
