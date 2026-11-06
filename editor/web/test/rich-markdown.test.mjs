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

test('GitHub alerts render with a label and editable content and keep their Markdown syntax', async () => {
  for (const type of ['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION']) {
    const source = `# Heading\n\n> [!${type}]\n> Keep **this** information.\n>\n> - First item\n> - Second item\n\nAfter.`
    bridge.load(source)
    const alert = root.querySelector(`[data-markdown-alert="${type}"]`)
    assert.ok(alert, `Expected a styled ${type} alert`)
    assert.equal(alert.querySelector('.markdown-alert-title')?.textContent, type[0] + type.slice(1).toLowerCase())
    assert.equal(alert.querySelector('.markdown-alert-title')?.contentEditable, 'false')
    assert.equal(alert.querySelector('strong')?.textContent, 'this')
    assert.equal(alert.querySelectorAll('li').length, 2)
    assert.equal(bridge.markdown(), source)
    await editElsewhere()
    const edited = bridge.markdown()
    assert.ok(edited.includes(`> [!${type}]`))
    bridge.load(edited)
    assert.equal(root.querySelectorAll(`[data-markdown-alert="${type}"]`).length, 1)
    const text = root.querySelector('[data-markdown-alert] strong')
    root.editor.commands.setTextSelection({ from: root.editor.view.posAtDOM(text, 0), to: root.editor.view.posAtDOM(text, 0) + 4 })
    root.editor.commands.insertContent('updated')
    assert.ok(bridge.markdown().includes('updated'))
    assert.ok(bridge.markdown().includes(`> [!${type}]`))
  }
})

test('a list ending in an empty item stays in Rich Mode and keeps every line', () => {
  const messages = []
  window.KgsNotesEditor = { postMessage: message => messages.push(JSON.parse(message)) }
  for (const source of ['- first\n- second\n-', '- first\n- second\n- ', '- first\n-\n', '-', '- ', '1. first\n2.', '* first\n*', '- [ ] first\n- [ ]', '- [ ] first\n-',
    '- first\n  -', '> - quoted\n> -', '- first\n-\n\nAfter']) {
    messages.length = 0
    bridge.load(source)
    assert.deepEqual(messages.filter(({ type }) => type === 'fallback'), [], `Fallback for ${JSON.stringify(source)}`)
    assert.equal(bridge.markdown(), source)
  }
  bridge.load('- first\n- second\n-')
  assert.equal(root.querySelectorAll('li').length, 3)
  delete window.KgsNotesEditor
})

test('a bare list marker ending a Note shows as an empty bullet without changing the Note', () => {
  const headings = () => root.querySelectorAll('h1, h2, h3').length
  const trailing = ['-', '- ', '-\n', '- \n\n']
  for (const tail of trailing) {
    const paragraph = `First line\nSecond line\n${tail}`
    bridge.load(paragraph)
    assert.equal(headings(), 0, 'A dangling bullet must not turn the paragraph into a heading')
    assert.equal(root.querySelectorAll('li').length, 1)
    assert.equal(bridge.markdown(), paragraph)

    bridge.load(`1. one\n2. two\n${tail}`)
    assert.equal(root.querySelectorAll('ol > li').length, 2)
    assert.equal(root.querySelectorAll('ul > li').length, 1)
    assert.ok(!root.textContent.includes('-'), 'The marker must not appear as text')

    bridge.load(`- [ ] one\n- [x] two\n${tail}`)
    assert.equal(root.querySelectorAll('li').length, 3, 'A checklist gains an empty checkbox')
    assert.ok(!root.querySelector('li:last-child')?.textContent.includes('-'))

    bridge.load(`- one\n- two\n  ${tail}`)
    assert.equal(headings(), 0)

    bridge.load(tail)
    assert.equal(root.querySelectorAll('li').length, 1, 'A Note holding only a marker is an empty bullet')
    assert.equal(bridge.markdown(), tail)
  }
})

test('real headings, separators and code keep their meaning around dash lines', () => {
  bridge.load('Title\n---')
  assert.equal(root.querySelectorAll('h2').length, 1)
  bridge.load('Title\n-\n\nBody')
  assert.equal(root.querySelectorAll('h2').length, 1, 'Only the end of a Note is still being written')
  bridge.load('```\ncode\n-')
  assert.equal(root.querySelector('pre')?.textContent, 'code\n-')
  bridge.load('---')
  assert.equal(root.querySelectorAll('hr').length, 1)
})

test('ordinary quotes and literal or unknown alert markers remain ordinary Markdown', () => {
  bridge.load('> An ordinary quote.\n\n> [!UNKNOWN]\n> Keep the marker.\n\n```md\n> [!WARNING]\n> Example only\n```\n\n`[!TIP]`')
  assert.equal(root.querySelectorAll('[data-markdown-alert]').length, 0)
  assert.equal(root.querySelectorAll('blockquote').length, 2)
  assert.ok(root.querySelector('pre').textContent.includes('[!WARNING]'))
})

