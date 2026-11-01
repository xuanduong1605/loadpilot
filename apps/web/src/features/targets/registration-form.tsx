'use client';

import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { Button, Notice } from '../../components/form-controls';
import { api, mutate } from '../../lib/api';
import { TargetFields, emptySystem } from './target-form';
import { ConnectionFields } from './connection-fields';
import { CONNECTION_FILE_LIMIT, emptyConnection, readConnectionFile } from './connection-file';
import type { Target } from './types';
import type { ConnectionPolicy } from './connection-types';
import styles from './targets.module.css';

export function RegistrationForm({ onSaved, onCancel }: { onSaved: (target: Target, check: boolean) => void; onCancel: () => void }) {
  const [system, setSystem] = useState({ ...emptySystem });
  const [connection, setConnection] = useState({ ...emptyConnection });
  const [connect, setConnect] = useState(true);
  const [secure, setSecure] = useState(false);
  const [policy, setPolicy] = useState<ConnectionPolicy>();
  const [pending, setPending] = useState(false);
  const [reading, setReading] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [error, setError] = useState('');
  const [imported, setImported] = useState(false);
  const [editDetails, setEditDetails] = useState(false);
  const importTicket = useRef(0);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true; setSecure(window.isSecureContext);
    void api<ConnectionPolicy>('/api/integrations/config').then(result => { if (alive.current) setPolicy(result); })
      .catch(() => { if (alive.current) setError('Cannot load connection settings. Reload this page, or register details only.'); });
    return () => { alive.current = false; importTicket.current++; };
  }, []);
  const canConnect = secure && !!policy?.encryptionAvailable;
  async function importFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]; event.target.value = '';
    if (!file || !canConnect || pending) return;
    const ticket = ++importTicket.current;
    setReading(true); setError(''); setImported(false);
    try {
      if (file.size > CONNECTION_FILE_LIMIT) throw new Error('Connection files must be 32 KiB or smaller.');
      const result = readConnectionFile(await file.text());
      if (!alive.current || ticket !== importTicket.current) return;
      setSystem(result.system); setConnection(result.connection); setAcknowledged(false); setConnect(true); setImported(true); setEditDetails(false);
    } catch (reason) { if (alive.current && ticket === importTicket.current) setError(reason instanceof Error ? reason.message : 'Cannot read this connection file.'); }
    finally { if (alive.current && ticket === importTicket.current) setReading(false); }
  }
  function mode(value: boolean) {
    importTicket.current++; setReading(false); setConnect(value); setAcknowledged(false); setError('');
    if (!value) { setConnection({ ...emptyConnection }); setImported(false); setEditDetails(false); }
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (pending || reading || (connect && (!canConnect || !acknowledged))) return;
    setPending(true); setError('');
    try {
      const saved = connect
        ? await mutate<Target>('/api/integrations/systems', 'POST', { system, connection: { ...connection, revision: 0, targetRevision: 1, readOnlyAcknowledged: acknowledged } })
        : await mutate<Target>('/api/targets', 'POST', system);
      setConnection({ ...emptyConnection });
      onSaved(saved, connect);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Cannot save this system.'); }
    finally { setPending(false); }
  }
  return <form className={`${styles.form} ${styles.panel}`} onSubmit={save} aria-busy={pending || reading} autoComplete="off">
    <div className={styles.modes} role="group" aria-label="Registration mode">
      <Button disabled={pending || reading} variant={connect ? 'primary' : 'secondary'} aria-pressed={connect} onClick={() => mode(true)}>Connect now</Button>
      <Button disabled={pending || reading} variant={connect ? 'secondary' : 'primary'} aria-pressed={!connect} onClick={() => mode(false)}>Register details only</Button>
    </div>
    {error && <Notice title="System was not saved" tone="error">{error}</Notice>}
    {connect && <>
      {!secure && <Notice title="Use a secure connection" tone="error">Open Loadpilot over HTTPS or a loopback SSH tunnel before importing or entering credentials. You can still register details only.</Notice>}
      {policy && !policy.encryptionAvailable && <Notice title="Connection setup is unavailable" tone="error">Ask the platform operator to configure the connection encryption key. You can still register details only.</Notice>}
      <section className={styles.importBox} aria-label="Quick connection setup">
        <h2>Connect from your Kubernetes PC</h2>
        <p className={styles.hint}>Run this command from your project folder on that PC. The helper gathers the workload details and opens a private SSH link to your VPS.</p>
        <code className={styles.setupCommand}>powershell -NoProfile -File scripts/connect-system.ps1</code>
        <label htmlFor="connection-file">Import connection file</label>
        <input id="connection-file" type="file" accept=".json,application/json" onChange={event => void importFile(event)} disabled={!canConnect || pending || reading} />
        <p className={styles.hint}>Choose the file printed by the helper. Administrator kubeconfigs are not accepted.</p>
        {imported && <p role="status">Connection details imported. Review them below before connecting.</p>}
      </section>
    </>}
    {connect && imported && <section aria-label="Review imported system"><h2>{system.name}</h2>
      <dl className={styles.details}><dt>Environment</dt><dd>{system.environment.toLowerCase()}</dd><dt>API</dt><dd>{system.baseUrl}</dd>
        <dt>Workload</dt><dd>{system.namespace} / {system.deployment} / {system.container}</dd><dt>Kubernetes</dt><dd>{connection.kubernetesUrl}</dd><dt>Credentials</dt><dd>Token included; certificate {connection.kubernetesCa ? 'included' : 'uses public trust'}</dd></dl>
    </section>}
    {connect && <Button variant="secondary" disabled={pending || reading} aria-expanded={editDetails} onClick={() => setEditDetails(value => !value)}>{editDetails ? 'Hide details' : imported ? 'Review or edit details' : 'Enter details manually'}</Button>}
    {(!connect || editDetails) && <TargetFields values={system} setValues={setSystem} disabled={pending || reading} />}
    {connect && <>
      {editDetails && <ConnectionFields values={connection} setValues={setConnection} disabled={pending || reading || !canConnect} />}
      <label className={styles.confirm}><input type="checkbox" checked={acknowledged} required disabled={pending || reading || !canConnect} onChange={event => setAcknowledged(event.target.checked)} />
        These credentials are scoped for read-only inspection of this system. I have not supplied administrator credentials.</label>
      <p className={styles.hint}>Connect system saves everything together, then checks connectivity from the Loadpilot server. It does not run a load test. Monitoring can be added later.</p>
    </>}
    {!connect && <p className={styles.hint}>Save the system details now and add a connection later.</p>}
    <div className={styles.actions}><Button type="submit" busy={pending} disabled={reading || (connect && (!canConnect || !acknowledged || (!imported && !editDetails)))}>{connect ? 'Connect system' : 'Register system'}</Button>
      <Button variant="secondary" disabled={pending || reading} onClick={onCancel}>Cancel</Button></div>
  </form>;
}
