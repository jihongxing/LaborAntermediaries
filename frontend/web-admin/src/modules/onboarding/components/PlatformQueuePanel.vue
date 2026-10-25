<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import type { Demand, Organization } from '../api/types'

defineProps<{ organizations: Organization[]; demands: Demand[] }>()
const emit = defineEmits<{
  refresh: []
  review: [type: 'organization' | 'demand', item: Organization | Demand]
}>()
</script>

<template>
  <section class="section-card">
    <div class="section-head">
      <div>
        <h2>平台审核队列</h2>
        <p>需要平台审核权限的身份才能看到队列。</p>
      </div>
      <el-button text :icon="Refresh" @click="emit('refresh')">刷新</el-button>
    </div>
    <div class="section-body">
      <el-divider content-position="left">组织申请 {{ organizations.length }}</el-divider>
      <div class="table-wrap">
        <el-table :data="organizations" size="small">
          <el-table-column prop="legalName" label="组织" />
          <el-table-column label="角色">
            <template #default="scope">
              {{ scope.row.organizationRole === 'EMPLOYER' ? '用工方' : '劳务公司' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="scope">
              <el-button link type="primary" @click="emit('review', 'organization', scope.row)">
                审核
              </el-button>
            </template>
          </el-table-column>
          <template #empty><div class="empty">暂无待审核组织</div></template>
        </el-table>
      </div>
      <el-divider content-position="left">需求申请 {{ demands.length }}</el-divider>
      <div class="table-wrap">
        <el-table :data="demands" size="small">
          <el-table-column prop="title" label="需求" />
          <el-table-column label="组织编号" prop="organizationId" />
          <el-table-column label="操作" width="90">
            <template #default="scope">
              <el-button link type="primary" @click="emit('review', 'demand', scope.row)">
                审核
              </el-button>
            </template>
          </el-table-column>
          <template #empty><div class="empty">暂无待审核需求</div></template>
        </el-table>
      </div>
    </div>
  </section>
</template>
