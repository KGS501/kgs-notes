import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'

export const assets = new URL('../../src/main/assets/kgs-editor/', import.meta.url)
const html = readFileSync(new URL('index.html', assets), 'utf8')
export const scriptPath = html.match(/<script[^>]+src="\.\/([^"]+)"/)[1]
const script = readFileSync(new URL(scriptPath, assets), 'utf8')

/** A fresh instance of the actual shipped bundle, as a newly created WebView renderer would run it. */
export function renderer() {
  const dom = new JSDOM(html.replace(/<script[^>]*>.*?<\/script>/gs, ''), {
    url: 'https://appassets.androidplatform.net/assets/kgs-editor/index.html',
    runScripts: 'outside-only', pretendToBeVisual: true,
  })
  const { window } = dom
  const messages = []
  window.KgsNotesEditor = { postMessage: message => messages.push(JSON.parse(message)) }
  window.Range.prototype.getClientRects = () => []
  window.Range.prototype.getBoundingClientRect = () => ({ x: 0, y: 0, top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0 })
  window.eval(script)
  const root = window.document.querySelector('.rich-editor')
  return {
    window, messages, root, bridge: window.kgsEditor, editor: root.editor,
    changes: () => messages.filter(message => message.type === 'changed'),
    close() { root.editor.destroy(); window.close() },
  }
}

export const settle = (ms = 40) => new Promise(resolve => setTimeout(resolve, ms))

/** Positions of every textblock, in document order. */
export function textblocks(editor) {
  const blocks = []
  editor.state.doc.descendants((node, pos) => {
    if (node.isTextblock) blocks.push({ node, start: pos + 1, end: pos + node.nodeSize - 1 })
  })
  return blocks
}

export function insertText(editor, position, text) {
  editor.commands.setTextSelection(position)
  editor.commands.insertContent(text)
}

/** Canonical comparison of the parsed rich document: node types, attributes, marks and text. */
export const structure = editor => JSON.stringify(editor.getJSON())

/**
 * Load in a fresh renderer, apply an edit, save, then reopen the saved text in
 * another fresh renderer. Returns the saved Markdown and asserts nothing.
 */
export function editAndReopen(source, edit) {
  const first = renderer()
  try {
    first.bridge.load(source)
    edit(first.editor)
    const saved = first.bridge.markdown()
    const edited = structure(first.editor)
    const second = renderer()
    try {
      second.bridge.load(saved)
      return { saved, edited, reopened: structure(second.editor), changes: first.changes() }
    } finally { second.close() }
  } finally { first.close() }
}
