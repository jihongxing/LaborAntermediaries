<!-- eslint-disable max-len, vue/max-attributes-per-line -->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import { platformApi, type PlatformAuditCoverageItem } from './api/platform'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const items = ref<PlatformAuditCoverageItem[]>([])

const statusLabel: Record<string, string> = {
  CONNECTED: '已接入',
  PARTIAL: '部分接入',
  PENDING: '待接入',
  NOT_IMPLEMENTED: '未实现',
  DEGRADED: '降级',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await platformApi.auditCoverage()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '审计覆盖目录暂时无法读取。'
  } finally {
    loading.value = false
  }
}

function formatTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(value)
      )
    : '—'
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
        <small>只读查看接入状态和最近事件</small>
      </div>
      <nav class="workspace-nav" aria-label="平台监察导航">
        <button type="button" @click="router.push('/platform/audit')">
          <span class="nav-marker"></span>
          监察查询
        </button>
        <button class="active" type="button">
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
          审计覆盖
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
            <span class="eyebrow">G9 审计覆盖目录</span>
            <h1>确认哪些模块会留下可复核事件</h1>
            <p>目录状态来自服务端事件目录和最近实际事件。缺少真实测量来源时不伪造延迟。</p>
          </div>
          <span class="status-pill approved">
            {{ items.filter(item => item.status === 'CONNECTED').length }} 项已接入
          </span>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>覆盖目录提示</strong>
          <p>{{ error }}</p>
          <button class="public-outline" type="button" @click="load">重新加载</button>
        </div>
        <section v-else class="panel-surface audit-coverage">
          <div v-if="loading" class="platform-empty">正在读取覆盖目录…</div>
          <div v-else-if="!items.length" class="platform-empty">当前没有目录项。</div>
          <div v-else class="audit-table-wrap">
            <table class="audit-table">
              <thead>
                <tr>
                  <th>事件码</th>
                  <th>模块</th>
                  <th>说明</th>
                  <th>状态</th>
                  <th>最近事件</th>
                  <th>延迟</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in items" :key="item.eventCode">
                  <td>
                    <code>{{ item.eventCode }}</code>
                  </td>
                  <td>{{ item.moduleCode }}</td>
                  <td>{{ item.description }}</td>
                  <td>
                    <span class="status-pill" :class="item.status.toLowerCase()">
                      {{ statusLabel[item.status] || item.status }}
                    </span>
                  </td>
                  <td>
                    {{ item.lastEventId || '—' }}
                    <small>{{ formatTime(item.lastEventAt) }}</small>
                  </td>
                  <td>
                    {{ item.latencySeconds == null ? '未测量' : `${item.latencySeconds} 秒` }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
