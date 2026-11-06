import { test } from 'node:test'
import assert from 'node:assert/strict'
import { editAndReopen, insertText, renderer, settle, structure, textblocks } from './renderer.mjs'

// Every case runs: edit -> save -> fresh renderer reopen -> second edit -> save
// -> fresh reopen. Assertions compare the freshly parsed document with the one
// that was saved, never only an untouched originalMarkdown.

const headingEdit = text => editor => insertText(editor, 1, text)
/** Edits the block under test itself, so it is re-serialized rather than preserved. */
const bodyEdit = text => editor => {
  const block = textblocks(editor).find(({ node }) => node.type.name !== 'heading')
  insertText(editor, block.start, text)
}

function twoEdits(source, edit, secondEdit = edit) {
  const first = editAndReopen(source, edit('X'))
  assert.equal(first.reopened, first.edited, `First save must reparse identically:\n${first.saved}`)
  const second = editAndReopen(first.saved, secondEdit('Y'))
  assert.equal(second.reopened, second.edited, `Second save must reparse identically:\n${second.saved}`)
  return { first, second }
}

function documentOf(markdown) {
  const view = renderer()
  // Copy out of the JSDOM realm so deep equality compares values only.
  try { view.bridge.load(markdown); return JSON.parse(JSON.stringify(view.editor.getJSON())) } finally { view.close() }
}

function texts(json, type) {
  const found = []
  const walk = node => {
    if (node.type === type) found.push((node.content ?? []).map(child => child.text ?? '').join(''))
    ;(node.content ?? []).forEach(walk)
  }
  walk(json)
  return found
}

function attrs(json, type) {
  const found = []
  const walk = node => { if (node.type === type) found.push(node.attrs); (node.content ?? []).forEach(walk); (node.marks ?? []).forEach(walk) }
  walk(json)
  return found
}

const PRESERVATION_CORPUS = {
  'unused reference definition': '# Heading\n\n[unused]: https://important.example/unused',
  'used and titled definitions': '# Heading\n\n[a][x] and [b] and [c][]\n\n[x]: https://x.example "Title"\n[b]: <https://b.example/a b> \'T\'\n[c]: https://c.example (Paren title)',
  'duplicate definitions': '# Heading\n\n[a]\n\n[a]: https://first.example\n[a]: https://second.example',
  'definitions with capsules': '# Heading\n\nSee [^n] and [ref].\n\n[^n]: Footnote.\n\n<!-- comment -->\n\n[ref]: https://ref.example',
  'angled link destination': '# Heading\n\n[open report](<reports/important report.pdf>)',
  'angled image destination': '# Heading\n\n![pic](<images/my pic.png> "t")',
  'linked image': '# Heading\n\n[![badge](badge.png)](https://target.example/important)',
  'linked image with titles and text': '# Heading\n\n[![badge](badge.png "bt")](https://target.example/important "lt") and [text](https://t.example)',
  'reference linked image': '# Heading\n\n[![badge][img]][lnk]\n\n[img]: badge.png\n[lnk]: https://target.example',
  'four-backtick fence': '# Heading\n\n````md\n```\n[example]: https://important.example/retained-code-value\n```\n````',
  'tilde fence with backticks': '# Heading\n\n~~~text\n````\n~~~~ not a close?\n~~~~',
  'inline code with backticks': '# Heading\n\nUse `` a`b `` and `` `x` `` here.',
  'table pipes and backslashes': '# Heading\n\n| Match | Replacement |\n| --- | --- |\n| a\\|b | keep-me |\n| **c\\|d** | x\\\\ |',
  'setext marker': '# Heading\n\nliteral paragraph\n\\---',
  'literal block prefixes': '# Heading\n\n\\# not heading\n\n\\- not list\n\n1\\. not ordered\n\n\\> not quote\n\n\\*\\*not bold\\*\\*\n\n\\+ plus\n\nliteral\n\\===',
  'inline escapes': '# Heading\n\nText with \\* star and \\_under\\_ and \\[brackets\\] and \\`tick\\` and a\\\\b',
  'unsupported syntax': '# Heading\n\nTerm\n: definition list\n\n*[HTML]: abbreviation\n\n{#custom-id .class}\n\n  Indented lazy\ncontinuation.\n\n* star list\n+ plus list\n\n***\n\n1) paren list\n\nTrailing spaces  \nhard break.\n',
  'Windows line endings': '# Heading\r\n\r\n[ref]: https://ref.example\r\n\r\n````\r\n```\r\n````\r\n',
}

