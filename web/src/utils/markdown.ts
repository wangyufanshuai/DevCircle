import DOMPurify from 'dompurify'
import { marked, Renderer } from 'marked'

export type MarkdownMedia = {
  mediaId: number
  url?: string
  contentType?: string
  altText?: string
}

const mediaReference = /^media:\/\/([1-9][0-9]*)$/
const mediaReferenceGlobal = /!\[[^\]\r\n]*]\(\s*media:\/\/([1-9][0-9]*)\s*\)/g

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[character] ?? character)
}

function safeMediaUrl(value?: string) {
  if (!value) return ''
  try {
    const parsed = new URL(value, window.location.origin)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : ''
  } catch {
    return ''
  }
}

export function markdownMediaIds(markdown: string) {
  return new Set([...markdown.matchAll(mediaReferenceGlobal)].map(match => Number(match[1])))
}

export function renderSafeMarkdown(markdown: string, media: MarkdownMedia[] = []) {
  const mediaById = new Map(media.map(item => [item.mediaId, item]))
  const renderer = new Renderer()

  renderer.html = ({ text }) => escapeHtml(text)
  renderer.image = ({ href, text }) => {
    const match = mediaReference.exec(href)
    if (!match) {
      return '<span class="inline-media-unavailable" role="note">外部图片已阻止，请使用已上传媒体</span>'
    }
    const mediaId = Number(match[1])
    const asset = mediaById.get(mediaId)
    if (!asset) {
      return `<span class="inline-media-unavailable" role="note">媒体 #${mediaId} 未关联或暂不可用</span>`
    }
    const alt = escapeHtml(asset.altText?.trim() || text || `媒体 ${mediaId}`)
    const url = safeMediaUrl(asset.url)
    if (!url) {
      return `<span class="inline-media-placeholder" role="img" aria-label="${alt}">正文媒体 #${mediaId} · ${alt}</span>`
    }
    const source = escapeHtml(url)
    if (asset.contentType?.startsWith('video/')) {
      return `<figure class="inline-media"><video controls preload="metadata" src="${source}" aria-label="${alt}"></video><figcaption>${alt}</figcaption></figure>`
    }
    return `<figure class="inline-media"><img src="${source}" alt="${alt}" loading="lazy" decoding="async"><figcaption>${alt}</figcaption></figure>`
  }

  const html = marked.parse(markdown, { async: false, renderer }) as string
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed'],
    FORBID_ATTR: ['style', 'onerror', 'onclick', 'onload']
  })
}
