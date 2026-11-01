'use client';

import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { mutate } from '../../lib/api';
import type { Target } from './types';
import type { ConnectionPolicy, ConnectionView } from './connection-types';
import styles from './targets.module.css';
import { ConnectionFields } from './connection-fields';
import { CONNECTION_FILE_LIMIT, readConnectionFile } from './connection-file';

export function ConnectionForm({ target, connection, policy, onSaved, onCancel }: {
  target: Target; connection: ConnectionView; policy: ConnectionPolicy; onSaved: () => void; onCancel: () => void;
}) {
  const [secure, setSecure] = useState(false);
  const [values, setValues] = useState({ kubernetesUrl: connection.kubernetesUrl, kubernetesCa: connection.kubernetesCa,
    kubernetesToken: '', prometheusUrl: connection.prometheusUrl, prometheusCa: connection.prometheusCa, prometheusToken: '' });
  const [acknowledged, setAcknowledged] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => setSecure(window.isSecureContext), []);
  async function importFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]; event.target.value = '';
    if (!file || !secure || !policy.encryptionAvailable || pending) return;
    setPending(true); setError('');
    try {
      if (file.size > CONNECTION_FILE_LIMIT) throw new Error('Connection files must be 32 KiB or smaller.');
      const imported = readConnectionFile(await file.text());
      if (imported.system.namespace !== target.namespace || imported.system.deployment !== target.deployment || imported.system.container !== target.container)
        throw new Error('This connection file selects a different workload. Choose the file for this system.');
      setValues(imported.connection); setAcknowledged(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot import this connection file.'); }
    finally { setPending(false); }
  }
  async function save(event: FormEvent) {
    event.preventDefault(); if (pending || !secure || !policy.encryptionAvailable) return;
    setPending(true); setError('');
    try {
      await mutate(`/api/integrations/targets/${target.id}/connection`, 'PUT', { ...values, revision: connection.revision, targetRevision: target.revision, readOnlyAcknowledged: acknowledged });
      setValues(previous => ({ ...previous, kubernetesToken: '', prometheusToken: '' }));
      onSaved();
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot save this connection.'); }
    finally { setPending(false); }
  }
  return <form className={`${styles.form} ${styles.panel}`} onSubmit={save} aria-busy={pending} autoComplete="off">
    <h2>{connection.revision ? 'Edit connection' : 'Connect this system'}</h2>
    {!secure && <Notice title="Use a secure connection" tone="error">Open Loadpilot over HTTPS or a loopback SSH tunnel before entering credentials.</Notice>}
    {!policy.encryptionAvailable && <Notice title="Connection setup is unavailable" tone="error">Ask the platform operator to configure the connection encryption key. Your system registration is still available.</Notice>}
    {error && <Notice title="Connection was not saved" tone="error">{error}</Notice>}
    <div className={styles.importBox}><label htmlFor="renew-connection-file">Import connection file (optional)</label>
      <input id="renew-connection-file" type="file" accept=".json,application/json" disabled={pending || !secure || !policy.encryptionAvailable} onChange={event => void importFile(event)} />
      <p className={styles.hint}>Rerun the setup helper and import its file here to renew an expired token.</p></div>
    <ConnectionFields values={values} setValues={setValues} disabled={pending || !secure || !policy.encryptionAvailable} hasToken={connection.hasKubernetesToken} hasPrometheusToken={connection.hasPrometheusToken} />
    <label className={styles.confirm}><input type="checkbox" checked={acknowledged} required disabled={pending} onChange={event => setAcknowledged(event.target.checked)} />
      These credentials are scoped for read-only inspection of this system. I have not supplied administrator credentials.</label>
    <details className={styles.hint}><summary>Allowed destinations</summary><p>{policy.allowedOrigins.length ? policy.allowedOrigins.join(', ') : 'No destinations configured. Ask the platform operator to allow the private service origins and addresses.'}</p></details>
    <p className={styles.hint}>Tokens are stored encrypted on the platform and never returned to this browser. Saving does not run an inspection or start a test.</p>
    <div className={styles.actions}><Button type="submit" busy={pending} disabled={!secure || !policy.encryptionAvailable || !acknowledged}>Save connection</Button><Button variant="secondary" disabled={pending} onClick={onCancel}>Cancel</Button></div>
  </form>;
}
