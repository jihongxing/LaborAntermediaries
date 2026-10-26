<!-- eslint-disable max-len, max-lines, curly, vue/max-attributes-per-line -->
<script setup lang="ts">
/* eslint-disable max-lines */
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import {
  platformApi,
  type FindingFactStatus,
  type FindingStage,
  type PlatformFindingDetail,
  type PlatformFindingSummary,
} from './api/platform'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const error = ref('')
const findings = ref<PlatformFindingSummary[]>([])
const selected = ref<PlatformFindingDetail>()
const responseText = ref('')
const reviewText = ref('')
const reviewStatus = ref<Exclude<FindingFactStatus, 'UNCONFIRMED'>>('SUPPORTED')
const creating = ref(false)
const createForm = reactive({ reviewCaseId: '', resourceVersion: '', title: '', description: '' })

function statusLabel(value: string) {
  return (
    (
      {
        WAITING_RESPONSE: '待回应',
        RESPONSE_CLAIMED: '回应处理中',
        WAITING_REVIEW: '待独立复核',
        REVIEW_CLAIMED: '复核处理中',
        CLOSED: '已关闭',
      } as Record<string, string>
    )[value] || value
  )
}

async function loadQueue() {
  loading.value = true
  error.value = ''
  try {
    findings.value = await platformApi.findings()
    const requested = typeof route.query.finding === 'string' ? route.query.finding : ''
    const first = requested
      ? findings.value.find(item => item.findingId === requested)
      : findings.value[0]
    if (first) await open(first)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '监察发现队列暂时无法读取。'
  } finally {
    loading.value = false
  }
}

async function open(item: PlatformFindingSummary) {
  error.value = ''
  try {
    selected.value = await platformApi.finding(item.findingId)
    router.replace({ query: { finding: item.findingId } })
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '发现详情无法读取。'
  }
}

async function claim(stage: FindingStage) {
  if (!selected.value) return
  try {
    await platformApi.claimFinding(selected.value.summary.findingId, stage)
    await open(selected.value.summary)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '认领失败，可能已被其他监察员占用。'
  }
}

async function respond() {
  if (!selected.value || !responseText.value.trim()) {
    error.value = '请填写回应内容。'
    return
  }
  try {
    selected.value = await platformApi.respondFinding(
      selected.value.summary.findingId,
      responseText.value.trim()
    )
    responseText.value = ''
    await loadQueue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '回应未提交。'
  }
}

async function review() {
  if (!selected.value || !reviewText.value.trim()) {
    error.value = '请填写复核依据。'
    return
  }
  try {
    selected.value = await platformApi.reviewFinding(
      selected.value.summary.findingId,
      reviewStatus.value,
      reviewText.value.trim()
    )
    reviewText.value = ''
    await loadQueue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '复核未提交。'
  }
}

async function create() {
  if (
    !createForm.reviewCaseId.trim() ||
    !createForm.title.trim() ||
    !createForm.description.trim()
  ) {
    error.value = '请填写审核事项、标题和事实描述。'
    return
  }
  creating.value = true
  try {
    const item = await platformApi.createFinding({
      reviewCaseId: createForm.reviewCaseId.trim(),
      resourceVersion: createForm.resourceVersion.trim() || undefined,
      title: createForm.title.trim(),
      description: createForm.description.trim(),
    })
    Object.assign(createForm, { reviewCaseId: '', resourceVersion: '', title: '', description: '' })
    await loadQueue()
    const created = findings.value.find(value => value.findingId === item.findingId)
    if (created) await open(created)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '发现未创建。'
  } finally {
    creating.value = false
  }
}

