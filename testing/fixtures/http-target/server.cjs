const http = require('node:http');

function createTarget() {
  const counts = { health: 0, slow: 0, error: 0, metrics: 0 };
  return http.createServer(async (request, response) => {
    if (request.method !== 'GET') { response.writeHead(405).end(); return; }
    const url = new URL(request.url, 'http://fixture');
    const route = url.pathname.slice(1);
    if (!Object.hasOwn(counts, route)) { response.writeHead(404).end(); return; }
    counts[route]++;
    if (route === 'metrics') {
      response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
      response.end(Object.entries(counts).map(([path, count]) => `fixture_http_requests_total{route="${path}"} ${count}\n`).join(''));
    } else {
      if (route === 'slow') {
        const delay = Number(url.searchParams.get('ms') ?? 100);
        if (!Number.isInteger(delay) || delay < 0 || delay > 500) { response.writeHead(400).end(); return; }
        await new Promise(resolve => setTimeout(resolve, delay));
      }
      response.writeHead(route === 'error' ? 503 : 200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ status: route === 'error' ? 'UNAVAILABLE' : 'UP' }));
    }
  });
}
module.exports = { createTarget };
if (require.main === module) createTarget().listen(18081, '127.0.0.1', () => console.log('Observable fixture: loopback18081'));
