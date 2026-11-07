// Parse only. Never import, evaluate or execute submitted source.
const acorn = require('acorn');
const POLICY = 'static-review-v1';
const ALLOWED = new Set(['k6', 'k6/http', 'k6/metrics', 'k6/execution']);
const FORBIDDEN = new Set(['eval', 'Function', 'AsyncFunction', 'require', 'open', 'fetch', 'WebSocket', 'process', 'globalThis', 'global', 'window', 'document', 'WebAssembly', 'Deno', 'Bun']);
function validate(source) {
  const result = { syntaxValid: false, policyAccepted: false, policyVersion: POLICY, parserVersion: acorn.version, nodeMajor: Number(process.versions.node.split('.')[0]), findings: [] };
  const add = (node, code, message) => {
    if (result.findings.length < 16) result.findings.push({ code, message, line: node?.loc?.start?.line ?? 1, column: (node?.loc?.start?.column ?? 0) + 1 });
  };
  if (Buffer.byteLength(source, 'utf8') > 65536) { add(null, 'SOURCE_TOO_LARGE', 'Source exceeds the 64 KiB UTF-8 limit.'); return result; }
  let tree;
  try { tree = acorn.parse(source, { ecmaVersion: 2020, sourceType: 'module', locations: true }); }
  catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
    add({ loc: { start: error.loc } }, 'SYNTAX_ERROR', 'Invalid JavaScript syntax for the supported ECMAScript 2020 profile.'); return result;
  }
  result.syntaxValid = true;
  let hasDefault = false;
  const stack = [tree];
  while (stack.length) {
    const node = stack.pop();
    if (node.type === 'ImportDeclaration' && !ALLOWED.has(node.source.value)) add(node, 'IMPORT_NOT_ALLOWED', 'Only k6, k6/http, k6/metrics and k6/execution imports are supported.');
    if (node.type === 'ImportExpression') add(node, 'DYNAMIC_IMPORT', 'Dynamic imports are not supported.');
    if ((node.type === 'ExportNamedDeclaration' || node.type === 'ExportAllDeclaration') && node.source) add(node, 'REEXPORT_NOT_ALLOWED', 'Module re-exports are not supported.');
    if (node.type === 'ExportDefaultDeclaration') {
      hasDefault = true;
      if (!['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression'].includes(node.declaration.type)) add(node, 'DEFAULT_FUNCTION_REQUIRED', 'Export the iteration function directly as default.');
    }
    if (node.type === 'ExportNamedDeclaration') {
      const names = (node.specifiers ?? []).map(specifier => specifier.exported.name);
      if (node.declaration?.id) names.push(node.declaration.id.name);
      for (const declaration of node.declaration?.declarations ?? []) {
        if (declaration.id.type !== 'Identifier') add(node, 'EXPORTED_PATTERN', 'Destructured exports are not supported.');
        names.push(declaration.id.name);
      }
      if (names.some(name => ['options', 'setup', 'teardown', 'handleSummary'].includes(name))) add(node, 'PLATFORM_CONTROLLED_EXPORT', 'Options, lifecycle and summary exports require execution-budget support and are not supported in this profile.');
    }
    if (node.type === 'Identifier' && FORBIDDEN.has(node.name)) add(node, 'UNSUPPORTED_CAPABILITY', 'A runtime, filesystem or dynamic-code capability is not supported in this profile.');
    if (node.type === 'MemberExpression' && ((!node.computed && ['constructor', '__proto__', 'prototype'].includes(node.property.name)) || (node.computed && node.property.type === 'Literal' && ['constructor', '__proto__', 'prototype'].includes(node.property.value)))) add(node, 'DYNAMIC_CODE_ACCESS', 'Prototype and constructor access is not supported.');
    for (const [key, value] of Object.entries(node)) {
      if (key === 'loc' || key === 'start' || key === 'end') continue;
      if (Array.isArray(value)) { for (const item of value) if (item && typeof item.type === 'string') stack.push(item); }
      else if (value && typeof value.type === 'string') stack.push(value);
    }
  }
  if (!hasDefault) add(null, 'DEFAULT_FUNCTION_REQUIRED', 'Export a default iteration function.');
  result.policyAccepted = result.findings.length === 0;
  return result;
}
module.exports = { validate };
if (require.main === module) {
  const execution = process.argv[2] === '--execution';
  const limit = execution ? 524288 : 65536;
  const run = input => execution ? require('./execution.cjs').validateExecution(JSON.parse(input)) : validate(input);
  const chunks = []; let size = 0;
  process.stdin.on('data', chunk => {
    size += chunk.length;
    if (size > limit) { process.stdout.write(JSON.stringify(validate('x'.repeat(65537)))); process.exit(0); }
    chunks.push(chunk);
  });
  process.stdin.on('end', () => process.stdout.write(JSON.stringify(run(Buffer.concat(chunks).toString('utf8')))));
}
