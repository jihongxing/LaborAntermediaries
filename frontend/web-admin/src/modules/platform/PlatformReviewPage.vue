<!-- eslint-disable max-lines -->
<script setup lang="ts">
/* eslint-disable max-lines */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../../platform/http/client'
import { setToken } from '../../platform/session/session'
import { authApi } from '../account/api/auth'
import {
  platformApi,
  type OrganizationReviewSubject,
  type PlatformEvidence,
  type PlatformTask,
  type ReviewHistory,
  type ReviewDecision,
  type CorrectionItemRequest,
  type Verification,
  type VerificationDimension,
  type VerificationResult,
} from './api/platform'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const tasks = ref<PlatformTask[]>([])
const selectedTask = ref<PlatformTask>()
const subject = ref<OrganizationReviewSubject>()
const evidence = ref<PlatformEvidence[]>([])
const verifications = ref<Verification[]>([])
const history = ref<ReviewHistory>()
const previewUrl = ref('')
const decision = ref<ReviewDecision>('APPROVE')
const reason = ref('')
const saving = ref(false)
const correctionItems = ref<CorrectionItemRequest[]>([])
const verificationForm = ref({
  evidenceId: '',
  dimension: 'AUTHENTICITY' as VerificationDimension,
  result: 'NOT_VERIFIED' as VerificationResult,
  sourceLabel: '',
  sourceReference: '',
  basis: '',
})

function taskLabel(task: PlatformTask) {
  return `组织审核事项 ${task.reviewCaseId} · 版本 ${task.resourceVersion}`
}

function evidenceLabel(item: PlatformEvidence) {
  return `${item.category} · ${item.mediaType} · ${Math.round(item.sizeBytes / 1024)} KB`
}

function verificationEvidenceCategory(evidenceId: string) {
  return evidence.value.find(item => item.evidenceId === evidenceId)?.category || '材料'
}

async function enterPlatformContext() {
  const session = await authApi.selectPlatformContext()
  setToken(session.accessToken)
}

async function loadQueue() {
  loading.value = true
  error.value = ''
  try {
    await enterPlatformContext()
    tasks.value = await platformApi.queue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '平台审核工作台暂时无法读取。'
  } finally {
    loading.value = false
  }
}

async function openTask(task: PlatformTask) {
  error.value = ''
  try {
    if (!task.claimedByMe) {
      await platformApi.claim(task.taskId)
    }
    correctionItems.value = []
    decision.value = 'APPROVE'
    reason.value = ''
    selectedTask.value = { ...task, claimedByMe: true, status: 'CLAIMED' }
    const [nextSubject, nextEvidence, nextVerifications, details] = await Promise.all([
      platformApi.subject(task.taskId),
      platformApi.evidence(task.taskId),
      platformApi.verifications(task.taskId),
      platformApi.details(task.taskId),
    ])
    subject.value = nextSubject
    evidence.value = nextEvidence
    verifications.value = nextVerifications
    history.value = details.history
    verificationForm.value.evidenceId = nextEvidence[0]?.evidenceId || ''
    if (previewUrl.value) {URL.revokeObjectURL(previewUrl.value)}
    previewUrl.value = ''
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '任务已被其他人员占用或无法读取。'
    await loadQueue()
  }
}

async function previewEvidence(evidenceId: string) {
  if (!selectedTask.value) {
    return
  }
  try {
    const image = await platformApi.preview(selectedTask.value.taskId, evidenceId)
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
    }
    previewUrl.value = URL.createObjectURL(image)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '材料预览不可用。'
  }
}

function decisionValidationMessage(corrections: CorrectionItemRequest[]): string | undefined {
  if (decision.value !== 'APPROVE' && !reason.value.trim()) {
    return '补正或拒绝需要填写申请方可理解的理由。'
  }
  if (decision.value === 'NEEDS_CORRECTION') {
    if (!corrections.length) {
      return '要求补正时至少添加一项补正事项。'
    }
    if (corrections.some(item =>
      !item.itemCode || !item.title || !item.description || !item.applicantAction)) {
      return '请完整填写每项问题、问题说明和申请方需要完成的动作。'
    }
  }
  return undefined
}

