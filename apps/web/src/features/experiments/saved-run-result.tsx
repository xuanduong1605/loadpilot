'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { api, mutate } from '../../lib/api';
import { requestId } from '../../lib/request-id';
import { Button, Notice } from '../../components/form-controls';
import controls from '../../components/form-controls.module.css';
import { uuid, type Sent } from '../ai-assistant/assistant-data';
import { recordLabel, useRunComparisonRecords } from './experiment-records';
import summary from './experiment-summary.module.css';
import styles from '../../components/console-ui.module.css';

type Window = { windowFrom: string; windowUntil: string; requestsPerSecond: number; p95LatencyMs: number; errorRatio: number; requestCount: number };
type Result = { runId: string; resultState: string; result: { evidence: {
  startedAt: string; finishedAt: string; summary: { httpRequests: number; k6HttpFailureRate: number; httpDurationMs: { p95: number } } | null;
} } | null; partialHttp: Window | null };

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 2 });

export function SavedRunResult({ runId, refreshKey, completed }: { runId: string; refreshKey: string; completed: boolean }) {
  const router = useRouter();
  const [result, setResult] = useState<Result>();
  const [error, setError] = useState('');
  const [analysisError, setAnalysisError] = useState<{ comparisonRunId?: string; message: string }>();
  const [analyzing, setAnalyzing] = useState<'single' | 'comparison'>();
  const [comparisonOpened, setComparisonOpened] = useState(false);
  const pending = useRef<{ conversationId: string; requestId: string; comparisonRunId?: string }>(undefined);
  const sending = useRef(false);
  const mounted = useRef(false);

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setError('');
    void api<Result>(`/api/experiments/runs/${runId}/result`, { signal: controller.signal })
      .then(value => {
        if (value.runId !== runId) throw new Error('The saved result could not be matched to this run.');
        if (active) setResult(value);
      })
      .catch(reason => { if (active) setError(reason.message); });
    return () => { active = false; controller.abort(); };
  }, [runId, refreshKey]);

  async function analyze(comparisonRunId?: string) {
    if (sending.current) return;
    sending.current = true;
    setAnalyzing(comparisonRunId ? 'comparison' : 'single');
    setAnalysisError(undefined);
    try {
      const request = pending.current && pending.current.comparisonRunId === comparisonRunId ? pending.current
        : { conversationId: requestId(), requestId: requestId(), comparisonRunId };
      pending.current = request;
      const saved = await mutate<Sent>(`/api/assistant/conversations/${request.conversationId}/analysis`, 'POST', {
        runId, requestId: request.requestId, expectedTurns: 0, comparisonRunId,
      });
      if (saved.summary.id !== request.conversationId || !uuid.test(saved.summary.id)
          || saved.turn.recordId !== runId || !saved.turn.reply.analysis
          || saved.turn.reply.analysis.comparison?.runId !== comparisonRunId) {
        throw new Error('Could not match the saved analysis to this request. Try again.');
      }
      if (mounted.current) router.push(`/assistant/?chat=${saved.summary.id}`);
    } catch (reason) {
      if (mounted.current) setAnalysisError({ comparisonRunId, message: reason instanceof Error ? reason.message : 'Could not request analysis.' });
    } finally {
      sending.current = false;
      if (mounted.current) setAnalyzing(undefined);
    }
  }

  const whole = result?.resultState === 'AVAILABLE' ? result.result?.evidence : undefined;
  const partial = result?.partialHttp;
  const canAnalyze = (whole?.summary?.httpRequests ?? 0) > 0 || (partial?.requestCount ?? 0) > 0;
  return <section className={styles.panel} aria-labelledby="saved-run-result-title">
    <div className={styles.panelHead}><h2 id="saved-run-result-title">Saved results</h2></div>
    <div className={styles.body}>
      {error ? <Notice title="Cannot load saved results" tone="error">{error} Use Reload to try again.</Notice> : !result ? <p role="status">Loading saved results…</p> : <>
        {whole?.summary ? <>
          <p>Whole-run k6 summary, including warm-up. Use validated measurement-window evidence for the SLO verdict.</p>
          <dl className={styles.meta}>
            <dt>Window (UTC)</dt><dd>{whole.startedAt} → {whole.finishedAt}</dd>
            <dt>HTTP requests</dt><dd>{number.format(whole.summary.httpRequests)}</dd>
            <dt>p95 HTTP duration</dt><dd>{number.format(whole.summary.httpDurationMs.p95)} ms</dd>
            <dt>k6 HTTP failure rate</dt><dd>{percent.format(whole.summary.k6HttpFailureRate)}</dd>
          </dl>
        </> : <p>Whole-run evidence: {result.resultState.toLowerCase().replaceAll('_', ' ')}.</p>}
          {partial && <>
            <p>Saved partial HTTP window. These observations cover a historical sample.</p>
            <dl className={styles.meta}>
              <dt>Window (UTC)</dt><dd>{partial.windowFrom} → {partial.windowUntil}</dd>
              <dt>HTTP requests</dt><dd>{number.format(partial.requestCount)}</dd>
              <dt>HTTP request rate</dt><dd>{number.format(partial.requestsPerSecond)} requests/s</dd>
              <dt>p95 HTTP duration</dt><dd>{number.format(partial.p95LatencyMs)} ms</dd>
              <dt>Error ratio</dt><dd>{percent.format(partial.errorRatio)}</dd>
            </dl>
          </>}
          {canAnalyze && <>
            <p id="run-analysis-disclosure">Gemini receives saved HTTP metrics and observation lengths. The analysis is saved in Assistant history.</p>
            <Button variant="secondary" busy={analyzing === 'single'} disabled={analyzing === 'comparison'}
              aria-describedby="run-analysis-disclosure" onClick={() => void analyze()}>
              {analyzing === 'single' ? 'Analyzing saved results…' : analysisError && !analysisError.comparisonRunId ? 'Retry analysis' : 'Analyze with Gemini'}
            </Button>
            {analysisError && !analysisError.comparisonRunId && <Notice title="Cannot open run analysis" tone="error">{analysisError.message}</Notice>}
            {completed && (whole?.summary?.httpRequests ?? 0) > 0 && <details className={summary.disclosure}
              onToggle={event => { if (event.currentTarget.open) setComparisonOpened(true); }}>
              <summary>Compare with another run</summary>
              {comparisonOpened && <RunComparisonPicker key={runId} baseline={runId} busy={analyzing === 'comparison'} disabled={Boolean(analyzing)}
                error={analysisError?.comparisonRunId ? analysisError : undefined} analyze={id => void analyze(id)} />}
            </details>}
          </>}
        </>}
    </div>
  </section>;
}

