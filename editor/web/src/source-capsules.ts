import { Node } from '@tiptap/core'
import { Lexer, Marked, Tokenizer, type marked } from 'marked'

/** Unsupported syntax stays canonical source, independent of edits around it. */
const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'])
const PREVIEW_TAGS = new Set(['a', 'b', 'blockquote', 'br', 'code', 'dd', 'del', 'details', 'div', 'dl', 'dt', 'em', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'i', 'kbd', 'li', 'mark', 'ol', 'p', 'pre', 's', 'small', 'span', 'strong', 'sub', 'summary', 'sup', 'table', 'tbody', 'td', 'th', 'thead', 'tr', 'u', 'ul'])
const ACTIVE_TAGS = new Set(['script', 'style', 'iframe', 'object', 'embed', 'svg', 'math', 'form', 'input', 'textarea', 'link', 'meta', 'base', 'img', 'video', 'audio'])

function pairedHtmlLength(source: string, opening: string): number {
  const tag = /^<([a-z][a-z0-9-]*)(?=[\s/>])/i.exec(opening)?.[1]?.toLowerCase()
  if (!tag || VOID_TAGS.has(tag) || /\/>$/.test(opening)) return opening.length
  const tags = new RegExp(`<(/?)${tag}(?=[\\s/>])(?:"[^"]*"|'[^']*'|[^'">])*>`, 'gi')
  let depth = 0
  for (const match of source.matchAll(tags)) {
    if (match[1]) depth -= 1
    else if (!/\/>$/.test(match[0])) depth += 1
    if (depth === 0) return match.index! + match[0].length
  }
  return opening.length
}

/** A private Marked instance avoids accumulating global tokenizer registrations. */
export function preservingMarked(): typeof marked {
  const instance = new Marked({
    tokenizer: {
      html(source) {
        const token = Tokenizer.prototype.html.call(this, source)
        return token ? { ...token, type: 'sourceHtmlBlock' } : false
      },
      tag(source) {
        const before = { ...this.lexer.state }
        const token = Tokenizer.prototype.tag.call(this, source)
        if (!token) return false // Let CommonMark autolinks and literal text tokenize normally.
        const length = pairedHtmlLength(source, token.raw)
        if (length > token.raw.length) Object.assign(this.lexer.state, before)
        return { ...token, raw: source.slice(0, length), text: source.slice(0, length), type: 'sourceHtmlInline' }
      },
    },
  })
  // MarkdownManager uses Marked's documented Lexer with the instance's defaults.
  return Object.assign(instance, { Lexer }) as unknown as typeof marked
}

