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
  type CorrectionItemRequest,
  type DemandReviewSubject,
  type PlatformEvidence,
  type PlatformTask,
  type ReviewDecision,
  type ReviewHistory,
  type Verification,
  type VerificationDimension,
  type VerificationResult,
} from './api/platform'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const tasks = ref<PlatformTask[]>([])
const selectedTask = ref<PlatformTask>()
const subject = ref<DemandReviewSubject>()
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

/**
 * 在受控平台岗位上下文中读取审核队列。
 * @return 无返回值；错误显示在工作台页面
 */
async function loadQueue() {
  loading.value = true
  error.value = ''
  try {
    const session = await authApi.selectPlatformContext()
    setToken(session.accessToken)
    tasks.value = await platformApi.queue('DEMAND_REVIEW')
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '需求审核队列暂时无法读取。'
  } finally {
    loading.value = false
  }
}

/**
 * 原子认领任务后并行读取该版本需求、材料、核验记录及事项历史。
 * @param task 待认领的需求审核任务
 * @return 无返回值；读取失败时保留错误并刷新队列
 */
async function openTask(task: PlatformTask) {
  error.value = ''
  try {
    if (!task.claimedByMe) {
      await platformApi.claim(task.taskId)
    }
    selectedTask.value = { ...task, claimedByMe: true, status: 'CLAIMED' }
    decision.value = 'APPROVE'
    reason.value = ''
    correctionItems.value = []
    const [nextSubject, nextEvidence, nextVerifications, details] = await Promise.all([
      platformApi.demandSubject(task.taskId),
      platformApi.evidence(task.taskId),
      platformApi.verifications(task.taskId),
      platformApi.details(task.taskId),
    ])
    subject.value = nextSubject
    evidence.value = nextEvidence
    verifications.value = nextVerifications
    history.value = details.history
    verificationForm.value.evidenceId = nextEvidence[0]?.evidenceId || ''
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
      previewUrl.value = ''
    }
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '任务已被占用或无法读取。'
    await loadQueue()
  }
}

/**
 * 申请当前认领任务所绑定材料的逐页 PNG 受控预览。
 * @param evidenceId 当前需求版本的材料编号
 * @return 无返回值；预览失败时显示可恢复错误
 */
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

/**
 * 校验决定所需理由和逐项补正清单。
 * @param corrections 即将提交的补正事项
 * @return 可显示的错误原因；校验通过时返回空值
 */
function decisionValidationMessage(corrections: CorrectionItemRequest[]): string | undefined {
  if (decision.value !== 'APPROVE' && !reason.value.trim()) {
    return '补正或拒绝需要填写申请方可理解的理由。'
  }
  if (decision.value === 'NEEDS_CORRECTION') {
    if (!corrections.length) {
      return '要求补正时至少添加一项补正事项。'
    }
    const codes = corrections.map(item => item.itemCode.trim())
    if (new Set(codes).size !== codes.length) {
      return '每项补正事项的代码必须唯一。'
    }
    if (corrections.length > 20 || corrections.some(item =>
      !item.itemCode.trim() || !item.title.trim() || !item.description.trim()
      || !item.applicantAction.trim())) {
      return '请完整填写 1 至 20 项补正事项，包括问题、说明和申请方动作。'
    }
  } else if (corrections.length) {
    return '通过或拒绝决定不能携带补正事项。'
  }
  return undefined
}

/**
 * 向服务端提交绑定当前任务版本的需求审核决定。
 * @return 无返回值；成功后关闭详情并刷新原岗位队列
 */
async function submitDecision() {
  if (!selectedTask.value) {
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
    await platformApi.decideDemand(
      selectedTask.value.taskId,
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
    reason.value = ''
    decision.value = 'APPROVE'
    await loadQueue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '审核决定未提交。'
  } finally {
    saving.value = false
  }
}

/**
 * 增加一条空白补正事项，并生成当前清单内未使用的代码。
 * @return 无返回值
 */
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

/**
 * 删除尚未提交的补正事项。
 * @param index 当前编辑列表中的事项位置
 * @return 无返回值
 */
function removeCorrectionItem(index: number) {
  correctionItems.value.splice(index, 1)
}

/**
 * 记录本次材料核验的来源和可复核依据。
 * @return 无返回值；成功后在页面追加服务端记录
 */
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

/**
 * 由当前任务认领人说明原因并释放任务回到需求审核岗位队列。
 * @return 无返回值
 */
async function releaseTask() {
  if (!selectedTask.value) {
    return
  }
  try {
    await platformApi.release(selectedTask.value.taskId, '需求审核员主动释放，待其他无冲突人员继续处理')
    selectedTask.value = undefined
    subject.value = undefined
    await loadQueue()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '任务释放失败。'
  }
}

/** 将后端时间转换为简洁的本地展示格式。 */
function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
    .format(new Date(value))
}

/** 将事项历史中的补正代码显示为便于审核员理解的标签。 */
function evidenceLabel(item: PlatformEvidence) {
  return `${item.category} · ${item.mediaType} · ${Math.round(item.sizeBytes / 1024)} KB`
}

