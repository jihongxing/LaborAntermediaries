<!-- eslint-disable max-len, max-lines, vue/max-attributes-per-line -->
<script setup lang="ts">
/* eslint-disable max-lines */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import { platformApi, type PlatformAuditEvent } from './api/platform'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const events = ref<PlatformAuditEvent[]>([])
const nextCursor = ref('')
const queryId = ref('')
const filters = reactive({
  organizationId: '',
  reviewCaseId: '',
  resourceId: '',
  resourceVersion: '',
  eventActorId: '',
  actorRole: '',
  action: '',
  outcome: '',
  fromAt: '',
  toAt: '',
  purposeCode: 'AUDIT_REVIEW',
})
const finding = reactive({
  reviewCaseId: '',
  resourceVersion: '',
  eventId: '',
  title: '',
  description: '',
})
const creating = ref(false)

function numberOrUndefined(value: string) {
  return value.trim() ? Number(value.trim()) : undefined
}

function queryParams(beforeId?: string) {
  return {
    organizationId: filters.organizationId || undefined,
    reviewCaseId: filters.reviewCaseId || undefined,
    resourceId: filters.resourceId || undefined,
    resourceVersion: numberOrUndefined(filters.resourceVersion),
    eventActorId: filters.eventActorId || undefined,
    actorRole: filters.actorRole || undefined,
    action: filters.action || undefined,
    outcome: filters.outcome || undefined,
    fromAt: filters.fromAt ? new Date(filters.fromAt).toISOString() : undefined,
    toAt: filters.toAt ? new Date(filters.toAt).toISOString() : undefined,
    beforeId,
    limit: 50,
    purposeCode: filters.purposeCode,
  }
}

async function runQuery(beforeId?: string) {
  loading.value = true
  error.value = ''
  try {
    const result = await platformApi.auditQuery(queryParams(beforeId))
    queryId.value = result.queryId
    events.value = beforeId ? [...events.value, ...result.events] : result.events
    nextCursor.value = result.nextCursor || ''
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '监察查询暂时无法读取。'
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  events.value = []
  nextCursor.value = ''
  void runQuery()
}

async function createFinding() {
  if (!finding.reviewCaseId.trim() || !finding.title.trim() || !finding.description.trim()) {
    error.value = '创建发现需要填写审核事项、标题和事实描述。'
    return
  }
  creating.value = true
  error.value = ''
  try {
    const created = await platformApi.createFinding({
      reviewCaseId: finding.reviewCaseId.trim(),
      resourceVersion: finding.resourceVersion.trim() || undefined,
      eventId: finding.eventId.trim() || undefined,
      title: finding.title.trim(),
      description: finding.description.trim(),
    })
    router.push(`/platform/audit/findings?finding=${created.findingId}`)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '监察发现未创建。'
  } finally {
    creating.value = false
  }
}

function formatTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value)
      )
    : '—'
}

