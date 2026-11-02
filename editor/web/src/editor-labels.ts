/** Presentation labels supplied by Android resources; canonical Markdown stays unchanged. */
const labels: Record<string, string> = {
  content: 'Rich note content', empty: 'Start writing…',
  sourceTitle: 'Preserved source — edit in Source Mode', sourceHint: 'Edit this fragment in Source Mode.',
  Comment: 'Comment', HTML: 'HTML', Metadata: 'Metadata', Footnote: 'Footnote', Math: 'Math',
  Directive: 'Directive', 'Footnote reference': 'Footnote reference',
  'Link reference': 'Link reference', 'Linked image': 'Linked image',
  NOTE: 'Note', TIP: 'Tip', IMPORTANT: 'Important', WARNING: 'Warning', CAUTION: 'Caution',
}
export function editorLabel(key: string): string { return labels[key] ?? key }
export function refreshLabels(root: ParentNode = document) {
  root.querySelectorAll<HTMLElement>('.markdown-alert-title').forEach(title => {
    title.textContent = editorLabel(title.parentElement?.dataset.markdownAlert ?? 'NOTE')
  })
  root.querySelectorAll<HTMLElement>('[data-source-kind]').forEach(capsule => {
    capsule.title = editorLabel('sourceTitle')
    capsule.setAttribute('aria-label', `${editorLabel(capsule.dataset.sourceKind ?? 'HTML')}. ${editorLabel('sourceHint')}`)
    const title = capsule.querySelector('.source-capsule-label')
    if (title) title.textContent = editorLabel(capsule.dataset.sourceComment === 'true' ? 'Comment' : capsule.dataset.sourceKind ?? 'HTML')
  })
}
export function setLabels(values: Record<string, string>, language: string) {
  for (const key of Object.keys(labels)) if (typeof values[key] === 'string') labels[key] = values[key]
  document.documentElement.lang = language
  document.documentElement.style.setProperty('--kgs-empty-hint', JSON.stringify(editorLabel('empty')))
  document.querySelector('.rich-editor')?.setAttribute('aria-label', editorLabel('content'))
  refreshLabels()
}
