import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readConnectionFile } from '../apps/web/src/features/targets/connection-file.ts';

const file = () => ({ format: 'loadpilot-connection-v1', system: { name: 'Fixture', baseUrl: 'http://127.0.0.1:18080/health',
  environment: 'STAGING', namespace: 'fixture', deployment: 'fixture-api', container: 'api' },
  connection: { kubernetesUrl: 'https://localhost:16443', kubernetesToken: 'fixture-token' } });
test('imports a versioned data file including PowerShell BOM, without inventing monitoring or backend references', () => {
  const imported = readConnectionFile('\uFEFF' + JSON.stringify(file()));
  assert.equal(imported.system.namespace, 'fixture');
  assert.equal(imported.system.credentialRef, '');
  assert.equal(imported.connection.prometheusUrl, '');
  assert.equal(imported.connection.kubernetesToken, 'fixture-token');
});
test('rejects kubeconfig, unknown formats, executable-looking fields and unexpected backend references', () => {
  for (const value of [{ apiVersion: 'v1', users: [{ user: { 'client-key-data': 'fixture-key' } }] },
    { ...file(), format: 'unknown' }, { ...file(), exec: { command: 'anything' } },
    { ...file(), system: { ...file().system, credentialRef: 'other-system' } },
    { ...file(), connection: { ...file().connection, insecureSkipTlsVerify: true } }]) {
    assert.throws(() => readConnectionFile(JSON.stringify(value)), /connection file/);
  }
});
test('rejects malformed, missing, mistyped, oversized and prototype-looking fields without echoing secrets', () => {
  const values = ['not-json-secret-value', 'null', '[]', JSON.stringify({ ...file(), system: null }),
    JSON.stringify({ ...file(), connection: { kubernetesUrl: 'https://localhost' } }),
    JSON.stringify({ ...file(), system: { ...file().system, environment: 'UNKNOWN' } }),
    JSON.stringify({ ...file(), connection: { ...file().connection, kubernetesToken: 123 } }),
    JSON.stringify({ ...file(), connection: { ...file().connection, kubernetesToken: 'x'.repeat(8193) } }),
    JSON.stringify(file()).replace('"name":', '"__proto__":"value","name":'), 'x'.repeat(32769)];
  for (const value of values) {
    assert.throws(() => readConnectionFile(value), error => error instanceof Error && !error.message.includes('secret-value'));
  }
});
test('rejects embedded URL credentials, non-TLS Kubernetes, unsupported workload names and malformed tokens before preview', () => {
  for (const value of [
    { ...file(), system: { ...file().system, baseUrl: 'https://admin:secret@example.invalid' } },
    { ...file(), system: { ...file().system, namespace: 'INVALID' } },
    { ...file(), connection: { ...file().connection, kubernetesUrl: 'http://localhost:16443' } },
    { ...file(), connection: { ...file().connection, kubernetesUrl: 'https://localhost/other' } },
    { ...file(), connection: { ...file().connection, kubernetesToken: 'token with spaces' } }
  ]) assert.throws(() => readConnectionFile(JSON.stringify(value)), /connection file/);
});
