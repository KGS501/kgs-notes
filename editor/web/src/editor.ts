import { Editor } from '@tiptap/core'
import Image from '@tiptap/extension-image'
import { TableKit } from '@tiptap/extension-table'
import TaskItem from '@tiptap/extension-task-item'
import TaskList from '@tiptap/extension-task-list'
import { Markdown } from '@tiptap/markdown'
import StarterKit from '@tiptap/starter-kit'
import './style.css'

declare global {
  interface Window {
    KgsNotesEditor?: { postMessage(message: string): void }
    kgsEditor: {
      load(markdown: string): void
      markdown(): string
      setDarkMode(enabled: boolean): void
      run(command: string): string[]
    }
  }
}

const IMAGE_EXTENSIONS = /\.(avif|gif|jpe?g|png|svg|webp)(?:[?#].*)?$/i

const attachmentImage = Image.extend({
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

let originalMarkdown = ''
let dirty = false
let suppressUpdates = false

const editor = new Editor({
  element: document.querySelector('#editor') as HTMLElement,
  extensions: [
    StarterKit,
    TableKit.configure({ table: { resizable: false } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    attachmentImage,
    Markdown.configure({
      markedOptions: { gfm: true, breaks: false, pedantic: false },
      indentation: { style: 'space', size: 2 },
    }),
  ],
  content: '',
  contentType: 'markdown',
  editorProps: {
    attributes: {
      class: 'rich-editor',
      'aria-label': 'Rich note content',
      spellcheck: 'true',
    },
  },
  onUpdate: () => {
    if (suppressUpdates) return
    dirty = true
    post({ type: 'changed', markdown: editor.getMarkdown() })
  },
  onTransaction: ({ editor: currentEditor }) => postActiveFormatting(currentEditor),
})

function post(payload: Record<string, unknown>) {
  window.KgsNotesEditor?.postMessage(JSON.stringify(payload))
}

function exactMarkdown() {
  return dirty ? editor.getMarkdown() : originalMarkdown
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
  if (markdown === exactMarkdown()) return
  originalMarkdown = markdown
  dirty = false
  suppressUpdates = true
  editor.commands.setContent(markdown, { contentType: 'markdown', emitUpdate: false })
  suppressUpdates = false
  postActiveFormatting()
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
  return activeFormatting()
}

window.kgsEditor = { load, markdown: exactMarkdown, setDarkMode, run }
post({ type: 'ready' })
