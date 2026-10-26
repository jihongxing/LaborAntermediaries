<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import { useSession } from '../../platform/session/session'
import { platformApi, type PlatformTask } from './api/platform'

const router = useRouter()
const tasks = ref<PlatformTask[]>([])
const role = ref('')
const loading = ref(true)
const error = ref('')

const reviewQueues: Record<
  string,
  { title: string; path: string; type: PlatformTask['taskType'] }
> = {
  ORGANIZATION_REVIEWER: {
    title: '组织审核',
    path: '/platform/reviews/organizations',
    type: 'ORGANIZATION_REVIEW',
  },
  DEMAND_REVIEWER: { title: '需求审核', path: '/platform/reviews/demands', type: 'DEMAND_REVIEW' },
  INDEPENDENT_REVIEWER: {
    title: '异议复核',
    path: '/platform/reviews/appeals',
    type: 'APPEAL_REVIEW',
  },
}
const roleNames: Record<string, string> = {
  ORGANIZATION_REVIEWER: '组织审核员',
  DEMAND_REVIEWER: '需求审核员',
  INDEPENDENT_REVIEWER: '独立复核员',
  INDEPENDENT_AUDITOR: '独立监察员',
  AUTHORIZATION_ADMIN: '平台授权管理员',
  TASK_RECOVERY: '异常任务恢复员',
  BID_SUPERVISOR: '应标监督员',
}

function currentRole() {
  try {
    const payload = useSession().token.value.split('.')[1]
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const claims = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')))
    return claims.context_type === 'PLATFORM' && typeof claims.actor_role === 'string'
      ? claims.actor_role
      : ''
  } catch {
    return ''
  }
}

async function load() {
  loading.value = true
  error.value = ''
  role.value = currentRole()
  try {
    const queue = reviewQueues[role.value]
    tasks.value = queue ? await platformApi.queue(queue.type) : []
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '当前岗位任务暂时无法读取。'
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
        <strong>{{ roleNames[role] || '待确认' }}</strong>
        <small>权限由服务端实时校验</small>
      </div>
      <nav class="workspace-nav" aria-label="平台工作台导航">
        <button class="active" type="button">
          <span class="nav-marker"></span>
          工作总览
        </button>
        <button
          v-if="reviewQueues[role]"
          type="button"
          @click="router.push(reviewQueues[role].path)"
        >
          <span class="nav-marker"></span>
          {{ reviewQueues[role].title }}
        </button>
        <button
          v-if="role === 'INDEPENDENT_AUDITOR'"
          type="button"
          @click="router.push('/platform/audit')"
        >
          <span class="nav-marker"></span>
          监察查询
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
          工作总览
        </div>
        <button class="workspace-user" type="button" @click="router.push('/account')">
          平台岗位会话
        </button>
      </header>
      <div class="workspace-content platform-content">
        <section class="workspace-intro">
          <div>
            <span class="eyebrow">平台岗位入口</span>
            <h1>工作总览</h1>
            <p>仅显示当前账号岗位可访问的工作入口和任务摘要。</p>
          </div>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>工作台提示</strong>
          <p>{{ error }}</p>
          <button class="public-outline" type="button" @click="load">重新加载</button>
        </div>
        <section v-else-if="loading" class="panel-surface platform-empty">
          正在读取当前岗位…
        </section>
        <section v-else-if="reviewQueues[role]" class="platform-home-grid">
          <div class="panel-surface platform-queue">
            <div class="section-kicker">{{ reviewQueues[role].title }}</div>
            <h2>当前队列 {{ tasks.length }} 项</h2>
            <p class="muted-copy">服务端最多返回前 100 项，数字是当前返回量，不代表历史累计量。</p>
            <button
              class="public-primary"
              type="button"
              @click="router.push(reviewQueues[role].path)"
            >
              进入{{ reviewQueues[role].title }}队列
            </button>
          </div>
          <div class="panel-surface platform-queue">
            <div class="section-kicker">任务摘要</div>
            <div v-if="!tasks.length" class="platform-empty">当前岗位没有待处理任务。</div>
            <div v-for="task in tasks.slice(0, 5)" :key="task.taskId" class="platform-home-task">
              <strong>事项 {{ task.reviewCaseId }} · 版本 {{ task.resourceVersion }}</strong>
              <span>
                {{
                  task.claimedByMe
                    ? '我已认领'
                    : task.status === 'CLAIMED'
                      ? '其他人员已认领'
                      : '待认领'
                }}
              </span>
            </div>
          </div>
        </section>
        <section v-else-if="role === 'INDEPENDENT_AUDITOR'" class="panel-surface platform-queue">
          <div class="section-kicker">独立监察</div>
          <h2>审计查询与事项历史</h2>
          <p class="muted-copy">按需检索已接入的审计事件，检查覆盖缺口并跟进监察发现。</p>
          <div class="appeal-actions">
            <button class="public-primary" type="button" @click="router.push('/platform/audit')">
              监察查询
            </button>
            <button
              class="public-outline"
              type="button"
              @click="router.push('/platform/audit/coverage')"
            >
              审计覆盖
            </button>
            <button
              class="public-outline"
              type="button"
              @click="router.push('/platform/audit/findings')"
            >
              监察发现
            </button>
          </div>
        </section>
        <section v-else class="panel-surface platform-empty">
          当前岗位没有首期 Web
          办理页面。岗位授权、异常恢复与应标监督仍按已批准的受控服务端流程执行。
        </section>
      </div>
    </main>
  </div>
</template>
