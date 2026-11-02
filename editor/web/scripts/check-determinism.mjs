// Builds the shipped editor assets twice and fails unless both builds are
// byte-identical (source maps embed paths relative to the real output
// directory, so the real directory is used rather than a temporary one).
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

const output = fileURLToPath(new URL('../../src/main/assets/kgs-editor', import.meta.url))

function digest(directory) {
  const files = {}
  const walk = path => readdirSync(path, { withFileTypes: true }).forEach(entry => {
    const full = join(path, entry.name)
    if (entry.isDirectory()) walk(full)
    else files[full.slice(output.length + 1)] = createHash('sha256').update(readFileSync(full)).digest('hex')
  })
  walk(directory)
  return files
}

await build({ logLevel: 'warn' })
const first = digest(output)
await build({ logLevel: 'warn' })
const second = digest(output)
const names = [...new Set([...Object.keys(first), ...Object.keys(second)])].sort()
const different = names.filter(name => first[name] !== second[name])
for (const name of names) console.log(`${second[name] ?? 'missing'}  ${name}`)
if (different.length) {
  console.error(`Non-deterministic editor build: ${different.join(', ')}`)
  process.exit(1)
}
console.log('Editor build is deterministic.')
