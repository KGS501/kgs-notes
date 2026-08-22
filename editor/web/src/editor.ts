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
      run(command: string): void
    }
  }
}

const IMAGE_EXTENSIONS = /\.(avif|gif|jpe?g|png|svg|webp)(?:[?#].*)?$/i

const attachmentImage = Image.extend({
  addNodeView() {
    return ({ node }) => {
      const source = String(node.attrs.src ?? '')
      if (IMAGE_EXTENSIONS.test(source)) {
        const image = document.createElement('img')
        image.src = source
        image.alt = String(node.attrs.alt ?? '')
        image.className = 'inline-image'
        return { dom: image }
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
})

function post(payload: Record<string, unknown>) {
  window.KgsNotesEditor?.postMessage(JSON.stringify(payload))
}

function exactMarkdown() {
  return dirty ? editor.getMarkdown() : originalMarkdown
}

function load(markdown: string) {
  if (markdown === exactMarkdown()) return
  originalMarkdown = markdown
  dirty = false
  suppressUpdates = true
  editor.commands.setContent(markdown, { contentType: 'markdown', emitUpdate: false })
  suppressUpdates = false
}

function setDarkMode(enabled: boolean) {
  document.documentElement.classList.toggle('dark', enabled)
}

function run(command: string) {
  const chain = editor.chain().focus()
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
}

window.kgsEditor = { load, markdown: exactMarkdown, setDarkMode, run }
post({ type: 'ready' })
