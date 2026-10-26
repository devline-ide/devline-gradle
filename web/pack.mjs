import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = new URL('../', import.meta.url)
const panel = resolve(process.argv[2] ?? fileURLToPath(new URL('build/panel', root)))
const output = resolve(process.argv[3] ?? fileURLToPath(new URL('build/extension-web', root)))
const code = (await readFile(join(panel, 'gradle.js'), 'utf8')).replaceAll('</script', '<\\/script')
const css = (await readFile(join(panel, 'gradle.css'), 'utf8')).replaceAll('</style', '<\\/style')
const html = '<meta charset="utf-8"><style>' + css + '</style><div id="app"></div><script>' + code + '</script>'
if (Buffer.byteLength(html) > 1024 * 1024) throw Error('Gradle panel exceeds 1 MiB')
await mkdir(output, { recursive: true })
await writeFile(join(output, 'gradle.html'), html)
