// Parse-only execution profile. No submitted module is imported, compiled or evaluated.
const acorn = require('acorn');
const POLICY = 'literal-http-v1';
const NODES = new Set(('Program ImportDeclaration ImportDefaultSpecifier ImportSpecifier ExportDefaultDeclaration FunctionDeclaration FunctionExpression ArrowFunctionExpression BlockStatement EmptyStatement ExpressionStatement ReturnStatement VariableDeclaration VariableDeclarator Identifier Literal MemberExpression CallExpression NewExpression ObjectExpression Property ArrayExpression BinaryExpression LogicalExpression UnaryExpression ConditionalExpression IfStatement ForStatement ForOfStatement WhileStatement DoWhileStatement BreakStatement ContinueStatement UpdateExpression AssignmentExpression SequenceExpression TemplateLiteral TemplateElement').split(' '));
const DANGEROUS = new Set(('constructor prototype __proto__ caller callee arguments call apply bind __defineGetter__ __defineSetter__ __lookupGetter__ __lookupSetter__').split(' '));
const K6 = new Set(['check', 'sleep', 'group', 'fail', 'randomSeed']);
const METRICS = new Set(['Counter', 'Gauge', 'Rate', 'Trend']);
const MATH = new Set(['abs', 'ceil', 'floor', 'round', 'trunc', 'min', 'max', 'pow', 'sqrt', 'random', 'sign', 'PI', 'E']);
const GLOBALS = new Set(['Math', 'JSON', 'undefined', 'NaN', 'Infinity', '__VU', '__ITER']);
const CAPABILITIES = new Set(('eval Function AsyncFunction require open fetch WebSocket process globalThis global window document WebAssembly Deno Bun Object Reflect Proxy console __ENV').split(' '));
function children(node) {
  const result = [];
  for (const [key, value] of Object.entries(node)) {
    if (['loc', 'start', 'end'].includes(key)) continue;
    if (Array.isArray(value)) result.push(...value.filter(x => x && typeof x.type === 'string'));
    else if (value && typeof value.type === 'string') result.push(value);
  }
  return result;
}
function validateExecution(input) {
  const result = { syntaxValid: false, policyAccepted: false, policyVersion: POLICY, parserVersion: acorn.version, nodeMajor: Number(process.versions.node.split('.')[0]), findings: [] };
  const add = (node, code, message) => { if (result.findings.length < 16) result.findings.push({ code, message, line: node?.loc?.start?.line ?? 1, column: (node?.loc?.start?.column ?? 0) + 1 }); };
  let target;
  try {
    if (!input || typeof input.source !== 'string' || Buffer.byteLength(input.source, 'utf8') > 65536 || typeof input.targetUrl !== 'string' || input.targetUrl.length > 2048 || !Number.isSafeInteger(input.requestTimeoutMs) || input.requestTimeoutMs < 1 || input.requestTimeoutMs > 120000) throw 0;
    target = new URL(input.targetUrl);
    if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || target.search || target.hash || /[\s\\]/.test(input.targetUrl)) throw 0;
  } catch { add(null, 'EXECUTION_INPUT', 'Provide bounded source, a reviewed HTTP origin and a positive request timeout.'); return result; }
  let tree;
  try { tree = acorn.parse(input.source, { ecmaVersion: 2020, sourceType: 'module', locations: true }); }
  catch { add(null, 'SYNTAX_ERROR', 'Source cannot be parsed under the supported execution profile.'); return result; }
  result.syntaxValid = true;
  const imports = new Map(), declarationNodes = [], bindings = new Set();
  const root = { names: new Set(), parent: null, function: true };
  const bind = (node, scope) => { declarationNodes.push(node);if (node?.type === 'Identifier') scope.names.add(node.name); };
  const declared = (name, scope) => { for (; scope; scope = scope.parent) if (scope.names.has(name)) return true;return false; };
  let hasDefault = false, count = 0;
  const nodes = [], stack = [[tree, null, null, root]];
  while (stack.length) {
    const [node, parent, grandparent, outer] = stack.pop();
    let scope = outer;
    if (['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression'].includes(node.type)) {
      scope = { names: new Set(), parent: outer, function: true };
      if (node.id) { bind(node.id, scope);if (node.type === 'FunctionDeclaration') outer.names.add(node.id.name); }
      for (const param of node.params) bind(param, scope);
    } else if (['BlockStatement', 'ForStatement', 'ForOfStatement'].includes(node.type)) scope = { names: new Set(), parent: outer, function: false };
    if (++count > 12000) { add(node, 'SOURCE_COMPLEXITY', 'Source exceeds the bounded execution profile complexity.'); break; }
    nodes.push([node, parent, grandparent, scope]);
    if (!NODES.has(node.type) || node.async || node.generator) add(node, 'EXECUTION_SYNTAX', 'This syntax or capability is unsupported for execution.');
    if (node.type === 'ExportDefaultDeclaration') {
      hasDefault = true;
      if (!['FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression'].includes(node.declaration.type)) add(node, 'DEFAULT_FUNCTION_REQUIRED', 'Export the iteration function directly as default.');
    }
    if (node.type === 'ImportDeclaration') {
      for (const spec of node.specifiers) {
        let kind;
        if (node.source.value === 'k6/http' && spec.type === 'ImportDefaultSpecifier') kind = 'http';
        if (node.source.value === 'k6' && spec.type === 'ImportSpecifier' && K6.has(spec.imported.name)) kind = 'k6';
        if (node.source.value === 'k6/metrics' && spec.type === 'ImportSpecifier' && METRICS.has(spec.imported.name)) kind = 'metric';
        if (!kind || imports.has(spec.local.name) || GLOBALS.has(spec.local.name) || DANGEROUS.has(spec.local.name)) add(spec, 'EXECUTION_IMPORT', 'Only direct HTTP, selected k6 helpers and metric constructors are supported.');
        else imports.set(spec.local.name, kind);
      }
      if (!node.specifiers.length) add(node, 'EXECUTION_IMPORT', 'Side-effect imports are unsupported.');
    }
    if (node.type === 'VariableDeclarator') {
      let destination = scope;
      if (parent?.kind === 'var') while (!destination.function) destination = destination.parent;
      bind(node.id, destination);
    }
    for (const child of children(node)) stack.push([child, node, parent, scope]);
  }
  for (const node of declarationNodes) {
    if (node.type !== 'Identifier') add(node, 'EXECUTION_BINDING', 'Destructured and rest bindings are unsupported.');
    else {
      bindings.add(node);
      if (imports.has(node.name) || GLOBALS.has(node.name) || DANGEROUS.has(node.name)) add(node, 'EXECUTION_BINDING', 'Imported and reserved bindings cannot be shadowed.');
    }
  }
  function safeInit(node, depth = 0) {
    if (!node || depth > 64) return false;
    if (node.type === 'Literal') return !node.regex && !node.bigint;
    if (['FunctionExpression', 'ArrowFunctionExpression'].includes(node.type)) return true;
    if (node.type === 'ArrayExpression') return node.elements.every(x => x === null || safeInit(x, depth + 1));
    if (node.type === 'ObjectExpression') return node.properties.every(p => p.type === 'Property' && p.kind === 'init' && !p.computed && !p.method && safeInit(p.value, depth + 1));
    if (node.type === 'NewExpression') return node.callee.type === 'Identifier' && imports.get(node.callee.name) === 'metric' && node.arguments.length <= 2 && node.arguments.every(x => x.type === 'Literal');
    return false;
  }
  for (const node of tree.body) {
    const inert = ['ImportDeclaration', 'FunctionDeclaration', 'ExportDefaultDeclaration', 'EmptyStatement'].includes(node.type) ||
      (node.type === 'VariableDeclaration' && node.kind === 'const' && node.declarations.every(d => safeInit(d.init)));
    if (!inert) add(node, 'EXECUTION_INIT', 'Module initialization supports declarations and literal constants only; move work into the iteration function.');
  }
  function literal(node) { return node?.type === 'Literal' ? node.value : undefined; }
  function object(node) {
    if (node?.type !== 'ObjectExpression') return null;
    const values = new Map();
    for (const p of node.properties) {
      const key = p.key?.type === 'Identifier' ? p.key.name : literal(p.key);
      if (p.type !== 'Property' || p.computed || p.method || p.kind !== 'init' || typeof key !== 'string' || values.has(key) || DANGEROUS.has(key)) return null;
      values.set(key, p.value);
    }
    return values;
  }
  function stringMap(node, headers) {
    const values = object(node);
    return values && values.size <= 32 && [...values].every(([key, value]) => key.length > 0 && key.length <= 128 && typeof literal(value) === 'string' && literal(value).length <= 1024 && !/[\r\n\x00]/.test(key + literal(value)) && (!headers || !['host', ':authority'].includes(key.toLowerCase())));
  }
  function httpCall(call, method) {
    const bodyMethod = ['post', 'put', 'patch', 'del', 'options'].includes(method);
    const generic = method === 'request';
    const paramsAt = generic ? 3 : bodyMethod ? 2 : 1;
    if (!['get', 'head', 'post', 'put', 'patch', 'del', 'options', 'request'].includes(method) || call.arguments.length !== paramsAt + 1 || (generic && !['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'].includes(literal(call.arguments[0])))) {
      add(call, 'HTTP_CALL_SHAPE', 'Use a supported direct HTTP method with explicit URL, body where applicable, and params.'); return;
    }
    const url = literal(call.arguments[generic ? 1 : 0]);
    try {
      if (typeof url !== 'string' || url.length > 2048 || /[\s\\\x00-\x1f\x7f]/.test(url)) throw 0;
      const parsed = new URL(url);
      if (parsed.origin !== target.origin || parsed.username || parsed.password || parsed.hash || !/^https?:\/\//.test(url)) throw 0;
    } catch { add(call, 'HTTP_DESTINATION', 'Every HTTP URL must be a literal on the exact reviewed origin, without credentials or fragments.'); }
    const params = object(call.arguments[paramsAt]);
    if (!params || [...params.keys()].some(k => !['timeout', 'redirects', 'headers', 'tags', 'responseType'].includes(k))) {
      add(call, 'HTTP_PARAMS', 'Use a literal params object with supported fields and no overrides or spreads.'); return;
    }
    const timeout = literal(params.get('timeout'));
    let ms = typeof timeout === 'number' ? timeout : NaN;
    if (typeof timeout === 'string' && /^\d+(?:\.\d{1,3})?(?:ms|s)$/.test(timeout)) ms = Number.parseFloat(timeout) * (timeout.endsWith('ms') ? 1 : 1000);
    if (!Number.isSafeInteger(ms) || ms < 1 || ms > input.requestTimeoutMs) add(call, 'HTTP_TIMEOUT', 'Every request needs a positive whole-millisecond literal timeout at or below the reviewed bound.');
    if (literal(params.get('redirects')) !== 0) add(call, 'HTTP_REDIRECTS', 'Every request must explicitly set redirects to zero.');
    if (params.has('headers') && !stringMap(params.get('headers'), true)) add(call, 'HTTP_HEADERS', 'Headers must be bounded literal strings without routing overrides.');
    if (params.has('tags') && !stringMap(params.get('tags'), false)) add(call, 'HTTP_TAGS', 'Tags must be bounded literal strings.');
    if (params.has('responseType') && !['none', 'text', 'binary'].includes(literal(params.get('responseType')))) add(call, 'HTTP_RESPONSE_TYPE', 'Use a supported literal response type.');
  }
  for (const [node, parent, grandparent, scope] of nodes) {
    if (node.type === 'Literal' && (node.regex || node.bigint)) add(node, 'EXECUTION_LITERAL', 'Regular-expression and bigint literals are unsupported.');
    if (node.type === 'Property' && (node.computed || node.method || node.kind !== 'init' || DANGEROUS.has(node.key?.name ?? literal(node.key)))) add(node, 'EXECUTION_PROPERTY', 'Computed, accessor, prototype and method properties are unsupported.');
    if (node.type === 'MemberExpression' && (node.computed || DANGEROUS.has(node.property.name))) add(node, 'EXECUTION_MEMBER', 'Computed, prototype and indirect-call access is unsupported.');
    if ((node.type === 'AssignmentExpression' && node.left.type !== 'Identifier') || (node.type === 'UpdateExpression' && node.argument.type !== 'Identifier') || (node.type === 'UnaryExpression' && node.operator === 'delete')) add(node, 'EXECUTION_MUTATION', 'Only local variable assignments are supported.');
    if (['AssignmentExpression', 'UpdateExpression'].includes(node.type)) {
      const name = (node.left ?? node.argument).name;
      if (imports.has(name) || GLOBALS.has(name)) add(node, 'EXECUTION_MUTATION', 'Imported and runtime bindings cannot be assigned.');
    }
    if (node.type === 'NewExpression' && !(node.callee.type === 'Identifier' && imports.get(node.callee.name) === 'metric')) add(node, 'EXECUTION_CONSTRUCTOR', 'Only directly imported k6 metric constructors are supported.');
    if (node.type !== 'Identifier') continue;
    if (CAPABILITIES.has(node.name)) add(node, 'EXECUTION_CAPABILITY', 'Dynamic code, environment, logging and runtime capabilities are unsupported.');
    const property = (parent?.type === 'MemberExpression' && parent.property === node && !parent.computed) || (parent?.type === 'Property' && parent.key === node && !parent.computed && !parent.shorthand);
    const importBinding = ['ImportSpecifier', 'ImportDefaultSpecifier'].includes(parent?.type);
    if (property || importBinding || bindings.has(node)) continue;
    const kind = imports.get(node.name);
    if (kind === 'http') {
      if (parent?.type !== 'MemberExpression' || parent.object !== node || parent.computed || grandparent?.type !== 'CallExpression' || grandparent.callee !== parent) add(node, 'HTTP_INDIRECT', 'HTTP capabilities cannot be aliased, passed, shadowed or accessed indirectly.');
      else httpCall(grandparent, parent.property.name);
    } else if (kind) {
      if (!['CallExpression', 'NewExpression'].includes(parent?.type) || parent.callee !== node || (kind === 'metric') !== (parent.type === 'NewExpression')) add(node, 'EXECUTION_IMPORT_USE', 'Use imported helpers and constructors directly.');
    } else if (['Math', 'JSON'].includes(node.name)) {
      if (parent?.type !== 'MemberExpression' || parent.object !== node || parent.computed || !(node.name === 'Math' ? MATH.has(parent.property.name) : ['parse', 'stringify'].includes(parent.property.name))) add(node, 'EXECUTION_GLOBAL', 'Only selected JSON and Math operations are supported.');
    } else if (!declared(node.name, scope) && !GLOBALS.has(node.name)) add(node, 'EXECUTION_GLOBAL', 'Implicit globals, environment access and runtime capabilities are unsupported.');
  }
  if (!hasDefault) add(null, 'DEFAULT_FUNCTION_REQUIRED', 'Export a default iteration function.');
  result.policyAccepted = result.findings.length === 0;
  return result;
}
module.exports = { validateExecution, POLICY };
