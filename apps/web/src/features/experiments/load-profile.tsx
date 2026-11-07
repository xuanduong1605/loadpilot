'use client';

import { Button, TextField } from '../../components/form-controls';
import styles from './settings.module.css';

export type LoadProfile = { mode: string; shape: string; startRate: number | null; measurementSeconds: number; stages: { durationSeconds: number; targetRate: number }[] };
export type ProfileValues = { mode: string; shape: string; startRate: string; measurementSeconds: string; stages: { durationSeconds: string; targetRate: string }[] };
export function profileValues(profile?: LoadProfile | null): ProfileValues {
  return profile ? { ...profile, startRate: profile.startRate === null ? '' : String(profile.startRate), measurementSeconds: String(profile.measurementSeconds), stages: profile.stages.map(stage => ({ durationSeconds: String(stage.durationSeconds), targetRate: String(stage.targetRate) })) }
    : { mode: '', shape: '', startRate: '', measurementSeconds: '', stages: [] };
}
export function encodeProfile(value: ProfileValues, durationSeconds: number): LoadProfile {
  const adaptive = value.mode === 'ADAPTIVE_LOAD';
  return { mode: value.mode, shape: value.shape, startRate: adaptive ? null : Number(value.startRate), measurementSeconds: Number(value.measurementSeconds),
    stages: adaptive ? [] : value.shape === 'STEADY' ? [{ durationSeconds, targetRate: Number(value.startRate) }] : value.stages.map(stage => ({ durationSeconds: Number(stage.durationSeconds), targetRate: Number(stage.targetRate) })) };
}
export function LoadProfileEditor({ value, onChange }: { value: ProfileValues; onChange: (value: ProfileValues) => void }) {
  const single = value.mode === 'SINGLE_RUN';
  return <>
    <label htmlFor="load-mode">Test mode</label>
    <select id="load-mode" required value={value.mode} onChange={event => onChange({ ...value, mode: event.target.value, shape: '' })}>
      <option value="">Choose mode</option><option value="SINGLE_RUN">Single-run profile</option><option value="ADAPTIVE_LOAD">Adaptive steady-load trials</option>
    </select>
    <label htmlFor="load-shape">Load shape</label>
    <select id="load-shape" required disabled={!value.mode} value={value.shape} onChange={event => onChange({ ...value, shape: event.target.value })}>
      <option value="">Choose shape</option><option value="STEADY">Steady</option>
      {single && <><option value="RAMP">Ramp</option><option value="SPIKE">Spike</option></>}
    </select>
    <TextField id="measurement-seconds" label="Measurement window length (seconds)" type="number" min={1} max={3600} step={1} required value={value.measurementSeconds}
      onChange={event => onChange({ ...value, measurementSeconds: event.target.value })} hint="The window starts after warm-up. Adaptive trials use the complete post-warm-up window. Ramp/spike tests measure a constant peak plateau; recovery is excluded." />
    {single && <TextField id="profile-start-rate" label={value.shape === 'STEADY' ? 'Steady rate (iterations/s)' : 'Starting rate (iterations/s; zero allowed)'} type="number" min={value.shape === 'STEADY' ? 1 : 0} max={10000} step={1} required value={value.startRate}
      onChange={event => onChange({ ...value, startRate: event.target.value })} />}
    {single && value.shape && value.shape !== 'STEADY' && <>
      <p>Stages include warm-up and recovery and must sum to the run duration. Each stage ramps from the preceding rate to its target; equal consecutive rates create a plateau.</p>
      {value.stages.map((stage, index) => <div className={styles.stage} key={index}>
        <h3>Stage {index + 1}</h3>
        <TextField id={`profile-stage-${index}-duration`} label={`Stage ${index + 1} duration (seconds)`} type="number" min={1} max={3600} step={1} required value={stage.durationSeconds}
          onChange={event => onChange({ ...value, stages: value.stages.map((item, at) => at === index ? { ...item, durationSeconds: event.target.value } : item) })} />
        <TextField id={`profile-stage-${index}-rate`} label={`Stage ${index + 1} target (iterations/s)`} type="number" min={0} max={10000} step={1} required value={stage.targetRate}
          onChange={event => onChange({ ...value, stages: value.stages.map((item, at) => at === index ? { ...item, targetRate: event.target.value } : item) })} />
        <Button variant="secondary" onClick={() => onChange({ ...value, stages: value.stages.filter((_, at) => at !== index) })}>Remove stage {index + 1}</Button>
      </div>)}
      <Button variant="secondary" disabled={value.stages.length >= 12} onClick={() => onChange({ ...value, stages: [...value.stages, { durationSeconds: '', targetRate: '' }] })}>Add stage</Button>
    </>}
  </>;
}
