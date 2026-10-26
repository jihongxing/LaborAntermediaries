<!-- eslint-disable max-len, vue/max-attributes-per-line -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import { platformApi, type ReviewHistory } from './api/platform'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const error = ref('')
const history = ref<ReviewHistory>()

function formatTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value)
      )
    : '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const reviewCaseId = String(route.params.reviewCaseId)
    history.value = await platformApi.auditHistory(reviewCaseId)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '事项历史暂时无法读取。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
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
        <small>跨组织事项完整时间线</small>
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
        <button type="button" @click="router.push('/platform/audit/findings')">
          <span class="nav-marker"></span>
          监察发现
        </button>
        <button class="active" type="button">
          <span class="nav-marker"></span>
          事项历史
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
          事项历史
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
            <span class="eyebrow">G9 事项历史</span>
            <h1>审核事项的完整内部时间线</h1>
            <p>
              此页面按稳定事项编号读取版本、决定、补正和全部关联审计事件，每次读取也会留下监察查询事件。
            </p>
          </div>
          <button class="public-outline" type="button" @click="router.push('/platform/audit')">
            返回监察查询
          </button>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>事项历史提示</strong>
          <p>{{ error }}</p>
          <button class="public-outline" type="button" @click="load">重新加载</button>
        </div>
        <div v-else-if="loading" class="panel-surface platform-empty">正在读取事项历史…</div>
        <section v-else-if="history" class="audit-history-grid">
          <div class="panel-surface platform-detail">
            <div class="platform-detail-head">
              <div>
                <div class="section-kicker">事项 {{ history.reviewCaseId }}</div>
                <h2>{{ history.resourceType }} · 资源 {{ history.resourceId }}</h2>
                <p>
                  当前状态 {{ history.currentStatus }} · 当前版本 {{ history.currentVersion }} ·
                  历史覆盖 {{ history.historyCoverage }}
                </p>
              </div>
              <span class="status-pill approved">内部视图</span>
            </div>
            <div class="platform-section">
              <div class="section-kicker">版本与处理决定</div>
              <article
                v-for="version in history.versions"
                :key="version.resourceVersion"
                class="history-card"
              >
                <strong>
                  版本 {{ version.resourceVersion }} · {{ version.decision || '尚无决定' }}
                </strong>
                <small>
                  创建 {{ formatTime(version.createdAt) }} · 提交
                  {{ formatTime(version.submittedAt) }} · 决定 {{ formatTime(version.decisionAt) }}
                </small>
                <p v-if="version.publicReason">对申请方说明：{{ version.publicReason }}</p>
                <ul v-if="version.correctionItems.length">
                  <li v-for="item in version.correctionItems" :key="item.correctionItemId">
                    {{ item.itemCode }} · {{ item.title }}：{{ item.description }}；动作：{{
                      item.applicantAction
                    }}
                  </li>
                </ul>
              </article>
              <div v-if="!history.versions.length" class="platform-empty">
                没有可展示的版本记录。
              </div>
            </div>
            <div class="platform-section">
              <div class="section-kicker">事项审计时间线</div>
              <ol class="audit-timeline">
                <li v-for="event in history.events" :key="event.eventId">
                  <time>{{ formatTime(event.occurredAt) }}</time>
                  <strong>{{ event.action }} · {{ event.outcome }}</strong>
                  <small>
                    事件 {{ event.eventId }} · 账号
                    {{ event.actorId || event.actorRole || '系统' }} · 版本
                    {{ event.resourceVersion || '—' }} · 原因 {{ event.reasonCode || '—' }}
                  </small>
                </li>
              </ol>
              <div v-if="!history.events.length" class="platform-empty">
                当前事项没有关联审计事件。
              </div>
            </div>
          </div>
          <aside class="panel-surface audit-history-aside">
            <div class="section-kicker">待补正事项</div>
            <div v-if="!history.unresolvedItems.length" class="platform-empty">
              没有未结补正事项。
            </div>
            <ul v-else>
              <li v-for="item in history.unresolvedItems" :key="item">{{ item }}</li>
            </ul>
            <div class="platform-section">
              <div class="section-kicker">读取边界</div>
              <p class="muted-copy">
                页面仅展示监察岗位允许的字段；材料原件、联系方式和业务敏感正文仍受专门资源授权控制。
              </p>
            </div>
          </aside>
        </section>
      </div>
    </main>
  </div>
</template>
