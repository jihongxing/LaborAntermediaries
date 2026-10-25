<script setup lang="ts">
import { Search, Upload, Plus } from '@element-plus/icons-vue'
import type { Organization } from '../api/types'
import { organizationStatusLabel, statusType } from '../../../shared/lib/format'

defineProps<{ organization?: Organization }>()
const emit = defineEmits<{ create: []; edit: []; submit: [] }>()
</script>

<template>
  <section class="section-card">
    <div class="section-head">
      <div>
        <h2>组织入驻</h2>
        <p>组织资料只追加版本，状态由平台审核推进。</p>
      </div>
      <el-button :icon="Plus" @click="emit('create')">新建申请</el-button>
    </div>
    <div class="section-body">
      <div v-if="organization">
        <div class="stat-grid">
          <div class="stat">
            <strong>{{ organization.version }}</strong>
            <span>当前版本</span>
          </div>
          <div class="stat">
            <strong>{{ organization.applicationVersion }}</strong>
            <span>申请资料版本</span>
          </div>
          <div class="stat">
            <strong>{{ organization.createdBy }}</strong>
            <span>创建人</span>
          </div>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="组织名称">{{ organization.legalName }}</el-descriptions-item>
          <el-descriptions-item label="登记标识">
            {{ organization.registrationId }}
          </el-descriptions-item>
          <el-descriptions-item label="业务角色">
            {{ organization.organizationRole === 'EMPLOYER' ? '用工方' : '劳务公司' }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(organization.status)">
              {{ organizationStatusLabel[organization.status] }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="organization.reviewReason" class="review-reason">
          补正/审核意见：{{ organization.reviewReason }}
        </div>
        <div class="row-actions" style="margin-top: 16px">
          <el-button
            v-if="['DRAFT', 'NEEDS_CORRECTION'].includes(organization.status)"
            :icon="Search"
            @click="emit('edit')"
          >
            查看与补正
          </el-button>
          <el-button
            v-if="['DRAFT', 'NEEDS_CORRECTION'].includes(organization.status)"
            type="primary"
            :icon="Upload"
            @click="emit('submit')"
          >
            提交平台审核
          </el-button>
        </div>
      </div>
      <div v-else class="empty">连接后加载组织状态，或先创建组织申请。</div>
    </div>
  </section>
</template>