/** 根据材料编号查找核验记录对应的材料类别。 */
function verificationEvidenceCategory(evidenceId: string) {
  return evidence.value.find(item => item.evidenceId === evidenceId)?.category || '材料'
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
        <strong>需求审核员</strong>
        <small>只处理本人认领且无冲突的需求版本</small>
      </div>
      <nav class="workspace-nav" aria-label="平台审核导航">
        <button type="button" @click="router.push('/platform/reviews/organizations')">
          <span class="nav-marker"></span>
          组织审核
        </button>
        <button class="active" type="button">
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
        <div class="workspace-breadcrumb">平台治理 <span>/</span> 需求审核</div>
        <div class="workspace-top-actions">
          <button class="workspace-user" type="button" @click="router.push('/account')">
            平台岗位会话
          </button>
        </div>
      </header>
      <div class="workspace-content platform-content">
        <section class="workspace-intro">
          <div>
            <span class="eyebrow">需求审核队列</span>
            <h1>核对需求版本，审核与发布分开</h1>
            <p>通过只表示本版本审核通过；用工方仍须按发布门禁显式发布。审核页不展示应标、供应商或实际报价。</p>
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
            <div v-if="loading" class="platform-empty">正在验证需求审核岗位…</div>
            <div v-else-if="!tasks.length" class="platform-empty">当前没有可认领的需求申请。</div>
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
                <strong>需求审核事项 {{ task.reviewCaseId }} · 版本 {{ task.resourceVersion }}</strong>
                <small>{{ task.claimedByMe ? '我已认领' : '待认领' }} · 事项 {{ task.reviewCaseId }}</small>
              </span>
              <span>→</span>
            </button>
          </div>

          <div v-if="selectedTask && subject" class="platform-detail panel-surface">
            <div class="platform-detail-head">
              <div>
                <div class="section-kicker">已认领需求版本</div>
                <h2>{{ subject.title }}</h2>
                <p>
                  需求 {{ subject.demandId }} · 版本 {{ subject.revision }} · 状态 {{ subject.status }}
                </p>
              </div>
              <button class="public-outline" type="button" @click="releaseTask">释放任务</button>
            </div>

            <div class="platform-facts">
              <div>
                <span>人数/数量</span><strong>{{ subject.quantity }} {{ subject.quantityUnit }}</strong>
              </div>
              <div><span>服务地点</span><strong>{{ subject.location }}</strong></div>
              <div><span>服务开始</span><strong>{{ formatTime(subject.startsAt) }}</strong></div>
              <div><span>服务结束</span><strong>{{ formatTime(subject.endsAt) }}</strong></div>
              <div><span>应标截止</span><strong>{{ formatTime(subject.bidDeadline) }}</strong></div>
              <div>
                <span>确认截止</span><strong>{{ formatTime(subject.confirmationDeadline) }}</strong>
              </div>
            </div>

            <div class="platform-section">
              <div class="section-kicker">需求字段核验</div>
              <dl class="demand-review-facts">
                <div><dt>服务范围</dt><dd>{{ subject.serviceScope }}</dd></div>
                <div><dt>主体关系</dt><dd>{{ subject.relatedPartyRelation }}</dd></div>
                <div><dt>用工目标与响应条件</dt><dd>{{ subject.target }}</dd></div>
                <div><dt>报价模式</dt><dd>{{ subject.pricingMode }}</dd></div>
                <div><dt>计量确认口径</dt><dd>{{ subject.measurementBasis }}</dd></div>
                <div><dt>结算周期约定</dt><dd>{{ subject.settlementCycle }}</dd></div>
                <div><dt>选标与配额规则</dt><dd>{{ subject.selectionRule }}</dd></div>
              </dl>
            </div>

            <div class="platform-section">
              <div class="section-kicker">支持材料与核验记录</div>
              <div v-if="!evidence.length" class="platform-empty">
                当前版本没有已绑定材料；不能据此推断需求内容已核实。
              </div>
              <div v-for="item in evidence" :key="item.evidenceId" class="evidence-row">
                <div>
                  <strong>{{ evidenceLabel(item) }}</strong>
                  <small>扫描：{{ item.scanStatus }} · 真实性：{{ item.authenticityStatus }}</small>
                </div>
                <button
                  class="public-outline evidence-preview-button"
                  type="button"
                  @click="previewEvidence(item.evidenceId)"
                >
                  受控预览
                </button>
              </div>
              <figure v-if="previewUrl" class="evidence-preview">
                <img :src="previewUrl" alt="需求审核材料的受控 PNG 预览" />
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
                <input v-model="verificationForm.sourceReference" placeholder="来源检索标识（可选）" />
                <textarea v-model="verificationForm.basis" placeholder="填写可复核的事实依据"></textarea>
                <button class="public-primary" type="button" @click="addVerification">
                  追加核验记录
                </button>
              </div>
            </div>

            <div class="platform-section">
              <div class="section-kicker">版本和处理历史</div>
              <div v-if="history" class="review-history">
                <p>事项 {{ history.reviewCaseId }} · 历史覆盖：{{ history.historyCoverage }}</p>
                <article v-for="version in history.versions" :key="version.resourceVersion">
                  <strong>
                    版本 {{ version.resourceVersion }} · {{ version.decision || '待审核' }}
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
                  <input v-model="decision" type="radio" value="APPROVE" /> 通过
                </label>
                <label>
                  <input v-model="decision" type="radio" value="NEEDS_CORRECTION" /> 要求补正
                </label>
                <label>
                  <input v-model="decision" type="radio" value="REJECT" /> 拒绝
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
                  <input
                    v-model="item.itemCode"
                    maxlength="64"
                    placeholder="事项代码，如 SERVICE_SCOPE"
                  />
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
              <textarea v-model="reason" placeholder="补正或拒绝必须说明申请方可理解的理由"></textarea>
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
            从左侧认领一项需求申请后，服务端才会开放对应版本、材料和历史。
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
