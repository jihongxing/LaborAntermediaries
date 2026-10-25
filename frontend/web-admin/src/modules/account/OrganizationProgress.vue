<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import type { OrganizationEntry } from './api/auth'

defineProps<{
  organizations: OrganizationEntry[]
  actionBusy: boolean
}>()

const emit = defineEmits<{
  enter: [entry: OrganizationEntry]
  submit: [organizationId: string]
  correct: [organizationId: string]
}>()

const statusLabels: Record<OrganizationEntry['organizationStatus'], string> = {
  DRAFT: '资料草稿',
  PENDING_REVIEW: '平台审核中',
  APPROVED: '组织已通过审核',
  NEEDS_CORRECTION: '需要补正',
  REJECTED: '审核未通过',
  SUSPENDED: '组织已暂停',
}

function roleLabel(role: OrganizationEntry['organizationRole']) {
  return role === 'EMPLOYER' ? '用工方' : '劳务供应商'
}

function membershipLabel(entry: OrganizationEntry) {
  if (entry.membershipStatus === 'ACTIVE') {
    return `成员已授权 · ${entry.memberRole || '组织成员'}`
  }
  if (entry.membershipStatus === 'REVOKED') {
    return '成员权限已撤销'
  }
  if (entry.membershipRequestStatus === 'PENDING') {
    return '加入申请审核中'
  }
  if (entry.membershipRequestStatus === 'REJECTED') {
    return '加入申请未通过'
  }
  return '尚未获得成员授权'
}
</script>

<template>
  <section class="account-progress">
    <div class="section-heading-row compact">
      <div>
        <span class="eyebrow">认证进度</span>
        <h2>组织和成员授权</h2>
      </div>
      <span class="muted-copy">{{ organizations.length }} 个相关组织</span>
    </div>
    <div v-if="organizations.length" class="account-org-list">
      <article v-for="entry in organizations" :key="entry.organizationId" class="account-org-card">
        <div class="account-org-main">
          <span class="account-org-kind">
            {{ roleLabel(entry.organizationRole) }} · 组织编号 {{ entry.organizationId }}
          </span>
          <strong>{{ statusLabels[entry.organizationStatus] }}</strong>
          <span>{{ membershipLabel(entry) }}</span>
        </div>
        <div class="account-org-actions">
          <button
            v-if="entry.membershipStatus === 'ACTIVE' && entry.organizationStatus === 'APPROVED'"
            class="public-primary"
            type="button"
            :disabled="actionBusy"
            @click="emit('enter', entry)"
          >
            进入组织空间
            <el-icon><ArrowRight /></el-icon>
          </button>
          <button
            v-else-if="
              entry.memberRole === 'ORGANIZATION_ADMIN' && entry.organizationStatus === 'DRAFT'
            "
            class="public-outline"
            type="button"
            :disabled="actionBusy"
            @click="emit('submit', entry.organizationId)"
          >
            提交平台审核
          </button>
          <button
            v-else-if="
              entry.memberRole === 'ORGANIZATION_ADMIN' &&
              entry.organizationStatus === 'NEEDS_CORRECTION'
            "
            class="public-outline"
            type="button"
            :disabled="actionBusy"
            @click="emit('correct', entry.organizationId)"
          >
            查看意见并补正
          </button>
          <span v-else class="status-pill neutral">等待组织或平台处理</span>
        </div>
      </article>
    </div>
    <div v-else class="account-empty-state">
      <strong>账号尚未关联组织</strong>
      <span>
        你可以创建一个组织申请，或提交加入已有组织的申请。平台不会因个人账号注册而授予组织权限。
      </span>
    </div>
  </section>
</template>
