const fs = require('node:fs');
const path = require('node:path');
const { format } = require('sql-formatter');

const resources = path.join(__dirname, '../backend/src/main/resources');
const check = process.argv.includes('--check');
let needsFormatting = false;

for (const name of fs.readdirSync(resources).filter((name) => name.endsWith('.sql'))) {
  const file = path.join(resources, name);
  const source = fs.readFileSync(file, 'utf8');
  const formatted =
    format(source, {
      language: 'sql',
      tabWidth: 4,
      keywordCase: 'upper',
      linesBetweenQueries: 1,
    }).trimEnd() + '\n';

  if (source !== formatted) {
    if (check) {
      process.stderr.write(`SQL formatting required: ${name}\n`);
      needsFormatting = true;
    } else {
      fs.writeFileSync(file, formatted);
      process.stdout.write(`Formatted SQL: ${name}\n`);
    }
  }
}

if (needsFormatting) process.exitCode = 1;
