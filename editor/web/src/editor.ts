import { Editor } from '@tiptap/core'
import Image from '@tiptap/extension-image'
import { TableKit } from '@tiptap/extension-table'
import TaskItem from '@tiptap/extension-task-item'
import TaskList from '@tiptap/extension-task-list'
import { Markdown } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import { MarkdownAlert } from './markdown-alert'
import { viewportBridge, type EditorViewport } from './editor-viewport'
import { preservingMarked, SourceBlock, SourceDefinition, SourceFootnoteReference, SourceHtmlBlock, SourceHtmlInline, SourceLinkedImage } from './source-capsules'
import { imageMarkdown, validateString, SafeCodeBlock, SafeHeading, SafeLink, SafeParagraph } from './markdown-serialization'
import { SourcePreservation } from './source-preservation'
import { EditorState } from '@tiptap/pm/state'
import './style.css'
import { editorLabel, setLabels as applyLabels } from './editor-labels'

declare global {
  interface Window {
    KgsNotesEditor?: { postMessage(message: string): void }
    kgsEditor: {
      load(markdown: string): void
      markdown(): string
      setDarkMode(enabled: boolean): void
      setLabels(labels: Record<string, string>, language: string): void
      run(command: string): void
      focus(): void
      viewport(): EditorViewport
      restoreViewport(viewport: EditorViewport): Promise<void>
    }
  }
}

