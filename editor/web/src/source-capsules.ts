import { editorLabel } from './editor-labels'
import { Node } from '@tiptap/core'
import { Lexer, Marked, Tokenizer, type marked, type Tokens } from 'marked'

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

let duplicateDefinitions = 0

/** Links around images have no editable rich form; keep their exact source. */
function linkedImage(token: Tokens.Link): Tokens.Generic | undefined {
  const image = token.tokens?.find((child): child is Tokens.Image => child.type === 'image')
  if (!image) return undefined
  return { type: 'sourceLinkedImage', raw: token.raw, text: token.raw, kind: 'Linked image',
    src: image.href, alt: image.text, href: token.href }
}

/** A private Marked instance avoids accumulating global tokenizer registrations. */
export function preservingMarked(): typeof marked {
  const instance = new Marked({
    tokenizer: {
      blockquote(source) {
        const token = Tokenizer.prototype.blockquote.call(this, source)
        if (!token) return false
        const marker = /^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\][ \t]*(?:\n|$)/.exec(token.text)
        if (!marker) return token
        return { ...token, type: 'markdownAlert', alertType: marker[1], tokens: this.lexer.blockTokens(token.text.slice(marker[0].length)) }
      },
      html(source) {
        const token = Tokenizer.prototype.html.call(this, source)
        return token ? { ...token, type: 'sourceHtmlBlock' } : false
      },
      def(source) {
        const token = Tokenizer.prototype.def.call(this, source)
        // Marked drops later duplicate labels from the token stream. Keep their
        // source under a label no reference can match (Rich Mode rejects NUL).
        if (token && this.lexer.tokens.links[token.tag]) {
          duplicateDefinitions += 1
          return { ...token, tag: `\u0000duplicate-${duplicateDefinitions}` }
        }
        return token
      },
      link(source) {
        const token = Tokenizer.prototype.link.call(this, source)
        return token && token.type === 'link' ? linkedImage(token) ?? token : token
      },
      reflink(source, links) {
        const token = Tokenizer.prototype.reflink.call(this, source, links)
        return token && token.type === 'link' ? linkedImage(token) ?? token : token
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

const KINDS = new Set(['HTML', 'Metadata', 'Footnote', 'Math', 'Directive', 'Footnote reference', 'Link reference', 'Linked image'])
function requireString(value: unknown) { if (typeof value !== 'string') throw new RangeError('Expected source text') }
function requireKind(value: unknown) { if (typeof value !== 'string' || !KINDS.has(value)) throw new RangeError('Unknown source kind') }

function capsule(name: string, inline: boolean, tokenName: string) {
  return Node.create({
    name, group: inline ? 'inline' : 'block', inline, atom: true, selectable: true, marks: '',
    addAttributes: () => ({ source: { default: '', validate: requireString }, kind: { default: 'HTML', validate: requireKind } }),
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
        dom.dataset.sourceKind = kind
        dom.dataset.sourceComment = String(/^\s*<!--/.test(source))
        dom.setAttribute('aria-label', `${editorLabel(kind)}. ${editorLabel('sourceHint')}`)
        dom.title = editorLabel('sourceTitle')
        if (kind === 'HTML' && !/^\s*<!--/.test(source)) {
          dom.append(htmlPreview(source))
          if (dom.childNodes.length > 0) { dom.classList.add('source-capsule--preview'); return { dom } }
        }
        const label = document.createElement('span')
        label.className = 'source-capsule-label'
        label.textContent = /^\s*<!--/.test(source) ? editorLabel('Comment') : editorLabel(kind)
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
    // Marked passes the remaining source without its first character, so only a
    // position after a newline is a real line start; `\---` stays a paragraph.
    start: source => {
      const match = /\n {0,3}(?:\[\^[^\]\n]+\]:|\$\$|:{3,}|---[ \t]*$|\+\+\+[ \t]*$)/m.exec(source)
      return match ? match.index + 1 : -1
    },
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

/** Link reference definitions stay visible, deletable and byte-exact. */
export const SourceDefinition = capsule('sourceDefinition', false, 'def').extend({
  parseMarkdown: token => ({ type: 'sourceDefinition', attrs: { source: (token.raw ?? '').replace(/\n+$/, ''), kind: 'Link reference' } }),
})

const PREVIEW_IMAGE = /\.(avif|gif|jpe?g|png|svg|webp)(?:[?#].*)?$/i

/** A linked image previews its picture; the outer destination stays in its source. */
export const SourceLinkedImage = capsule('sourceLinkedImage', true, 'sourceLinkedImage').extend({
  addAttributes: () => ({ source: { default: '', validate: requireString }, kind: { default: 'Linked image', validate: requireKind },
    src: { default: '', validate: requireString }, alt: { default: '', validate: requireString } }),
  parseMarkdown: token => ({ type: 'sourceLinkedImage', attrs: {
    source: token.raw ?? '', kind: 'Linked image', src: String(token.src ?? ''), alt: String(token.alt ?? ''),
  } }),
  addNodeView() {
    return ({ node }) => {
      const dom = document.createElement('span')
      dom.contentEditable = 'false'
      dom.dataset.sourceCapsule = 'sourceLinkedImage'
      dom.dataset.sourceKind = 'Linked image'
      dom.dataset.sourceComment = 'false'
      dom.className = 'source-capsule source-capsule--inline source-capsule--linked-image'
      dom.setAttribute('role', 'group')
      dom.setAttribute('aria-label', `${editorLabel('Linked image')}. ${editorLabel('sourceHint')}`)
      dom.title = editorLabel('sourceTitle')
      const src = String(node.attrs.src)
      // Only an image resource is requested; the outer link is never navigable here.
      if (PREVIEW_IMAGE.test(src) && !/^\s*(?:javascript|data|blob|file|content):/i.test(src)) {
        const image = document.createElement('img')
        image.src = src
        image.alt = String(node.attrs.alt)
        image.className = 'inline-image inline-image--linked'
        image.draggable = false
        dom.classList.add('source-capsule--preview')
        dom.append(image)
      } else {
        const label = document.createElement('span')
        label.className = 'source-capsule-label'
        label.textContent = editorLabel('Linked image')
        const code = document.createElement('code')
        code.textContent = String(node.attrs.source)
        dom.append(label, code)
      }
      return { dom }
    }
  },
})
