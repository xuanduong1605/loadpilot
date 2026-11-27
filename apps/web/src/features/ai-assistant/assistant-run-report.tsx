import type { EvidenceId, RunAnalysis } from './assistant-data';
import styles from './assistant.module.css';

const evidenceLabel = { WHOLE_RUN: 'Whole-run k6 summary', PARTIAL_HTTP: 'Partial HTTP window',
  COMPARISON_WHOLE_RUN: 'Comparison whole-run summary' } satisfies Record<EvidenceId, string>;
const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 });
const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 2 });
const time = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', dateStyle: 'medium', timeStyle: 'medium' });
const label = (state: string) => state.toLowerCase().replaceAll('_', ' ');

export function AssistantRunReport({ id, report }: { id: string; report: RunAnalysis }) {
  const evidenceAnchor = (scope: EvidenceId) => `evidence-${id}-${scope.toLowerCase()}`;
  const scopeLabel = (scope: EvidenceId) => report.comparison && scope === 'WHOLE_RUN' ? 'Baseline whole-run summary' : evidenceLabel[scope];
  return <section className={styles.runReport} aria-labelledby={`analysis-${id}`}>
    <h2 id={`analysis-${id}`}>{report.comparison ? 'Run comparison' : 'Run analysis'}</h2>
    <dl className={styles.runObservations}>
      <div><dt>{report.comparison ? 'Baseline status' : 'Run status'}</dt><dd>{label(report.runStatus)}</dd></div>
      <div><dt>{report.comparison ? 'Baseline SLO verdict' : 'Recorded SLO verdict'}</dt><dd>{label(report.verdict)}</dd></div>
      {report.comparison && <>
        <div><dt>Comparison status</dt><dd>{label(report.comparison.runStatus)}</dd></div>
        <div><dt>Comparison SLO verdict</dt><dd>{label(report.comparison.verdict)}</dd></div>
      </>}
    </dl>
    {report.comparison && <p className={styles.reportNotice}>Both runs requested {number.format(report.comparison.requestedIterationsPerSecond)} iterations/s during measurement. Achieved load is unverified.</p>}
    {report.observations.length > 0 && <>
      <h3>Saved observations</h3>
      <p>{report.comparison ? 'The historical summaries include warm-up. An SLO verdict requires validated measurement-window evidence.'
        : 'Whole-run evidence includes warm-up. Partial windows are historical samples. An SLO verdict requires validated measurement-window evidence.'}</p>
      {report.observations.map(item => <section key={item.id} className={styles.runObservation} aria-labelledby={evidenceAnchor(item.id)}>
        <h4 id={evidenceAnchor(item.id)} tabIndex={-1}>{scopeLabel(item.id)}</h4>
        <p><time dateTime={item.from}>{time.format(new Date(item.from))}</time> to <time dateTime={item.until}>{time.format(new Date(item.until))}</time> UTC</p>
        <dl className={styles.runObservations}>
          <div><dt>HTTP requests</dt><dd>{number.format(item.httpRequests)}</dd></div>
          <div><dt>p95 HTTP duration</dt><dd>{number.format(item.p95LatencyMs)} ms</dd></div>
          <div><dt>{item.id === 'PARTIAL_HTTP' ? 'Error ratio' : 'k6 HTTP failure rate'}</dt><dd>{percent.format(item.httpFailureRatio)}</dd></div>
          {item.httpRequestsPerSecond != null && <div><dt>HTTP throughput</dt><dd>{number.format(item.httpRequestsPerSecond)} requests/s</dd></div>}
        </dl>
      </section>)}
    </>}
    {report.status === 'INSUFFICIENT_DATA' && <p className={styles.reportNotice}>Insufficient data for AI findings.</p>}
    {report.status === 'PROVIDER_UNAVAILABLE' && <p className={styles.reportNotice}>Gemini analysis is temporarily unavailable. Saved observations are still available.</p>}
    {report.status === 'READY' && <>
      <h3>Gemini hypotheses and checks</h3>
      {report.findings.length ? <ul className={styles.runFindings}>{report.findings.map((finding, index) => <li key={index}>
        <p><strong>Hypothesis</strong> {finding.hypothesis}</p>
        <p><strong>Suggested check</strong> {finding.suggestedCheck}</p>
        <ul className={styles.links} aria-label="Cited observations">{finding.evidenceIds.map(scope =>
          <li key={scope}><a href={`#${evidenceAnchor(scope)}`}>{scopeLabel(scope)}</a></li>)}</ul>
      </li>)}</ul> : <p>The saved evidence did not support a specific hypothesis.</p>}
    </>}
  </section>;
}
