// Playwright tool entry; requires the isolated TLS fixture and one UI-seeded
// "TLS fixture system" record with its generated public CA. Never run on production.
async (page) => {
  const base = 'http://127.0.0.1:4173';
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const failures = [];
  const record = response => { if (!new URL(response.url()).pathname.startsWith('/api/') && response.status() >= 400) failures.push(response.url()); };
  page.on('response', record);
  try {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`${base}/targets/`);
    await page.getByRole('heading', { name: /Systems|Sign in/, level: 1 }).waitFor();
    if (page.url().includes('/login/')) {
      await page.getByLabel('Username', { exact: true }).fill('admin');
      await page.getByLabel('Password', { exact: true }).fill('integration-test-password-123');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.waitForURL(`${base}/targets/`);
    }
    const seed = await page.evaluate(async () => {
      const systems = await (await fetch('/api/targets?limit=100')).json();
      const fixture = systems.items.find(system => system.name.startsWith('TLS fixture system ') && system.baseUrl === 'http://127.0.0.1:18080/health');
      if (!fixture) throw new Error('Seed the isolated fixture public CA through the connection UI first');
      const config = await (await fetch(`/api/integrations/targets/${fixture.id}/connection`)).json();
      return { id: fixture.id, ca: config.kubernetesCa };
    });
    assert(seed.ca.includes('BEGIN CERTIFICATE'), 'Fixture public CA is unavailable');
    await page.getByRole('link', { name: 'Register system', exact: true }).click();
    const name = `Connection regression ${Date.now()}`;
    for (const [label, value] of Object.entries({ 'System name': name, 'API base URL': 'http://127.0.0.1:18080/health', Namespace: 'fixture', Deployment: 'fixture-api', Container: 'api' }))
      await page.getByLabel(label, { exact: true }).fill(value);
    await page.getByRole('button', { name: 'Register system', exact: true }).click();
    await page.waitForURL('**/targets/detail/?id=*');
    const id = new URL(page.url()).searchParams.get('id');
    await page.getByRole('button', { name: 'Set up connection', exact: true }).click();
    await page.getByLabel('Kubernetes API URL', { exact: true }).fill('https://127.0.0.1:18443');
    await page.getByLabel('Read-only Kubernetes token', { exact: true }).fill('fixture-readonly-token');
    await page.getByLabel('Kubernetes CA certificate (PEM, optional)', { exact: true }).fill(seed.ca);
    await page.getByRole('checkbox', { name: /These credentials are scoped/ }).check();
    await page.getByRole('button', { name: 'Save connection', exact: true }).click();
    await page.getByRole('button', { name: 'Edit connection', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Check connection & refresh', exact: true }).click();
    await page.getByRole('table').waitFor();
    assert(await page.getByRole('rowheader', { name: /fixture-pod/ }).count() === 1, 'Owned Pod not shown');
    assert(await page.getByRole('rowheader', { name: /unrelated-pod/ }).count() === 0, 'Unrelated Pod included');
    await page.getByRole('button', { name: 'Edit connection', exact: true }).click();
    assert(await page.getByLabel('Read-only Kubernetes token', { exact: true }).inputValue() === '', 'Stored token returned to form');
    await page.getByLabel('Read-only Kubernetes token', { exact: true }).fill('invalid-fixture-token');
    await page.getByRole('checkbox', { name: /These credentials are scoped/ }).check();
    await page.getByRole('button', { name: 'Save connection', exact: true }).click();
    await page.getByRole('button', { name: 'Check connection & refresh', exact: true }).click();
    await page.getByText('HTTP 401 from Kubernetes apps/v1 and core/v1.', { exact: true }).waitFor();
    assert(await page.evaluate(async () => (await fetch('/api/access/session')).status) === 200, 'Cluster401 revoked platform session');
    const independent = await page.evaluate(async id => (await (await fetch(`/api/integrations/targets/${id}/status`)).json()).kubernetes.state, seed.id);
    assert(independent === 'READY', 'Changing one system affected another system');
    await page.getByRole('button', { name: 'Edit connection', exact: true }).click();
    await page.getByLabel('Read-only Kubernetes token', { exact: true }).fill('fixture-readonly-token');
    await page.getByRole('checkbox', { name: /These credentials are scoped/ }).check();
    await page.getByRole('button', { name: 'Save connection', exact: true }).click();
    await page.getByRole('button', { name: 'Check connection & refresh', exact: true }).click();
    await page.getByRole('table').waitFor();
    const background = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(`/${id}/status`)
      && !response.request().headers()['x-loadpilot-interactive'], { timeout: 25000 });
    await page.getByLabel('Auto-refresh every 15 seconds', { exact: true }).check();
    const updated = await (await background).json();
    assert(updated.pods[0].usage.cpuMillicores === 125 && updated.pods[0].usage.memoryBytes === 33554432, 'Live unit conversion incorrect');
    await page.getByLabel('Auto-refresh every 15 seconds', { exact: true }).uncheck();
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile overflow');
    assert(await page.evaluate(() => localStorage.length === 0 && sessionStorage.length === 0), 'Connection stored in browser storage');
    assert(failures.length === 0, `Static failures: ${failures.join(', ')}`);
    return { passed: true, id, checks: ['self-service setup', 'write-only token', 'owned Pod scope', 'unit conversion', 'token rotation', 'cluster401 distinct from session401', 'multiple-system isolation', 'live background refresh', 'mobile', 'no browser storage', 'static routes'] };
  } finally { page.off('response', record); }
}