onMounted(() => runQuery())
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
        <small>跨组织只读查询，发现处理追加留痕</small>
      </div>
      <nav class="workspace-nav" aria-label="平台监察导航">
        <button class="active" type="button">
          <span class="nav-marker"></span>
          监察查询
        </button>
        <button type="button" @click="router.push('/platform/audit/coverage')">
          <span class="nav-marker"></span>
          审计覆盖
        </button>
        <button type="button" @click="router.push('/platform/audit/findings')">
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
          监察查询
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
            <span class="eyebrow">G9 监察查询</span>
            <h1>按事项、事件和组织重建处理链</h1>
            <p>查询范围、用途和读取动作都会产生审计记录；详情页只展示服务端授权后的内部时间线。</p>
          </div>
          <span class="status-pill approved">{{ events.length }} 条事件</span>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>监察工作台提示</strong>
          <p>{{ error }}</p>
        </div>
        <section class="audit-filter-grid panel-surface">
          <label>
            组织编号
            <input v-model="filters.organizationId" inputmode="numeric" placeholder="可选" />
          </label>
          <label>
            审核事项
            <input v-model="filters.reviewCaseId" placeholder="reviewCaseId" />
          </label>
          <label>
            资源编号
            <input v-model="filters.resourceId" placeholder="可选" />
          </label>
          <label>
            资源版本
            <input v-model="filters.resourceVersion" inputmode="numeric" placeholder="可选" />
          </label>
          <label>
            操作者账号
            <input v-model="filters.eventActorId" inputmode="numeric" placeholder="可选" />
          </label>
          <label>
            平台岗位
            <input v-model="filters.actorRole" placeholder="如 DEMAND_REVIEWER" />
          </label>
          <label>
            动作码
            <input v-model="filters.action" placeholder="如 demand:review" />
          </label>
          <label>
            结果
            <select v-model="filters.outcome">
              <option value="">全部</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="DENIED">DENIED</option>
              <option value="FAILED">FAILED</option>
              <option value="CONFLICT">CONFLICT</option>
            </select>
          </label>
          <label>
            开始时间
            <input v-model="filters.fromAt" type="datetime-local" />
          </label>
          <label>
            结束时间
            <input v-model="filters.toAt" type="datetime-local" />
          </label>
          <label class="wide">
            访问用途码
            <input v-model="filters.purposeCode" maxlength="64" />
          </label>
          <div class="audit-filter-actions">
            <button
              class="public-primary"
              type="button"
              :disabled="loading"
              @click="resetQuery"
            >
              执行查询
            </button>
            <button
              class="public-outline"
              type="button"
              @click="router.push('/platform/audit/coverage')"
            >
              查看覆盖目录
            </button>
          </div>
        </section>
        <section class="audit-grid">
          <div class="panel-surface audit-results">
            <div class="section-kicker">
              查询结果
              <span v-if="queryId">· 查询 {{ queryId }}</span>
            </div>
            <div v-if="loading" class="platform-empty">正在读取审计目录…</div>
            <div v-else-if="!events.length" class="platform-empty">没有符合条件的事件。</div>
            <div v-else class="audit-table-wrap">
              <table class="audit-table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>动作 / 结果</th>
                    <th>组织 / 资源</th>
                    <th>操作者</th>
                    <th>事项</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="event in events" :key="event.eventId">
                    <td>{{ formatTime(event.occurredAt) }}</td>
                    <td>
                      <strong>{{ event.action }}</strong>
                      <small>{{ event.outcome }} · {{ event.reasonCode || '—' }}</small>
                    </td>
                    <td>
                      {{ event.organizationId || '平台' }}
                      <small>
                        {{ event.resourceType }} / {{ event.resourceId }} · v{{
                          event.resourceVersion || '—'
                        }}
                      </small>
                    </td>
                    <td>
                      {{ event.actorId || event.actorType }}
                      <small>{{ event.actorRole || '—' }}</small>
                    </td>
                    <td>
                      <button
                        v-if="event.reviewCaseId"
                        class="text-link"
                        type="button"
                        @click="router.push(`/platform/audit/requests/${event.reviewCaseId}`)"
                      >
                        {{ event.reviewCaseId }}
                      </button>
                      <span v-else>—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <button
              v-if="nextCursor"
              class="public-outline audit-more"
              type="button"
              :disabled="loading"
              @click="runQuery(nextCursor)"
            >
              加载更早事件
            </button>
          </div>
          <div class="panel-surface audit-create">
            <div class="section-kicker">登记监察发现</div>
            <p class="muted-copy">
              发现必须关联已存在的审核事项；创建后进入回应和独立复核队列，不改变原业务状态。
            </p>
            <label>
              审核事项编号
              <input v-model="finding.reviewCaseId" placeholder="必填" />
            </label>
            <label>
              业务版本
              <input v-model="finding.resourceVersion" placeholder="可选" />
            </label>
            <label>
              关联事件编号
              <input v-model="finding.eventId" placeholder="可选" />
            </label>
            <label>
              发现标题
              <input v-model="finding.title" maxlength="200" />
            </label>
            <label>
              事实描述
              <textarea v-model="finding.description" maxlength="4000"></textarea>
            </label>
            <button
              class="public-primary"
              type="button"
              :disabled="creating"
              @click="createFinding"
            >
              {{ creating ? '保存中…' : '创建待确认发现' }}
            </button>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
