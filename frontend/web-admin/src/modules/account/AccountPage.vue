<script setup lang="ts">
/* eslint-disable max-lines */
import { onMounted, ref } from 'vue'
import { authApi, type AccountSummary, type OrganizationEntry } from './api/auth'
import { identityApi } from '../onboarding/api/identity'
import type {
  MembershipRole,
  OrganizationCorrectionItem,
  OrganizationForm,
  OrganizationRole,
  OrganizationReviewHistory,
} from '../onboarding/api/types'
import { logoutSession, setOrganizationId, setToken } from '../../platform/session/session'
import PublicHeader from '../../shared/ui/PublicHeader.vue'
import OrganizationApplicationForm from './OrganizationApplicationForm.vue'
import OrganizationProgress from './OrganizationProgress.vue'
import AccountOrganizationActions from './AccountOrganizationActions.vue'
import JoinOrganizationForm from './JoinOrganizationForm.vue'
import AccountSummaryNav from './AccountSummaryNav.vue'
import AccountStatusNote from './AccountStatusNote.vue'
import AccountRecoveryNotices from './AccountRecoveryNotices.vue'
import AccountPlatformEntry from './AccountPlatformEntry.vue'

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
const correctionItems = ref<OrganizationCorrectionItem[]>([])
const appealHistory = ref<OrganizationReviewHistory>()
const appealReason = ref('')
const appealNotice = ref('')
const appealBusy = ref(false)
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
    const history = await identityApi.organizationReviewHistory(organizationId)
    correctionItems.value = history.versions.find(
      version => version.resourceVersion === history.currentVersion
    )?.correctionItems ?? []
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

/** 只读取当前组织管理员获准查看的版本和对外决定。 */
async function openOrganizationAppeal(organizationId: string) {
  appealHistory.value = undefined
  appealNotice.value = ''
  appealReason.value = ''
  error.value = ''
  appealBusy.value = true
  try {
    appealHistory.value = await identityApi.organizationReviewHistory(organizationId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '审核历史暂时无法读取。'
  } finally {
    appealBusy.value = false
  }
}

/** 将当前有效审核决定与本人说明交给服务端校验并创建复核事项。 */
async function submitOrganizationAppeal() {
  const history = appealHistory.value
  const decisionId = history?.versions.find(
    version => version.resourceVersion === history.currentVersion
  )?.decisionId
  if (!decisionId || !appealReason.value.trim()) {
    error.value = '请选择当前审核决定并填写复核理由。'
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
      appealHistory.value = await identityApi.organizationReviewHistory(history.resourceId)
    } catch {
      error.value ||= '复核进度暂时无法刷新，请稍后重试。'
    }
    appealBusy.value = false
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
    correctionItems.value = []
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
  correctionItems.value = []
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

          <AccountRecoveryNotices />
          <AccountPlatformEntry @navigate="emit('navigate', $event)" />

          <AccountOrganizationActions @create="toggleOrganizationForm" @join="toggleJoinForm" />

          <OrganizationApplicationForm
            v-if="createFormVisible || editingOrganizationId"
            :form="form"
            :organization-role="organizationRole"
            :is-correction="Boolean(editingOrganizationId)"
            :correction-reason="correctionReason"
            :correction-items="correctionItems"
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
            @appeal="openOrganizationAppeal"
          />

          <section v-if="appealHistory" class="workspace-access-card account-appeal">
            <div>
              <span class="eyebrow">组织审核历史</span>
              <h2>对当前决定申请独立复核</h2>
              <p>组织编号 {{ appealHistory.resourceId }} · 当前版本 v{{ appealHistory.currentVersion }}</p>
              <p v-for="version in appealHistory.versions" :key="version.resourceVersion">
                v{{ version.resourceVersion }} · {{ version.decision || '尚无决定' }}
                <span v-if="version.publicReason"> · {{ version.publicReason }}</span>
              </p>
              <p v-for="appeal in appealHistory.appeals" :key="appeal.appealCaseId">
                复核事项 {{ appeal.appealCaseId }} · {{ appeal.status }}
                <span v-if="appeal.outcome"> · {{ appeal.outcome }}</span>
                <span v-if="appeal.outcome"> · 原审核状态不会自动变更</span>
              </p>
              <p v-if="appealHistory.appeals.some(appeal => appeal.outcome)">
                复核意见类型是流程反馈；详细对外答复仍需按已批准披露流程处理。
              </p>
              <textarea
                v-if="appealHistory.versions.some(version =>
                  version.resourceVersion === appealHistory?.currentVersion &&
                  version.decisionId) &&
                  !appealHistory.appeals.some(appeal =>
                    appeal.resourceVersion === appealHistory?.currentVersion &&
                    ['PENDING', 'IN_REVIEW'].includes(appeal.status))"
                v-model="appealReason"
                maxlength="2000"
                placeholder="说明对当前审核决定有异议的事实与理由"
                aria-label="独立复核理由"
              ></textarea>
              <button
                class="public-outline"
                type="button"
                :disabled="appealBusy || !appealReason.trim()"
                @click="submitOrganizationAppeal"
              >提交复核申请</button>
              <button
                class="text-action"
                type="button"
                :disabled="appealBusy"
                @click="openOrganizationAppeal(appealHistory.resourceId)"
              >刷新复核进度</button>
              <p v-if="appealNotice" role="status">{{ appealNotice }}</p>
            </div>
          </section>

          <AccountStatusNote />
        </section>
      </div>
    </main>
  </div>
</template>
