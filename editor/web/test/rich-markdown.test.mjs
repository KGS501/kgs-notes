import { after, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'

// Run the actual bundled renderer and Android bridge, not a second parser.
const html = readFileSync(new URL('../../src/main/assets/kgs-editor/index.html', import.meta.url), 'utf8')
const script = html.match(/<script[^>]+src="([^"]+)"/)[1]
const dom = new JSDOM(html.replace(/<script[^>]*>.*?<\/script>/gs, ''), {
  url: 'https://appassets.androidplatform.net/assets/kgs-editor/index.html',
  runScripts: 'outside-only', pretendToBeVisual: true,
})
const { window } = dom
window.Range.prototype.getClientRects = () => []
window.Range.prototype.getBoundingClientRect = () => ({ x: 0, y: 0, top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0 })
window.eval(readFileSync(new URL(`../../src/main/assets/kgs-editor/${script}`, import.meta.url), 'utf8'))
const bridge = window.kgsEditor
const root = window.document.querySelector('.rich-editor')
after(() => { root.editor.destroy(); window.close() })

async function editElsewhere() {
  root.editor.commands.setTextSelection(1)
  bridge.run('heading')
  await new Promise(resolve => setTimeout(resolve, 60))
}

test('GFM tables, task lists and angle autolinks render and survive editing', async () => {
  const source = '# Reference\n\n- [x] Ready\n\n| Link | Label |\n| --- | --- |\n| <https://example.org/reference> | **Reference** |\n\n<reader@example.org>\n'
  bridge.load(source)
  assert.equal(root.querySelectorAll('table').length, 1)
  assert.equal(root.querySelector('input').checked, true)
  assert.equal(root.querySelector('a').getAttribute('href'), 'https://example.org/reference')
  assert.equal(bridge.markdown(), source)
  await editElsewhere()
  assert.ok(bridge.markdown().includes('https://example.org/reference'))
  assert.ok(bridge.markdown().includes('reader@example.org'))
  assert.equal(root.querySelectorAll('table').length, 1)
})

test('HTML is rendered safely and retained exactly after unrelated editing', async () => {
  const fragment = '<details open><summary>Details</summary><p><strong>Important</strong><br>Second line</p></details>'
  bridge.load(`# Heading\n\n${fragment}\n\nNormal **paragraph**.`)
  assert.equal(root.querySelector('details summary')?.textContent, 'Details')
  assert.equal(root.querySelector('details strong')?.textContent, 'Important')
  assert.equal(root.querySelector('[data-source-capsule]')?.contentEditable, 'false')
  await editElsewhere()
  assert.ok(bridge.markdown().includes(fragment))
  assert.equal(root.querySelector('p strong')?.textContent, 'Important')
})

test('inline HTML and table breaks retain source when the heading changes', async () => {
  const inline = '<sup>2</sup>'
  const row = '| First<br />Second | <em>Value</em> |'
  bridge.load(`# Heading\n\nArea${inline}.\n\n| Item | Label |\n| --- | --- |\n${row}`)
  assert.equal(root.querySelector('sup')?.textContent, '2')
  assert.equal(root.querySelectorAll('td br:not(.ProseMirror-trailingBreak)').length, 1)
  await editElsewhere()
  assert.ok(bridge.markdown().includes(inline))
  assert.ok(bridge.markdown().includes(row))
})

test('comments, front matter, footnotes, math and directives remain intact within Rich Mode', async () => {
  const fragments = [
    '<!-- keep this exact comment -->',
    '[^one]: A **footnote**.\n    Continued line.',
    '$$\nx^{2} + y^{2} = 1\n$$',
    ':::warning\nKeep this **directive**.\n:::',
  ]
  const frontMatter = '---\ntitle: Sample\ncustom: true\n---'
  bridge.load(`${frontMatter}\n\n# Heading\n\nNormal **text** with reference[^one].\n\n${fragments.join('\n\n')}`)
  assert.equal(root.querySelector('strong')?.textContent, 'text')
  // Edit the actual heading, leaving the source fragments independent.
  const heading = root.querySelector('h1')
  root.editor.commands.setTextSelection(root.editor.view.posAtDOM(heading, 0))
  bridge.run('heading')
  await new Promise(resolve => setTimeout(resolve, 60))
  for (const fragment of [frontMatter, ...fragments, '[^one]']) assert.ok(bridge.markdown().includes(fragment))
})

test('unsafe HTML remains inert and is preserved rather than executed or dropped', async () => {
  const fragment = '<div onclick="window.injected=true"><script>window.injected=true</script><iframe src="https://example.org"></iframe><img src=x onerror="window.injected=true">Safe text</div>'
  bridge.load(`# Heading\n\n${fragment}`)
  assert.equal(window.injected, undefined)
  assert.equal(root.querySelectorAll('script, iframe, img, [onclick], [onerror]').length, 0)
  await editElsewhere()
  assert.ok(bridge.markdown().includes(fragment))
})

