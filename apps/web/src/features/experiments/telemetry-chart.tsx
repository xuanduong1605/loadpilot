import styles from '../../components/console-ui.module.css';

export type TelemetryPoint = { at: string; requested: number | null; actual: number | null };

export function TelemetryChart({ points, unit, requestedLabel = 'Requested', actualLabel = 'Achieved' }: { points: TelemetryPoint[]; unit: string; requestedLabel?: string; actualLabel?: string }) {
  const valid = points.some(point => point.actual !== null || point.requested !== null);
  const values = points.flatMap(point => [point.requested, point.actual]).filter((value): value is number => value !== null && Number.isFinite(value) && value >= 0);
  const max = Math.max(1, ...values) * 1.15;
  const first = points.length ? Date.parse(points[0].at) : 0;
  const last = points.length ? Date.parse(points[points.length - 1].at) : 0;
  const x = (index: number) => last > first ? 48 + (Date.parse(points[index].at) - first) * 490 / (last - first) : 293;
  const y = (value: number) => 160 - value / max * 135;
  const path = (key: 'requested' | 'actual') => points.map((point, index) => point[key] == null ? '' : `${index && points[index - 1][key] !== null ? 'L' : 'M'}${x(index)},${y(point[key]!)}`).join(' ');
  const time = (at: string) => new Date(at).toISOString().slice(11, 19);
  return <figure className={styles.chart}><figcaption><span className={styles.legend}>{requestedLabel}</span><span className={styles.legend} data-series="actual">{actualLabel}</span><span>{unit} · UTC · {points.length} observations this visit</span></figcaption>
    {!valid ? <div className={styles.chartEmpty}><strong>No valid observations yet</strong><span>Charts appear when fresh observations match the selected scope.</span></div> : <svg className={styles.plot} viewBox="0 0 560 190" role="img" aria-label={`${requestedLabel} and ${actualLabel}, ${unit}. ${points.length} observations`}>
      {[0, .5, 1].map(ratio => <g key={ratio}><line x1="48" x2="538" y1={y(max * ratio)} y2={y(max * ratio)} stroke="var(--line)" /><text x="40" y={y(max * ratio) + 4} textAnchor="end" fill="var(--muted)" fontSize="10" fontFamily="var(--mono)">{(max * ratio).toLocaleString('en', { maximumFractionDigits: 1 })}</text></g>)}
      {(['requested', 'actual'] as const).map(key => <g key={key}><path d={path(key)} fill="none" stroke={key === 'actual' ? 'var(--success)' : 'var(--primary-hover)'} strokeWidth="2" strokeDasharray={key === 'requested' ? '5 4' : undefined} />{points.map((point, index) => point[key] !== null && <circle key={index} cx={x(index)} cy={y(point[key]!)} r="2.5" fill={key === 'actual' ? 'var(--success)' : 'var(--primary-hover)'} />)}</g>)}
      <text x={last > first ? 48 : 293} y="185" textAnchor={last > first ? 'start' : 'middle'} fill="var(--muted)" fontSize="10" fontFamily="var(--mono)">{time(points[0].at)}</text>{last > first && <text x="538" y="185" textAnchor="end" fill="var(--muted)" fontSize="10" fontFamily="var(--mono)">{time(points[points.length - 1].at)}</text>}
    </svg>}
  </figure>;
}
