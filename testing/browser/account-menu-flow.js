// Actual exported UI + isolated native backend only. Never run on the VPS.
async (page) => {
  const base = 'http://127.0.0.1:4173';
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const staticFailures = [], errors = [];
  const response = result => { if (!new URL(result.url()).pathname.startsWith('/api/') && result.status() >= 400) staticFailures.push(new URL(result.url()).pathname); };
  const pageError = error => errors.push(error.message);
  page.on('response', response); page.on('pageerror', pageError);
  const avatar = () => page.getByRole('button', { name: 'Account menu for admin', exact: true });
  const open = async () => { await avatar().click(); await page.getByRole('menu', { name: 'Account', exact: true }).waitFor(); };
  const cleanShell = async () => {
    assert(await page.locator('footer').count() === 0, 'Page footer remains');
    assert(await page.getByText(/Performance lab|Operator workspace|Kubernetes performance engineering|Signed in as/).count() === 0, 'Redundant shell text remains');
    assert(await page.locator('aside').getByText('L', { exact: true }).count() === 0, 'Sidebar L badge remains');
    assert(await page.locator('header button:visible').count() === 1, 'Top right contains controls beyond avatar');
    assert(await avatar().isVisible(), 'Avatar not visible');
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Horizontal overflow');
  };
  try {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(`${base}/`);
    await page.getByRole('button', { name: /Sign in|Account menu for admin/, exact: true }).waitFor();
    if (page.url().includes('/login/')) {
      await page.getByLabel('Username', { exact: true }).fill('admin');
      await page.getByLabel('Password', { exact: true }).fill('integration-test-password-123');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.waitForURL(base + '/');
    }
    await avatar().waitFor();
    await page.evaluate(() => localStorage.removeItem('loadpilot:compact-layout'));
    await page.reload(); await avatar().waitFor(); await cleanShell();
    assert(await page.getByRole('menu').count() === 0, 'Account menu starts open');
    await avatar().focus(); await page.keyboard.press('ArrowDown');
    await page.getByRole('menuitem', { name: 'Settings', exact: true }).waitFor();
    assert(await page.getByRole('menuitem', { name: 'Settings', exact: true }).evaluate(node => node === document.activeElement), 'First menu item did not receive focus');
    await page.keyboard.press('End');
    assert(await page.getByRole('menuitem', { name: 'Sign out', exact: true }).evaluate(node => node === document.activeElement), 'End key did not focus final item');
    await page.keyboard.press('ArrowDown');
    assert(await page.getByRole('menuitem', { name: 'Settings', exact: true }).evaluate(node => node === document.activeElement), 'Arrow navigation did not wrap');
    await page.keyboard.press('Escape');
    assert(await avatar().getAttribute('aria-expanded') === 'false', 'Escape did not close account menu');
    assert(await avatar().evaluate(node => node === document.activeElement), 'Escape did not restore avatar focus');
    await page.keyboard.press('ArrowUp');
    assert(await page.getByRole('menuitem', { name: 'Sign out', exact: true }).evaluate(node => node === document.activeElement), 'ArrowUp did not open at the final item');
    await page.keyboard.press('Tab');
    assert(await page.getByRole('menu').count() === 0, 'Tab did not dismiss account menu');
    await open(); await page.getByRole('heading', { level: 1 }).click();
    assert(await page.getByRole('menu').count() === 0, 'Outside click did not dismiss account menu');
    await open(); await page.getByRole('menuitem', { name: 'Settings', exact: true }).click();
    await page.waitForURL(base + '/settings/'); await page.getByRole('heading', { name: 'Settings', exact: true, level: 1 }).waitFor();
    assert(await page.getByRole('menu').count() === 0, 'Settings navigation left menu open');
    await open(); await page.getByRole('menuitem', { name: 'Preferences', exact: true }).click();
    await page.getByRole('dialog', { name: 'Preferences', exact: true }).waitFor();
    const originalPadding = await page.locator('main').evaluate(node => getComputedStyle(node).paddingTop);
    await page.getByRole('checkbox', { name: /Compact layout/ }).check();
    const compactPadding = await page.locator('main').evaluate(node => getComputedStyle(node).paddingTop);
    assert(parseFloat(compactPadding) < parseFloat(originalPadding), 'Compact preference did not change the layout');
    await page.getByRole('button', { name: 'Done', exact: true }).click();
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === 'Account menu for admin');
    assert(await avatar().evaluate(node => node === document.activeElement), 'Preferences did not restore avatar focus');
    await page.reload(); await avatar().waitFor();
    assert(await page.evaluate(() => document.documentElement.dataset.compactLayout) === 'true', 'Preference did not survive reload');
    for (const path of ['/', '/targets/', '/targets/detail/?new=1', '/scenarios/', '/experiments/', '/comparisons/', '/settings/']) {
      await page.goto(base + path); await avatar().waitFor(); await cleanShell();
    }
    await page.setViewportSize({ width: 390, height: 844 }); await cleanShell(); await open();
    await page.screenshot({ path: '.tools/account-menu-review/mobile-menu.png', fullPage: true });
    await page.getByRole('menuitem', { name: 'Preferences', exact: true }).click();
    await page.getByRole('dialog', { name: 'Preferences', exact: true }).waitFor();
    const bounds = await page.getByRole('dialog', { name: 'Preferences', exact: true }).boundingBox();
    assert(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 390, 'Mobile preferences dialog overflows');
    await page.evaluate(() => { window.__loadpilotTestSet = Storage.prototype.setItem; Storage.prototype.setItem = function() { throw new DOMException('Storage disabled for test', 'QuotaExceededError'); }; });
    await page.getByRole('checkbox', { name: /Compact layout/ }).uncheck();
    await page.getByText('Browser storage is unavailable. Changes apply for this visit.', { exact: true }).waitFor();
    assert(await page.evaluate(() => document.documentElement.dataset.compactLayout) === 'false', 'Storage failure blocked current-visit preferences');
    await page.screenshot({ path: '.tools/account-menu-review/mobile-preferences.png', fullPage: true });
    await page.evaluate(() => { Storage.prototype.setItem = window.__loadpilotTestSet; delete window.__loadpilotTestSet; });
    await page.getByRole('checkbox', { name: /Compact layout/ }).check();
    await page.getByRole('checkbox', { name: /Compact layout/ }).uncheck(); await page.keyboard.press('Escape');
    assert(await page.getByRole('dialog').count() === 0, 'Escape did not close preferences');
    await page.getByRole('button', { name: 'Toggle navigation', exact: true }).click();
    await page.getByRole('link', { name: 'Systems', exact: true }).click();
    await page.getByRole('heading', { name: 'Systems', exact: true, level: 1 }).waitFor();
    await page.setViewportSize({ width: 1440, height: 1000 }); await open();
    await page.screenshot({ path: '.tools/account-menu-review/desktop-menu.png', fullPage: true });
    assert(await page.evaluate(() => sessionStorage.length === 0 && Object.keys(localStorage).every(key => key === 'loadpilot:compact-layout') && ['true', 'false'].includes(localStorage.getItem('loadpilot:compact-layout'))), 'Unexpected browser storage');
    await page.route('**/api/access/logout', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'ACCESS_UNAVAILABLE', message: 'Sign-in service is unavailable. Try again later.' }) }));
    await page.getByRole('menuitem', { name: 'Sign out', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'Sign out failed' }).waitFor();
    assert(await avatar().isEnabled(), 'Failed logout left avatar disabled');
    assert(await page.evaluate(async () => (await fetch('/api/access/session')).status) === 200, 'Mocked logout failure lost the session');
    await page.unroute('**/api/access/logout'); await open();
    await page.getByRole('menuitem', { name: 'Sign out', exact: true }).click(); await page.waitForURL(base + '/login/');
    assert(await page.evaluate(async () => (await fetch('/api/status')).status) === 401, 'Menu logout did not revoke API access');
    assert(await page.locator('footer').count() === 0, 'Login page has footer text');
    await page.evaluate(() => localStorage.removeItem('loadpilot:compact-layout'));
    assert(!staticFailures.length && !errors.length, `Static/runtime errors: ${staticFailures.join(', ')} ${errors.join(', ')}`);
    return { passed: true, checks: ['avatar-only header', 'settings link', 'menu keyboard/focus/dismissal', 'working browser preference', 'preference reload', 'blocked storage fallback', 'all workspace pages footer-free', 'sidebar badge removed', 'mobile menu/dialog/navigation', 'failed logout retry', 'menu logout revokes backend access', 'login footer-free'], staticFailures, errors };
  } finally {
    await page.unroute('**/api/access/logout');
    await page.evaluate(() => { if (window.__loadpilotTestSet) { Storage.prototype.setItem = window.__loadpilotTestSet; delete window.__loadpilotTestSet; } });
    page.off('response', response); page.off('pageerror', pageError);
  }
}
