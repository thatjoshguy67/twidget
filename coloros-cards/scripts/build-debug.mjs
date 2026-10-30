import { cpSync, readFileSync, writeFileSync, mkdirSync, mkdtempSync, existsSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const project = mkdtempSync(resolve(tmpdir(), 'twidget-coloros-'));
cpSync(resolve(root, 'src'), resolve(project, 'src'), { recursive: true });
const manifest = readFileSync(resolve(root, 'src/manifest.template.json'), 'utf8').replaceAll('__APPLICATION_ID__', 'com.tjg.twidget.debug');
writeFileSync(resolve(project, 'src/manifest.json'), manifest);
writeFileSync(resolve(project, 'package.json'), JSON.stringify({ name: 'twidget-coloros-cards', version: '0.2.0' }));
const cli = process.env.COLOROS_HAP_CLI || resolve(root, 'node_modules/hap-toolkit/bin/index.js');
const result = spawnSync(process.execPath, [cli, 'build', '--target', 'card', '--disable-script-v8-v65'], { cwd: project, stdio: 'inherit' });
if (result.status !== 0) process.exit(result.status || 1);
// OEM extensions warn in the public compiler. Refuse to package if they were dropped.
for (const [name, component] of [['followers', 'Followers'], ['brief', 'Brief']]) {
  const css = readFileSync(resolve(project, `build/cards/${name}/${component}.css.json`), 'utf8');
  if (!css.includes('"backdropFilter":"host-blur"') || !css.includes('"themeBackgroundColor":"uxCardColorBackground"')) throw Error('Compiler dropped host material properties');
  if (!existsSync(resolve(project, `build/cards/${name}/${component}.template.json`))) throw Error('Missing compiled lite template');
}
const dist = resolve(project, 'dist');
const rpks = readdirSync(dist).filter(name => name.endsWith('.rpk'));
if (rpks.length !== 1) throw Error('Expected one complete RPK');
const output = resolve(root, '../app/src/debug/assets/coloros-cards.rpk');
mkdirSync(dirname(output), { recursive: true });
cpSync(resolve(dist, rpks[0]), output);
console.log(`Packaged debug native cards: ${output}`);