function RunComparisonPicker({ baseline, busy, disabled, error, analyze }: {
  baseline: string; busy: boolean; disabled: boolean; error?: { comparisonRunId?: string; message: string }; analyze: (id: string) => void;
}) {
  const runs = useRunComparisonRecords(baseline);
  const [selected, setSelected] = useState('');
  const currentError = selected && error?.comparisonRunId === selected ? error.message : '';
  return <div className={controls.field}>
    {runs.items.length > 0 && <><label htmlFor="analysis-comparison-run">Comparison run</label>
    <select id="analysis-comparison-run" className={controls.input} value={selected} disabled={disabled}
      aria-describedby="run-comparison-disclosure" onChange={event => setSelected(event.target.value)}>
      <option value="">Choose a compatible run</option>
      {runs.items.map(run => <option key={run.id} value={run.id}>
        {run.targetName ?? 'Unnamed system'} · {recordLabel(run)} · {new Date(run.listedAt).toLocaleString()}
      </option>)}
    </select></>}
    {runs.loading && <p role="status">Checking compatible runs…</p>}
    {runs.error && <><Notice title="Cannot load runs" tone="error">{runs.error}</Notice>
      <div><Button variant="secondary" onClick={runs.retry}>Retry runs</Button></div></>}
    {runs.hasMore && !runs.loading && !runs.error && <div><Button variant="secondary" onClick={runs.more}>Load older runs</Button></div>}
    {!runs.loading && !runs.error && !runs.items.length && <p>{runs.hasMore
      ? 'No compatible runs on this page. Load older runs to keep looking.'
      : 'No completed runs share these inputs and have a usable whole-run summary.'}</p>}
    <p id="run-comparison-disclosure">Gemini receives both whole-run HTTP summaries and the shared requested rate. This comparison uses historical evidence.</p>
    {runs.items.length > 0 && <div><Button variant="secondary" busy={busy} disabled={!selected || disabled} aria-describedby="run-comparison-disclosure"
      onClick={() => analyze(selected)}>{busy ? 'Comparing saved results…' : currentError ? 'Retry comparison' : 'Compare with Gemini'}</Button></div>}
    {currentError && <Notice title="Cannot compare these runs" tone="error">{currentError}</Notice>}
  </div>;
}
