const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createTarget } = require('./server.cjs');

test('observable HTTP fixture has deterministic health/errors, bounded delay and route counters', async () => {
  const server = createTarget();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.deepEqual(await (await fetch(base+'/health')).json(), { status: 'UP' });
    assert.equal((await fetch(base+'/error')).status,503);
    assert.equal((await fetch(base+'/slow?ms=501')).status,400);
    assert.equal((await fetch(base+'/slow?ms=5')).status,200);
    assert.equal((await fetch(base+'/health',{method:'POST'})).status,405);
    const metrics = await (await fetch(base+'/metrics')).text();
    assert.match(metrics,/fixture_http_requests_total\{route="health"\} 1/);
    assert.match(metrics,/fixture_http_requests_total\{route="error"\} 1/);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
