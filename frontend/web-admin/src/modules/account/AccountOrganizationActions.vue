<script setup lang="ts">
import { ArrowRight, CircleCheck, DocumentAdd, OfficeBuilding } from '@element-plus/icons-vue'
import type { OrganizationRole } from '../onboarding/api/types'

const emit = defineEmits<{
  create: [role: OrganizationRole]
  join: []
}>()
</script>

<template>
  <div class="account-actions-grid">
    <button class="account-action" type="button" @click="emit('create', 'EMPLOYER')">
      <span class="action-icon blue"><OfficeBuilding /></span>
      <strong>申请成为用工方</strong>
      <span>提交主体和经办授权资料，发布真实用工需求。</span>
      <em>
        开始申请
        <el-icon><ArrowRight /></el-icon>
      </em>
    </button>
    <button class="account-action" type="button" @click="emit('create', 'LABOR_SUPPLIER')">
      <span class="action-icon green"><DocumentAdd /></span>
      <strong>申请成为劳务供应商</strong>
      <span>提交供应商资质和组织资料，参与适合的服务需求。</span>
      <em>
        开始申请
        <el-icon><ArrowRight /></el-icon>
      </em>
    </button>
    <button class="account-action" type="button" @click="emit('join')">
      <span class="action-icon amber"><CircleCheck /></span>
      <strong>加入已有组织</strong>
      <span>提交组织编号和成员岗位申请，由组织管理员审批。</span>
      <em>
        提交加入申请
        <el-icon><ArrowRight /></el-icon>
      </em>
    </button>
  </div>
</template>