onMounted(loadQueue)
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
        <span>当前岗位</span>
        <strong>独立监察员</strong>
        <small>回应与复核必须分离</small>
      </div>
      <nav class="workspace-nav" aria-label="平台监察导航">
        <button type="button" @click="router.push('/platform/audit')">
          <span class="nav-marker"></span>
          监察查询
        </button>
        <button type="button" @click="router.push('/platform/audit/coverage')">
          <span class="nav-marker"></span>
          审计覆盖
        </button>
        <button class="active" type="button">
          <span class="nav-marker"></span>
          监察发现
        </button>
        <button type="button" @click="router.push('/platform/reviews/organizations')">
          <span class="nav-marker"></span>
          审核队列
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
          监察发现
        </div>
        <div class="workspace-top-actions">
          <button class="workspace-user" type="button" @click="router.push('/account')">
            平台岗位会话
          </button>
        </div>
      </header>
      <div class="workspace-content platform-content">
        <section class="workspace-intro">
          <div>
            <span class="eyebrow">G9 监察发现</span>
            <h1>把异常变成可回应、可复核的事实记录</h1>
            <p>发现记录只追加处理历史；它不会直接改变组织、需求或审核事项状态。</p>
          </div>
          <span class="status-pill approved">{{ findings.length }} 条发现</span>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>监察发现提示</strong>
          <p>{{ error }}</p>
        </div>
        <section class="audit-grid">
          <div class="panel-surface platform-queue">
            <div class="section-kicker">发现队列</div>
            <div v-if="loading" class="platform-empty">正在读取发现队列…</div>
            <div v-else-if="!findings.length" class="platform-empty">暂无监察发现。</div>
            <button
              v-for="item in findings"
              :key="item.findingId"
              class="platform-task"
              :class="{ selected: selected?.summary.findingId === item.findingId }"
              type="button"
              @click="open(item)"
            >
              <span class="platform-task-dot"></span>
              <span>
                <strong>{{ item.title }}</strong>
                <small>
                  {{ item.findingId }} · 事项 {{ item.reviewCaseId }} ·
                  {{ statusLabel(item.workflowStatus) }}
                </small>
              </span>
              <span>→</span>
            </button>
            <div class="platform-section">
              <div class="section-kicker">登记新发现</div>
              <label>
                审核事项
                <input v-model="createForm.reviewCaseId" placeholder="必填" />
              </label>
              <label>
                业务版本
                <input v-model="createForm.resourceVersion" placeholder="可选" />
              </label>
              <label>
                标题
                <input v-model="createForm.title" maxlength="200" />
              </label>
              <label>
                事实描述
                <textarea v-model="createForm.description" maxlength="4000"></textarea>
              </label>
              <button
                class="public-primary"
                type="button"
                :disabled="creating"
                @click="create"
              >
                {{ creating ? '保存中…' : '创建待确认发现' }}
              </button>
            </div>
          </div>
          <div class="panel-surface platform-detail">
            <div v-if="!selected" class="platform-empty">从左侧选择一条监察发现。</div>
            <template v-else>
              <div class="platform-detail-head">
                <div>
                  <div class="section-kicker">发现详情 · {{ selected.summary.findingId }}</div>
                  <h2>{{ selected.summary.title }}</h2>
                  <p>
                    事项 {{ selected.summary.reviewCaseId }} · {{ selected.summary.resourceType }}
                    {{ selected.summary.resourceId }} · 版本
                    {{ selected.summary.resourceVersion || '—' }}
                  </p>
                </div>
                <span class="status-pill" :class="selected.summary.workflowStatus.toLowerCase()">
                  {{ statusLabel(selected.summary.workflowStatus) }}
                </span>
              </div>
              <div class="platform-facts">
                <div>
                  <span>事实状态</span>
                  <strong>{{ selected.summary.factStatus }}</strong>
                </div>
                <div>
                  <span>创建人</span>
                  <strong>{{ selected.summary.createdBy }}</strong>
                </div>
                <div>
                  <span>创建时间</span>
                  <strong>{{ selected.summary.createdAt }}</strong>
                </div>
                <div>
                  <span>更新时间</span>
                  <strong>{{ selected.summary.updatedAt }}</strong>
                </div>
              </div>
              <div class="platform-section">
                <div class="section-kicker">追加式处理记录</div>
                <article v-for="entry in selected.entries" :key="entry.entryId" class="audit-entry">
                  <strong>{{ entry.entryType }} · {{ entry.actorId }}</strong>
                  <small>{{ entry.createdAt }} · {{ entry.factStatus || '—' }}</small>
                  <p>{{ entry.content }}</p>
                </article>
              </div>
              <div v-if="selected.activeClaim" class="claim-note">
                当前认领：{{ selected.activeClaim.stage }} · 账号
                {{ selected.activeClaim.actorId }} · {{ selected.activeClaim.status }}
              </div>
              <div class="platform-section finding-actions">
                <div
                  v-if="
                    selected.summary.workflowStatus === 'WAITING_RESPONSE' ||
                    selected.summary.workflowStatus === 'RESPONSE_CLAIMED'
                  "
                >
                  <div class="section-kicker">回应阶段</div>
                  <button
                    v-if="selected.summary.workflowStatus === 'WAITING_RESPONSE'"
                    class="public-outline"
                    type="button"
                    @click="claim('RESPONSE')"
                  >
                    原子认领回应
                  </button>
                  <textarea
                    v-model="responseText"
                    placeholder="回应事实、补充说明或处理依据"
                  ></textarea>
                  <button class="public-primary" type="button" @click="respond">
                    追加回应并进入复核
                  </button>
                </div>
                <div
                  v-if="
                    selected.summary.workflowStatus === 'WAITING_REVIEW' ||
                    selected.summary.workflowStatus === 'REVIEW_CLAIMED'
                  "
                >
                  <div class="section-kicker">独立复核阶段</div>
                  <button
                    v-if="selected.summary.workflowStatus === 'WAITING_REVIEW'"
                    class="public-outline"
                    type="button"
                    @click="claim('REVIEW')"
                  >
                    原子认领复核
                  </button>
                  <select v-model="reviewStatus">
                    <option value="SUPPORTED">支持发现</option>
                    <option value="NOT_SUPPORTED">不支持发现</option>
                  </select>
                  <textarea v-model="reviewText" placeholder="填写独立复核依据和结论"></textarea>
                  <button class="public-primary" type="button" @click="review">
                    提交复核并关闭
                  </button>
                </div>
                <div v-if="selected.summary.workflowStatus === 'CLOSED'" class="platform-empty">
                  该发现已关闭，历史记录保持只读。
                </div>
              </div>
            </template>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