for (const [name, source] of Object.entries(PRESERVATION_CORPUS)) {
  test(`unrelated edits keep ${name} byte-exact through fresh reopen and a second edit`, () => {
    const { second } = twoEdits(source, headingEdit)
    const eol = source.includes('\r\n') ? '\r\n' : '\n'
    assert.equal(second.saved, source.replace('# Heading', '# YXHeading'))
    assert.ok(second.saved.endsWith(source.slice(source.indexOf(eol))))
  })
}

test('reference definitions are visible, preserved capsules and keep resolving links', () => {
  const source = PRESERVATION_CORPUS['used and titled definitions']
  const { second } = twoEdits(source, bodyEdit)
  const json = documentOf(second.saved)
  assert.deepEqual(attrs(json, 'sourceDefinition').map(({ source: raw }) => raw),
    ['[x]: https://x.example "Title"', "[b]: <https://b.example/a b> 'T'", '[c]: https://c.example (Paren title)'])
  assert.deepEqual(attrs(json, 'link').map(({ href, title }) => [href, title]),
    [['https://x.example', 'Title'], ['https://b.example/a b', 'T'], ['https://c.example', 'Paren title']])
  const view = renderer()
  try {
    view.bridge.load(source)
    const capsules = view.root.querySelectorAll('[data-source-capsule="sourceDefinition"]')
    assert.equal(capsules.length, 3)
    assert.equal(capsules[0].contentEditable, 'false')
    assert.ok(capsules[0].textContent.includes('https://x.example'))
  } finally { view.close() }
})

test('unused and duplicate definitions survive edits next to them', () => {
  for (const source of [PRESERVATION_CORPUS['unused reference definition'], PRESERVATION_CORPUS['duplicate definitions']]) {
    const { second } = twoEdits(source, headingEdit)
    for (const line of source.split('\n').filter(line => line.startsWith('['))) assert.ok(second.saved.includes(line), line)
  }
  const { second } = twoEdits('Paragraph\n\n[unused]: https://important.example/unused', bodyEdit)
  assert.equal(second.saved, 'YXParagraph\n\n[unused]: https://important.example/unused')
})

test('edited links and images keep angled destinations and titles', () => {
  const cases = [
    ['Intro [open report](<reports/important report.pdf>) tail', 'link', { href: 'reports/important report.pdf' }],
    ['Intro [p](<a(b> "q\\"t") tail', 'link', { href: 'a(b', title: 'q"t' }],
    ['Intro [p](https://e.example/a_(b)) tail', 'link', { href: 'https://e.example/a_(b)' }],
    ['Intro [p](<a\\>b> "t") tail', 'link', { href: 'a>b', title: 't' }],
  ]
  for (const [source, type, expected] of cases) {
    const { second } = twoEdits(source, bodyEdit)
    const [found] = attrs(documentOf(second.saved), type)
    for (const [key, value] of Object.entries(expected)) assert.equal(found[key], value, `${source} -> ${second.saved}`)
  }
})

