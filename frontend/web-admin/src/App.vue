<script setup lang="ts">
/* eslint-disable max-lines */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from './platform/http/client'
import { useSession } from './platform/session/session'
import { demandApi } from './modules/onboarding/api/demand'
import { identityApi } from './modules/onboarding/api/identity'
import OrganizationPanel from './modules/onboarding/components/OrganizationPanel.vue'
import MembershipPanel from './modules/onboarding/components/MembershipPanel.vue'
import DemandPanel from './modules/onboarding/components/DemandPanel.vue'
import PlatformQueuePanel from './modules/onboarding/components/PlatformQueuePanel.vue'
import OpenDemandPanel from './modules/onboarding/components/OpenDemandPanel.vue'
import BiddingPanel from './modules/onboarding/components/BiddingPanel.vue'
import OrganizationDialog from './modules/onboarding/components/OrganizationDialog.vue'
import DemandDialog from './modules/onboarding/components/DemandDialog.vue'
import ReviewDialog from './modules/onboarding/components/ReviewDialog.vue'
import WorkspaceHeader from './shared/ui/WorkspaceHeader.vue'
import PublicPortal from './modules/public/PublicPortal.vue'
import AccountPage from './modules/account/AccountPage.vue'
import WorkspaceOverview from './modules/workspace/WorkspaceOverview.vue'
import type {
  Demand,
  DemandForm,
  MembershipRequest,
  MembershipRole,
  Organization,
  OrganizationForm,
} from './modules/onboarding/api/types'
import { toLocalDateTime } from './shared/lib/format'
import { emptyDemandForm, emptyOrganizationForm } from './modules/onboarding/model/forms'

const { token, organizationId, setOrganizationId, clear: clearSession } = useSession()
const activeOrg = ref<Organization>()
const organizations = ref<Organization[]>([])
const organizationMembers = ref<MembershipRequest[]>([])
const demands = ref<Demand[]>([])
const platformDemands = ref<Demand[]>([])
const openDemands = ref<Demand[]>([])
const loading = ref(false)
const orgFormVisible = ref(false)
const editingOrganization = ref(false)
const demandFormVisible = ref(false)
const editingDemand = ref<Demand>()
const reviewTarget = ref<{ type: 'organization' | 'demand'; item: Organization | Demand }>()
const reviewVisible = ref(false)
const reviewDecision = ref('APPROVE')
const reviewReason = ref('')
const orgForm = reactive<OrganizationForm>(emptyOrganizationForm())
const demandForm = reactive<DemandForm>(emptyDemandForm())
const selectedRole = ref<MembershipRole>('DEMAND_EDITOR')

const routePath = ref(window.location.pathname)
const currentRoute = computed(() => {
  if (routePath.value.startsWith('/__dev/console')) {
    return 'dev-console'
  }
  if (routePath.value === '/account') {
    return 'account'
  }
  if (routePath.value.startsWith('/workspace/employer')) {
    return 'employer-workspace'
  }
  if (routePath.value.startsWith('/workspace/supplier')) {
    return 'supplier-workspace'
  }
  if (routePath.value.startsWith('/demands/')) {
    return 'public-demand'
  }
  return 'public-home'
})
const publicDemandId = computed(() =>
  currentRoute.value === 'public-demand' ? routePath.value.split('/')[2] : undefined
)

function navigate(path: string) {
  const [pathname, hash] = path.split('#')
  window.history.pushState({}, '', path)
  routePath.value = pathname || '/'
  window.scrollTo({ top: 0, behavior: 'smooth' })
  if (hash) {
    window.setTimeout(() => document.getElementById(hash)?.scrollIntoView({ behavior: 'smooth' }), 0)
  }
}

function handlePopState() {
  routePath.value = window.location.pathname
}

onMounted(() => window.addEventListener('popstate', handlePopState))
onBeforeUnmount(() => window.removeEventListener('popstate', handlePopState))

