// Isolated native backend/Nginx/TLS fixture only; never production.
async (page) => {
  const base = 'http://127.0.0.1:4173';
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const failures = [], writes = [], checks = [];
  const record = response => {
    const path = new URL(response.url()).pathname;
    if (!path.startsWith('/api/') && response.status() >= 400) failures.push(path);
    if (response.request().method() === 'POST' && path === '/api/integrations/systems') writes.push(response.status());
    if (path.endsWith('/status') && path.startsWith('/api/integrations/targets/')) checks.push(path);
  };
  const name = `Quick connection ${Date.now()}`;
  const bundle = { format: 'loadpilot-connection-v1', system: { name, environment: 'STAGING', baseUrl: 'http://127.0.0.1:18080/health', namespace: 'fixture', deployment: 'fixture-api', container: 'api' },
    connection: { kubernetesUrl: 'https://127.0.0.1:18443', kubernetesCa: '', kubernetesToken: 'fixture-readonly-token' } };
  const file = value => ({ name: 'system.loadpilot.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(value)) });
  const upload = async value => page.getByLabel('Import connection file', { exact: true }).setInputFiles(file(value));
  const list = async () => page.evaluate(async () => (await (await fetch('/api/targets?limit=100')).json()).items);
  const acknowledge = () => page.getByRole('checkbox', { name: /These credentials are scoped/ }).check();
  const connect = () => page.getByRole('button', { name: 'Connect system', exact: true }).click();
  const open = async () => { await page.goto(`${base}/targets/detail/?new=1`); await page.waitForFunction(() => !!document.querySelector('#connection-file') && !document.querySelector('#connection-file').disabled); };
  page.on('response', record);
  try {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`${base}/targets/detail/?new=1`);
    await page.getByRole('button', { name: /Sign in|Connect system/, exact: true }).waitFor();
    if (page.url().includes('/login/')) {
      await page.getByLabel('Username', { exact: true }).fill('admin');
      await page.getByLabel('Password', { exact: true }).fill('integration-test-password-123');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.waitForURL(`${base}/targets/detail/?new=1`);
    }
    await page.waitForFunction(() => !document.querySelector('#connection-file').disabled);
    bundle.connection.kubernetesCa = await page.evaluate(async () => {
      const systems = await (await fetch('/api/targets?limit=100')).json();
      const fixture = systems.items.find(system => system.name.startsWith('TLS fixture system ') && system.baseUrl === 'http://127.0.0.1:18080/health');
      if (!fixture) throw new Error('Seed the isolated TLS fixture first');
      return (await (await fetch(`/api/integrations/targets/${fixture.id}/connection`)).json()).kubernetesCa;
    });
    assert(!(await page.getByLabel('Prometheus URL', { exact: true }).isVisible()), 'Monitoring was expanded initially');
    await page.screenshot({ path: '.tools/quick-connect-review/registration-desktop.png', fullPage: true });
    await upload({ apiVersion: 'v1', users: [{ name: 'admin-kubeconfig' }] });
    await page.getByRole('alert').filter({ hasText: 'kubeconfigs are not supported' }).waitFor();
    assert(writes.length === 0, 'Invalid file caused a write');
    await upload({ ...bundle, connection: { ...bundle.connection, kubernetesUrl: 'https://unrelated.example.invalid' } });
    await page.getByText('Connection details imported. Review them below before connecting.', { exact: true }).waitFor();
    await page.getByRole('heading', { name, exact: true }).waitFor();
    await acknowledge(); await connect();
    await page.getByRole('alert').filter({ hasText: 'not allowed' }).waitFor();
    assert(!(await list()).some(target => target.name === name), 'Rejected connection left a partial system');
    await page.getByRole('button', { name: 'Review or edit details', exact: true }).click();
    assert(await page.getByLabel('System name', { exact: true }).inputValue() === name, 'Import did not fill system fields');
    await page.getByLabel('Kubernetes API URL', { exact: true }).fill('https://127.0.0.1:18443');
    await connect(); await page.waitForURL('**/targets/detail/?id=*&check=1');
    const id = new URL(page.url()).searchParams.get('id');
    await page.getByRole('rowheader', { name: /fixture-pod/ }).waitFor();
    assert(checks.filter(path => path.includes(id)).length === 1, 'Initial inspection did not run exactly once');
    assert(writes.join(',') === '403,201', 'Expected one failed save and one atomic save');
    assert((await list()).filter(target => target.name === name).length === 1, 'Retry created duplicates');
    assert(await page.getByRole('rowheader', { name: /unrelated-pod/ }).count() === 0, 'Unrelated Pod included');
    await page.getByRole('button', { name: 'Edit connection', exact: true }).click();
    assert(await page.getByLabel('Read-only Kubernetes token', { exact: true }).inputValue() === '', 'Saved token returned to browser');
    const renew = page.getByLabel('Import connection file (optional)', { exact: true });
    await renew.setInputFiles(file({ ...bundle, system: { ...bundle.system, deployment: 'other-api' } }));
    await page.getByRole('alert').filter({ hasText: 'different workload' }).waitFor();
    assert(await page.getByLabel('Read-only Kubernetes token', { exact: true }).inputValue() === '', 'Wrong-workload import changed token');
    await renew.setInputFiles(file(bundle)); await acknowledge();
    await page.getByRole('button', { name: 'Save connection', exact: true }).click();
    await page.getByRole('button', { name: 'Edit connection', exact: true }).waitFor();
    await open(); await upload(bundle); await acknowledge(); await connect();
    await page.getByRole('alert').filter({ hasText: 'already exists' }).waitFor();
    assert((await list()).filter(target => target.name === name).length === 1, 'Duplicate attempt changed systems');
    await upload({ ...bundle, system: { ...bundle.system, name: name + ' invalid token' }, connection: { ...bundle.connection, kubernetesToken: 'invalid-fixture-token' } });
    await acknowledge(); await connect(); await page.waitForURL('**/targets/detail/?id=*&check=1');
    await page.getByText('HTTP 401 from Kubernetes apps/v1 and core/v1.', { exact: true }).waitFor();
    assert(await page.evaluate(async () => (await fetch('/api/access/session')).status) === 200, 'Upstream401 revoked browser session');
    await open(); await page.getByRole('button', { name: 'Register details only', exact: true }).click();
    assert(await page.getByLabel('Read-only Kubernetes token', { exact: true }).count() === 0, 'Details-only requires credentials');
    for (const [label, value] of Object.entries({ 'System name': name + ' details only', 'API base URL': 'https://fixture.example.invalid', Namespace: 'fixture', Deployment: 'fixture-api', Container: 'api' })) await page.getByLabel(label, { exact: true }).fill(value);
    await page.getByRole('button', { name: 'Register system', exact: true }).click();
    await page.waitForURL('**/targets/detail/?id=*'); await page.getByRole('button', { name: 'Set up connection', exact: true }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 }); await open();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile overflow');
    await page.screenshot({ path: '.tools/quick-connect-review/registration-mobile.png', fullPage: true });
    await page.getByRole('button', { name: 'Enter details manually', exact: true }).click();
    await page.getByLabel('Namespace', { exact: true }).fill('Not Valid');
    assert(await page.getByLabel('Namespace', { exact: true }).evaluate(element => !element.checkValidity()), 'Manual namespace validation missing');
    assert(await page.evaluate(() => !localStorage.length && !sessionStorage.length), 'Browser storage used');
    assert(failures.length === 0, `Static failures: ${failures.join(',')}`);
    return { result: 'PASS', writes, automaticInspection: true, rollback: true, import: true, renewal: true, duplicate: true, upstream401: true, metadataOnly: true, mobile: true, staticFailures: failures };
  } finally { page.off('response', record); }
}
