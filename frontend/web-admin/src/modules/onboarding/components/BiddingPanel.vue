<script setup lang="ts">
/* eslint-disable max-lines */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Close, EditPen, Refresh, Select, Tickets } from '@element-plus/icons-vue'
import { ApiError } from '../../../platform/http/client'
import { biddingApi } from '../api/bidding'
import type { Award, Bid, BidForm, Demand, Organization, Snapshot } from '../api/types'
import { displayDate } from '../../../shared/lib/format'

const props = defineProps<{
  organization?: Organization
  demands: Demand[]
  openDemands: Demand[]
}>()
const supplierBids = ref<Bid[]>([])
const supplierAwards = ref<Award[]>([])
const selectedDemand = ref<Demand>()
const employerBids = ref<Bid[]>([])
const awards = ref<Award[]>([])
const snapshots = ref<Snapshot[]>([])
const bidDialogVisible = ref(false)
const confirmationDialogVisible = ref(false)
const confirmationTarget = ref<Award>()
const rejectionReason = ref('')
const submitting = ref(false)
const allocations = reactive<Record<string, string>>({})
const form = reactive<BidForm>(emptyBidForm())

const isSupplier = computed(() => props.organization?.organizationRole === 'LABOR_SUPPLIER')
const isEmployer = computed(() => props.organization?.organizationRole === 'EMPLOYER')

function emptyBidForm(): BidForm {
  return {
    committedQuantity: '',
    commitment: '',
    deviationItems: '无',
    proposal: '',
    quotedAmount: '',
    quoteCurrency: 'CNY',
    quoteUnit: '',
    taxRate: '0',
    paymentTerm: '',
    paymentArrangement: '',
    acceptedTerms: false,
  }
}

function notifyError(error: unknown) {
  ElMessage.error(error instanceof ApiError ? error.message : error instanceof Error ? error.message : '操作失败')
}

async function refreshSupplier() {
  try {
    supplierBids.value = await biddingApi.ownBids()
    const demandIds = [...new Set(supplierBids.value.map(item => item.demandId))]
    const awardSets = await Promise.all(demandIds.map(demandId => biddingApi.awards(demandId)))
    supplierAwards.value = awardSets.flat()
  } catch (error) {
    notifyError(error)
  }
}

async function selectDemand(demand: Demand) {
  selectedDemand.value = demand
  employerBids.value = []
  awards.value = []
  snapshots.value = []
  try {
    if (isEmployer.value) {
      employerBids.value = await biddingApi.demandBids(demand.id)
      awards.value = await biddingApi.awards(demand.id)
      snapshots.value = await biddingApi.snapshots(demand.id)
    }
  } catch (error) {
    notifyError(error)
  }
}

function openBid(demand: Demand) {
  selectedDemand.value = demand
  Object.assign(form, emptyBidForm())
  bidDialogVisible.value = true
}

async function submitBid() {
  if (!selectedDemand.value) {
    return
  }
  submitting.value = true
  try {
    await biddingApi.submit(selectedDemand.value.id, form)
    bidDialogVisible.value = false
    ElMessage.success('应标版本已提交')
    await refreshSupplier()
  } catch (error) {
    notifyError(error)
  } finally {
    submitting.value = false
  }
}

async function withdraw(bid: Bid) {
  try {
    await biddingApi.withdraw(bid.id)
    ElMessage.success('应标已撤回')
    await refreshSupplier()
  } catch (error) {
    notifyError(error)
  }
}

async function award() {
  if (!selectedDemand.value) {
    return
  }
  const lines = Object.entries(allocations)
    .filter(([, quantity]) => Number(quantity) > 0)
    .map(([bidId, quantity]) => ({ bidId, quantity }))
  if (!lines.length) {
    ElMessage.warning('请至少填写一条配额')
    return
  }
  try {
    awards.value = await biddingApi.award(selectedDemand.value.id, lines)
    ElMessage.success('选标授予已提交，等待供应商逐家确认')
  } catch (error) {
    notifyError(error)
  }
}

function openConfirmation(awardItem: Award) {
  confirmationTarget.value = awardItem
  rejectionReason.value = ''
  confirmationDialogVisible.value = true
}

