<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, type AccountSummary } from '../account/api/auth'
import { identityApi } from '../onboarding/api/identity'
import type { Organization } from '../onboarding/api/types'
import { logoutSession } from '../../platform/session/session'
import { onSessionExpired } from '../../platform/http/client'

const route = useRoute()
const router = useRouter()
const organization = ref<Organization>()
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
    if (currentOrganization.status !== 'APPROVED') {
      await router.replace('/account')
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '组织空间暂时无法读取。'
  } finally {
    loading.value = false
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
      </div>
    </main>
  </div>
</template>
