'use client';

import { useEffect, useRef, useState } from 'react';
import type { LoadProfile } from './load-profile';
import styles from './settings.module.css';

/** Requested configuration only; never represents achieved or measured target load. */
export function ProfilePreview({ profile, durationSeconds, warmupSeconds }: { profile: LoadProfile; durationSeconds: number; warmupSeconds: number }) {
  const host = useRef<HTMLElement>(null);
  const [width, setWidth] = useState(600);
  useEffect(() => {
    if (!host.current) return;
    const observer = new ResizeObserver(entries => {
      const next = Math.max(220, Math.floor(entries[0].contentRect.width));
      setWidth(previous => previous === next ? previous : next);
    });
    observer.observe(host.current); return () => observer.disconnect();
  }, []);
  if (profile.mode !== 'SINGLE_RUN' || profile.startRate === null || !profile.stages.length || durationSeconds <= 0) return null;
  const peak = Math.max(profile.startRate, ...profile.stages.map(stage => stage.targetRate));
  if (peak <= 0) return null;
  const left = 42, right = width - 16, top = 22, bottom = 174;
  const x = (seconds: number) => left + seconds / durationSeconds * (right - left);
  const y = (rate: number) => bottom - rate / peak * (bottom - top);
  let seconds = 0;
  const points = [`${x(0)},${y(profile.startRate)}`];
  for (const stage of profile.stages) { seconds += stage.durationSeconds; points.push(`${x(seconds)},${y(stage.targetRate)}`); }
  return <figure className={styles.profilePreview} ref={host}>
    <figcaption>Requested load profile · iterations/s</figcaption>
    <svg role="img" aria-label="Requested load profile" viewBox={`0 0 ${width} 210`} style={{ display: 'block', width: '100%', height: 210 }}>
      <title>Requested load profile</title>
      <desc>Peak {peak} iterations per second. Shaded measurement window {warmupSeconds} to {warmupSeconds + profile.measurementSeconds} seconds. This is configuration, not achieved load.</desc>
      <rect x={x(warmupSeconds)} y={top} width={x(warmupSeconds + profile.measurementSeconds) - x(warmupSeconds)} height={bottom - top} fill="var(--primary-soft)" />
      <path d={`M${left},${top}V${bottom}H${right}`} fill="none" stroke="var(--line-strong)" />
      <polyline points={points.join(' ')} fill="none" stroke="var(--primary)" strokeWidth={2} />
      <g fill="var(--muted)" fontSize={11}>
        <text x={left - 8} y={top + 4} textAnchor="end">{peak}</text><text x={left - 8} y={bottom + 4} textAnchor="end">0</text>
        <text x={left} y={bottom + 23}>0 s</text><text x={right} y={bottom + 23} textAnchor="end">{durationSeconds} s</text>
      </g>
    </svg>
    <p>Shading marks the measurement window. Rates shown are requested, not achieved.</p>
  </figure>;
}