test('code examples display literally without HTML capsules', async () => {
  const source = '# Examples\n\n`<section>inline</section>`\n\n```html\n<!-- comment -->\n<div>example</div>\n```\n\n~~~mermaid\ngraph TD; A-->B\n~~~'
  bridge.load(source)
  assert.equal(root.querySelectorAll('[data-source-capsule]').length, 0)
  assert.equal(root.querySelectorAll('pre').length, 2)
  await editElsewhere()
  assert.ok(bridge.markdown().includes('<div>example</div>'))
  assert.ok(bridge.markdown().includes('graph TD; A-->B'))
})

test('nested tags, quoted attributes, nested directives and indented code retain their source', async () => {
  const fragments = [
    '<span title="a > b"><span>Nested</span> tail</span>',
    '<unknown-element data-original="yes">Original text</unknown-element>',
    ':::outer\n:::inner\nText\n:::\n```text\n:::not-a-container\n```\n:::',
    '[^multi]: First line.\n\n    Second line.\n    Third line.',
  ]
  bridge.load(`# Heading\n\n    <div>Code example</div>\n\n${fragments.join('\n\n')}`)
  await editElsewhere()
  for (const fragment of fragments) assert.ok(bridge.markdown().includes(fragment))
  assert.equal(root.querySelector('pre code')?.textContent, '<div>Code example</div>')
})

test('HTML preview strips dangerous URLs, events, styles, SVG and resource elements', async () => {
  const fragment = '<p style="color:red" onmouseover="window.injected=true"><a href="java&#x73;cript:alert(1)">Unsafe</a><a href="https://example.org">Safe</a><svg onload="window.injected=true"><script>window.injected=true</script></svg><link href="https://example.org/style.css"><object data="https://example.org">Embed</object></p>'
  bridge.load(`# Heading\n\n${fragment}`)
  assert.equal(root.querySelectorAll('[style], [onmouseover], [onload], script, svg, link, object').length, 0)
  const anchors = root.querySelectorAll('a')
  assert.equal(anchors[0].hasAttribute('href'), false)
  assert.equal(anchors[1].getAttribute('href'), 'https://example.org')
  await editElsewhere()
  assert.ok(bridge.markdown().includes(fragment))
  assert.equal(window.injected, undefined)
})

test('renderer failure keeps canonical source and reports a safe fallback without leaking content', () => {
  const source = '# Recoverable source\n\nKeep **all** text.'
  const messages = []
  window.KgsNotesEditor = { postMessage: message => messages.push(JSON.parse(message)) }
  const parse = root.editor.markdown.parse
  // A controlled failure at the third-party renderer boundary.
  root.editor.markdown.parse = () => { throw new Error('Private parser detail') }
  try { bridge.load(source) } finally { root.editor.markdown.parse = parse }
  assert.equal(bridge.markdown(), source)
  const fallback = messages.find(message => message.type === 'fallback')
  assert.ok(fallback)
  assert.equal(JSON.stringify(fallback).includes('Private parser detail'), false)
  assert.equal(JSON.stringify(fallback).includes(source), false)
  bridge.load(source)
  assert.equal(root.querySelector('h1')?.textContent, 'Recoverable source')
  window.KgsNotesEditor = undefined
})

// Optional private fixture stays outside the repository and is never printed.
if (process.env.KGS_NOTES_MARKDOWN_FIXTURE) test('supplied Markdown renders and retains its tables and links after editing and reopening', async () => {
  const bytes = readFileSync(process.env.KGS_NOTES_MARKDOWN_FIXTURE)
  const source = bytes.toString('utf8')
  bridge.load(source)
  assert.ok(bridge.markdown() === source, 'Opening must retain original source')
  assert.equal(root.querySelectorAll('[data-source-capsule]').length, 0)
  const tables = () => JSON.stringify([...root.querySelectorAll('table')].map(table => table.textContent))
  const links = () => JSON.stringify([...root.querySelectorAll('a')].map(anchor => anchor.getAttribute('href')))
  const before = { tables: tables(), links: links() }
  assert.ok(root.querySelectorAll('table').length > 0)
  assert.ok(root.querySelectorAll('a').length > 0)
  await editElsewhere()
  bridge.load(bridge.markdown())
  assert.ok(tables() === before.tables, 'Table contents must survive editing and reopening')
  assert.ok(links() === before.links, 'Link targets must survive editing and reopening')
  assert.ok(readFileSync(process.env.KGS_NOTES_MARKDOWN_FIXTURE).equals(bytes), 'Original fixture must remain untouched')
})
