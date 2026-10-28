// Next's segment export currently flattens POSIX separators but leaves Windows
// separators in relative segment paths. Publish the filenames the router requests.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../out');
let normalized = 0;

function flatten(directory, prefix, relative = '') {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const source = path.join(directory, entry.name);
    const segment = relative ? `${relative}.${entry.name}` : entry.name;
    if (entry.isDirectory()) flatten(source, prefix, segment);
    else if (entry.isFile() && entry.name.endsWith('.txt')) {
      const destination = `${prefix}.${segment}`;
      const content = fs.readFileSync(source);
      if (fs.existsSync(destination)) {
        if (!content.equals(fs.readFileSync(destination))) throw new Error('Conflicting exported segment filenames');
      } else { fs.writeFileSync(destination, content); normalized++; }
    }
  }
}

function visit(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const child = path.join(directory, entry.name);
    if (entry.name.startsWith('__next.')) flatten(child, child);
    else if (entry.name !== '_next') visit(child);
  }
}

visit(root);
console.log(`Static export: normalized ${normalized} segment filenames.`);
