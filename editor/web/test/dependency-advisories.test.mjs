import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { assets, renderer, scriptPath } from './renderer.mjs'

// Published advisories: GHSA-cp6q-959q-f8rh (@tiptap/core < 3.30.4),
// GHSA-j95f-988m-3j2f (@tiptap/core < 3.30.5) and GHSA-c8x8-7fp4-3x9w
// (prosemirror-view < 1.42.3). The shipped bundle, not only package.json,
// must contain the fixed code.
const FIXED = { '@tiptap/core': '3.30.5', 'prosemirror-view': '1.42.3' }

const newer = (version, minimum) => {
  const a = version.split('.').map(Number)
  const b = minimum.split('.').map(Number)
  for (let index = 0; index < 3; index += 1) if (a[index] !== b[index]) return a[index] > b[index]
  return true
}

const lock = readFileSync(new URL('../pnpm-lock.yaml', import.meta.url), 'utf8')
const map = JSON.parse(readFileSync(new URL(`${scriptPath}.map`, assets), 'utf8'))

test('the lockfile resolves only patched versions of the advised packages', () => {
  for (const [name, minimum] of Object.entries(FIXED)) {
    const escaped = name.replace(/[/@]/g, match => `\\${match}`)
    const versions = [...lock.matchAll(new RegExp(`^  '?${escaped}@(\\d+\\.\\d+\\.\\d+)`, 'gm'))].map(match => match[1])
    assert.ok(versions.length > 0, name)
    for (const version of versions) assert.ok(newer(version, minimum), `${name}@${version} must be >= ${minimum}`)
  }
})

test('the shipped bundle was built from the patched sources', () => {
  const bundled = name => map.sources
    .map((source, index) => ({ source, content: map.sourcesContent[index] ?? '' }))
    .filter(({ source }) => source.includes(`/node_modules/${name}/`))
  for (const [name, minimum] of Object.entries(FIXED)) {
    const files = bundled(name)
    assert.ok(files.length > 0, `${name} is bundled`)
    for (const { source } of files) {
      const version = /@(\d+\.\d+\.\d+)/.exec(source.slice(source.indexOf(name.replace('/', '+'))))?.[1]
      assert.ok(version && newer(version, minimum), `${source} must be >= ${minimum}`)
    }
  }
  const view = bundled('prosemirror-view').map(({ content }) => content).join('\n')
  // Slice context attributes are validated; a failure ends the wrapping.
  assert.match(view, /try \{\s*content = Fragment\.from\(type\.create\(array\[i \+ 1\], content\)\);\s*\}\s*catch \(e\) \{\s*break;/)
  const model = bundled('prosemirror-model').map(({ content }) => content).join('\n')
  assert.match(model, /else if \(attr\.validate\) \{\s*attr\.validate\(given\);/)
  const core = bundled('@tiptap/core').map(({ content }) => content).join('\n')
  assert.match(core, /if \(key === "__proto__"\) \{\s*Object\.defineProperty\(mergedAttributes, key/)
  // The minified asset itself carries the context validation.
  const shipped = readFileSync(new URL(scriptPath, assets), 'utf8')
  assert.match(shipped, /try\{[a-z]+=[a-z]+\.from\([a-z]+\.create\([a-z]+\[[a-z]+\+1\],[a-z]+\)\)\}catch\{break\}/)
})

const pasted = (context, open = 1) =>
  `<p data-pm-slice="${open} ${open} ${JSON.stringify(context).replace(/"/g, '&quot;')}">Pasted <strong>rich</strong> text</p>`

function paste(html) {
  const view = renderer()
  try {
    view.bridge.load('')
    view.editor.commands.setTextSelection(1)
    view.editor.view.pasteHTML(html, new view.window.Event('paste'))
    return { json: JSON.parse(JSON.stringify(view.editor.getJSON())), markdown: view.bridge.markdown(), html: view.root.innerHTML }
  } finally { view.close() }
}

test('clipboard slice context with invalid attributes is rejected while rich paste keeps working', () => {
  // Ordinary rich paste and a valid context still apply.
  const plain = paste('<p>Pasted <strong>rich</strong> text</p>')
  assert.equal(plain.markdown, 'Pasted **rich** text')
  const quoted = paste(pasted(['blockquote', null]))
  assert.equal(quoted.json.content[0].type, 'blockquote')
  assert.equal(quoted.markdown, '> Pasted **rich** text')
  const alert = paste(pasted(['markdownAlert', { alertType: 'WARNING' }]))
  assert.equal(alert.json.content[0].attrs.alertType, 'WARNING')

  // Inert forged values: each must be refused as wrapping context.
  const forged = [
    ['markdownAlert', { alertType: '"><img src=x onerror=alert(1)>' }],
    ['heading', { level: '1 onclick=alert(1)' }],
    ['heading', { level: 9 }],
    ['codeBlock', { language: 'x\n```' }],
    ['sourceHtmlBlock', { source: { toString: null }, kind: 'HTML' }],
    ['sourceHtmlBlock', { source: '<b>x</b>', kind: 'Unknown kind' }],
  ]
  for (const context of forged) {
    const result = paste(pasted(context))
    assert.deepEqual(result.json.content.filter(node => node.content).map(node => node.type), ['paragraph'], JSON.stringify(context))
    assert.equal(result.markdown, 'Pasted **rich** text', JSON.stringify(context))
    assert.ok(!/onerror|onclick/.test(result.html))
  }
})
