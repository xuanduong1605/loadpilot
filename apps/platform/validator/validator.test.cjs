const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validate } = require('./validate.cjs');
test('accepts supported syntax without executing source or literal text', () => {
  const result = validate("import http from 'k6/http';\n// require open eval\nthrow new Error('must never execute');\nexport default function () { http.get(__ENV.TARGET_URL); }");
  assert.equal(result.policyAccepted, true);
});
test('reports syntax positions without echoing submitted source', () => {
  const result = validate('export default function () { secret-marker @ }');
  assert.equal(result.syntaxValid, false);
  assert.ok(result.findings[0].column > 1);
  assert.ok(!JSON.stringify(result).includes('secret-marker'));
});
test('rejects remote/local/reexport/dynamic imports, decoded identifiers and platform options', () => {
  for (const source of [
    "import x from 'https://example.invalid/code.js'; export default function() {}",
    "import x from './private.js'; export default function() {}",
    "export * from 'https://example.invalid/code.js'; export default function() {}",
    "export default function() { import('k6/http'); }",
    'export const options = { vus: 100000 }; export default function() {}',
    'export default function() { ev\\u0061l("1"); }',
    'export default function() { open("private-file"); }',
  ]) assert.equal(validate(source).policyAccepted, false, source);
});
test('bounds source/findings and requires a default function', () => {
  assert.equal(validate('x'.repeat(65537)).findings[0].code, 'SOURCE_TOO_LARGE');
  assert.equal(validate('export default 42;').policyAccepted, false);
  assert.equal(validate('const a = 1;').policyAccepted, false);
  assert.ok(validate('export default function() {' + 'eval("1");'.repeat(100) + '}').findings.length <= 16);
});
