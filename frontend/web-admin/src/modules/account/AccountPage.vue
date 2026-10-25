<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { authApi, type AccountSummary, type OrganizationEntry } from './api/auth'
import { identityApi } from '../onboarding/api/identity'
import type { MembershipRole, OrganizationForm, OrganizationRole } from '../onboarding/api/types'
import { logoutSession, setOrganizationId, setToken } from '../../platform/session/session'
import PublicHeader from '../../shared/ui/PublicHeader.vue'
import OrganizationApplicationForm from './OrganizationApplicationForm.vue'
import OrganizationProgress from './OrganizationProgress.vue'
import AccountOrganizationActions from './AccountOrganizationActions.vue'
import JoinOrganizationForm from './JoinOrganizationForm.vue'
import AccountSummaryNav from './AccountSummaryNav.vue'
import AccountStatusNote from './AccountStatusNote.vue'

const emit = defineEmits<{ navigate: [path: string] }>()

const account = ref<AccountSummary>()
const organizations = ref<OrganizationEntry[]>([])
const loading = ref(true)
const actionBusy = ref(false)
const error = ref('')
const createFormVisible = ref(false)
const joinFormVisible = ref(false)
const editingOrganizationId = ref<string>()
const editingOrganizationVersion = ref('')
const correctionReason = ref('')
const organizationRole = ref<OrganizationRole>('EMPLOYER')
const requestedRole = ref<MembershipRole>('DEMAND_EDITOR')
const organizationIdInput = ref('')
const form = ref<OrganizationForm>({
  organizationRole: 'EMPLOYER',
  legalName: '',
  registrationId: '',
  authorizationReference: '',
  qualificationReference: '',
})

async function loadAccount() {
  loading.value = true
  error.value = ''
  try {
    const [currentAccount, entries] = await Promise.all([authApi.me(), authApi.organizations()])
    account.value = currentAccount
    organizations.value = entries
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '账号信息暂时无法读取。'
  } finally {
    loading.value = false
  }
}

async function createOrganization() {
  error.value = ''
  actionBusy.value = true
  try {
    await identityApi.createOrganization({
      ...form.value,
      organizationRole: organizationRole.value,
    })
    createFormVisible.value = false
    form.value = {
      organizationRole: 'EMPLOYER',
      legalName: '',
      registrationId: '',
      authorizationReference: '',
      qualificationReference: '',
    }
    await loadAccount()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '组织申请暂时无法创建。'
  } finally {
    actionBusy.value = false
  }
}

async function submitOrganization(organizationId: string) {
  error.value = ''
  actionBusy.value = true
  try {
    await identityApi.submitOrganization(organizationId)
    await loadAccount()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '组织申请暂时无法提交。'
  } finally {
    actionBusy.value = false
  }
}

async function editOrganizationCorrection(organizationId: string) {
  error.value = ''
  actionBusy.value = true
  try {
    const organization = await identityApi.getOrganization(organizationId)
    if (organization.status !== 'NEEDS_CORRECTION') {
      throw new Error('组织状态已变化，请刷新账号中心后重试。')
    }
    editingOrganizationId.value = organizationId
    editingOrganizationVersion.value = organization.version
    correctionReason.value = organization.reviewReason || '平台尚未提供补正说明。'
    organizationRole.value = organization.organizationRole
    form.value = {
      organizationRole: organization.organizationRole,
      legalName: organization.legalName,
      registrationId: organization.registrationId,
      authorizationReference: organization.authorizationReference,
      qualificationReference: organization.qualificationReference || '',
    }
    createFormVisible.value = false
    joinFormVisible.value = false
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '组织补正资料暂时无法读取。'
  } finally {
    actionBusy.value = false
  }
}

async function saveOrganizationCorrection() {
  const organizationId = editingOrganizationId.value
  if (!organizationId) {
    return
  }
  error.value = ''
  actionBusy.value = true
  try {
    await identityApi.updateOrganization(organizationId, {
      ...form.value,
      expectedVersion: editingOrganizationVersion.value,
    })
    await identityApi.submitOrganization(organizationId)
    editingOrganizationId.value = undefined
    correctionReason.value = ''
    await loadAccount()
  } catch (cause) {
    error.value =
      cause instanceof Error
        ? `补正提交未完成：${cause.message}。如资料已保存，请刷新后再次提交。`
        : '补正提交暂时无法完成，请刷新状态后重试。'
    await loadAccount()
  } finally {
    actionBusy.value = false
  }
}

