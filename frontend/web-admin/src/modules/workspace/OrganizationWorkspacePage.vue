<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, type AccountSummary } from '../account/api/auth'
import { identityApi } from '../onboarding/api/identity'
import { demandApi } from '../onboarding/api/demand'
import type { ApplicantReviewHistory, Demand, Organization } from '../onboarding/api/types'
import { logoutSession } from '../../platform/session/session'
import { onSessionExpired } from '../../platform/http/client'

const route = useRoute()
const router = useRouter()
const organization = ref<Organization>()
const demands = ref<Demand[]>([])
const demandHistory = ref<ApplicantReviewHistory>()
const appealReason = ref('')
const appealNotice = ref('')
const appealBusy = ref(false)
const account = ref<AccountSummary>()
const loading = ref(true)
const error = ref('')

/** 刷新失效时返回公共登录页并保留原目标路径。 */
onSessionExpired(() => {
  void router.replace({ name: 'login', query: { redirect: route.fullPath } })
})

/**
 * 从后端重新读取当前令牌绑定的组织和账号状态。
 */
async function loadWorkspace() {
  loading.value = true
  error.value = ''
  try {
    const organizationId = String(route.params.organizationId)
    const [currentAccount, currentOrganization] = await Promise.all([
      authApi.me(),
      identityApi.getOrganization(organizationId),
    ])
    account.value = currentAccount
    organization.value = currentOrganization
    demands.value = currentOrganization.organizationRole === 'EMPLOYER'
      ? await demandApi.list(organizationId) : []
    if (currentOrganization.status !== 'APPROVED') {
      await router.replace('/account')
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '组织空间暂时无法读取。'
  } finally {
    loading.value = false
  }
}

/** 用工组织成员按服务端授权读取指定需求的公开审核版本。 */
async function openDemandHistory(demandId: string) {
  error.value = ''
  appealNotice.value = ''
  demandHistory.value = undefined
  appealReason.value = ''
  appealBusy.value = true
  try {
    demandHistory.value = await demandApi.reviewHistory(demandId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '需求审核历史暂时无法读取。'
  } finally {
    appealBusy.value = false
  }
}

/** 服务端再次校验申请人、决定与当前需求版本，避免旧版申诉。 */
async function submitDemandAppeal() {
  const history = demandHistory.value
  const decisionId = history?.versions.find(
    version => version.resourceVersion === history.currentVersion
  )?.decisionId
  if (!decisionId || !appealReason.value.trim()) {
    error.value = '请先查看当前审核决定并填写复核理由。'
    return
  }
  appealBusy.value = true
  error.value = ''
  try {
    const created = await identityApi.createAppeal(decisionId, appealReason.value.trim())
    appealNotice.value = `独立复核申请已提交，事项编号 ${created.appealCaseId}。原审核决定不会自动变更。`
    appealReason.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '独立复核申请未提交。'
  } finally {
    try {
      demandHistory.value = await demandApi.reviewHistory(history.resourceId)
    } catch {
      error.value ||= '复核进度暂时无法刷新，请稍后重试。'
    }
    appealBusy.value = false
  }
}

/** 结束服务端 Refresh Session 并清理本标签页访问令牌。 */
async function logout() {
  await logoutSession()
  await router.replace('/')
}

onMounted(loadWorkspace)
</script>

<template>
  <div class="workspace-product">
    <aside class="workspace-sidebar">
      <button class="workspace-brand" type="button" @click="router.push('/')">
        <span class="public-brand-mark">劳</span>
        <span>
          <strong>劳务供需平台</strong>
          <small>组织工作空间</small>
        </span>
      </button>
      <div v-if="organization" class="workspace-org-switcher">
        <span>当前组织</span>
        <strong>{{ organization.legalName }}</strong>
        <small>{{ organization.organizationRole === 'EMPLOYER' ? '用工方' : '劳务供应商' }}</small>
      </div>
      <nav class="workspace-nav" aria-label="组织空间导航">
        <button class="active" type="button">
          <span class="nav-marker"></span>
          空间总览
        </button>
        <button type="button" @click="router.push('/demands')">
          <span class="nav-marker"></span>
          公开需求大厅
        </button>
        <button type="button" @click="router.push('/account')">
          <span class="nav-marker"></span>
          组织与成员授权
        </button>
      </nav>
      <div class="workspace-sidebar-footer">
        <button type="button" @click="router.push('/account')">个人账号</button>
        <button type="button" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="workspace-main">
      <header class="workspace-topbar">
        <div class="workspace-breadcrumb">
          组织空间
          <span>/</span>
          空间总览
        </div>
        <div class="workspace-top-actions">
          <button class="workspace-user" type="button" @click="router.push('/account')">
            <span>{{ account?.displayName || '个人账号' }}</span>
          </button>
        </div>
      </header>

      <div class="workspace-content">
        <section class="workspace-intro">
          <div>
            <span class="eyebrow">
              {{
                organization?.organizationRole === 'EMPLOYER'
                  ? '用工方组织空间'
                  : '劳务供应商组织空间'
              }}
            </span>
            <h1>{{ loading ? '正在验证组织授权…' : organization?.legalName || '组织工作空间' }}</h1>
            <p>当前空间由服务端按登录账号、组织审核状态和成员岗位授权。</p>
          </div>
          <span v-if="organization" class="status-pill approved">组织已通过审核</span>
        </section>

        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>无法读取组织空间</strong>
          <p>{{ error }}</p>
          <button class="public-outline" type="button" @click="loadWorkspace">重新加载</button>
        </div>
        <section v-else-if="organization && !loading" class="workspace-access-card">
          <span class="action-icon green"><span class="nav-marker"></span></span>
          <div>
            <span class="eyebrow">组织身份已核验</span>
            <h2>你正在访问已授权的组织空间</h2>
            <p>
              组织编号 {{ organization.id }} ·
              {{ organization.organizationRole === 'EMPLOYER' ? '用工方' : '劳务供应商' }} ·
              成员权限由平台审核及组织管理员授权共同决定。
            </p>
          </div>
          <button class="public-outline" type="button" @click="router.push('/account')">
            查看账号和授权
          </button>
        </section>
        <section
          v-if="organization?.organizationRole === 'EMPLOYER' && !loading"
          class="workspace-access-card workspace-demand-history"
        >
          <div>
            <span class="eyebrow">需求审核</span>
            <h2>查看审核结果与申请复核</h2>
            <p v-if="!demands.length">当前组织还没有需求。</p>
            <article v-for="demand in demands" :key="demand.id">
              <strong>{{ demand.title }}</strong>
              <span> · v{{ demand.revision }} · {{ demand.status }}</span>
              <button
                v-if="!['DRAFT', 'PENDING_REVIEW'].includes(demand.status)"
                class="text-action"
                type="button"
                :disabled="appealBusy"
                @click="openDemandHistory(demand.id)"
              >查看审核历史</button>
            </article>
            <div v-if="demandHistory" class="review-history">
              <strong>
                需求 {{ demandHistory.resourceId }} · 当前版本 v{{ demandHistory.currentVersion }}
              </strong>
              <p v-for="version in demandHistory.versions" :key="version.resourceVersion">
                v{{ version.resourceVersion }} · {{ version.decision || '尚无决定' }}
                <span v-if="version.publicReason"> · {{ version.publicReason }}</span>
              </p>
              <p v-for="appeal in demandHistory.appeals" :key="appeal.appealCaseId">
                复核事项 {{ appeal.appealCaseId }} · {{ appeal.status }}
                <span v-if="appeal.outcome"> · {{ appeal.outcome }}</span>
                <span v-if="appeal.outcome"> · 原审核状态不会自动变更</span>
              </p>
              <p v-if="demandHistory.appeals.some(appeal => appeal.outcome)">
                复核意见类型是流程反馈；详细对外答复仍需按已批准披露流程处理。
              </p>
              <template
                v-if="demandHistory.versions.some(version =>
                  version.resourceVersion === demandHistory?.currentVersion &&
                  version.decisionId) &&
                  !demandHistory.appeals.some(appeal =>
                    appeal.resourceVersion === demandHistory?.currentVersion &&
                    ['PENDING', 'IN_REVIEW'].includes(appeal.status))"
              >
                <textarea
                  v-model="appealReason"
                  maxlength="2000"
                  placeholder="说明对当前审核决定有异议的事实与理由"
                  aria-label="需求独立复核理由"
                ></textarea>
                <button
                  class="public-outline"
                  type="button"
                  :disabled="appealBusy || !appealReason.trim()"
                  @click="submitDemandAppeal"
                >提交复核申请</button>
              </template>
              <p v-if="appealNotice" role="status">{{ appealNotice }}</p>
              <button
                class="text-action"
                type="button"
                :disabled="appealBusy"
                @click="openDemandHistory(demandHistory.resourceId)"
              >刷新复核进度</button>
            </div>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
