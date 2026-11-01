'use client';

import { useState, type FormEvent, type Dispatch, type SetStateAction } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import { mutate } from '../../lib/api';
import type { Target, TargetInput } from './types';
import styles from './targets.module.css';

export const emptySystem: TargetInput = { name: '', baseUrl: '', environment: 'STAGING', namespace: '', deployment: '', container: '', credentialRef: '', monitoringRef: '' };

export function TargetFields({ values, setValues, disabled, title = 'System details' }: {
  values: TargetInput; setValues: Dispatch<SetStateAction<TargetInput>>; disabled: boolean; title?: string;
}) {
  const field = (key: keyof TargetInput) => ({ value: values[key] ?? '', onChange: (event: React.ChangeEvent<HTMLInputElement>) => setValues(previous => ({ ...previous, [key]: event.target.value })) });
  return <fieldset className={styles.fields} disabled={disabled}><legend>{title}</legend>
    <TextField id="target-name" label="System name" required maxLength={100} {...field('name')} />
    <div><label htmlFor="target-environment">Environment</label><select id="target-environment" value={values.environment} onChange={event => setValues(previous => ({ ...previous, environment: event.target.value as TargetInput['environment'] }))}>
      <option value="DEVELOPMENT">Development</option><option value="STAGING">Staging</option><option value="PRODUCTION">Production</option></select></div>
    <div className={styles.wide}><TextField id="target-url" label="API base URL" type="url" required maxLength={2048} placeholder="https://api.example.com" hint="Your application's address, reachable from Loadpilot. No passwords, query parameters or fragments." {...field('baseUrl')} /></div>
    <TextField id="target-namespace" label="Namespace" required maxLength={63} pattern={'[a-z0-9]([a-z0-9\\-]*[a-z0-9])?'} {...field('namespace')} />
    <TextField id="target-deployment" label="Deployment" required maxLength={253} {...field('deployment')} />
    <TextField id="target-container" label="Container" required maxLength={63} pattern={'[a-z0-9]([a-z0-9\\-]*[a-z0-9])?'} {...field('container')} />
  </fieldset>;
}

export function TargetForm({ target, onSaved, onCancel }: { target?: Target; onSaved: (target: Target) => void; onCancel: () => void }) {
  const [values, setValues] = useState<TargetInput>({ name: target?.name ?? '', baseUrl: target?.baseUrl ?? '', environment: target?.environment ?? 'STAGING',
    namespace: target?.namespace ?? '', deployment: target?.deployment ?? '', container: target?.container ?? '',
    credentialRef: target?.credentialRef ?? '', monitoringRef: target?.monitoringRef ?? '' });
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function save(event: FormEvent) {
    event.preventDefault(); if (pending) return;
    setPending(true); setError('');
    try {
      const saved = await mutate<Target>(target ? `/api/targets/${target.id}` : '/api/targets', target ? 'PUT' : 'POST', { ...values, revision: target?.revision });
      onSaved(saved);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot save this system.'); }
    finally { setPending(false); }
  }
  return <form className={`${styles.form} ${styles.panel}`} onSubmit={save} aria-busy={pending}>
    {error && <Notice title="System was not saved" tone="error">{error}</Notice>}
    <TargetFields values={values} setValues={setValues} disabled={pending} title={target ? 'Edit system' : 'Register system'} />
    <p className={styles.hint}>Registration saves this system&apos;s details. Its connection will remain unchecked until diagnostics run.</p>
    <div className={styles.actions}><Button type="submit" busy={pending}>{target ? 'Save changes' : 'Register system'}</Button><Button variant="secondary" disabled={pending} onClick={onCancel}>Cancel</Button></div>
  </form>;
}
