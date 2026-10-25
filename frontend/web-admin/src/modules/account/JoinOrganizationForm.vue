<script setup lang="ts">
import type { MembershipRole } from '../onboarding/api/types'

defineProps<{
  organizationId: string
  requestedRole: MembershipRole
  actionBusy: boolean
}>()

const emit = defineEmits<{
  'update:organization-id': [organizationId: string]
  'update:requested-role': [role: MembershipRole]
  submit: []
  cancel: []
}>()
</script>

<template>
  <form class="account-inline-form" @submit.prevent="emit('submit')">
    <div class="section-heading-row compact">
      <div>
        <span class="eyebrow">成员申请</span>
        <h2>申请加入已有组织</h2>
      </div>
    </div>
    <div class="account-form-grid">
      <label>
        <span>组织编号</span>
        <input
          :value="organizationId"
          inputmode="numeric"
          required
          placeholder="请向组织管理员获取"
          @input="emit('update:organization-id', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        <span>申请岗位</span>
        <select
          :value="requestedRole"
          @change="
            emit(
              'update:requested-role',
              ($event.target as HTMLSelectElement).value as MembershipRole
            )
          "
        >
          <option value="DEMAND_EDITOR">需求编辑</option>
          <option value="DEMAND_PUBLISHER">需求发布</option>
          <option value="SUPPLIER_BIDDER">供应商应标</option>
          <option value="DEMAND_SELECTOR">用工方选标</option>
        </select>
      </label>
    </div>
    <div class="account-form-actions">
      <button class="public-outline" type="button" @click="emit('cancel')">取消</button>
      <button class="public-primary" type="submit" :disabled="actionBusy">提交加入申请</button>
    </div>
  </form>
</template>