test('re-serialized images escape their description and destination', () => {
  const view = renderer()
  try {
    view.bridge.load('Intro\n\n![old](old.png)')
    let imagePosition
    view.editor.state.doc.descendants((node, pos) => { if (node.type.name === 'image') imagePosition = pos })
    view.editor.view.dispatch(view.editor.state.tr.setNodeMarkup(imagePosition, undefined,
      { src: 'images/my pic (1).png', alt: 'a [b] \\ c', title: 'say "hi"' }))
    const saved = view.bridge.markdown()
    const [image] = attrs(documentOf(saved), 'image')
    assert.equal(image.src, 'images/my pic (1).png', saved)
    assert.equal(image.alt, 'a [b] \\ c', saved)
    assert.equal(image.title, 'say "hi"', saved)
  } finally { view.close() }
})

test('linked images keep their outer destination and preview only the image', () => {
  for (const name of ['linked image', 'linked image with titles and text', 'reference linked image']) {
    const source = PRESERVATION_CORPUS[name]
    const outer = source.match(/\]\]?\(?([^)\s\]]*target\.example[^)\s]*)|\[lnk\]/)[0]
    for (const edit of [headingEdit, bodyEdit]) {
      const { second } = twoEdits(source, edit)
      assert.ok(second.saved.includes(outer), `${name}: ${second.saved}`)
    }
  }
  const view = renderer()
  try {
    view.bridge.load(PRESERVATION_CORPUS['linked image with titles and text'])
    const capsule = view.root.querySelector('[data-source-capsule="sourceLinkedImage"]')
    assert.equal(capsule.querySelector('img').getAttribute('src'), 'badge.png')
    assert.equal(capsule.querySelector('img').alt, 'badge')
    assert.equal(capsule.querySelectorAll('a').length, 0, 'The outer link is not navigable inside the editor')
    assert.equal(view.root.querySelector('a').getAttribute('href'), 'https://t.example')
  } finally { view.close() }
  // Editing the paragraph that contains the linked image keeps it exactly.
  const { second } = twoEdits('Before [![b](b.png "t")](https://x.example/target "lt") after', bodyEdit)
  assert.equal(second.saved, 'YXBefore [![b](b.png "t")](https://x.example/target "lt") after')
})

test('edited code blocks choose fences that the content cannot close', () => {
  const cases = [
    ['````md\n```\n[example]: https://important.example/retained-code-value\n```\n````', 'md'],
    ['`````\n````\n```\n`````', null],
    ['~~~text\n````\n~~~', 'text'],
    ['```\nplain\n```', null],
  ]
  for (const [source, language] of cases) {
    const original = documentOf(source)
    const [code] = texts(original, 'codeBlock')
    const { second } = twoEdits(source, bodyEdit)
    const reopened = documentOf(second.saved)
    assert.deepEqual(texts(reopened, 'codeBlock'), [`YX${code}`], second.saved)
    assert.equal(attrs(reopened, 'codeBlock')[0].language, language)
    assert.equal(reopened.content.filter(node => node.type !== 'paragraph' || node.content).length, 1, second.saved)
  }
  // A language containing a backtick must use a tilde fence.
  const view = renderer()
  try {
    view.bridge.load('Intro')
    view.editor.commands.setContent({ type: 'doc', content: [{ type: 'codeBlock', attrs: { language: 'a`b' }, content: [{ type: 'text', text: '```\n~~~' }] }] })
    const saved = view.bridge.markdown()
    const reopened = documentOf(saved)
    assert.deepEqual(texts(reopened, 'codeBlock'), ['```\n~~~'], saved)
    assert.equal(attrs(reopened, 'codeBlock')[0].language, 'a`b')
  } finally { view.close() }
})

test('edited inline code keeps embedded backticks and boundary spaces', () => {
  const { second } = twoEdits('Use `` a`b `` and `` `x` `` and ` `` ` here.', bodyEdit)
  const reopened = documentOf(second.saved)
  const code = []
  const walk = node => { if (node.marks?.some(mark => mark.type === 'code')) code.push(node.text); (node.content ?? []).forEach(walk) }
  walk(reopened)
  assert.deepEqual(code, ['a`b', '`x`', '``'], second.saved)
})