/** 处理组织申请表单提交，区分新建草稿与审核补正闭环。 */
async function submitOrganizationForm() {
  if (editingOrganizationId.value) {
    await saveOrganizationCorrection()
  } else {
    await createOrganization()
  }
}

/** 关闭当前组织资料编辑并清理暂存审核意见。 */
function cancelOrganizationForm() {
  editingOrganizationId.value = undefined
  correctionReason.value = ''
  createFormVisible.value = false
}

/** 打开用工方或供应商组织申请表单。 */
function toggleOrganizationForm(role: OrganizationRole) {
  if (editingOrganizationId.value) {
    return
  }
  organizationRole.value = role
  form.value.organizationRole = role
  createFormVisible.value = !createFormVisible.value
  joinFormVisible.value = false
}

/** 切换加入已有组织表单并关闭其他组织申请表单。 */
function toggleJoinForm() {
  joinFormVisible.value = !joinFormVisible.value
  createFormVisible.value = false
}

/** 关闭加入组织申请表单。 */
function cancelJoinForm() {
  joinFormVisible.value = false
}

async function joinOrganization() {
  const id = organizationIdInput.value.trim()
  if (!/^\d+$/.test(id)) {
    error.value = '请输入组织编号。'
    return
  }
  error.value = ''
  actionBusy.value = true
  try {
    await identityApi.joinOrganization(id, requestedRole.value)
    joinFormVisible.value = false
    organizationIdInput.value = ''
    await loadAccount()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加入申请暂时无法提交。'
  } finally {
    actionBusy.value = false
  }
}

async function enterOrganization(entry: OrganizationEntry) {
  error.value = ''
  actionBusy.value = true
  try {
    const session = await authApi.selectOrganization(entry.organizationId)
    setToken(session.accessToken)
    setOrganizationId(entry.organizationId)
    emit('navigate', `/workspace/${entry.organizationId}`)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '当前账号暂不能进入该组织空间。'
    await loadAccount()
  } finally {
    actionBusy.value = false
  }
}

async function logout() {
  await logoutSession()
  emit('navigate', '/')
}

onMounted(loadAccount)
</script>

<template>
  <div class="public-site account-site">
    <PublicHeader
      active="account"
      @navigate="emit('navigate', $event)"
      @account="emit('navigate', '/account')"
    />
    <main class="public-main account-main">
      <div class="account-intro">
        <span class="eyebrow">个人账号</span>
        <h1>先完成组织认证，再进入业务空间</h1>
        <p>登录只代表你拥有个人账号。组织身份、成员关系和具体操作权限需要分别审核和授权。</p>
      </div>
      <div class="account-layout">
        <AccountSummaryNav
          :account="account"
          @navigate="emit('navigate', $event)"
          @logout="logout"
        />
        <section class="account-content">
          <div v-if="loading" class="account-status-bar">
            <strong>正在读取账号和组织状态…</strong>
          </div>
          <div v-else class="account-status-bar">
            <div>
              <span class="status-label">个人账号状态</span>
              <strong>
                {{ account?.status === 'ACTIVE' ? '账号已验证并启用' : '账号状态需要处理' }}
              </strong>
            </div>
            <span
              class="status-pill"
              :class="account?.status === 'ACTIVE' ? 'approved' : 'neutral'"
            >
              {{ account?.status || '未知' }}
            </span>
          </div>

          <AccountOrganizationActions @create="toggleOrganizationForm" @join="toggleJoinForm" />

          <OrganizationApplicationForm
            v-if="createFormVisible || editingOrganizationId"
            :form="form"
            :organization-role="organizationRole"
            :is-correction="Boolean(editingOrganizationId)"
            :correction-reason="correctionReason"
            :action-busy="actionBusy"
            @update:form="form = $event"
            @submit="submitOrganizationForm"
            @cancel="cancelOrganizationForm"
          />

          <JoinOrganizationForm
            v-if="joinFormVisible"
            :organization-id="organizationIdInput"
            :requested-role="requestedRole"
            :action-busy="actionBusy"
            @update:organization-id="organizationIdInput = $event"
            @update:requested-role="requestedRole = $event"
            @submit="joinOrganization"
            @cancel="cancelJoinForm"
          />

          <p v-if="error" class="auth-message account-error" role="alert">{{ error }}</p>

          <OrganizationProgress
            :organizations="organizations"
            :action-busy="actionBusy"
            @enter="enterOrganization"
            @submit="submitOrganization"
            @correct="editOrganizationCorrection"
          />

          <AccountStatusNote />
        </section>
      </div>
    </main>
  </div>
</template>
