import type { Editor, JSONContent } from '@tiptap/core'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'

/**
 * Lossless Rich editing: every top-level Markdown block keeps its exact
 * original source until the user changes that block. Only changed blocks are
 * serialized, so unsupported or unusual syntax elsewhere in the Note can never
 * be normalized away by an unrelated edit.
 */

interface Group {
  start: number
  end: number
  nodes: ProseMirrorNode[]
  count: number
}

interface ParseRecord { raw: string; count: number }

// The private MarkdownManager parse steps of the exactly pinned @tiptap/markdown.
interface ManagerInternals {
  parse(markdown: string): JSONContent
  serialize(content: JSONContent): string
  parseToken?: (token: { raw?: string }, ...rest: unknown[]) => unknown
  createImplicitEmptyParagraphsFromSpace?: (token: { raw?: string }, ...rest: unknown[]) => unknown[]
}

export interface PreservedParse {
  content: JSONContent
  /** Records the loaded blocks; false when lossless preservation is unavailable. */
  apply(editor: Editor): boolean
}

export class SourcePreservation {
  private source = ''
  private groups: Group[] = []
  private identity = new Map<ProseMirrorNode, { group: number; index: number }>()
  // The editor starts as an empty Note, which needs no preserved source.
  private active = true
  private lineEnding = '\n'

  /** Parses through the editor's own Markdown pipeline while recording block boundaries. */
  parse(editor: Editor, markdown: string): PreservedParse {
    const manager = editor.markdown as unknown as ManagerInternals
    const records: ParseRecord[] = []
    const parseToken = manager.parseToken
    const spaceParagraphs = manager.createImplicitEmptyParagraphsFromSpace
    const instrumented = typeof parseToken === 'function' && typeof spaceParagraphs === 'function'
    let depth = 0
    if (instrumented) {
      manager.parseToken = function (token, ...rest) {
        depth += 1
        try {
          const result = parseToken.call(this, token, ...rest)
          if (depth === 1) records.push({ raw: token.raw ?? '', count: result == null ? 0 : Array.isArray(result) ? result.length : 1 })
          return result
        } finally { depth -= 1 }
      }
      manager.createImplicitEmptyParagraphsFromSpace = function (token, ...rest) {
        const result = spaceParagraphs.call(this, token, ...rest)
        if (depth === 0) records.push({ raw: token.raw ?? '', count: result.length })
        return result
      }
    }
    let content: JSONContent
    try {
      content = manager.parse(markdown)
    } finally {
      if (instrumented) {
        delete manager.parseToken
        delete manager.createImplicitEmptyParagraphsFromSpace
      }
    }
    const nodeCount = content.content?.length ?? 0
    return {
      content,
      apply: currentEditor => {
        this.capture(currentEditor, markdown, records, instrumented, nodeCount, content)
        return this.active
      },
    }
  }

  private capture(editor: Editor, markdown: string, records: ParseRecord[], instrumented: boolean, nodeCount: number, content: JSONContent) {
    this.source = markdown
    this.groups = []
    this.identity = new Map()
    this.lineEnding = /\r\n/.test(markdown) && !/(^|[^\r])\n/.test(markdown) ? '\r\n' : '\n'
    this.active = false
    if (!instrumented) return

    const normalized = markdown.replace(/\r\n|\r/g, '\n')
    if (records.map(record => record.raw).join('') !== normalized) return
    if (records.reduce((sum, record) => sum + record.count, 0) !== nodeCount) return

    // Map normalized offsets back to the original text (CRLF and lone CR).
    const original: number[] = []
    for (let index = 0; index < markdown.length; index += 1) {
      original.push(index)
      if (markdown[index] === '\r' && markdown[index + 1] === '\n') index += 1
    }
    original.push(markdown.length)

    let offset = 0
    let pendingStart: number | undefined
    for (const record of records) {
      const start = offset
      offset += record.raw.length
      if (record.count > 0) {
        this.groups.push({ start: original[pendingStart ?? start], end: original[offset], nodes: [], count: record.count })
        pendingStart = undefined
      } else if (record.raw.trim()) {
        // Source without a rich node travels with the next block (or the last one).
        pendingStart ??= start
      }
    }
    if (pendingStart !== undefined) {
      if (!this.groups.length) return
      this.groups[this.groups.length - 1].end = original[offset]
    }

    const doc = editor.state.doc
    if (doc.childCount < nodeCount) return
    let child = 0
    for (const [groupIndex, group] of this.groups.entries()) {
      for (let index = 0; index < group.count; index += 1, child += 1) {
        const node = doc.child(child)
        if (node.type.name !== content.content?.[child]?.type) return
        group.nodes.push(node)
        this.identity.set(node, { group: groupIndex, index })
      }
    }
    this.active = true
  }

  /** Markdown for the current document, reusing original source for every unchanged block. */
  serialize(editor: Editor): string {
    const manager = editor.markdown as unknown as ManagerInternals
    if (!this.active) return editor.getMarkdown()
    const children: ProseMirrorNode[] = []
    editor.state.doc.forEach(node => { children.push(node) })

    type Part = { first: number; last: number } | { nodes: ProseMirrorNode[] }
    const parts: Part[] = []
    const used = new Set<number>()
    let expected = 0
    for (let index = 0; index < children.length;) {
      const node = children[index]
      const known = this.identity.get(node)
      let group = known?.index === 0 ? known.group : -1
      if (!known && expected < this.groups.length && this.groups[expected].nodes[0]?.eq(node)) group = expected
      if (group >= 0 && !used.has(group) && this.intact(group, children, index)) {
        used.add(group)
        const previous = parts.at(-1)
        if (previous && 'last' in previous && previous.last === group - 1) previous.last = group
        else parts.push({ first: group, last: group })
        index += this.groups[group].nodes.length
        expected = group + 1
        continue
      }
      const previous = parts.at(-1)
      if (previous && 'nodes' in previous) previous.nodes.push(node)
      else parts.push({ nodes: [node] })
      index += 1
    }

    const texts: string[] = []
    parts.forEach((part, index) => {
      if ('nodes' in part) {
        const text = manager.serialize({ type: 'doc', content: part.nodes.map(node => node.toJSON()) })
          .replace(/^\n+|\n+$/g, '')
        if (text.trim()) texts.push(text.replace(/\n/g, this.lineEnding))
        return
      }
      const start = index === 0 && part.first === 0 ? 0 : this.groups[part.first].start
      const lastPart = index === parts.length - 1 && part.last === this.groups.length - 1
      const end = lastPart ? this.source.length : this.groups[part.last].end
      const text = this.source.slice(start, end)
      if (text.trim() || lastPart) texts.push(lastPart ? text : text.replace(/(?:\r?\n)+$/, ''))
    })
    if (!texts.length) return ''
    let result = texts.map((text, index) => index === 0 ? text : text.replace(/^(?:\r?\n)+/, ''))
      .reduce((joined, text) => joined.replace(/(?:\r?\n)+$/, '') + this.lineEnding + this.lineEnding + text)
    // Keep the Note's final newline convention when the last block was edited.
    if (/\n$/.test(this.source) && !/\n$/.test(result)) result += this.lineEnding
    return result
  }

  private intact(group: number, children: ProseMirrorNode[], index: number): boolean {
    return this.groups[group].nodes.every((node, offset) => {
      const current = children[index + offset]
      return current !== undefined && (current === node || current.eq(node))
    })
  }
}