async function confirmAward(accepted: boolean) {
  const awardItem = confirmationTarget.value
  if (!awardItem) {
    return
  }
  if (!accepted && !rejectionReason.value.trim()) {
    ElMessage.warning('拒绝授予必须填写理由')
    return
  }
  try {
    const reason = accepted ? undefined : rejectionReason.value.trim()
    const result = await biddingApi.confirm(awardItem.id, accepted, reason)
    confirmationDialogVisible.value = false
    if (selectedDemand.value) {
      awards.value = await biddingApi.awards(selectedDemand.value.id)
      snapshots.value = await biddingApi.snapshots(selectedDemand.value.id)
    }
    ElMessage.success(result.status === 'EXPIRED'
      ? '确认已超时，配额已释放'
      : accepted ? '已确认授予，BP-05 快照已交接' : '已拒绝授予，配额已释放')
  } catch (error) {
    notifyError(error)
  }
}

watch(() => props.organization?.id, () => {
  if (isSupplier.value) {
    refreshSupplier()
  }
})
</script>

<template>
  <section v-if="isSupplier" class="section-card span-2">
    <div class="section-head">
      <div>
        <h2>供应商应标工作台</h2>
        <p>只显示当前供应商自己的应标与授予，暂停期间仅允许撤回。</p>
      </div>
      <el-button text :icon="Refresh" @click="refreshSupplier">刷新</el-button>
    </div>
    <div class="section-body">
      <el-table :data="openDemands" size="small" class="table-wrap">
        <el-table-column prop="title" label="开放需求" min-width="180" />
        <el-table-column label="截止时间" min-width="170">
          <template #default="scope">{{ displayDate(scope.row.bidDeadline) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="scope">
            {{ scope.row.quantity }} {{ scope.row.quantityUnit }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="scope">
            <el-button
              link
              type="primary"
              :icon="EditPen"
              @click="openBid(scope.row)"
            >提交应标</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider />
      <el-table :data="supplierBids" size="small">
        <el-table-column prop="demandId" label="需求" width="130" />
        <el-table-column prop="revision" label="版本" width="80" />
        <el-table-column prop="committedQuantity" label="承诺数量" width="110" />
        <el-table-column prop="quotedAmount" label="报价" width="120" />
        <el-table-column prop="status" label="状态" width="160" />
        <el-table-column label="操作" width="100">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === 'ACTIVE'"
              link
              type="danger"
              :icon="Close"
              @click="withdraw(scope.row)"
            >撤回</el-button>
          </template>
        </el-table-column>
        <template #empty><div class="empty">暂无自己的应标版本</div></template>
      </el-table>
      <el-divider />
      <el-table :data="supplierAwards" size="small">
        <el-table-column prop="demandId" label="需求" width="130" />
        <el-table-column prop="awardedQuantity" label="授予数量" width="120" />
        <el-table-column prop="status" label="状态" width="190" />
        <el-table-column label="确认" width="170">
          <template #default="scope">
            <div v-if="scope.row.status === 'PENDING_CONFIRMATION'" class="row-actions">
              <el-button
                link
                type="success"
                :icon="Check"
                @click="openConfirmation(scope.row)"
              >确认</el-button>
              <el-button
                link
                type="danger"
                :icon="Close"
                @click="openConfirmation(scope.row)"
              >拒绝</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty><div class="empty">暂无待确认授予</div></template>
      </el-table>
    </div>
  </section>

  <section v-else-if="isEmployer" class="section-card span-2">
    <div class="section-head">
      <div>
        <h2>选标与配额工作台</h2>
        <p>选标权限独立于需求编辑；每家供应商独立确认后才交接 BP-05 快照。</p>
      </div>
      <el-select
        v-model="selectedDemand"
        value-key="id"
        placeholder="选择需求"
        style="width: 260px"
        @change="selectDemand"
      >
        <el-option
          v-for="demand in demands"
          :key="demand.id"
          :label="demand.title"
          :value="demand"
        />
      </el-select>
    </div>
    <div v-if="selectedDemand" class="section-body">
      <div class="award-toolbar">
        <el-button type="primary" :icon="Select" @click="award">提交配额授予</el-button>
      </div>
      <el-table :data="employerBids" size="small">
        <el-table-column prop="supplierOrganizationId" label="供应商" width="120" />
        <el-table-column prop="revision" label="应标版本" width="100" />
        <el-table-column prop="committedQuantity" label="承诺容量" width="110" />
        <el-table-column prop="quotedAmount" label="报价" width="120" />
        <el-table-column prop="deviationItems" label="偏离项" min-width="180" />
        <el-table-column label="授予数量" width="150">
          <template #default="scope">
            <el-input
              v-model="allocations[scope.row.id]"
              placeholder="0"
            />
          </template>
        </el-table-column>
      </el-table>
      <el-divider />
      <el-table :data="awards" size="small">
        <el-table-column prop="supplierOrganizationId" label="供应商" width="120" />
        <el-table-column prop="awardedQuantity" label="授予数量" width="120" />
        <el-table-column prop="status" label="确认状态" width="180" />
        <el-table-column prop="confirmationDeadline" label="确认截止" min-width="170">
          <template #default="scope">{{ displayDate(scope.row.confirmationDeadline) }}</template>
        </el-table-column>
      </el-table>
      <el-divider />
      <div class="muted"><Tickets /> 已交接 BP-05 快照：{{ snapshots.length }} 条</div>
    </div>
    <div v-else class="empty">选择一个需求后加载应标和配额</div>
  </section>

  <el-dialog v-model="confirmationDialogVisible" title="确认最终条件" width="720px">
    <template v-if="confirmationTarget">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="需求版本">
          {{ confirmationTarget.terms.demandRevision }}
        </el-descriptions-item>
        <el-descriptions-item label="需求标题">
          {{ confirmationTarget.terms.demandTitle }}
        </el-descriptions-item>
        <el-descriptions-item label="需求数量">
          {{ confirmationTarget.terms.demandQuantity }} {{ confirmationTarget.terms.quantityUnit }}
        </el-descriptions-item>
        <el-descriptions-item label="授予数量">
          {{ confirmationTarget.awardedQuantity }}
        </el-descriptions-item>
        <el-descriptions-item label="服务范围">
          {{ confirmationTarget.terms.serviceScope }}
        </el-descriptions-item>
        <el-descriptions-item label="服务地点">
          {{ confirmationTarget.terms.location }}
        </el-descriptions-item>
        <el-descriptions-item label="承诺内容">
          {{ confirmationTarget.terms.commitment }}
        </el-descriptions-item>
        <el-descriptions-item label="偏离项">
          {{ confirmationTarget.terms.deviationItems }}
        </el-descriptions-item>
        <el-descriptions-item label="报价">
          {{ confirmationTarget.terms.quotedAmount }} {{ confirmationTarget.terms.quoteCurrency }} /
          {{ confirmationTarget.terms.quoteUnit }}
        </el-descriptions-item>
        <el-descriptions-item label="税率">
          {{ confirmationTarget.terms.taxRate }}%
        </el-descriptions-item>
        <el-descriptions-item label="账期">
          {{ confirmationTarget.terms.paymentTerm }}
        </el-descriptions-item>
        <el-descriptions-item label="付款安排">
          {{ confirmationTarget.terms.paymentArrangement }}
        </el-descriptions-item>
        <el-descriptions-item label="确认截止">
          {{ displayDate(confirmationTarget.confirmationDeadline) }}
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-position="top" class="confirmation-form">
        <el-form-item label="拒绝理由">
          <el-input
            v-model="rejectionReason"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
      </el-form>
    </template>
    <template #footer>
      <el-button @click="confirmationDialogVisible = false">取消</el-button>
      <el-button type="danger" :icon="Close" @click="confirmAward(false)">拒绝并释放配额</el-button>
      <el-button type="primary" :icon="Check" @click="confirmAward(true)">确认并交接快照</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="bidDialogVisible" title="提交应标版本" width="620px">
    <el-form label-position="top">
      <el-form-item label="承诺数量"><el-input v-model="form.committedQuantity" /></el-form-item>
      <el-form-item label="承诺内容">
        <el-input v-model="form.commitment" type="textarea" />
      </el-form-item>
      <el-form-item label="偏离项">
        <el-input v-model="form.deviationItems" type="textarea" />
      </el-form-item>
      <el-form-item label="服务方案"><el-input v-model="form.proposal" type="textarea" /></el-form-item>
      <el-form-item label="报价"><el-input v-model="form.quotedAmount" /></el-form-item>
      <el-form-item label="报价单位"><el-input v-model="form.quoteUnit" /></el-form-item>
      <el-form-item label="税率"><el-input v-model="form.taxRate" /></el-form-item>
      <el-form-item label="账期"><el-input v-model="form.paymentTerm" /></el-form-item>
      <el-form-item label="付款安排">
        <el-input v-model="form.paymentArrangement" type="textarea" />
      </el-form-item>
      <el-checkbox v-model="form.acceptedTerms">我已确认需求条款和应标截止时间</el-checkbox>
    </el-form>
    <template #footer>
      <el-button @click="bidDialogVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :icon="Check"
        @click="submitBid"
      >提交应标</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.award-toolbar {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.confirmation-form {
  margin-top: 16px;
}
</style>