test('edited table cells keep pipes, backslashes and their columns', () => {
  const source = '| Match | Replacement |\n| --- | --- |\n| a\\|b | keep-me |\n| **c\\|d** | x\\\\ |\n| `e\\|f` | last |'
  const cells = json => {
    const rows = []
    const walk = node => {
      if (node.type === 'tableRow') rows.push(node.content.map(cell => JSON.stringify(cell.content)))
      ;(node.content ?? []).forEach(walk)
    }
    walk(json)
    return rows
  }
  const before = documentOf(source)
  const { second } = twoEdits(source, bodyEdit)
  const after = documentOf(second.saved)
  assert.equal(cells(after).length, cells(before).length)
  assert.ok(cells(after).every(row => row.length === 2), second.saved)
  assert.deepEqual(cells(after).slice(1), cells(before).slice(1))
  assert.ok(JSON.stringify(after).includes('keep-me'))
  assert.ok(JSON.stringify(after).includes('a|b'))
  assert.ok(JSON.stringify(after).includes('x\\\\'))
})

test('edited paragraphs keep literal block markers as paragraph text', () => {
  const literals = ['# not heading', '- not list', '+ plus', '* star', '1. not ordered', '2) paren', '> not quote',
    '---', '===', '- - -', '```', '~~~', '| a | b |', '    four spaces', '#hashtag stays', '#']
  for (const literal of literals) {
    // The literal is the second line of a paragraph, after a hard break, and a paragraph of its own.
    const view = renderer()
    let saved
    try {
      view.bridge.load('Intro')
      view.editor.commands.setContent({ type: 'doc', content: [
        { type: 'paragraph', content: [{ type: 'text', text: `first line\n${literal}` }] },
        { type: 'paragraph', content: [{ type: 'text', text: 'before' }, { type: 'hardBreak' }, { type: 'text', text: literal }] },
        { type: 'paragraph', content: [{ type: 'text', text: literal }] },
        { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: `Title ${literal}` }] },
      ] })
      saved = view.bridge.markdown()
    } finally { view.close() }
    const reopened = documentOf(saved)
    const blocks = reopened.content.filter(node => node.type !== 'paragraph' || node.content)
    assert.deepEqual(blocks.map(node => node.type), ['paragraph', 'paragraph', 'paragraph', 'heading'], `${literal}: ${saved}`)
    assert.equal(texts(reopened, 'paragraph')[2], literal.trimStart() === literal ? literal : texts(reopened, 'paragraph')[2], saved)
    assert.ok(JSON.stringify(reopened).includes(JSON.stringify(literal.trim()).slice(1, -1)), `${literal}: ${saved}`)
    assert.ok(texts(reopened, 'heading')[0].endsWith(literal.trim()), `${literal}: ${saved}`)
  }
  const { second } = twoEdits(PRESERVATION_CORPUS['setext marker'].replace('# Heading\n\n', ''), bodyEdit)
  assert.deepEqual(documentOf(second.saved).content.map(node => node.type), ['paragraph'], second.saved)
  assert.ok(texts(documentOf(second.saved), 'paragraph')[0].endsWith('---'))
})

