import type { TargetInput } from './types';

export type ConnectionValues = { kubernetesUrl: string; kubernetesCa: string; kubernetesToken: string;
  prometheusUrl: string; prometheusCa: string; prometheusToken: string };
export const emptyConnection: ConnectionValues = { kubernetesUrl: '', kubernetesCa: '', kubernetesToken: '', prometheusUrl: '', prometheusCa: '', prometheusToken: '' };
export const CONNECTION_FILE_LIMIT = 32768;

/** Accept our data-only file, never kubeconfig, executable configuration or unknown fields. */
export function readConnectionFile(text: string): { system: TargetInput; connection: ConnectionValues } {
  const invalid = () => new Error('Choose a Loadpilot connection file created by the setup helper. Kubernetes kubeconfigs are not supported.');
  if (new TextEncoder().encode(text).length > CONNECTION_FILE_LIMIT) throw invalid();
  let data: unknown;
  try { data = JSON.parse(text.replace(/^\uFEFF/, '')); } catch { throw invalid(); }
  const object = (value: unknown): Record<string, unknown> => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid();
    return value as Record<string, unknown>;
  };
  const root = object(data);
  if (root.format !== 'loadpilot-connection-v1' || Object.keys(root).some(k => !['format', 'system', 'connection'].includes(k))) throw invalid();
  const system = object(root.system), connection = object(root.connection);
  const lengths: Record<string, number> = { name: 100, baseUrl: 2048, environment: 11, namespace: 63, deployment: 253, container: 63 };
  if (Object.keys(system).some(k => !Object.hasOwn(lengths, k)) || Object.entries(lengths).some(([key, max]) => typeof system[key] !== 'string' || !(system[key] as string).trim() || (system[key] as string).length > max)) throw invalid();
  if (!['DEVELOPMENT', 'STAGING', 'PRODUCTION'].includes(system.environment as string)) throw invalid();
  const copied = { ...emptyConnection };
  for (const key of Object.keys(connection)) {
    if (!Object.hasOwn(copied, key)) throw invalid();
    const field = key as keyof ConnectionValues, value = connection[key];
    const max = key.endsWith('Ca') ? 16384 : key.endsWith('Token') ? 8192 : 2048;
    if (typeof value !== 'string' || value.length > max) throw invalid();
    copied[field] = value;
  }
  if (!copied.kubernetesUrl || !copied.kubernetesToken) throw invalid();
  const url = (value: string, tls: boolean) => {
    try {
      const parsed = new URL(value);
      if (!(tls ? parsed.protocol === 'https:' : ['http:', 'https:'].includes(parsed.protocol)) || parsed.username || parsed.password || parsed.search || parsed.hash || parsed.port === '0') throw invalid();
      return parsed;
    } catch { throw invalid(); }
  };
  url(system.baseUrl as string, false);
  if (url(copied.kubernetesUrl, true).pathname !== '/') throw invalid();
  if (copied.prometheusUrl) url(copied.prometheusUrl, true);
  const label = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/;
  if (!label.test(system.namespace as string) || !label.test(system.container as string) || (system.deployment as string).split('.').some(part => part.length > 63 || !label.test(part))) throw invalid();
  if (![copied.kubernetesToken, copied.prometheusToken].every(value => /^[A-Za-z0-9._~+/=\-]*$/.test(value))) throw invalid();
  return { system: { ...system, credentialRef: '', monitoringRef: '' } as TargetInput, connection: copied };
}