test('copying and pasting a rich alert retains its warning type and Markdown marker', () => {
  bridge.load('> [!WARNING]\n> Keep **this** warning.')
  const clipboard = root.editor.getHTML()
  bridge.load('Destination.\n\n')
  root.editor.commands.insertContent(clipboard)
  assert.equal(root.querySelector('[data-markdown-alert]')?.getAttribute('data-markdown-alert'), 'WARNING')
  assert.ok(bridge.markdown().includes('> [!WARNING]'))
  assert.ok(bridge.markdown().includes('**this** warning.'))
})

test('viewport handoff maps repeated text in lists and tables to its original source offset', () => {
  const source = '# Heading\n\n' + '- Same **phrase**.\n'.repeat(30) + '\n| Name | Value |\n| --- | --- |\n| Same | Last value |'
  bridge.load(source)
  const paragraphs = [...root.querySelectorAll('li p')]
  const position = root.editor.view.posAtDOM(paragraphs[23].firstChild, 3)
  const originalCoords = root.editor.view.coordsAtPos
  const originalHit = root.editor.view.posAtCoords
  root.editor.view.coordsAtPos = () => ({ top: 14, bottom: 36, left: 20, right: 20 })
  root.editor.view.posAtCoords = () => ({ pos: position, inside: position })
  try {
    const viewport = bridge.viewport()
    assert.equal(viewport.sourceOffset, source.indexOf('- Same') + 23 * '- Same **phrase**.\n'.length + 5)
    assert.equal(viewport.top, 14)
    assert.equal(bridge.markdown(), source)
  } finally {
    root.editor.view.coordsAtPos = originalCoords
    root.editor.view.posAtCoords = originalHit
  }
})

test('restoring a source viewport aligns the matching rich text and cursor without editing the Note', async () => {
  const source = '# Heading\n\n' + '- Same **phrase**.\n'.repeat(30) + '\nEnd &amp; detail.'
  bridge.load(source)
  const sourceOffset = source.indexOf('- Same') + 23 * '- Same **phrase**.\n'.length + 2
  const expectedPosition = root.editor.view.posAtDOM(root.querySelectorAll('li p')[23].firstChild, 0)
  const originalCoords = root.editor.view.coordsAtPos
  const originalScroll = window.scrollTo
  const previousFocus = window.document.activeElement
  let restoredPosition
  let scrollTop = 0
  Object.defineProperty(window, 'scrollY', { configurable: true, get: () => scrollTop })
  window.scrollTo = (_, y) => { scrollTop = y }
  root.editor.view.coordsAtPos = pos => {
    restoredPosition = pos
    return { top: 680 - scrollTop, bottom: 702 - scrollTop, left: 20, right: 20 }
  }
  try {
    await bridge.restoreViewport({ sourceOffset, top: 16, selectionStart: sourceOffset + 1, selectionEnd: sourceOffset + 3 })
    assert.equal(restoredPosition, expectedPosition)
    assert.equal(scrollTop, 664)
    assert.equal(root.editor.state.selection.from, expectedPosition + 1)
    assert.equal(root.editor.state.selection.to, expectedPosition + 3)
    assert.equal(bridge.markdown(), source)
    assert.ok(window.document.activeElement === previousFocus, 'Restoring a viewport must preserve focus')
  } finally {
    root.editor.view.coordsAtPos = originalCoords
    window.scrollTo = originalScroll
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 })
  }
})

test('encoded text before an identical literal paragraph maps to the correct source occurrence', () => {
  const source = 'First &amp; detail.\n\nFirst & detail.'
  bridge.load(source)
  const originalCoords = root.editor.view.coordsAtPos
  const originalHit = root.editor.view.posAtCoords
  const text = root.querySelector('p').firstChild
  const position = root.editor.view.posAtDOM(text, 8)
  root.editor.view.coordsAtPos = () => ({ top: 16, bottom: 38, left: 20, right: 20 })
  root.editor.view.posAtCoords = () => ({ pos: position, inside: position })
  try { assert.equal(bridge.viewport().sourceOffset, 12) }
  finally { root.editor.view.coordsAtPos = originalCoords; root.editor.view.posAtCoords = originalHit }
})

test('an unchanged viewport returns to its exact fractional scroll offset after a round trip', async () => {
  bridge.load('# Heading\n\n' + 'Paragraph **content**.\n\n'.repeat(50))
  const originalCoords = root.editor.view.coordsAtPos
  const originalHit = root.editor.view.posAtCoords
  const originalScroll = window.scrollTo
  let scrollTop = 0
  Object.defineProperty(window, 'scrollY', { configurable: true, get: () => scrollTop })
  window.scrollTo = (_, y) => { scrollTop = y }
  root.editor.view.coordsAtPos = () => ({ top: 19, bottom: 41, left: 20, right: 20 })
  root.editor.view.posAtCoords = () => ({ pos: 1, inside: 1 })
  try {
    await bridge.restoreViewport({ sourceOffset: 10, top: 16, selectionStart: 0, selectionEnd: 0,
      scrollY: 643.25, width: window.innerWidth, height: window.innerHeight })
    assert.equal(scrollTop, 643.25)
  } finally {
    root.editor.view.coordsAtPos = originalCoords
    root.editor.view.posAtCoords = originalHit
    window.scrollTo = originalScroll
    Object.defineProperty(window, 'scrollY', { configurable: true, value: 0 })
  }
})

