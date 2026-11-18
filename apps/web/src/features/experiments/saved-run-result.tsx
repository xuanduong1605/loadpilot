'use client';

import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { Notice } from '../../components/form-controls';
import styles from '../../components/console-ui.module.css';

type Window = { windowFrom: string; windowUntil: string; requestsPerSecond: number; p95LatencyMs: number; errorRatio: number; requestCount: number };
type Result = { runId: string; resultState: string; result: { evidence: {
  startedAt: string; finishedAt: string; summary: { httpRequests: number; k6HttpFailureRate: number; httpDurationMs: { p95: number } } | null;
} } | null; partialHttp: Window | null };

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 2 });

export function SavedRunResult({ runId }: { runId: string }) {
  const [result, setResult] = useState<Result>();
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    void api<Result>(`/api/experiments/runs/${runId}/result`, { signal: controller.signal })
      .then(value => {
        if (value.runId !== runId) throw new Error('The saved result could not be matched to this run.');
        if (active) setResult(value);
      })
      .catch(reason => { if (active) setError(reason.message); });
    return () => { active = false; controller.abort(); };
  }, [runId]);

  const whole = result?.resultState === 'AVAILABLE' ? result.result?.evidence : undefined;
  const partial = result?.partialHttp;
  return <section className={styles.panel} aria-labelledby="saved-run-result-title">
    <div className={styles.panelHead}><h2 id="saved-run-result-title">Saved results</h2></div>
    <div className={styles.body}>
      {error ? <Notice title="Cannot load saved results" tone="error">{error} Use Reload to try again.</Notice> : !result ? <p role="status">Loading saved results…</p> :
        whole?.summary ? <>
          <p>Whole-run k6 summary, including warm-up. Use validated measurement-window evidence for the SLO verdict.</p>
          <dl className={styles.meta}>
            <dt>Window (UTC)</dt><dd>{whole.startedAt} → {whole.finishedAt}</dd>
            <dt>HTTP requests</dt><dd>{number.format(whole.summary.httpRequests)}</dd>
            <dt>p95 HTTP duration</dt><dd>{number.format(whole.summary.httpDurationMs.p95)} ms</dd>
            <dt>k6 HTTP failure rate</dt><dd>{percent.format(whole.summary.k6HttpFailureRate)}</dd>
          </dl>
        </> : <>
          <p>Whole-run evidence: {result.resultState.toLowerCase().replaceAll('_', ' ')}.</p>
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
        </>}
    </div>
  </section>;
}
