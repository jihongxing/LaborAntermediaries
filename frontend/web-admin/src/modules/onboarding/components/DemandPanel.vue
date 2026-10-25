<script setup lang="ts">
import { Plus, Promotion, Search, Upload } from '@element-plus/icons-vue'
import type { Demand, DemandStatus, Organization } from '../api/types'
import { demandStatusLabel, displayDate, statusType } from '../../../shared/lib/format'

const demandLabel = (status: string) => demandStatusLabel[status as DemandStatus] || status

defineProps<{ demands: Demand[]; organization?: Organization }>()
const emit = defineEmits<{
  create: []
  edit: [item: Demand]
  submit: [item: Demand]
  publish: [item: Demand]
}>()
</script>

<template>
  <section class="section-card span-2">
    <div class="section-head">
      <div>
        <h2>需求管理</h2>
        <p>需求经过组织补正、平台审核后，才能显式发布。</p>
      </div>
      <el-button
        type="primary"
        :icon="Plus"
        :disabled="!organization || organization.organizationRole !== 'EMPLOYER'"
        @click="emit('create')"
      >
        创建需求
      </el-button>
    </div>
    <div class="section-body">
      <div class="table-wrap">
        <el-table :data="demands" stripe>
          <el-table-column prop="title" label="需求" min-width="180" />
          <el-table-column label="状态" width="140">
            <template #default="scope">
              <el-tag :type="statusType(scope.row.status)">
                {{ demandLabel(scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="scope">v{{ scope.row.revision }}</template>
          </el-table-column>
          <el-table-column label="服务窗口" min-width="220">
            <template #default="scope">
              {{ displayDate(scope.row.startsAt) }} - {{ displayDate(scope.row.endsAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="260">
            <template #default="scope">
              <div class="row-actions">
                <el-button
                  v-if="['DRAFT', 'NEEDS_CORRECTION'].includes(scope.row.status)"
                  link
                  :icon="Search"
                  @click="emit('edit', scope.row)"
                >
                  补正
                </el-button>
                <el-button
                  v-if="['DRAFT', 'NEEDS_CORRECTION'].includes(scope.row.status)"
                  link
                  type="primary"
                  :icon="Upload"
                  @click="emit('submit', scope.row)"
                >
                  送审
                </el-button>
                <el-button
                  v-if="scope.row.status === 'APPROVED_PENDING_PUBLISH'"
                  link
                  type="success"
                  :icon="Promotion"
                  @click="emit('publish', scope.row)"
                >
                  发布
                </el-button>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty">暂无需求。组织准入通过后可创建第一版。</div>
          </template>
        </el-table>
      </div>
    </div>
  </section>
</template>
