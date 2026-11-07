'use client';

import { useState } from 'react';
import { Button, TextField } from '../../components/form-controls';
import { Icon } from '../../components/icon';
import { StatusBadge } from '../../components/console-ui';
import styles from '../../components/console-ui.module.css';

export function GoalAssistant() {
  const [goal, setGoal] = useState('');
  return <section className={styles.panel} aria-labelledby="goal-title"><div className={styles.panelHead}><h2 id="goal-title">Experiment goals</h2><StatusBadge tone="warning">AI unavailable</StatusBadge></div><div className={styles.body}>
    <label className={styles.fieldLabel} htmlFor="experiment-goal">Describe your goal in natural language<textarea id="experiment-goal" className={styles.textarea} rows={3} value={goal} onChange={event => setGoal(event.target.value)} maxLength={4000} placeholder="Example: find the load that meets this app's p95 latency and error limits…" /></label>
    <div className={styles.actions} style={{ marginTop: 12 }}><Button disabled>Parse goal</Button><span className={styles.note}>Enter accepted values in the form below. This description is not saved.</span></div>
  </div></section>;
}

export function OptimizationPlan() {
  return <aside><section className={styles.panel} aria-labelledby="optimization-title"><div className={styles.panelHead}><h2 id="optimization-title">Optimization bounds</h2><StatusBadge>Unavailable</StatusBadge></div><div className={styles.body}>
    <p className={styles.muted}>Tune CPU and replicas for one Deployment/container. Keep memory fixed and always restore the original configuration.</p>
    <fieldset disabled style={{ border: 0, padding: 0, margin: '18px 0', display: 'grid', gap: 12 }} aria-label="CPU and replica search ranges unavailable">
      <TextField id="optimizer-cpu-min" label="Minimum CPU (millicores)" type="number" min={1} step={1} placeholder="Not configured" />
      <TextField id="optimizer-cpu-max" label="Maximum CPU (millicores)" type="number" min={1} step={1} placeholder="Not configured" />
      <TextField id="optimizer-cpu-step" label="CPU step (millicores)" type="number" min={1} step={1} placeholder="Not configured" />
      <TextField id="optimizer-replica-min" label="Minimum replicas" type="number" min={1} step={1} placeholder="Not configured" />
      <TextField id="optimizer-replica-max" label="Maximum replicas" type="number" min={1} step={1} placeholder="Not configured" />
    </fieldset><p className={styles.note}>Optimization is not implemented yet. These ranges are not submitted or added to the current snapshot.</p>
  </div></section>
  <section className={styles.panel}><div className={styles.panelHead}><h2>Pre-flight checklist</h2><Icon name="lock" width={15} height={15} /></div><div className={styles.body}><ol className={styles.muted} style={{ paddingLeft: 18, margin: 0 }}><li>Select a target and an approved script.</li><li>Define SLOs, load and finite budgets.</li><li>Validate and save the draft.</li><li>Review the snapshot and its SHA-256.</li><li>Verify runtime conditions before starting.</li></ol><p className={styles.note}>Approving inputs does not start load. An SLO verdict requires validated measurement evidence.</p></div></section></aside>;
}
