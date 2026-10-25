<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import type { Demand } from '../api/types'
import { displayDate } from '../../../shared/lib/format'

defineProps<{ demands: Demand[] }>()
const emit = defineEmits<{ refresh: [] }>()
</script>

<template>
  <section class="section-card">
    <div class="section-head">
      <div>
        <h2>开放需求</h2>
        <p>供应商只读取发布时生成的自身租户投递快照。</p>
      </div>
      <el-button text :icon="Refresh" @click="emit('refresh')">刷新</el-button>
    </div>
    <div class="section-body">
      <div class="table-wrap">
        <el-table :data="demands" size="small">
          <el-table-column prop="title" label="需求" />
          <el-table-column label="截止时间">
            <template #default="scope">{{ displayDate(scope.row.bidDeadline) }}</template>
          </el-table-column>
          <el-table-column label="数量">
            <template #default="scope">
              {{ scope.row.quantity }} {{ scope.row.quantityUnit }}
            </template>
          </el-table-column>
          <template #empty><div class="empty">当前身份没有可见的开放需求投递</div></template>
        </el-table>
      </div>
    </div>
  </section>
</template>