const isConnected = computed(() => Boolean(token.value && organizationId.value))
const organizationLabel = computed(() => activeOrg.value?.legalName || '未选择组织')

function notifyError(error: unknown) {
  const message =
    error instanceof ApiError && error.requestId
      ? `${error.message}（请求 ${error.requestId}）`
      : error instanceof Error
        ? error.message
        : '操作失败'
  ElMessage.error(message)
}

async function refreshWorkspace() {
  if (!isConnected.value) {
    ElMessage.warning('请先输入外部身份令牌和组织编号')
    return
  }
  loading.value = true
  try {
    activeOrg.value = await identityApi.getOrganization(organizationId.value)
    demands.value = await demandApi.list(organizationId.value)
    await Promise.allSettled([loadMemberships(), loadPlatformQueues(), loadOpenDemands()])
    ElMessage.success('工作台已刷新')
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function loadMemberships() {
  organizationMembers.value = await identityApi.pendingMemberships(organizationId.value)
}
async function loadPlatformQueues() {
  await Promise.allSettled([
    demandApi.pendingReviews().then(items => {
      platformDemands.value = items
    }),
    identityApi.pendingOrganizations().then(items => {
      organizations.value = items
    }),
  ])
}
async function loadOpenDemands() {
  openDemands.value = await demandApi.openForBid()
}

function resetOrgForm() {
  Object.assign(orgForm, emptyOrganizationForm())
  editingOrganization.value = false
  orgFormVisible.value = true
}
function editOrg() {
  if (activeOrg.value) {
    Object.assign(orgForm, {
      organizationRole: activeOrg.value.organizationRole,
      legalName: activeOrg.value.legalName,
      registrationId: activeOrg.value.registrationId,
      authorizationReference: '',
      qualificationReference: '',
    })
  }
  editingOrganization.value = true
  orgFormVisible.value = true
}
function resetDemandForm() {
  Object.assign(demandForm, emptyDemandForm())
  editingDemand.value = undefined
  demandFormVisible.value = true
}
function editDemand(item: Demand) {
  Object.assign(demandForm, item, {
    startsAt: toLocalDateTime(item.startsAt),
    endsAt: toLocalDateTime(item.endsAt),
    bidDeadline: toLocalDateTime(item.bidDeadline),
  })
  editingDemand.value = item
  demandFormVisible.value = true
}

async function saveOrganization(form: OrganizationForm) {
  try {
    activeOrg.value =
      editingOrganization.value && activeOrg.value?.id
        ? await identityApi.updateOrganization(activeOrg.value.id, {
            ...form,
            expectedVersion: activeOrg.value.version,
          })
        : await identityApi.createOrganization(form)
    setOrganizationId(activeOrg.value.id)
    editingOrganization.value = false
    orgFormVisible.value = false
    ElMessage.success('组织资料已保存')
    await refreshWorkspace()
  } catch (error) {
    notifyError(error)
  }
}
async function submitOrganization() {
  if (!activeOrg.value) {
    return
  }
  try {
    activeOrg.value = await identityApi.submitOrganization(activeOrg.value.id)
    ElMessage.success('组织申请已提交')
  } catch (error) {
    notifyError(error)
  }
}
async function joinOrganization() {
  try {
    await identityApi.joinOrganization(organizationId.value, selectedRole.value)
    ElMessage.success('加入申请已提交，后续由组织管理员审批')
  } catch (error) {
    notifyError(error)
  }
}
async function decideMembership(requestId: string, decision: string) {
  try {
    await identityApi.decideMembership(organizationId.value, requestId, decision)
    ElMessage.success('成员申请已处理')
    await loadMemberships()
  } catch (error) {
    notifyError(error)
  }
}
async function saveDemand(form: DemandForm) {
  try {
    if (editingDemand.value) {
      await demandApi.update(editingDemand.value, form)
    } else {
      await demandApi.create(organizationId.value, form)
    }
    demandFormVisible.value = false
    ElMessage.success('需求版本已保存')
    await refreshWorkspace()
  } catch (error) {
    notifyError(error)
  }
}
async function submitDemand(item: Demand) {
  try {
    await demandApi.submit(item.id)
    ElMessage.success('需求已送审')
    await refreshWorkspace()
  } catch (error) {
    notifyError(error)
  }
}
async function publishDemand(item: Demand) {
  try {
    await demandApi.publish(item.id)
    ElMessage.success('需求已发布')
    await refreshWorkspace()
  } catch (error) {
    notifyError(error)
  }
}
function openReview(type: 'organization' | 'demand', item: Organization | Demand) {
  reviewTarget.value = { type, item }
  reviewDecision.value = 'APPROVE'
  reviewReason.value = ''
  reviewVisible.value = true
}
async function confirmReview() {
  if (!reviewTarget.value) {
    return
  }
  try {
    const { type, item } = reviewTarget.value
    if (type === 'organization') {
      await identityApi.reviewOrganization(item.id, reviewDecision.value, reviewReason.value)
    } else {
      await demandApi.review(item.id, reviewDecision.value, reviewReason.value)
    }
    reviewTarget.value = undefined
    reviewVisible.value = false
    ElMessage.success('审核结论已记录')
    await refreshWorkspace()
  } catch (error) {
    notifyError(error)
  }
}
async function confirmClear() {
  await ElMessageBox.confirm('仅清除本页内存中的令牌和组织上下文。', '退出当前工作区', {
    type: 'warning',
  })
  clearSession()
  activeOrg.value = undefined
  demands.value = []
}
</script>

<template>
  <PublicPortal
    v-if="currentRoute === 'public-home' || currentRoute === 'public-demand'"
    :detail-id="publicDemandId"
    @navigate="navigate"
  />
  <AccountPage v-else-if="currentRoute === 'account'" @navigate="navigate" />
  <WorkspaceOverview
    v-else-if="currentRoute === 'employer-workspace' || currentRoute === 'supplier-workspace'"
    :role="currentRoute === 'employer-workspace' ? 'employer' : 'supplier'"
    @navigate="navigate"
  />
  <div v-else class="app-shell">
    <WorkspaceHeader
      v-model:token="token"
      v-model:organization-id="organizationId"
      :organization-name="organizationLabel"
      :loading="loading"
      @refresh="refreshWorkspace"
      @clear="confirmClear"
    />
    <main class="main">
      <div class="workspace-grid">
        <OrganizationPanel
          :organization="activeOrg"
          @create="resetOrgForm"
          @edit="editOrg"
          @submit="submitOrganization"
        />
        <MembershipPanel
          v-model="selectedRole"
          :requests="organizationMembers"
          @join="joinOrganization"
          @decide="decideMembership"
        />
        <DemandPanel
          class="span-2"
          :demands="demands"
          :organization="activeOrg"
          @create="resetDemandForm"
          @edit="editDemand"
          @submit="submitDemand"
          @publish="publishDemand"
        />
        <PlatformQueuePanel
          :organizations="organizations"
          :demands="platformDemands"
          @refresh="loadPlatformQueues"
          @review="openReview"
        />
        <OpenDemandPanel :demands="openDemands" @refresh="loadOpenDemands" />
        <BiddingPanel :organization="activeOrg" :demands="demands" :open-demands="openDemands" />
      </div>
    </main>
    <OrganizationDialog
      v-model="orgFormVisible"
      :initial-value="orgForm"
      :editing="editingOrganization"
      @save="saveOrganization"
    />
    <DemandDialog
      v-model="demandFormVisible"
      :initial-value="demandForm"
      :editing="Boolean(editingDemand)"
      @save="saveDemand"
    />
    <ReviewDialog
      v-model="reviewVisible"
      v-model:decision="reviewDecision"
      v-model:reason="reviewReason"
      @save="confirmReview"
    />
  </div>
</template>