const IMAGE_EXTENSIONS = /\.(avif|gif|jpe?g|png|svg|webp)(?:[?#].*)?$/i

const attachmentImage = Image.extend({
  addAttributes() {
    const parent = this.parent?.() ?? {}
    return Object.fromEntries(Object.entries(parent).map(([name, attribute]) =>
      ['src', 'alt', 'title'].includes(name) ? [name, { ...attribute, validate: validateString }] : [name, attribute]))
  },
  renderMarkdown: node => imageMarkdown(node.attrs),
  addNodeView() {
    return ({ node, editor: currentEditor }) => {
      const source = String(node.attrs.src ?? '')
      if (IMAGE_EXTENSIONS.test(source)) {
        const image = document.createElement('img')
        image.src = source
        image.alt = String(node.attrs.alt ?? '')
        image.className = 'inline-image'
        image.draggable = false

        let holdTimer: number | undefined
        let dragging = false
        let pointerId: number | undefined
        let startX = 0
        let startY = 0
        let targetBlock: Element | null = null
        let targetAfter = false

        const editorRoot = () => image.closest('.rich-editor')
        const sourceBlock = () => image.closest('.rich-editor > *')

        const clearTarget = () => {
          targetBlock?.classList.remove('image-drop-target-before', 'image-drop-target-after')
          targetBlock = null
        }

        const finish = () => {
          if (holdTimer !== undefined) window.clearTimeout(holdTimer)
          holdTimer = undefined
          clearTarget()
          image.classList.remove('inline-image--dragging')
          dragging = false
          pointerId = undefined
        }

        const updateTarget = (event: PointerEvent) => {
          const root = editorRoot()
          const sourceLine = sourceBlock()
          const pointed = document.elementFromPoint(event.clientX, event.clientY)
            ?.closest('.rich-editor > *')
          if (!root || !sourceLine || !pointed || pointed === sourceLine || pointed.parentElement !== root) {
            clearTarget()
            return
          }
          clearTarget()
          targetBlock = pointed
          const rect = pointed.getBoundingClientRect()
          targetAfter = event.clientY >= rect.top + rect.height / 2
          pointed.classList.add(targetAfter ? 'image-drop-target-after' : 'image-drop-target-before')
        }

        const moveLine = () => {
          const sourceLine = sourceBlock()
          const destination = targetBlock
          if (!sourceLine || !destination) return
          const view = currentEditor.view
          const sourcePosition = view.posAtDOM(sourceLine, 0)
          const destinationPosition = view.posAtDOM(destination, 0)
          const sourceNode = currentEditor.state.doc.nodeAt(sourcePosition)
          const destinationNode = currentEditor.state.doc.nodeAt(destinationPosition)
          if (!sourceNode || !destinationNode) return

          let insertionPosition = destinationPosition + (targetAfter ? destinationNode.nodeSize : 0)
          if (insertionPosition > sourcePosition) insertionPosition -= sourceNode.nodeSize
          if (insertionPosition === sourcePosition) return

          currentEditor.view.dispatch(
            currentEditor.state.tr
              .delete(sourcePosition, sourcePosition + sourceNode.nodeSize)
              .insert(insertionPosition, sourceNode)
              .scrollIntoView(),
          )
        }

        const onPointerDown = (event: PointerEvent) => {
          if (!event.isPrimary || event.button !== 0) return
          pointerId = event.pointerId
          startX = event.clientX
          startY = event.clientY
          holdTimer = window.setTimeout(() => {
            dragging = true
            image.classList.add('inline-image--dragging')
          }, 420)
        }

        const onPointerMove = (event: PointerEvent) => {
          if (event.pointerId !== pointerId) return
          if (!dragging) {
            if (Math.hypot(event.clientX - startX, event.clientY - startY) > 10) finish()
            return
          }
          event.preventDefault()
          updateTarget(event)
        }

        const onPointerUp = (event: PointerEvent) => {
          if (event.pointerId !== pointerId) return
          if (dragging) {
            event.preventDefault()
            moveLine()
          }
          finish()
        }

        const onContextMenu = (event: Event) => event.preventDefault()
        image.addEventListener('pointerdown', onPointerDown)
        image.addEventListener('contextmenu', onContextMenu)
        document.addEventListener('pointermove', onPointerMove)
        document.addEventListener('pointerup', onPointerUp)
        document.addEventListener('pointercancel', onPointerUp)

        return {
          dom: image,
          destroy() {
            finish()
            image.removeEventListener('pointerdown', onPointerDown)
            image.removeEventListener('contextmenu', onContextMenu)
            document.removeEventListener('pointermove', onPointerMove)
            document.removeEventListener('pointerup', onPointerUp)
            document.removeEventListener('pointercancel', onPointerUp)
          },
        }
      }

      const card = document.createElement('div')
      card.className = 'attachment-card'
      card.setAttribute('role', 'group')
      card.setAttribute('aria-label', `Attachment ${String(node.attrs.alt ?? 'File')}`)
      card.contentEditable = 'false'
      card.innerHTML = '<span class="attachment-icon">↓</span><span><strong></strong><small>Attachment</small></span>'
      const label = card.querySelector('strong')
      if (label) label.textContent = String(node.attrs.alt ?? 'Attachment')
      return { dom: card }
    }
  },
})

const preservation = new SourcePreservation()
let originalMarkdown = ''
let dirty = false
let suppressUpdates = false
let loadedSuccessfully = true

const editor = new Editor({
  element: document.querySelector('#editor') as HTMLElement,
  extensions: [
    StarterKit.configure({ paragraph: false, heading: false, codeBlock: false, link: false }),
    SafeParagraph,
    SafeHeading,
    SafeCodeBlock,
    // Links never navigate the editor document; the native client blocks every other page.
    SafeLink.configure({ openOnClick: false }),
    TableKit.configure({ table: { resizable: false } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    attachmentImage,
    MarkdownAlert,
    SourceHtmlBlock,
    SourceHtmlInline,
    SourceBlock,
    SourceFootnoteReference,
    SourceDefinition,
    SourceLinkedImage,
    Markdown.configure({
      marked: preservingMarked(),
      markedOptions: { gfm: true, breaks: false, pedantic: false },
      indentation: { style: 'space', size: 2 },
    }),
  ],
  content: '',
  contentType: 'markdown',
  editorProps: {
    attributes: {
      class: 'rich-editor',
      'aria-label': editorLabel('content'),
      spellcheck: 'true',
    },
  },
  onUpdate: () => {
    if (suppressUpdates) return
    dirty = true
    post({ type: 'changed', markdown: preservation.serialize(editor) })
  },
  onTransaction: ({ editor: currentEditor }) => {
    if (currentEditor.isFocused) postActiveFormatting(currentEditor)
  },
})

// Android blurs contenteditable when a blank page margin receives the tap.
// Preserve focus on pointer down, then move the caret only for a real tap;
// vertical drags still belong to the browser's normal scrolling gesture.
const paper = document.querySelector('.paper') as HTMLElement
const main = document.querySelector('main')
let blankTap: { id: number; x: number; y: number } | null = null
// WebView can hit-test unused viewport space to HTML rather than .paper,
// especially while its native height changes. Cover the whole document surface.
document.addEventListener('pointerdown', event => {
  if (!editor.isEditable) return
  const target = event.target as HTMLElement
  const paragraph = target.closest('p')
  const emptyParagraph = paragraph?.textContent === '' && !paragraph.querySelector('img, input, video')
  const background = target === document.documentElement || target === document.body || target === main || target === paper
  if (!background && target !== editor.view.dom && target !== editor.options.element && !emptyParagraph) return
  blankTap = { id: event.pointerId, x: event.clientX, y: event.clientY }
  event.preventDefault()
})
document.addEventListener('pointerup', event => {
  const tap = blankTap
  blankTap = null
  if (!tap || tap.id !== event.pointerId || Math.hypot(event.clientX - tap.x, event.clientY - tap.y) > 8) return
  if (!editor.isEditable) return
  const lastBlock = editor.view.dom.lastElementChild?.getBoundingClientRect()
  const belowText = lastBlock != null && event.clientY > lastBlock.bottom
  const hit = belowText ? null : editor.view.posAtCoords({ left: event.clientX, top: event.clientY })
  const position = hit?.pos ?? editor.state.doc.content.size - 1
  editor.commands.setTextSelection(position)
  // Keep focus inside the user gesture. The queued Tiptap focus command can
  // miss Android's keyboard activation after a blank-area pointer event.
  editor.view.focus()
  post({ type: 'requestKeyboard' })
})
document.addEventListener('pointermove', event => {
  if (blankTap && Math.hypot(event.clientX - blankTap.x, event.clientY - blankTap.y) > 8) blankTap = null
})
document.addEventListener('pointercancel', () => { blankTap = null })

// The app shrinks the WebView above the keyboard instead of letting Android pan
// it, so Chromium sees an ordinary resize and leaves the caret where it was.
// Follow the shrinking viewport, frame by frame, like a native text field.
const caretMargin = 28
let viewportHeight = window.innerHeight
window.addEventListener('resize', () => {
  const shrank = window.innerHeight < viewportHeight
  viewportHeight = window.innerHeight
  if (shrank && editor.isFocused) revealCaret()
})
function revealCaret() {
  const caret = editor.view.coordsAtPos(editor.state.selection.head)
  const bottom = window.innerHeight - caretMargin
  if (caret.bottom > bottom) window.scrollBy(0, caret.bottom - bottom)
  else if (caret.top < caretMargin) window.scrollBy(0, caret.top - caretMargin)
}

function post(payload: Record<string, unknown>) {
  window.KgsNotesEditor?.postMessage(JSON.stringify(payload))
}

function exactMarkdown() {
  return dirty ? preservation.serialize(editor) : originalMarkdown
}

function postActiveFormatting(currentEditor: Editor = editor) {
  post({ type: 'formatting', commands: activeFormatting(currentEditor) })
}

function activeFormatting(currentEditor: Editor = editor) {
  return [
    { command: 'bold', active: currentEditor.isActive('bold') },
    { command: 'italic', active: currentEditor.isActive('italic') },
    { command: 'heading', active: currentEditor.isActive('heading', { level: 2 }) },
    { command: 'bullet', active: currentEditor.isActive('bulletList') },
    { command: 'numbered', active: currentEditor.isActive('orderedList') },
    { command: 'task', active: currentEditor.isActive('taskList') },
    { command: 'quote', active: currentEditor.isActive('blockquote') },
    { command: 'code', active: currentEditor.isActive('codeBlock') },
    { command: 'table', active: currentEditor.isActive('table') },
  ].filter(({ active }) => active).map(({ command }) => command)
}

function load(markdown: string) {
  if (loadedSuccessfully && markdown === exactMarkdown()) return
  originalMarkdown = markdown
  dirty = false
  suppressUpdates = true
  try {
    const parsed = preservation.parse(editor, markdown)
    // A programmatic load is never an undoable edit: it replaces the document
    // outside history, then starts a fresh history at the loaded Note.
    loadedSuccessfully = editor.chain().setMeta('addToHistory', false)
      .setContent(parsed.content, { emitUpdate: false }).run()
    if (!loadedSuccessfully) post({ type: 'fallback' })
    else {
      resetHistory()
      // Without exact block boundaries an edit could normalize unrelated
      // source, so the Note stays in Source Mode instead.
      if (parsed.apply(editor)) postActiveFormatting()
      else {
        loadedSuccessfully = false
        post({ type: 'fallback' })
      }
    }
  } catch {
    loadedSuccessfully = false
    // Never forward parser exceptions or Note contents through diagnostic messages.
    post({ type: 'fallback' })
  } finally {
    suppressUpdates = false
  }
}

function resetHistory() {
  const { state } = editor
  editor.view.updateState(EditorState.create({ schema: state.schema, doc: state.doc, selection: state.selection, plugins: state.plugins }))
}

function setLabels(labels: Record<string, string>, language: string) {
  applyLabels(labels, language)
  editor.setOptions({ editorProps: {
    ...editor.options.editorProps,
    attributes: { ...editor.options.editorProps.attributes, 'aria-label': editorLabel('content') },
  } })
}

function setDarkMode(enabled: boolean) {
  document.documentElement.classList.toggle('dark', enabled)
}

function execute(command: string) {
  const chain = editor.chain()
  switch (command) {
    case 'bold': chain.toggleBold().run(); break
    case 'italic': chain.toggleItalic().run(); break
    case 'heading': chain.toggleHeading({ level: 2 }).run(); break
    case 'bullet': chain.toggleBulletList().run(); break
    case 'numbered': chain.toggleOrderedList().run(); break
    case 'task': chain.toggleTaskList().run(); break
    case 'quote': chain.toggleBlockquote().run(); break
    case 'code': chain.toggleCodeBlock().run(); break
    case 'table': chain.insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); break
    case 'undo': chain.undo().run(); break
    case 'redo': chain.redo().run(); break
  }
  const active = activeFormatting()
  post({ type: 'formatting', commands: active })
  return active
}

function run(command: string) {
  // A native toolbar click moves Android focus away from the WebView. Tiptap's
  // focus command completes on the next frame; applying a stored mark before
  // that frame lets the later focus transaction clear it again.
  editor.commands.focus()
  window.requestAnimationFrame(() => execute(command))
}

function focus() {
  editor.view.focus()
}

const viewportAccess = viewportBridge(editor, exactMarkdown)

// The host remembers where each Note was read, so a recreated editor (a fold,
// rotation or relaunch) can return to the same content.
let viewportReport = 0
function reportViewport() {
  window.clearTimeout(viewportReport)
  viewportReport = window.setTimeout(() => {
    try { post({ type: 'viewport', viewport: viewportAccess.viewport() }) } catch { /* layout not ready */ }
  }, 150)
}
window.addEventListener('scroll', reportViewport, { passive: true })
editor.on('selectionUpdate', reportViewport)

// Links never navigate the editor document: the host opens Notes in the app and
// everything else outside it. A tap while typing still places the caret, so
// linked text stays editable; a tap while reading opens the link.
let linkTap: { id: number; x: number; y: number; href: string } | null = null
paper.addEventListener('pointerdown', event => {
  linkTap = null
  const link = (event.target as Element | null)?.closest?.('a[href]')
  if (!link || event.button !== 0 || (editor.isEditable && editor.isFocused)) return
  linkTap = { id: event.pointerId, x: event.clientX, y: event.clientY, href: link.getAttribute('href') ?? '' }
  // Keep the keyboard closed: the tap opens the link instead of focusing it.
  event.preventDefault()
}, true)
paper.addEventListener('pointermove', event => {
  if (linkTap && Math.hypot(event.clientX - linkTap.x, event.clientY - linkTap.y) > 8) linkTap = null
}, true)
paper.addEventListener('pointercancel', () => { linkTap = null }, true)
paper.addEventListener('pointerup', event => {
  const tap = linkTap
  linkTap = null
  if (!tap || tap.id !== event.pointerId || Math.hypot(event.clientX - tap.x, event.clientY - tap.y) > 8) return
  if (tap.href.trim()) post({ type: 'openLink', href: tap.href })
}, true)
document.addEventListener('click', event => {
  if ((event.target as Element | null)?.closest?.('a[href]')) event.preventDefault()
}, true)

window.kgsEditor = { load, markdown: exactMarkdown, setDarkMode, setLabels, run, focus, ...viewportAccess }
post({ type: 'ready' })
