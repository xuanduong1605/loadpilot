// Run with the Playwright browser_run_code_unsafe tool against the isolated stack.
async (page) => {
  const base = 'http://127.0.0.1:4173';
  const name = `Browser system ${Date.now()}`;
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const failures = [];
  const record = response => { if (!new URL(response.url()).pathname.startsWith('/api/') && response.status() >= 400) failures.push(response.url()); };
  page.on('response', record);
  try {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${base}/targets/detail/?new=1`);
    await page.getByRole('button', { name: /Sign in|Register system/, exact: true }).waitFor();
    if (page.url().includes('/login/')) {
      await page.getByLabel('Username', { exact: true }).fill('admin');
      await page.getByLabel('Password', { exact: true }).fill('integration-test-password-123');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.waitForURL(`${base}/targets/detail/?new=1`);
    }
    for (const [label, value] of Object.entries({ 'System name': name, 'API base URL': 'https://fixture.example.invalid', Namespace: 'fixture', Deployment: 'fixture-api', Container: 'api' }))
      await page.getByLabel(label, { exact: true }).fill(value);
    await page.getByRole('button', { name: 'Register system', exact: true }).click();
    await page.waitForURL('**/targets/detail/?id=*');
    const url = page.url();
    const id = new URL(url).searchParams.get('id');
    await page.getByRole('heading', { name, exact: true }).waitFor();
    await page.reload();
    await page.getByRole('heading', { name, exact: true }).waitFor();
    await page.getByRole('button', { name: 'Edit system', exact: true }).click();
    await page.getByLabel('System name', { exact: true }).fill(`${name} edited`);
    await page.getByRole('button', { name: 'Save changes', exact: true }).click();
    await page.getByRole('heading', { name: `${name} edited`, exact: true }).waitFor();
    await page.getByRole('button', { name: 'Edit system', exact: true }).click();
    await page.getByLabel('System name', { exact: true }).fill(`${name} draft`);
    const update = await page.evaluate(async id => {
      const target = await (await fetch(`/api/targets/${id}`)).json();
      const csrf = await (await fetch('/api/access/csrf')).json();
      return (await fetch(`/api/targets/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify({ ...target, name: `${target.name} concurrent` }) })).status;
    }, id);
    assert(update === 200, 'Concurrent update setup failed');
    await page.getByRole('button', { name: 'Save changes', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'changed' }).waitFor();
    assert(await page.getByLabel('System name', { exact: true }).inputValue() === `${name} draft`, 'Conflict discarded the draft');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.reload();
    await page.getByRole('button', { name: 'Archive system', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm archive', exact: true }).click();
    await page.getByText('Archived', { exact: true }).waitFor();
    await page.getByRole('link', { name: 'All systems', exact: true }).click();
    await page.getByRole('heading', { name: 'Systems', exact: true }).waitFor();
    await page.getByText('Loading systems…').waitFor({ state: 'hidden' });
    assert(await page.getByRole('link', { name: `${name} edited concurrent`, exact: true }).count() === 0, 'Archived system in active list');
    await page.getByLabel('Include archived systems').check();
    await page.getByRole('link', { name: `${name} edited concurrent`, exact: true }).waitFor();
    await page.goto(`${base}/targets/detail/?id=invalid`);
    await page.getByText('Choose a system', { exact: true }).waitFor();
    await page.goto(`${base}/targets/detail/?new=1`);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByLabel('Namespace', { exact: true }).fill('Not Valid');
    assert(await page.getByLabel('Namespace', { exact: true }).evaluate(element => !element.checkValidity()), 'Namespace pattern not enforced');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile form overflow');
    assert(failures.length === 0, `Static failures: ${failures.join(', ')}`);
    return { passed: true, id, url, checks: ['register', 'bookmarked reload', 'edit', 'stale edit preserves draft', 'archive', 'archived filter', 'missing ID', 'mobile form', 'namespace validation', 'static route data'] };
  } finally { page.off('response', record); }
}
