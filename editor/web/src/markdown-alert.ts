import { Node } from '@tiptap/core'
import { editorLabel } from './editor-labels'

/** GitHub-style alerts remain ordinary portable Markdown when saved. */
export const MarkdownAlert = Node.create({
  name: 'markdownAlert', group: 'block', content: 'block+', defining: true,
  addAttributes: () => ({ alertType: {
    default: 'NOTE', rendered: false,
    validate: (value: unknown) => {
      if (!['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'].includes(value as string)) throw new RangeError('Unknown alert type')
    },
    parseHTML: element => {
      const type = element.getAttribute('data-markdown-alert') ?? 'NOTE'
      return ['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'].includes(type) ? type : 'NOTE'
    },
  } }),
  parseHTML: () => [{ tag: 'aside[data-markdown-alert]' }],
  renderHTML: ({ node }) => ['aside', { 'data-markdown-alert': node.attrs.alertType }, 0],
  markdownTokenName: 'markdownAlert',
  parseMarkdown: (token, helpers) => {
    const content = helpers.parseChildren(token.tokens ?? [])
    return helpers.createNode('markdownAlert', { alertType: token.alertType }, content.length ? content : [{ type: 'paragraph' }])
  },
  renderMarkdown: (node, helpers) => `> [!${node.attrs.alertType}]\n${helpers.renderChildren(node.content ?? [], '\n\n')
    .split('\n').map(line => line ? `> ${line}` : '>').join('\n')}`,
  addNodeView() {
    return ({ node }) => {
      const dom = document.createElement('aside')
      const type = String(node.attrs.alertType)
      dom.dataset.markdownAlert = type
      dom.className = 'markdown-alert'
      const title = document.createElement('div')
      title.className = 'markdown-alert-title'
      title.contentEditable = 'false'
      title.textContent = editorLabel(type)
      const contentDOM = document.createElement('div')
      contentDOM.className = 'markdown-alert-content'
      dom.append(title, contentDOM)
      return { dom, contentDOM }
    }
  },
})
