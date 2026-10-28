'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button, Notice, TextField } from '../../components/form-controls';
import { mutate } from '../../lib/api';
import type { Target } from './types';
import type { ConnectionPolicy, ConnectionView } from './connection-types';
import styles from './targets.module.css';

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
  const field = (name: keyof typeof values) => ({ value: values[name], onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setValues(previous => ({ ...previous, [name]: event.target.value })) });
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
    <fieldset className={styles.fields} disabled={pending || !secure || !policy.encryptionAvailable}><legend>Kubernetes</legend>
      <div className={styles.wide}><TextField id="connection-kube-url" label="Kubernetes API URL" type="url" required maxLength={2048} placeholder="https://cluster.example.com:6443" {...field('kubernetesUrl')} /></div>
      <div className={styles.wide}><TextField id="connection-kube-token" label="Read-only Kubernetes token" type="password" autoComplete="off" required={!connection.hasKubernetesToken} maxLength={8192}
        hint={connection.hasKubernetesToken ? 'A token is saved. Leave blank to keep it, or enter a replacement.' : 'Use a namespace-scoped read-only ServiceAccount token. Do not upload an administrator kubeconfig.'} {...field('kubernetesToken')} /></div>
      <div className={styles.wide}><label htmlFor="connection-kube-ca">Kubernetes CA certificate (PEM, optional)</label><textarea id="connection-kube-ca" rows={5} maxLength={16384} {...field('kubernetesCa')} />
        <p className={styles.hint}>Leave blank for a publicly trusted certificate. Certificate verification is always enabled.</p></div>
    </fieldset>
    <fieldset className={styles.fields} disabled={pending || !secure || !policy.encryptionAvailable}><legend>Monitoring (optional)</legend>
      <div className={styles.wide}><TextField id="connection-prom-url" label="Prometheus URL" type="url" maxLength={2048} placeholder="https://prometheus.example.com" hint="Leave blank to disable monitoring for this system." {...field('prometheusUrl')} /></div>
      <div className={styles.wide}><TextField id="connection-prom-token" label="Prometheus token (optional)" type="password" autoComplete="off" maxLength={8192}
        hint={connection.hasPrometheusToken ? 'A token is saved. Leave blank to keep it.' : 'Use a scoped monitoring token if your endpoint requires one.'} {...field('prometheusToken')} /></div>
      <div className={styles.wide}><label htmlFor="connection-prom-ca">Prometheus CA certificate (PEM, optional)</label><textarea id="connection-prom-ca" rows={4} maxLength={16384} {...field('prometheusCa')} /></div>
    </fieldset>
    <label className={styles.confirm}><input type="checkbox" checked={acknowledged} required disabled={pending} onChange={event => setAcknowledged(event.target.checked)} />
      These credentials are scoped for read-only inspection of this system. I have not supplied administrator credentials.</label>
    <details className={styles.hint}><summary>Allowed destinations</summary><p>{policy.allowedOrigins.length ? policy.allowedOrigins.join(', ') : 'No destinations configured. Ask the platform operator to allow the private service origins and addresses.'}</p></details>
    <p className={styles.hint}>Tokens are stored encrypted on the platform and never returned to this browser. Saving does not run an inspection or start a test.</p>
    <div className={styles.actions}><Button type="submit" busy={pending} disabled={!secure || !policy.encryptionAvailable || !acknowledged}>Save connection</Button><Button disabled={pending} onClick={onCancel}>Cancel</Button></div>
  </form>;
}
