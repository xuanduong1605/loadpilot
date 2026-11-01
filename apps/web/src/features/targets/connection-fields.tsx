import type { Dispatch, SetStateAction } from 'react';
import { TextField } from '../../components/form-controls';
import type { ConnectionValues } from './connection-file';
import styles from './targets.module.css';

export function ConnectionFields({ values, setValues, disabled, hasToken = false, hasPrometheusToken = false }: {
  values: ConnectionValues; setValues: Dispatch<SetStateAction<ConnectionValues>>; disabled: boolean; hasToken?: boolean; hasPrometheusToken?: boolean;
}) {
  const field = (name: keyof ConnectionValues) => ({ value: values[name], onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setValues(previous => ({ ...previous, [name]: event.target.value })) });
  return <>
    <fieldset className={styles.fields} disabled={disabled}><legend>Kubernetes connection</legend>
      <div className={styles.wide}><TextField id="connection-kube-url" label="Kubernetes API URL" type="url" required maxLength={2048} placeholder="https://cluster.example.com:6443" hint="The cluster endpoint reachable from the Loadpilot server." {...field('kubernetesUrl')} /></div>
      <div className={styles.wide}><TextField id="connection-kube-token" label="Read-only Kubernetes token" type="password" autoComplete="off" required={!hasToken} maxLength={8192}
        hint={hasToken ? 'A token is saved. Leave blank to keep it, or enter a replacement.' : 'Use the setup helper or a namespace-scoped read-only ServiceAccount token.'} {...field('kubernetesToken')} /></div>
      <details className={`${styles.wide} ${styles.optional}`} open={values.kubernetesCa ? true : undefined}>
        <summary>Certificate authority (optional)</summary>
        <label htmlFor="connection-kube-ca">Kubernetes CA certificate (PEM, optional)</label><textarea id="connection-kube-ca" rows={4} maxLength={16384} {...field('kubernetesCa')} />
        <p className={styles.hint}>Only needed for a private CA. Certificate verification is always enabled.</p>
      </details>
    </fieldset>
    <details className={styles.optional} open={values.prometheusUrl ? true : undefined}>
      <summary>Monitoring (optional)</summary>
      <fieldset className={styles.fields} disabled={disabled}><legend>Prometheus</legend>
        <div className={styles.wide}><TextField id="connection-prom-url" label="Prometheus URL" type="url" maxLength={2048} placeholder="https://prometheus.example.com" hint="You can add monitoring later." {...field('prometheusUrl')} /></div>
        <div className={styles.wide}><TextField id="connection-prom-token" label="Prometheus token (optional)" type="password" autoComplete="off" maxLength={8192}
          hint={hasPrometheusToken ? 'A token is saved. Leave blank to keep it.' : 'Only needed if the endpoint requires a token.'} {...field('prometheusToken')} /></div>
        <div className={styles.wide}><label htmlFor="connection-prom-ca">Prometheus CA certificate (PEM, optional)</label><textarea id="connection-prom-ca" rows={4} maxLength={16384} {...field('prometheusCa')} /></div>
      </fieldset>
    </details>
  </>;
}