test('unchanged blocks keep source when other blocks are inserted, deleted or moved', () => {
  const source = '# Heading\n\n* star  list\n+ plus\n\n[ref]: <https://ref.example/a b>\n\nTerm\n: definition\n\nLast   paragraph'
  const view = renderer()
  try {
    view.bridge.load(source)
    // Insert a new block after the heading.
    const heading = view.editor.state.doc.child(0)
    view.editor.commands.insertContentAt(heading.nodeSize, { type: 'paragraph', content: [{ type: 'text', text: 'New' }] })
    assert.equal(view.bridge.markdown(), '# Heading\n\nNew\n\n* star  list\n+ plus\n\n[ref]: <https://ref.example/a b>\n\nTerm\n: definition\n\nLast   paragraph')
    // Delete the list.
    const list = view.editor.state.doc.child(2)
    const listStart = heading.nodeSize + view.editor.state.doc.child(1).nodeSize
    view.editor.commands.deleteRange({ from: listStart, to: listStart + list.nodeSize })
    assert.equal(view.bridge.markdown(), '# Heading\n\nNew\n\n+ plus\n\n[ref]: <https://ref.example/a b>\n\nTerm\n: definition\n\nLast   paragraph')
    // Move the definition to the end, as image line dragging does.
    const doc = view.editor.state.doc
    let offset = 0
    for (let index = 0; index < 3; index += 1) offset += doc.child(index).nodeSize
    const definition = doc.child(3)
    view.editor.view.dispatch(view.editor.state.tr.delete(offset, offset + definition.nodeSize)
      .insert(view.editor.state.doc.content.size - definition.nodeSize, definition))
    assert.equal(view.bridge.markdown(), '# Heading\n\nNew\n\n+ plus\n\nTerm\n: definition\n\nLast   paragraph\n\n[ref]: <https://ref.example/a b>')
  } finally { view.close() }
})

test('Undo right after opening a Note keeps its body and emits no change', async () => {
  const source = '# Existing note\n\nKeep this important body.'
  const view = renderer()
  try {
    view.bridge.load(source)
    view.bridge.run('undo')
    await settle()
    view.bridge.run('undo')
    await settle()
    assert.equal(view.bridge.markdown(), source)
    assert.equal(view.root.querySelector('h1')?.textContent, 'Existing note')
    view.bridge.run('redo')
    await settle()
    assert.equal(view.bridge.markdown(), source)
    assert.deepEqual(view.changes(), [], 'Undo/Redo without an edit must not reach autosave')
  } finally { view.close() }
})

test('after the first edit Undo restores the loaded original and stops there', async () => {
  const source = '# Existing note\n\n[ref]: https://ref.example\n\nKeep this important body.'
  const view = renderer()
  try {
    view.bridge.load(source)
    insertText(view.editor, 1, 'Edited ')
    assert.equal(view.changes().at(-1).markdown, source.replace('# Existing', '# Edited Existing'))
    view.bridge.run('undo')
    await settle()
    assert.equal(view.bridge.markdown(), source)
    assert.equal(view.changes().at(-1).markdown, source, 'Autosave receives the exact original source after Undo')
    const count = view.changes().length
    for (let index = 0; index < 3; index += 1) { view.bridge.run('undo'); await settle() }
    assert.equal(view.bridge.markdown(), source)
    assert.equal(view.changes().length, count, 'Undo stops at the loaded Note')
    // Autosave of the last change, then a close and reopen in a new renderer.
    const stored = view.changes().at(-1).markdown
    const reopened = renderer()
    try {
      reopened.bridge.load(stored)
      reopened.bridge.run('undo')
      await settle()
      assert.equal(reopened.bridge.markdown(), source)
      assert.deepEqual(reopened.changes(), [])
    } finally { reopened.close() }
  } finally { view.close() }
})

test('a programmatic reload starts a new history instead of undoing into the previous content', async () => {
  const view = renderer()
  try {
    view.bridge.load('# First\n\nBody one.')
    insertText(view.editor, 1, 'A')
    // Source Mode or sync delivers new text to the same renderer.
    view.bridge.load('# Second\n\nBody two.')
    view.bridge.run('undo')
    await settle()
    assert.equal(view.bridge.markdown(), '# Second\n\nBody two.')
    assert.equal(view.changes().length, 1)
  } finally { view.close() }
})