async function submitDecision() {
  const task = selectedTask.value
  if (!task) {
    error.value = '请先认领审核任务。'
    return
  }
  const corrections = correctionItems.value.map(item => ({
    itemCode: item.itemCode.trim(),
    title: item.title.trim(),
    description: item.description.trim(),
    applicantAction: item.applicantAction.trim(),
  }))
  const validationMessage = decisionValidationMessage(corrections)
  if (validationMessage) {
    error.value = validationMessage
    return
  }
  saving.value = true
  error.value = ''
  try {
    await platformApi.decide(
      task.taskId,
      decision.value,
      reason.value.trim() || undefined,
      decision.value === 'NEEDS_CORRECTION' ? corrections : []
    )
    selectedTask.value = undefined
    subject.value = undefined
    evidence.value = []
    verifications.value = []
    history.value = undefined
    correctionItems.value = []
    decision.value = 'APPROVE'
    reason.value = ''
    await loadQueue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '审核决定未提交。'
  } finally {
    saving.value = false
  }
}

function addCorrectionItem() {
  const usedCodes = new Set(correctionItems.value.map(item => item.itemCode.trim()))
  let number = 1
  while (usedCodes.has(`ITEM_${number}`)) {
    number += 1
  }
  correctionItems.value.push({
    itemCode: `ITEM_${number}`,
    title: '',
    description: '',
    applicantAction: '',
  })
}

function removeCorrectionItem(index: number) {
  correctionItems.value.splice(index, 1)
}

async function addVerification() {
  if (!selectedTask.value || !verificationForm.value.evidenceId) {
    error.value = '请选择要核验的材料。'
    return
  }
  try {
    const record = await platformApi.recordVerification(
      selectedTask.value.taskId,
      verificationForm.value.evidenceId,
      {
        dimension: verificationForm.value.dimension,
        result: verificationForm.value.result,
        sourceLabel: verificationForm.value.sourceLabel,
        sourceReference: verificationForm.value.sourceReference || undefined,
        basis: verificationForm.value.basis,
      }
    )
    verifications.value = [...verifications.value, record]
    verificationForm.value.sourceLabel = ''
    verificationForm.value.sourceReference = ''
    verificationForm.value.basis = ''
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '核验记录未保存。'
  }
}

async function releaseTask() {
  if (!selectedTask.value) {return}
  try {
    await platformApi.release(
      selectedTask.value.taskId,
      '审核人员主动释放，待其他无冲突人员继续处理'
    )
    selectedTask.value = undefined
    subject.value = undefined
    await loadQueue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '任务释放失败。'
  }
}

