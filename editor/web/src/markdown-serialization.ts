import CodeBlock from '@tiptap/extension-code-block'
import Heading from '@tiptap/extension-heading'
import Link from '@tiptap/extension-link'
import Paragraph from '@tiptap/extension-paragraph'

/**
 * Context-aware Markdown output for edited blocks. Unedited blocks keep their
 * original source (see source-preservation.ts); these renderers make sure an
 * edited block reparses to the same structure and text.
 */

/** Characters that begin a block construct when they start a paragraph line. */
export function escapeLineStarts(markdown: string): string {
  return markdown.split('\n').map(line => {
    const indent = /^[ \t]*/.exec(line)![0]
    // Four columns of indentation would turn the line into indented code.
    if (/^(?: {4}|\t| {0,3}\t)/.test(line)) return `&#32;${line.slice(1)}`
    const rest = line.slice(indent.length)
    let escaped = rest
      .replace(/^(#{1,6})(?=[ \t]|$)/, '\\$1')
      .replace(/^([-+*])(?=[ \t]|$)/, '\\$1')
      .replace(/^(\d{1,9})([.)])(?=[ \t]|$)/, '$1\\$2')
      .replace(/^([=-])(?=[=-]*[ \t]*$)/, '\\$1')
    // A pipe row followed by a delimiter row would become a table.
    if (escaped === rest && /\|/.test(rest) && /^\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/.test(rest)) {
      escaped = rest.replace(/(^|[^\\])\|/g, '$1\\|')
    }
    return indent + escaped
  }).join('\n')
}

/** Trailing `#` runs would be read as an optional closing sequence. */
export function escapeHeadingText(markdown: string): string {
  return markdown.replace(/(^|[ \t])(#+)([ \t]*)$/, '$1\\$2$3')
}

function balancedParentheses(value: string): boolean {
  let depth = 0
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]
    if (character === '\\') { index += 1; continue }
    if (character === '(') depth += 1
    if (character === ')' && --depth < 0) return false
  }
  return depth === 0
}

/** A link or image destination that reparses to exactly `url`. */
export function markdownDestination(url: string): string {
  if (url === '') return '<>'
  if (/[\s<>\u0000-\u001f\u007f]/.test(url) || !balancedParentheses(url.replace(/\\/g, ''))) {
    return `<${url.replace(/[\\<>]/g, '\\$&')}>`
  }
  return url.replace(/\\/g, '\\\\')
}

export function markdownTitle(title: string): string {
  return title ? ` "${title.replace(/[\\"]/g, '\\$&')}"` : ''
}

/** Brackets in image descriptions must not end or open the label early. */
export function markdownLabel(text: string): string {
  return text.replace(/(\\*)([[\]])/g, (_match, slashes: string, bracket: string) =>
    slashes.length % 2 === 0 ? `${slashes}\\${bracket}` : `${slashes}${bracket}`)
}

function longestRun(text: string, marker: string): number {
  let longest = 0
  for (const match of text.matchAll(marker === '`' ? /`+/g : /~+/g)) longest = Math.max(longest, match[0].length)
  return longest
}

/** Fenced code whose delimiter cannot be closed by any line of its own content. */
export function fencedCode(text: string, language: string): string {
  const marker = language.includes('`') ? '~' : '`'
  const fence = marker.repeat(Math.max(3, longestRun(text, marker) + 1))
  return `${fence}${language}\n${text}\n${fence}`
}

export const SafeParagraph = Paragraph.extend({
  renderMarkdown(node, helpers, context) {
    const rendered = this.parent?.(node, helpers, context) ?? ''
    return node.content?.length ? escapeLineStarts(rendered) : rendered
  },
})

/** Attribute validators also reject forged values arriving through clipboard slice context. */
export function validateString(value: unknown) {
  if (value != null && typeof value !== 'string') throw new RangeError('Expected text attribute')
}

export const SafeHeading = Heading.extend({
  addAttributes() {
    const parent = this.parent?.() ?? {}
    return { ...parent, level: { ...parent.level, validate: (value: unknown) => {
      if (!Number.isInteger(value) || (value as number) < 1 || (value as number) > 6) throw new RangeError('Invalid heading level')
    } } }
  },
  renderMarkdown(node, helpers) {
    const level = node.attrs?.level ? parseInt(String(node.attrs.level), 10) : 1
    if (!node.content) return ''
    return `${'#'.repeat(level)} ${escapeHeadingText(helpers.renderChildren(node.content).replace(/\n/g, ' '))}`
  },
})

export const SafeCodeBlock = CodeBlock.extend({
  addAttributes() {
    const parent = this.parent?.() ?? {}
    return { ...parent, language: { ...parent.language, validate: (value: unknown) => {
      validateString(value)
      if (typeof value === 'string' && /[\u0000-\u001f]/.test(value)) throw new RangeError('Invalid code language')
    } } }
  },
  renderMarkdown(node, helpers) {
    const language = String(node.attrs?.language || '')
    return fencedCode(node.content ? helpers.renderChildren(node.content) : '', language)
  },
})

export const SafeLink = Link.extend({
  renderMarkdown(node, helpers) {
    const href = String(node.attrs?.href ?? '')
    const title = String(node.attrs?.title ?? '')
    return `[${helpers.renderChildren(node)}](${markdownDestination(href)}${markdownTitle(title)})`
  },
})

export function imageMarkdown(attrs: Record<string, unknown> | undefined): string {
  const src = String(attrs?.src ?? '')
  const alt = String(attrs?.alt ?? '')
  const title = String(attrs?.title ?? '')
  return `![${markdownLabel(alt)}](${markdownDestination(src)}${markdownTitle(title)})`
}
