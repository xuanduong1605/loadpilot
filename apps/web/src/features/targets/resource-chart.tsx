import { useEffect, useId, useRef, useState } from 'react';
import type { ResourcePoint } from './resources';
import styles from './system-detail.module.css';

export function ResourceChart({ history, metric }: { history: ResourcePoint[]; metric: 'cpuMillicores' | 'memoryBytes' }) {
  const id = useId();
  const frame = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(840);
  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    const resize = () => setWidth(Math.max(240, element.clientWidth));
    resize();
    const observer = new ResizeObserver(resize); observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const divisor = metric === 'memoryBytes' ? 1048576 : 1;
  const unit = metric === 'memoryBytes' ? 'MiB' : 'mCPU';
  const values = history.map(point => point[metric] === null ? null : point[metric]! / divisor);
  if (!values.some(value => value !== null)) return <div ref={frame} className={styles.chartEmpty}>
    <span className={styles.chartEmptyMark} aria-hidden="true">↗</span>
    <strong>{history.length ? 'No valid resource samples' : 'No observations yet'}</strong>
    <p>{history.length ? 'Missing or invalid metrics stay unavailable. Refresh after the metrics source recovers.' : 'Check the connection to see current usage. Further refreshes build the trend for this visit.'}</p>
  </div>;
  const maximum = Math.max(...values.filter((value): value is number => value !== null), 1) * 1.2;
  const first = Date.parse(history[0].collectedAt), last = Date.parse(history.at(-1)!.collectedAt);
  const height = width < 500 ? 220 : 240, left = width < 500 ? 42 : 62, right = width - 14, bottom = height - 44;
  const x = (index: number) => last === first ? (left + right) / 2 : left + (Date.parse(history[index].collectedAt) - first) / (last - first) * (right - left);
  const y = (value: number) => bottom - value / maximum * (bottom - 32);
  const segments: string[] = [];
  let current = '';
  values.forEach((value, index) => {
    if (value === null) { if (current) segments.push(current); current = ''; }
    else current += `${current ? ' L' : 'M'}${x(index)},${y(value)}`;
  });
  if (current) segments.push(current);
  const format = (value: number) => new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(value);
  const time = (value: string) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, fractionalSecondDigits: last - first < 1000 ? 3 : undefined });
  return <div ref={frame} className={styles.chart}>
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-labelledby={id}>
      <title id={id}>{metric === 'memoryBytes' ? 'Memory' : 'CPU'} usage in {unit}; {history.length} observation{history.length === 1 ? '' : 's'} collected this visit</title>
      {[0, 1, 2, 3].map(index => { const value = maximum * index / 3; return <g key={index}>
        <line x1={left} x2={right} y1={y(value)} y2={y(value)} className={styles.chartGrid} />
        <text x={left - 12} y={y(value) + 4} textAnchor="end" className={styles.chartLabel}>{format(value)}</text>
      </g>; })}
      {segments.map((path, index) => <path key={index} d={path} fill="none" className={styles.chartLine} />)}
      {values.map((value, index) => value === null ? null : <circle key={history[index].collectedAt} cx={x(index)} cy={y(value)} r="4" className={styles.chartDot}>
        <title>{time(history[index].collectedAt)}: {format(value)} {unit}</title>
      </circle>)}
      <text x={last === first ? (left + right) / 2 : left} y={height - 12} textAnchor={last === first ? 'middle' : 'start'} className={styles.chartLabel}>{time(history[0].collectedAt)}</text>
      {last !== first && <text x={right} y={height - 12} textAnchor="end" className={styles.chartLabel}>{time(history.at(-1)!.collectedAt)}</text>}
    </svg>
  </div>;
}
