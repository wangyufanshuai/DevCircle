import type { FeedItem, Post } from './api/types'

export type SceneKey = 'mixed' | 'deduction' | 'sci_fi' | 'cosmos' | 'search' | 'write' | 'admin'

export const themeOptions = [
  { label: '混合推荐', value: 'mixed' },
  { label: '推理', value: 'deduction' },
  { label: '科幻', value: 'sci_fi' },
  { label: '宇宙', value: 'cosmos' }
]

export const contentKindOptions = [
  { label: '案件档案', value: 'CASE_FILE' },
  { label: '逻辑谜题', value: 'PUZZLE' },
  { label: '科幻短篇', value: 'FICTION' },
  { label: '世界观设定', value: 'WORLDBUILDING' },
  { label: '宇宙档案', value: 'COSMIC_ARTICLE' },
  { label: '观测日志', value: 'OBSERVATION_LOG' }
]

export const difficultyOptions = [
  { label: '入门', value: 'EASY' },
  { label: '进阶', value: 'MEDIUM' },
  { label: '高难', value: 'HARD' }
]

export function themeLabel(theme: string) {
  return theme === 'DEDUCTION' ? '推理' : theme === 'SCI_FI' ? '科幻' : '宇宙'
}

export function themeClass(theme: string) {
  return theme === 'DEDUCTION' ? 'theme-deduction' : theme === 'SCI_FI' ? 'theme-scifi' : 'theme-cosmos'
}

export function kindLabel(kind: string) {
  return contentKindOptions.find((item) => item.value === kind)?.label ?? kind
}

export function difficultyLabel(difficulty: string) {
  return difficultyOptions.find((item) => item.value === difficulty)?.label ?? difficulty
}

export function sceneToTheme(scene: string) {
  const map: Record<string, Post['theme']> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
  return map[scene]
}

export function sceneTitle(scene: string) {
  if (scene === 'deduction') return '推理馆'
  if (scene === 'sci_fi') return '科幻馆'
  if (scene === 'cosmos') return '宇宙档案'
  if (scene === 'search') return '搜索结果'
  if (scene === 'write') return '创作台'
  if (scene === 'admin') return '运营控制台'
  return '星环首页'
}

export function wrapPost(post: Post, reason: string, algorithm: string): FeedItem {
  return { post, reason, score: 0, liked: false, favorited: false, algorithm, mediaType: post.type, features: {}, recallSources: ['search'] }
}

export function topByTheme(items: FeedItem[], theme: Post['theme'], count: number) {
  return items
    .filter((item) => item.post.theme === theme)
    .sort((a, b) => b.post.likes + b.post.views / 100 - (a.post.likes + a.post.views / 100))
    .slice(0, count)
}

export function channelItems(items: FeedItem[], scene: string) {
  const theme = sceneToTheme(scene)
  return theme ? items.filter((item) => item.post.theme === theme) : items
}
