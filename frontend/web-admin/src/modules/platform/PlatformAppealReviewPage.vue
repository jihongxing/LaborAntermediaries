<!-- eslint-disable max-lines, max-len, vue/max-attributes-per-line -->
<script setup lang="ts">
/* eslint-disable max-lines */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import {
  platformApi,
  type AppealOutcome,
  type PlatformTask,
  type PlatformTaskDetails,
} from './api/platform'

const route = useRoute()
const router = useRouter()
const tasks = ref<PlatformTask[]>([])
const details = ref<PlatformTaskDetails>()
const loading = ref(true)
const working = ref(false)
const error = ref('')
const notice = ref('')
const actionReason = ref('')
const reviewReason = ref('')
const outcome = ref<AppealOutcome>('UPHOLD_ORIGINAL')
const statusFilter = ref('ALL')
const versionFilter = ref('')

const appealId = computed(() => String(route.params.appealId || ''))
const selectedTask = computed(() => tasks.value.find(task => task.appealCaseId === appealId.value))
const visibleTasks = computed(() =>
  tasks.value.filter(
    task =>
      (statusFilter.value === 'ALL' ||
        (statusFilter.value === 'MINE' ? task.claimedByMe : task.status === statusFilter.value)) &&
      (!versionFilter.value.trim() || task.resourceVersion === versionFilter.value.trim())
  )
)

function message(cause: unknown, fallback: string) {
  return cause instanceof ApiError ? cause.message : fallback
}

function formatTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value)
      )
    : '—'
}

async function loadQueue() {
  loading.value = true
  error.value = ''
  details.value = undefined
  try {
    tasks.value = await platformApi.queue('APPEAL_REVIEW')
    const task = selectedTask.value
    if (task?.claimedByMe) {
      details.value = await platformApi.details(task.taskId)
    }
  } catch (cause) {
    error.value = message(cause, '独立复核队列暂时无法读取。')
  } finally {
    loading.value = false
  }
}

async function openTask(task: PlatformTask) {
  if (task.status === 'CLAIMED' && !task.claimedByMe) {
    return
  }
  working.value = true
  error.value = ''
  notice.value = ''
  try {
    if (!task.claimedByMe) {
      const claim = await platformApi.claim(task.taskId)
      if (!claim.active) {
        throw new Error('任务认领已失效，请刷新队列。')
      }
    }
    const destination = `/platform/reviews/appeals/${task.appealCaseId}`
    if (route.path === destination) {
      await loadQueue()
    } else {
      await router.push(destination)
    }
  } catch (cause) {
    const failure = message(
      cause,
      cause instanceof Error ? cause.message : '认领失败，请刷新任务状态。'
    )
    await loadQueue()
    error.value = failure
  } finally {
    working.value = false
  }
}

async function leaveTask(action: 'release' | 'recuse') {
  if (!selectedTask.value?.claimedByMe || !actionReason.value.trim()) {
    error.value = '请填写释放或回避理由。'
    return
  }
  working.value = true
  error.value = ''
  try {
    if (action === 'release') {
      await platformApi.release(selectedTask.value.taskId, actionReason.value.trim())
    } else {
      await platformApi.recuse(selectedTask.value.taskId, actionReason.value.trim())
    }
    actionReason.value = ''
    notice.value =
      action === 'release' ? '任务已释放，回到独立复核岗位队列。' : '已记录回避并撤销本任务访问。'
    await router.push('/platform/reviews/appeals')
  } catch (cause) {
    error.value = message(cause, '任务操作未完成。')
  } finally {
    working.value = false
  }
}

async function submitReview() {
  if (!selectedTask.value?.claimedByMe || !details.value?.appeal) {
    error.value = '请先认领并读取复核事项。'
    return
  }
  if (!reviewReason.value.trim()) {
    error.value = '请填写独立复核依据。'
    return
  }
  working.value = true
  error.value = ''
  try {
    await platformApi.reviewAppeal(
      selectedTask.value.taskId,
      outcome.value,
      reviewReason.value.trim()
    )
    reviewReason.value = ''
    notice.value = '独立复核意见已记录；原审核决定和业务状态不会自动改变。'
    await router.push('/platform/reviews/appeals')
  } catch (cause) {
    error.value = message(cause, '复核意见未提交，请检查当前任务状态。')
  } finally {
    working.value = false
  }
}