/** Parse inert template contents; only newly created allowlisted elements enter the live DOM. */
function htmlPreview(source: string): DocumentFragment {
  const template = document.createElement('template')
  template.innerHTML = source
  const preview = document.createDocumentFragment()
  function copy(node: globalThis.Node, parent: globalThis.Node) {
    if (node.nodeType === 3) { parent.appendChild(document.createTextNode(node.textContent ?? '')); return }
    if (node.nodeType !== 1) return
    const element = node as HTMLElement
    const tag = element.localName.toLowerCase()
    if (ACTIVE_TAGS.has(tag)) return
    const destination = PREVIEW_TAGS.has(tag) ? document.createElement(tag) : parent
    if (destination !== parent) {
      // Never copy events, style, arbitrary attributes or resource-loading elements.
      if (tag === 'details' && element.hasAttribute('open')) (destination as HTMLElement).setAttribute('open', '')
      if (tag === 'a') {
        const href = element.getAttribute('href') ?? ''
        if (!/[\u0000-\u0020\u007f]/.test(href) && /^(https?:\/\/|mailto:|tel:|#)/i.test(href)) {
          (destination as HTMLElement).setAttribute('href', href)
          ;(destination as HTMLElement).setAttribute('rel', 'noopener noreferrer')
        }
      }
      for (const attr of tag === 'ol' ? ['start'] : tag === 'td' || tag === 'th' ? ['colspan', 'rowspan'] : []) {
        const value = element.getAttribute(attr)
        if (value && /^\d{1,2}$/.test(value) && Number(value) > 0) (destination as HTMLElement).setAttribute(attr, value)
      }
      parent.appendChild(destination)
    }
    element.childNodes.forEach(child => copy(child, destination))
  }
  template.content.childNodes.forEach(node => copy(node, preview))
  return preview
}

function capsule(name: string, inline: boolean, tokenName: string) {
  return Node.create({
    name, group: inline ? 'inline' : 'block', inline, atom: true, selectable: true, marks: '',
    addAttributes: () => ({ source: { default: '' }, kind: { default: 'HTML' } }),
    parseHTML: () => [],
    renderHTML: ({ node }) => [inline ? 'span' : 'div', { 'data-source-capsule': name }, node.attrs.source],
    markdownTokenName: tokenName,
    parseMarkdown: token => ({ type: name, attrs: { source: token.raw ?? '', kind: token.kind ?? 'HTML' } }),
    renderMarkdown: node => node.attrs.source,
    addNodeView() {
      return ({ node }) => {
        const dom = document.createElement(inline ? 'span' : 'div')
        const source = String(node.attrs.source)
        const kind = String(node.attrs.kind)
        dom.contentEditable = 'false'
        dom.dataset.sourceCapsule = name
        dom.className = `source-capsule ${inline ? 'source-capsule--inline' : 'source-capsule--block'}`
        dom.setAttribute('role', 'group')
        dom.setAttribute('aria-label', `${kind}. Edit this fragment in Source Mode.`)
        dom.title = 'Preserved source — edit in Source Mode'
        if (kind === 'HTML' && !/^\s*<!--/.test(source)) {
          dom.append(htmlPreview(source))
          if (dom.childNodes.length > 0) { dom.classList.add('source-capsule--preview'); return { dom } }
        }
        const label = document.createElement('span')
        label.className = 'source-capsule-label'
        label.textContent = /^\s*<!--/.test(source) ? 'Comment' : kind
        dom.append(label)
        if (!/^\s*<!--/.test(source)) {
          const code = document.createElement('code')
          code.textContent = source
          dom.append(code)
        }
        return { dom }
      }
    },
  })
}

export const SourceHtmlBlock = capsule('sourceHtmlBlock', false, 'sourceHtmlBlock')
export const SourceHtmlInline = capsule('sourceHtmlInline', true, 'sourceHtmlInline')

function directive(source: string): string | undefined {
  const opening = /^ {0,3}(:{3,})[^\n]*\n/.exec(source)
  if (!opening) return undefined
  let depth = 1
  let offset = opening[0].length
  let codeFence: { marker: string; length: number } | undefined
  for (const line of source.slice(offset).match(/[^\n]*(?:\n|$)/g) ?? []) {
    offset += line.length
    const fence = /^ {0,3}(`{3,}|~{3,})(.*)/.exec(line)
    if (codeFence) {
      if (fence && fence[1][0] === codeFence.marker && fence[1].length >= codeFence.length && !fence[2].trim()) codeFence = undefined
      continue
    }
    if (fence) { codeFence = { marker: fence[1][0], length: fence[1].length }; continue }
    const marker = /^ {0,3}(:{3,})([^\n]*)/.exec(line)
    if (!marker) continue
    if (marker[2].trim()) depth += 1
    else if (marker[1].length >= opening[1].length) depth -= 1
    if (depth === 0) return source.slice(0, offset).replace(/\n$/, '')
  }
  return source // An unfinished directive must also survive without losing its contents.
}

export const SourceBlock = capsule('sourceBlock', false, 'sourceBlock').extend({
  markdownTokenizer: {
    name: 'sourceBlock', level: 'block',
    start: source => source.search(/^ {0,3}(?:\[\^[^\]\n]+\]:|\$\$|:{3,}|---[ \t]*$|\+\+\+[ \t]*$)/m),
    tokenize(source, tokens) {
      const metadata = tokens.length === 0 && /^(---|\+\+\+)[ \t]*\n[\s\S]*?\n\1[ \t]*(?=\n|$)/.exec(source)
      if (metadata && /^[\w-]+\s*[:=]/m.test(metadata[0])) return { type: 'sourceBlock', raw: metadata[0], kind: 'Metadata' }
      const footnote = /^ {0,3}\[\^[^\]\n]+\]:[^\n]*(?:\n(?:[ \t]*\n)*(?: {4}|\t)[^\n]*)*/.exec(source)
      if (footnote) return { type: 'sourceBlock', raw: footnote[0], kind: 'Footnote' }
      const math = /^ {0,3}\$\$(?:[ \t]*\n[\s\S]*?\n {0,3}\$\$|[^\n]+\$\$)[ \t]*(?=\n|$)/.exec(source)
      if (math) return { type: 'sourceBlock', raw: math[0], kind: 'Math' }
      const container = directive(source)
      if (container) return { type: 'sourceBlock', raw: container, kind: 'Directive' }
      return undefined
    },
  },
})

export const SourceFootnoteReference = capsule('sourceFootnoteReference', true, 'sourceFootnoteReference').extend({
  markdownTokenizer: {
    name: 'sourceFootnoteReference', level: 'inline', start: source => source.indexOf('[^'),
    tokenize(source) {
      const reference = /^\[\^[^\]\n]+\]/.exec(source)
      return reference ? { type: 'sourceFootnoteReference', raw: reference[0], kind: 'Footnote reference' } : undefined
    },
  },
})
