<script setup lang="ts">
import { UserFilled } from '@element-plus/icons-vue'
import type { MembershipRequest, MembershipRole } from '../api/types'
import { displayDate } from '../../../shared/lib/format'

defineProps<{ requests: MembershipRequest[]; modelValue: MembershipRole }>()
const emit = defineEmits<{
  'update:modelValue': [value: MembershipRole]
  join: []
  decide: [id: string, decision: string]
}>()
</script>

<template>
  <section class="section-card">
    <div class="section-head">
      <div>
        <h2>组织加入</h2>
        <p>成员加入由组织管理员逐项审批。</p>
      </div>
      <el-button :icon="UserFilled" @click="emit('join')">提交加入</el-button>
    </div>
    <div class="section-body">
      <el-select
        :model-value="modelValue"
        style="width: 100%; margin-bottom: 14px"
        @update:model-value="emit('update:modelValue', $event as MembershipRole)"
      >
        <el-option label="需求编辑" value="DEMAND_EDITOR" />
        <el-option label="需求发布" value="DEMAND_PUBLISHER" />
        <el-option label="供应商应标" value="SUPPLIER_BIDDER" />
        <el-option label="用工方选标" value="DEMAND_SELECTOR" />
      </el-select>
      <div class="table-wrap">
        <el-table :data="requests" size="small">
          <el-table-column prop="actorId" label="申请人" width="120" />
          <el-table-column prop="requestedRole" label="申请角色" />
          <el-table-column prop="createdAt" label="提交时间">
            <template #default="scope">{{ displayDate(scope.row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="scope">
              <div class="row-actions">
                <el-button
                  link
                  type="success"
                  @click="emit('decide', scope.row.requestId, 'APPROVE')"
                >
                  批准
                </el-button>
                <el-button
                  link
                  type="danger"
                  @click="emit('decide', scope.row.requestId, 'REJECT')"
                >
                  拒绝
                </el-button>
              </div>
            </template>
          </el-table-column>
          <template #empty><div class="empty">暂无待处理加入申请</div></template>
        </el-table>
      </div>
    </div>
  </section>
</template>