test('typing still records ordinary undo steps after a load', async () => {
  const view = renderer()
  try {
    view.bridge.load('Text')
    insertText(view.editor, 5, ' one')
    view.editor.view.dispatch(view.editor.state.tr.setMeta('addToHistory', true).insertText(' two', view.editor.state.doc.content.size - 1))
    view.bridge.run('undo')
    await settle()
    view.bridge.run('undo')
    await settle()
    assert.equal(view.bridge.markdown(), 'Text')
    view.bridge.run('redo')
    await settle()
    assert.notEqual(view.bridge.markdown(), 'Text')
  } finally { view.close() }
})

test('links are not opened or navigated from inside the editor', () => {
  const view = renderer()
  try {
    let opened = 0
    view.window.open = () => { opened += 1; return null }
    view.bridge.load('[file](.kgs-notes-attachments/note/0123abcd-0123-4567-89ab-0123456789ab.svg)')
    const anchor = view.root.querySelector('a')
    anchor.dispatchEvent(new view.window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
    assert.equal(opened, 0)
  } finally { view.close() }
})

test('a tap on a link while reading asks the host to open it, without focusing the editor', async () => {
  const view = renderer()
  try {
    view.bridge.load('See [the plan](../Work/Plan%20A.md) and [site](https://example.org/x).')
    const [note, site] = view.root.querySelectorAll('a')
    const tap = (anchor, move = 0) => {
      const options = { bubbles: true, cancelable: true, button: 0, clientX: 10, clientY: 10 }
      const down = new view.window.MouseEvent('pointerdown', options)
      anchor.dispatchEvent(down)
      if (move) anchor.dispatchEvent(new view.window.MouseEvent('pointermove', { ...options, clientY: 10 + move }))
      anchor.dispatchEvent(new view.window.MouseEvent('pointerup', { ...options, clientY: 10 + move }))
      const click = new view.window.MouseEvent('click', options)
      anchor.dispatchEvent(click)
      return { down, click }
    }
    const opened = () => view.messages.filter(message => message.type === 'openLink').map(message => message.href)
    const first = tap(note)
    tap(site)
    assert.deepEqual(opened(), ['../Work/Plan%20A.md', 'https://example.org/x'])
    assert.equal(first.down.defaultPrevented, true, 'the keyboard stays closed')
    assert.equal(first.click.defaultPrevented, true, 'the editor document never navigates')
    // A scroll that starts on a link is not a tap.
    tap(site, 40)
    assert.equal(opened().length, 2)
    // While typing, a tap places the caret in the linked text instead.
    view.editor.commands.focus()
    await settle()
    assert.equal(view.editor.isFocused, true)
    const typing = tap(note)
    assert.equal(opened().length, 2)
    assert.equal(typing.down.defaultPrevented, false)
    assert.equal(typing.click.defaultPrevented, true)
  } finally { view.close() }
})

test('render-only normalization never replaces untouched source', () => {
  const source = '#  Spaced   heading  #\n\n*  item\n\n__strong__ and *em*\n\n<br/>\n\n| a |\n|:-|\n| b |'
  const view = renderer()
  try {
    view.bridge.load(source)
    view.editor.commands.insertContentAt(view.editor.state.doc.content.size, { type: 'paragraph', content: [{ type: 'text', text: 'New' }] })
    assert.equal(view.bridge.markdown(), `${source}\n\nNew`)
    assert.equal(structure(view.editor).includes('New'), true)
  } finally { view.close() }
})

test('extension markers in the middle of a line stay literal paragraph text', () => {
  for (const [source, text] of [['\\---', '---'], ['a:::b', 'a:::b'], ['x[^1]: y', null], ['p $$x$$ q', 'p $$x$$ q'], ['a+++', 'a+++']]) {
    const json = documentOf(source)
    const blocks = json.content.filter(node => node.type !== 'paragraph' || node.content)
    assert.deepEqual(blocks.map(node => node.type), ['paragraph'], source)
    if (text) assert.equal(texts(json, 'paragraph')[0], text, source)
    const { second } = twoEdits(source, bodyEdit)
    assert.deepEqual(documentOf(second.saved).content.filter(node => node.type !== 'paragraph' || node.content).map(node => node.type), ['paragraph'], second.saved)
  }
  // Real line starts still begin preserved blocks after a paragraph.
  const json = documentOf('Text\n:::note\nInside\n:::\n\nMore\n$$\nx\n$$')
  assert.deepEqual(attrs(json, 'sourceBlock').map(({ kind }) => kind), ['Directive', 'Math'])
})

test('the audit round-trip corpus survives heading, body-start and body-end edits through two fresh reopens', async () => {
  const { readFileSync } = await import('node:fs')
  const corpus = JSON.parse(readFileSync(new URL('./fixtures/audit-roundtrip-corpus.json', import.meta.url), 'utf8'))
  const bodyEnd = text => editor => {
    const blocks = textblocks(editor).filter(({ node }) => node.type.name !== 'heading' && node.content.size)
    insertText(editor, (blocks.at(-1) ?? textblocks(editor)[0]).end, text)
  }
  for (const source of corpus) {
    const { second } = twoEdits(source, headingEdit)
    assert.equal(second.saved, source.replace('# Heading', '# YXHeading'))
    twoEdits(source, bodyEdit)
    twoEdits(source, bodyEnd)
  }
})

test('a Note whose block boundaries cannot be recorded falls back to Source Mode with its exact text', () => {
  const view = renderer()
  const source = '# Heading\n\n[ref]: https://ref.example'
  try {
    // Simulate a future @tiptap/markdown without the instrumented parse steps.
    view.editor.markdown.createImplicitEmptyParagraphsFromSpace = undefined
    view.bridge.load(source)
    assert.ok(view.messages.some(message => message.type === 'fallback'))
    assert.equal(view.bridge.markdown(), source)
    assert.ok(!JSON.stringify(view.messages).includes('ref.example'))
    delete view.editor.markdown.createImplicitEmptyParagraphsFromSpace
    view.bridge.load(source)
    insertText(view.editor, 1, 'X')
    assert.equal(view.bridge.markdown(), '# XHeading\n\n[ref]: https://ref.example')
  } finally { view.close() }
})

for (const [name, source] of [
  ['a bullet', '- one\n- two\n- '],
  ['a star bullet', '* one\n* two\n* '],
  ['a number', '1. one\n2. two\n3. '],
  ['a nested bullet', '- one\n  - two\n  - '],
  ['a bullet below other blocks', '# Title\n\nBody\n\n- one\n- '],
  ['a bullet with more spaces', '- one\n-   '],
  ['a bullet then a space line', '- one\n- \n  '],
  ['a paragraph with trailing spaces', 'Body   '],
]) {
  test(`a Note ending in ${name} with no text stays in Rich Mode with its exact source`, () => {
    const view = renderer()
    try {
      view.bridge.load(source)
      assert.deepEqual(view.messages.filter(message => message.type === 'fallback'), [])
      assert.equal(view.bridge.markdown(), source)
    } finally { view.close() }
  })
}

test('editing above a list ending in an empty bullet keeps that bullet exactly', () => {
  const view = renderer()
  try {
    view.bridge.load('# Title\n\n- one\n- ')
    insertText(view.editor, 1, 'X')
    assert.equal(view.bridge.markdown(), '# XTitle\n\n- one\n- ')
  } finally { view.close() }
})

test('editing a list ending in an empty bullet keeps the bullet as a list item', () => {
  const { saved } = editAndReopen('- one\n- ', editor => insertText(editor, 4, 'X'))
  assert.match(saved, /^- oXne\n-/)
  const view = renderer()
  try {
    view.bridge.load(saved)
    assert.deepEqual(view.messages.filter(message => message.type === 'fallback'), [])
    assert.equal(view.editor.getJSON().content[0].content.length, 2)
  } finally { view.close() }
})
