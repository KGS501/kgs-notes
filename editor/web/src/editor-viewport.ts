import type { Editor } from '@tiptap/core'

export type EditorViewport = {
  sourceOffset: number
  top: number
  selectionStart: number
  selectionEnd: number
  scrollY?: number
  width?: number
  height?: number
}

const last = <T>(values: T[]) => values[values.length - 1]

/** A transient source/ProseMirror map. Markdown remains the only content representation. */
export function viewportBridge(editor: Editor, markdown: () => string) {
  let mappedDoc: unknown
  let mappedSource = ''
  let entries: { position: number; offsets: number[] }[] = []
  let cancelLayoutAnchor = () => {}

  function map() {
    const source = markdown()
    if (mappedDoc === editor.state.doc && mappedSource === source) return
    mappedDoc = editor.state.doc
    mappedSource = source
    entries = []
    let cursor = 0
    let decoded: { text: string; offsets: number[]; ends: number[] } | undefined
    const decodedSource = () => {
      if (decoded) return decoded
      const characters: string[] = [], offsets: number[] = [], ends: number[] = []
      const entity = document.createElement('textarea')
      const candidates = /\r\n|\r|\\[!"#$%&'()*+,\-./:;<=>?@[\\\]^_`{|}~]|&(?:#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]+);|[\s\S]/g
      for (const match of source.matchAll(candidates)) {
        const raw = match[0]
        let visible = raw
        if (raw === '\r\n' || raw === '\r') visible = '\n'
        else if (raw.startsWith('\\') && raw.length === 2) visible = raw[1]
        else if (raw.startsWith('&') && raw.endsWith(';')) { entity.innerHTML = raw; visible = entity.value }
        for (const character of visible.split('')) {
          characters.push(character); offsets.push(match.index!); ends.push(match.index! + raw.length)
        }
      }
      return decoded = { text: characters.join(''), offsets, ends }
    }
    editor.state.doc.descendants((node, position) => {
      const text = node.isText ? node.text! : node.isAtom ? String(node.attrs.source ?? node.attrs.src ?? '') : ''
      if (!text) return
      const start = source.indexOf(text, cursor)
      let offsets: number[] = []
      const encodedBeforeMatch = start >= 0 && /[&\\\r]/.test(source.slice(cursor, start)) && node.isText
      if (start >= 0 && !encodedBeforeMatch) {
        offsets = Array.from({ length: node.isText ? text.length + 1 : 2 }, (_, index) => start + (node.isText ? index : index * text.length))
      } else {
        // Decode only once for the entire source, even with thousands of encoded
        // text runs. Keep literal code spellings when they are the earlier match.
        const visible = decodedSource()
        let low = 0, high = visible.offsets.length
        while (low < high) {
          const middle = (low + high) >>> 1
          if (visible.offsets[middle] < cursor) low = middle + 1
          else high = middle
        }
        const found = visible.text.indexOf(text, low)
        if (found >= 0 && (start < 0 || visible.offsets[found] < start)) {
          const end = visible.ends[found + text.length - 1]
          offsets = node.isText ? [...visible.offsets.slice(found, found + text.length), end] : [visible.offsets[found], end]
        } else if (start >= 0) offsets = Array.from({ length: node.isText ? text.length + 1 : 2 }, (_, index) => start + (node.isText ? index : index * text.length))
      }
      if (!offsets.length) offsets = [cursor, cursor]
      entries.push({ position, offsets })
      cursor = last(offsets)!
    })
  }

  function sourceForPosition(position: number) {
    map()
    const entry = entries.find(value => position >= value.position && position < value.position + value.offsets.length)
      ?? entries.find(value => value.position >= position) ?? last(entries)
    return entry ? entry.offsets[Math.max(0, Math.min(position - entry.position, entry.offsets.length - 1))] : 0
  }

  function positionForSource(offset: number) {
    map()
    const entry = entries.find(value => last(value.offsets)! >= offset) ?? last(entries)
    if (!entry) return Math.min(1, editor.state.doc.content.size)
    const index = entry.offsets.findIndex(value => value >= offset)
    return Math.min(editor.state.doc.content.size, entry.position + (index < 0 ? entry.offsets.length - 1 : index))
  }

  function viewport(): EditorViewport {
    const pos = editor.view.posAtCoords({ left: 21, top: 16 })?.pos ?? 1
    const { from, to } = editor.state.selection
    return { sourceOffset: sourceForPosition(pos), top: editor.view.coordsAtPos(pos).top,
      selectionStart: sourceForPosition(from), selectionEnd: sourceForPosition(to),
      scrollY: window.scrollY, width: window.innerWidth, height: window.innerHeight }
  }

  async function restoreViewport(anchor: EditorViewport) {
    cancelLayoutAnchor()
    let position = positionForSource(anchor.sourceOffset)
    let top = anchor.top
    editor.commands.setTextSelection({ from: positionForSource(anchor.selectionStart), to: positionForSource(anchor.selectionEnd) })
    const align = () => window.scrollTo(0, Math.max(0, window.scrollY + editor.view.coordsAtPos(position).top - top))
    // Wait for layout and focus/IME work before revealing the incoming editor.
    await new Promise<void>(resolve => window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
      if (anchor.scrollY != null && anchor.width === window.innerWidth && anchor.height === window.innerHeight) {
        window.scrollTo(0, anchor.scrollY)
        position = editor.view.posAtCoords({ left: 21, top: 16 })?.pos ?? position
        top = editor.view.coordsAtPos(position).top
      } else align()
      resolve()
    })))
    // Late image/font sizing must not push the restored content away. Stop anchoring
    // immediately when the person interacts, or the document is edited.
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(align)
    observer?.observe(document.querySelector('.paper')!)
    const cancel = () => {
      observer?.disconnect()
      for (const name of ['pointerdown', 'wheel', 'keydown']) document.removeEventListener(name, cancel, true)
      editor.off('update', cancel)
    }
    for (const name of ['pointerdown', 'wheel', 'keydown']) document.addEventListener(name, cancel, true)
    editor.on('update', cancel)
    cancelLayoutAnchor = cancel
  }

  editor.on('destroy', () => cancelLayoutAnchor())
  return { viewport, restoreViewport }
}
