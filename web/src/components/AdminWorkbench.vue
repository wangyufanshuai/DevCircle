<script setup lang="ts">
import { NButton, NIcon } from 'naive-ui'
import { Check, RotateCcw, X } from 'lucide-vue-next'
import type { DegradationOverview, LeaderboardEntry, ModerationTask, OpsHealth, OutboxEvent, RecommendExperiment, RiskUser } from '../api/types'

defineProps<{
  moderationTasks: ModerationTask[]
  riskUsers: RiskUser[]
  recommendExperiments: RecommendExperiment[]
  opsHealth: OpsHealth | null
  outboxEvents: OutboxEvent[]
  degradationOverview: DegradationOverview | null
  leaderboard: LeaderboardEntry[]
}>()

const emit = defineEmits<{
  moderation: [taskId: number, action: 'approve' | 'reject' | 'escalate' | 'require-changes']
  refreshOps: []
  replayOutbox: []
}>()
</script>

<template>
  <section class="admin-grid">
    <div class="panel admin-panel">
      <p class="eyebrow">Moderation</p>
      <h2>治理审核队列</h2>
      <div v-for="item in moderationTasks" :key="item.taskId" class="audit-row">
        <div>
          <strong>{{ item.title }}</strong>
          <span>{{ item.queue }} / {{ item.riskLevel }} / 风险 {{ item.riskScore }} / {{ item.reason }}</span>
        </div>
        <div class="audit-actions">
          <n-button size="small" type="success" @click="emit('moderation', item.taskId, 'approve')"><n-icon :component="Check" />通过</n-button>
          <n-button size="small" type="error" @click="emit('moderation', item.taskId, 'reject')"><n-icon :component="X" />驳回</n-button>
          <n-button size="small" @click="emit('moderation', item.taskId, 'escalate')">复审</n-button>
          <n-button size="small" @click="emit('moderation', item.taskId, 'require-changes')">修改</n-button>
        </div>
      </div>
    </div>

    <div class="panel admin-panel">
      <p class="eyebrow">Risk</p>
      <h2>风险用户</h2>
      <div v-for="user in riskUsers" :key="user.userId" class="rank-row">
        <span>用户 #{{ user.userId }} / {{ user.status }}</span>
        <strong>{{ user.riskScore }} 风险分</strong>
        <small>{{ user.reason }}</small>
      </div>
    </div>

    <div class="panel admin-panel">
      <p class="eyebrow">Experiment</p>
      <h2>推荐实验</h2>
      <div v-for="exp in recommendExperiments" :key="exp.experimentId" class="rank-row">
        <span>{{ exp.name }}</span>
        <strong>{{ exp.status }} / {{ exp.scene }}</strong>
        <small>{{ exp.experimentId }} / {{ exp.trafficPercent }}% 流量</small>
      </div>
    </div>

    <div class="panel admin-panel">
      <p class="eyebrow">Runtime</p>
      <h2>工程状态</h2>
      <div class="rank-row">
        <span>数据库 {{ opsHealth?.database ?? 'UNKNOWN' }} / 来源 {{ opsHealth?.source ?? '-' }}</span>
        <strong>Outbox {{ opsHealth?.outboxBacklog ?? 0 }}</strong>
        <small>待审 {{ opsHealth?.pendingModeration ?? 0 }} / 风险用户 {{ opsHealth?.riskUsers ?? 0 }}</small>
      </div>
      <div class="rank-row">
        <span>推荐降级 {{ degradationOverview?.degraded ? '是' : '否' }} / {{ degradationOverview?.source ?? '-' }}</span>
        <strong>事件 {{ degradationOverview?.recommendEvents ?? 0 }}</strong>
        <small>{{ degradationOverview?.reason ?? '运行状态正常或暂无降级原因' }}</small>
      </div>
      <div v-for="event in outboxEvents" :key="event.eventId" class="rank-row">
        <span>{{ event.topic }} / {{ event.status }}</span>
        <strong>{{ event.aggregateType ?? 'OUTBOX' }}</strong>
        <small>{{ event.eventId }}</small>
      </div>
      <div class="button-row">
        <n-button secondary @click="emit('refreshOps')">刷新状态</n-button>
        <n-button secondary @click="emit('replayOutbox')"><n-icon :component="RotateCcw" />重放 Outbox</n-button>
      </div>
    </div>

    <div class="panel admin-panel">
      <p class="eyebrow">Leaderboard</p>
      <h2>解谜排行榜</h2>
      <div v-for="entry in leaderboard" :key="entry.userId" class="rank-row">
        <span>{{ entry.username }}</span>
        <strong>{{ entry.totalPoints }} 分</strong>
        <small>{{ entry.correctCount }} 题正确</small>
      </div>
    </div>
  </section>
</template>