test('long Notes with repeated encoded paragraphs keep the correct reading anchor', () => {
  const paragraph = 'Repeated &amp; **encoded &amp; content**.\n\n'
  const source = paragraph.repeat(500)
  bridge.load(source)
  const originalCoords = root.editor.view.coordsAtPos
  const originalHit = root.editor.view.posAtCoords
  const text = root.querySelectorAll('p')[375].querySelector('strong').firstChild
  const position = root.editor.view.posAtDOM(text, 10)
  root.editor.view.coordsAtPos = () => ({ top: 16, bottom: 38, left: 20, right: 20 })
  root.editor.view.posAtCoords = () => ({ pos: position, inside: position })
  try { assert.equal(bridge.viewport().sourceOffset, 375 * paragraph.length + paragraph.indexOf('content')) }
  finally { root.editor.view.coordsAtPos = originalCoords; root.editor.view.posAtCoords = originalHit }
})

test('Windows line endings map back to the original source without normalizing saved text', () => {
  const source = 'First line\r\nSecond line\r\n\r\nFirst line\nSecond line'
  bridge.load(source)
  const originalCoords = root.editor.view.coordsAtPos
  const originalHit = root.editor.view.posAtCoords
  const text = root.querySelector('p').firstChild
  const position = root.editor.view.posAtDOM(text, text.textContent.indexOf('Second'))
  root.editor.view.coordsAtPos = () => ({ top: 16, bottom: 38, left: 20, right: 20 })
  root.editor.view.posAtCoords = () => ({ pos: position, inside: position })
  try {
    assert.equal(bridge.viewport().sourceOffset, source.indexOf('Second'))
    assert.equal(bridge.markdown(), source)
  } finally { root.editor.view.coordsAtPos = originalCoords; root.editor.view.posAtCoords = originalHit }
})

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

test('German presentation labels change without translating or rewriting Markdown', async () => {
  const source = '> [!WARNING]\n> Keep this user text.\n\n<!-- User comment -->\n\n$$x^2$$'
  bridge.load(source)
  try {
    bridge.setLabels({ WARNING: 'Warnung', Comment: 'Kommentar', Math: 'Formel',
      content: 'Formatierter Notizinhalt', empty: 'Schreib etwas…',
      sourceHint: 'Im Quelltextmodus bearbeiten.', sourceTitle: 'Erhaltener Quelltext' }, 'de')
    await new Promise(resolve => setTimeout(resolve, 20))
    assert.equal(window.document.documentElement.lang, 'de')
    assert.equal(root.getAttribute('aria-label'), 'Formatierter Notizinhalt')
    assert.equal(root.querySelector('.markdown-alert-title').textContent, 'Warnung')
    assert.ok(root.textContent.includes('Keep this user text.'))
    assert.ok(root.textContent.includes('Kommentar'))
    assert.equal(bridge.markdown(), source)
    root.editor.commands.insertContentAt(root.editor.state.doc.content.size, { type: 'paragraph', content: [{ type: 'text', text: 'User edit' }] })
    await new Promise(resolve => setTimeout(resolve, 20))
    assert.equal(root.getAttribute('aria-label'), 'Formatierter Notizinhalt')
    assert.ok(bridge.markdown().includes('> [!WARNING]'))
    assert.ok(!bridge.markdown().includes('Warnung'))
    bridge.load(source + '\n\n> [!WARNING]\n> Another user text.')
    assert.equal(root.querySelectorAll('.markdown-alert-title')[1].textContent, 'Warnung')
    assert.ok(bridge.markdown().includes('> [!WARNING]'))
  } finally {
    bridge.setLabels({ WARNING: 'Warning', Comment: 'Comment', Math: 'Math',
      content: 'Rich note content', empty: 'Start writing…',
      sourceHint: 'Edit this fragment in Source Mode.', sourceTitle: 'Preserved source — edit in Source Mode' }, 'en')
  }
})

for (const surface of ['.paper', 'html', 'body', 'main']) test(`a blank ${surface} tap focuses synchronously at the end without changing content`, () => {
  bridge.load('A short paragraph')
  root.editor.view.dom.blur()
  root.editor.commands.setTextSelection(1)
  assert.equal(window.document.activeElement === root, false)
  const originalHit = root.editor.view.posAtCoords
  root.editor.view.posAtCoords = () => null
  const paper = window.document.querySelector(surface)
  const pointer = type => {
    const event = new window.Event(type, { bubbles: true, cancelable: true })
    Object.assign(event, { pointerId: 1, clientX: 150, clientY: 400 })
    paper.dispatchEvent(event)
  }
  try {
    pointer('pointerdown')
    pointer('pointerup')
    assert.equal(window.document.activeElement === root, true)
    assert.equal(root.editor.state.selection.from, root.editor.state.doc.content.size - 1)
    assert.equal(bridge.markdown(), 'A short paragraph')
  } finally {
    root.editor.view.posAtCoords = originalHit
  }
})