onBeforeUnmount(() => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
  }
})

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
        <strong>组织审核员</strong>
        <small>仅处理已认领且无冲突的任务</small>
      </div>
      <nav class="workspace-nav" aria-label="平台审核导航">
        <button class="active" type="button">
          <span class="nav-marker"></span>
          组织审核
        </button>
        <button type="button" @click="router.push('/platform/reviews/demands')">
          <span class="nav-marker"></span>
          需求审核
        </button>
        <button type="button" @click="router.push('/platform/audit')">
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
          组织审核
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
            <span class="eyebrow">组织审核队列</span>
            <h1>先认领，再核验，再形成结论</h1>
            <p>工作台只展示当前岗位允许的摘要；事项详情、材料和历史必须绑定到本人有效认领。</p>
          </div>
          <span class="status-pill approved">{{ tasks.length }} 项待处理</span>
        </section>
        <div v-if="error" class="workspace-empty-state" role="alert">
          <strong>工作台提示</strong>
          <p>{{ error }}</p>
          <button class="public-outline" type="button" @click="loadQueue">重新加载</button>
        </div>

        <section v-else class="platform-layout">
          <div class="platform-queue panel-surface">
            <div class="section-kicker">待认领事项</div>
            <div v-if="loading" class="platform-empty">正在验证平台岗位…</div>
            <div v-else-if="!tasks.length" class="platform-empty">当前没有可认领的组织申请。</div>
            <button
              v-for="task in tasks"
              :key="task.taskId"
              class="platform-task"
              :class="{ selected: selectedTask?.taskId === task.taskId }"
              type="button"
              @click="openTask(task)"
            >
              <span class="platform-task-dot"></span>
              <span>
                <strong>{{ taskLabel(task) }}</strong>
                <small>
                  {{ task.status === 'CLAIMED' && task.claimedByMe ? '我已认领' : '待认领' }} · 事项
                  {{ task.reviewCaseId }}
                </small>
              </span>
              <span>→</span>
            </button>
          </div>

          <div v-if="selectedTask && subject" class="platform-detail panel-surface">
            <div class="platform-detail-head">
              <div>
                <div class="section-kicker">已认领详情</div>
                <h2>{{ subject.legalName }}</h2>
                <p>
                  组织编号 {{ subject.organizationId }} ·
                  {{ subject.organizationRole === 'EMPLOYER' ? '用工方' : '劳务供应商' }} · 申请版本
                  {{ subject.applicationVersion }}
                </p>
              </div>
              <button class="public-outline" type="button" @click="releaseTask">释放任务</button>
            </div>
            <div class="platform-facts">
              <div>
                <span>登记标识</span>
                <strong>{{ subject.registrationId }}</strong>
              </div>
              <div>
                <span>申请状态</span>
                <strong>{{ subject.status }}</strong>
              </div>
              <div>
                <span>授权引用</span>
                <strong>{{ subject.authorizationReference || '未提交' }}</strong>
              </div>
              <div>
                <span>资质引用</span>
                <strong>{{ subject.qualificationReference || '不适用/未提交' }}</strong>
              </div>
            </div>
            <div class="platform-section">
              <div class="section-kicker">材料与逐项核验</div>
              <div v-if="!evidence.length" class="platform-empty">
                当前版本没有已绑定材料；不能据此推断材料真实或资格成立。
              </div>
              <div v-for="item in evidence" :key="item.evidenceId" class="evidence-row">
                <div>
                  <strong>{{ evidenceLabel(item) }}</strong>
                  <small>
                    扫描：{{ item.scanStatus }} · 真实性：{{ item.authenticityStatus }} · 来源：{{
                      item.sourceCode
                    }}
                  </small>
                </div>
                <span>
                  {{ verifications.filter(v => v.evidenceId === item.evidenceId).length }} 条核验
                  <button
                    class="public-outline evidence-preview-button"
                    type="button"
                    @click="previewEvidence(item.evidenceId)"
                  >
                    受控预览
                  </button>
                </span>
              </div>
              <figure v-if="previewUrl" class="evidence-preview">
                <img :src="previewUrl" alt="已授权的材料栅格预览" />
                <figcaption>受控 PNG 预览，不提供原件下载</figcaption>
              </figure>
              <div v-if="verifications.length" class="verification-history">
                <strong>核验记录</strong>
                <article v-for="record in verifications" :key="record.verificationId">
                  <span>{{ verificationEvidenceCategory(record.evidenceId) }}</span>
                  <small>
                    {{ record.dimension }} · {{ record.result }} · {{ record.sourceLabel }} ·
                    {{ record.createdAt }}
                  </small>
                  <p>{{ record.basis }}</p>
                </article>
              </div>
              <div v-if="evidence.length" class="verification-form">
                <select v-model="verificationForm.evidenceId">
                  <option v-for="item in evidence" :key="item.evidenceId" :value="item.evidenceId">
                    {{ evidenceLabel(item) }}
                  </option>
                </select>
                <select v-model="verificationForm.dimension">
                  <option value="COMPLETENESS">完整性</option>
                  <option value="AUTHENTICITY">真实性</option>
                  <option value="VALIDITY">有效性</option>
                </select>
                <select v-model="verificationForm.result">
                  <option value="NOT_VERIFIED">待核实</option>
                  <option value="VERIFIED">已核实</option>
                  <option value="CONFLICT">存在冲突</option>
                  <option value="COMPLETE">齐全</option>
                  <option value="MISSING">缺失</option>
                  <option value="VALID">有效</option>
                  <option value="EXPIRED">已过期</option>
                  <option value="NOT_APPLICABLE">不适用</option>
                </select>
                <input v-model="verificationForm.sourceLabel" placeholder="核验来源名称" />
                <input
                  v-model="verificationForm.sourceReference"
                  placeholder="来源检索标识（可选）"
                />
                <textarea
                  v-model="verificationForm.basis"
                  placeholder="填写可复核的事实依据"
                ></textarea>
                <button class="public-primary" type="button" @click="addVerification">
                  追加核验记录
                </button>
              </div>
            </div>
            <div class="platform-section">
              <div class="section-kicker">事项版本与处理历史</div>
              <div v-if="history" class="review-history">
                <p>
                  事项 {{ history.reviewCaseId }} · 历史覆盖：{{ history.historyCoverage }}
                </p>
                <article v-for="version in history.versions" :key="version.resourceVersion">
                  <strong>
                    版本 {{ version.resourceVersion }} ·
                    {{ version.decision || '待审核' }}
                  </strong>
                  <p v-if="version.publicReason">对申请方说明：{{ version.publicReason }}</p>
                  <ul v-if="version.correctionItems.length">
                    <li v-for="item in version.correctionItems" :key="item.correctionItemId">
                      {{ item.title }}：{{ item.description }}；申请方动作：{{ item.applicantAction }}
                    </li>
                  </ul>
                </article>
                <ol>
                  <li v-for="event in history.events" :key="event.eventId">
                    {{ event.occurredAt }} · {{ event.actorRole || '申请方/系统' }} ·
                    {{ event.action }} · {{ event.outcome }}
                  </li>
                </ol>
              </div>
            </div>
            <div class="platform-section">
              <div class="section-kicker">审核结论</div>
              <div class="decision-row">
                <label>
                  <input v-model="decision" type="radio" value="APPROVE" />
                  通过
                </label>
                <label>
                  <input v-model="decision" type="radio" value="NEEDS_CORRECTION" />
                  要求补正
                </label>
                <label>
                  <input v-model="decision" type="radio" value="REJECT" />
                  拒绝
                </label>
              </div>
              <div v-if="decision === 'NEEDS_CORRECTION'" class="correction-editor">
                <div
                  v-for="(item, index) in correctionItems"
                  :key="index"
                  class="correction-editor-item"
                >
                  <div class="correction-editor-title">
                    <strong>补正事项 {{ index + 1 }}</strong>
                    <button
                      class="public-outline"
                      type="button"
                      @click="removeCorrectionItem(index)"
                    >移除</button>
                  </div>
                  <input v-model="item.itemCode" maxlength="64" placeholder="事项代码，如 AUTHORITY" />
                  <input v-model="item.title" maxlength="120" placeholder="问题标题" />
                  <textarea
                    v-model="item.description"
                    maxlength="1000"
                    placeholder="具体缺项、冲突或核验问题"
                  ></textarea>
                  <textarea
                    v-model="item.applicantAction"
                    maxlength="1000"
                    placeholder="申请方需要完成的动作"
                  ></textarea>
                </div>
                <button
                  class="public-outline"
                  :disabled="correctionItems.length >= 20"
                  type="button"
                  @click="addCorrectionItem"
                >
                  添加补正事项
                </button>
              </div>
              <textarea
                v-model="reason"
                placeholder="补正或拒绝必须说明申请方可理解的理由"
              ></textarea>
              <button
                class="public-primary"
                :disabled="saving"
                type="button"
                @click="submitDecision"
              >
                {{ saving ? '提交中…' : '提交审核结论' }}
              </button>
            </div>
          </div>
          <div v-else class="platform-detail panel-surface platform-empty">
            从左侧认领一项组织申请后，服务端才会开放对应版本、材料和历史。
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