watch(() => route.params.appealId, loadQueue, { immediate: true })
</script>

<template>
  <div class="workspace-product platform-product">
    <aside class="workspace-sidebar">
      <button class="workspace-brand" type="button" @click="router.push('/')">
        <span class="public-brand-mark">劳</span>
        <span>
          <strong>劳务供需平台</strong>
          <small>平台治理工作台</small>
        </span>
      </button>
      <div class="workspace-org-switcher platform-role-note">
        <span>所需岗位</span>
        <strong>独立复核员</strong>
        <small>原处理人不得复核本人决定</small>
      </div>
      <nav class="workspace-nav" aria-label="独立复核导航">
        <button type="button" @click="router.push('/platform')">
          <span class="nav-marker"></span>
          工作总览
        </button>
        <button class="active" type="button" @click="router.push('/platform/reviews/appeals')">
          <span class="nav-marker"></span>
          异议复核
        </button>
      </nav>
      <div class="workspace-sidebar-footer">
        <button type="button" @click="router.push('/account')">个人账号</button>
        <button type="button" @click="router.push('/')">退出工作台</button>
      </div>
    </aside>
    <main class="workspace-main">
      <header class="workspace-topbar">
        <div class="workspace-breadcrumb">
          平台治理
          <span>/</span>
          独立复核
        </div>
        <button class="workspace-user" type="button" @click="router.push('/account')">
          平台岗位会话
        </button>
      </header>
      <div class="workspace-content platform-content">
        <section class="workspace-intro">
          <div>
            <span class="eyebrow">异议复核队列</span>
            <h1>核对原决定，再记录独立意见</h1>
            <p>队列只显示任务摘要；本人认领后才能读取申请理由、原决定和事项历史。</p>
          </div>
          <span v-if="!error" class="status-pill approved">{{ tasks.length }} 项待处理</span>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>复核提示</strong>
          <p>{{ error }}</p>
          <button class="public-outline" type="button" @click="loadQueue">刷新队列</button>
        </div>
        <p v-if="notice" class="claim-note" role="status">{{ notice }}</p>
        <section v-if="!error" class="platform-layout">
          <div class="platform-queue panel-surface">
            <div class="section-kicker">岗位任务</div>
            <div class="appeal-filters">
              <select v-model="statusFilter" aria-label="按认领状态筛选">
                <option value="ALL">全部待处理</option>
                <option value="PENDING">待认领</option>
                <option value="MINE">我已认领</option>
                <option value="CLAIMED">已认领</option>
              </select>
              <input
                v-model="versionFilter"
                aria-label="按版本筛选"
                inputmode="numeric"
                placeholder="版本号"
              />
            </div>
            <div v-if="loading" class="platform-empty">正在读取独立复核队列…</div>
            <div v-else-if="!visibleTasks.length" class="platform-empty">
              当前筛选条件下没有复核任务。
            </div>
            <button
              v-for="task in visibleTasks"
              :key="task.taskId"
              class="platform-task"
              :class="{ selected: appealId === task.appealCaseId }"
              :disabled="working || (task.status === 'CLAIMED' && !task.claimedByMe)"
              type="button"
              @click="openTask(task)"
            >
              <span class="platform-task-dot"></span>
              <span>
                <strong>复核事项 {{ task.appealCaseId }} · 原事项 {{ task.reviewCaseId }}</strong>
                <small>
                  {{ task.resourceType }} · 版本 {{ task.resourceVersion }} ·
                  {{
                    task.claimedByMe
                      ? '我已认领'
                      : task.status === 'CLAIMED'
                        ? '其他人员已认领'
                        : '待认领'
                  }}
                  · {{ formatTime(task.createdAt) }}
                </small>
              </span>
              <span>→</span>
            </button>
            <p class="muted-copy">当前队列最多展示 100 项；已被他人认领的任务不可打开。</p>
          </div>
          <div
            v-if="details?.appeal && selectedTask?.claimedByMe"
            class="platform-detail panel-surface"
          >
            <div class="platform-detail-head">
              <div>
                <div class="section-kicker">已认领复核事项 {{ details.appeal.appealCaseId }}</div>
                <h2>
                  {{
                    details.task.resourceType === 'ORGANIZATION' ? '组织审核异议' : '需求审核异议'
                  }}
                </h2>
                <p>
                  原事项 {{ details.task.reviewCaseId }} · 资源 {{ details.task.resourceId }} · 版本
                  {{ details.task.resourceVersion }}
                </p>
              </div>
              <span class="status-pill approved">本人认领</span>
            </div>
            <div class="platform-facts">
              <div>
                <span>原决定</span>
                <strong>
                  {{ details.appeal.originalDecision }} · {{ details.appeal.originalStatus }}
                </strong>
              </div>
              <div>
                <span>原决定编号</span>
                <strong>{{ details.appeal.originalDecisionId }}</strong>
              </div>
              <div>
                <span>原处理人编号</span>
                <strong>{{ details.appeal.originalReviewerId }}</strong>
              </div>
              <div>
                <span>原决定时间</span>
                <strong>{{ formatTime(details.appeal.originalDecisionAt) }}</strong>
              </div>
            </div>
            <div class="platform-section">
              <div class="section-kicker">申请理由</div>
              <p class="appeal-reason">{{ details.appeal.requestReason }}</p>
            </div>
            <div class="platform-section">
              <div class="section-kicker">原事项版本与处理历史</div>
              <div class="review-history">
                <p>
                  事项 {{ details.history.reviewCaseId }} · 历史覆盖：{{
                    details.history.historyCoverage
                  }}
                </p>
                <article v-for="version in details.history.versions" :key="version.resourceVersion">
                  <strong>
                    版本 {{ version.resourceVersion }} · {{ version.decision || '未形成决定' }}
                  </strong>
                  <p v-if="version.publicReason">对申请方说明：{{ version.publicReason }}</p>
                  <ul v-if="version.correctionItems.length">
                    <li v-for="item in version.correctionItems" :key="item.correctionItemId">
                      {{ item.title }}：{{ item.description }}
                    </li>
                  </ul>
                </article>
                <ol>
                  <li v-for="event in details.history.events" :key="event.eventId">
                    {{ formatTime(event.occurredAt) }} · {{ event.actorRole || '申请方/系统' }} ·
                    {{ event.action }} · {{ event.outcome }}
                  </li>
                </ol>
              </div>
            </div>
            <div class="platform-section">
              <div class="section-kicker">独立复核意见</div>
              <p class="muted-copy">请求重新审核仅记录流程意见，不会自动撤销或改写原决定。</p>
              <div class="decision-row">
                <label>
                  <input v-model="outcome" type="radio" value="UPHOLD_ORIGINAL" />
                  维持原决定
                </label>
                <label>
                  <input v-model="outcome" type="radio" value="REQUEST_RECONSIDERATION" />
                  请求重新审核
                </label>
              </div>
              <textarea
                v-model="reviewReason"
                maxlength="2000"
                placeholder="填写可复核的事实、依据和理由"
              ></textarea>
              <button
                class="public-primary"
                :disabled="working"
                type="button"
                @click="submitReview"
              >
                提交复核意见
              </button>
            </div>
            <div class="platform-section">
              <div class="section-kicker">释放或回避</div>
              <textarea
                v-model="actionReason"
                maxlength="2000"
                placeholder="填写释放或回避理由"
              ></textarea>
              <div class="appeal-actions">
                <button
                  class="public-outline"
                  :disabled="working"
                  type="button"
                  @click="leaveTask('release')"
                >
                  释放任务
                </button>
                <button
                  class="public-outline"
                  :disabled="working"
                  type="button"
                  @click="leaveTask('recuse')"
                >
                  声明回避
                </button>
              </div>
            </div>
          </div>
          <div v-else class="platform-detail panel-surface platform-empty">
            <template v-if="appealId && selectedTask && !selectedTask.claimedByMe">
              复核事项 {{ appealId }} 尚未由本人认领。请从左侧认领后查看原决定与历史。
            </template>
            <template v-else-if="appealId && !loading">
              该复核事项不在当前岗位待处理队列中。
            </template>
            <template v-else>从左侧选择一项复核任务。已认领任务才开放详情。</template>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
