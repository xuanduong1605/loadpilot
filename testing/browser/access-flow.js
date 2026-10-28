// Playwright tool entry point: run this file with browser_run_code_unsafe.
// Use only the isolated backend described in contracts/access.md.
async (page) => {
  const base = 'http://127.0.0.1:4173';
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const staticFailures = [];
  const record = response => {
    const path = new URL(response.url()).pathname;
    if (!path.startsWith('/api/') && response.status() >= 400) staticFailures.push(path);
  };
  page.on('response', record);
  try {
    await page.context().clearCookies();
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${base}/targets/`);
    await page.waitForURL('**/login/?next=*');
    assert(new URL(page.url()).searchParams.get('next') === '/targets/', 'Lost return destination');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    assert(await page.locator(':focus').getAttribute('id') === 'login-username', 'Invalid username not focused');
    await page.getByLabel('Username', { exact: true }).fill('admin');
    await page.getByLabel('Password', { exact: true }).fill('incorrect');
    await page.getByRole('button', { name: 'Show password' }).click();
    assert(await page.getByLabel('Password', { exact: true }).getAttribute('type') === 'text', 'Password toggle failed');
    await page.getByRole('button', { name: 'Hide password' }).click();
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'incorrect' }).waitFor();
    assert(await page.getByLabel('Password', { exact: true }).inputValue() === '', 'Failed password retained');
    await page.getByLabel('Password', { exact: true }).fill('integration-test-password-123');
    await page.getByLabel('Password', { exact: true }).press('Enter');
    await page.waitForURL(`${base}/targets/`);
    await page.getByText('Signed in as admin').waitFor();
    await page.reload();
    await page.getByText('Signed in as admin').waitFor();
    for (const name of ['Scenarios', 'Experiments', 'Comparisons', 'Settings', 'Systems']) {
      await page.getByRole('link', { name, exact: true }).click();
      await page.getByRole('heading', { name, exact: true, level: 1 }).waitFor();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: 'Toggle navigation' }).click();
    await page.getByRole('link', { name: 'Experiments', exact: true }).click();
    assert(await page.getByRole('button', { name: 'Toggle navigation' }).getAttribute('aria-expanded') === 'false', 'Mobile menu stayed open');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile overflow');
    const cookie = (await page.context().cookies()).find(cookie => cookie.name === 'LOADPILOT_SESSION');
    assert(cookie?.httpOnly && cookie.sameSite === 'Lax' && cookie.path === '/', 'Session cookie policy failed');
    assert(await page.evaluate(() => localStorage.length === 0 && sessionStorage.length === 0), 'Unexpected browser storage');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.waitForURL(`${base}/login/`);
    assert(await page.evaluate(async () => (await fetch('/api/status')).status) === 401, 'Logout did not revoke API access');
    assert(staticFailures.length === 0, `Static route failures: ${staticFailures.join(', ')}`);
    return { passed: true, checks: ['deep link', 'validation', 'password toggle', 'wrong credentials', 'keyboard login', 'reload', 'section navigation', 'mobile', 'cookie policy', 'no storage', 'logout'], staticFailures };
  } finally { page.off('response', record); }
}
