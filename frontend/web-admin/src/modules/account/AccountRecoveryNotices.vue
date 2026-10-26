<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { recoveryNoticesApi, type RecoveryNotice } from './api/recovery-notices'

const notices = ref<RecoveryNotice[]>([])
const error = ref('')

/** 通知读取与账号主信息分开，避免通知故障遮蔽组织进度。 */
async function loadNotices() {
  error.value = ''
  try {
    notices.value = await recoveryNoticesApi.list()
  } catch {
    error.value = '任务通知暂时无法读取。'
  }
}

/** 使用任务类型说明变动，不披露申请方身份或内部理由。 */
function taskLabel(taskType: RecoveryNotice['taskType']) {
  return {
    ORGANIZATION_REVIEW: '组织审核',
    DEMAND_REVIEW: '需求审核',
    APPEAL_REVIEW: '独立复核',
  }[taskType]
}

onMounted(loadNotices)
</script>

<template>
  <section v-if="notices.length || error" class="account-recovery-notices" aria-label="任务通知">
    <div class="section-heading-row compact">
      <div><span class="eyebrow">站内通知</span><h2>任务变动</h2></div>
      <button class="text-action" type="button" @click="loadNotices">刷新</button>
    </div>
    <p v-if="error" class="auth-message account-error" role="alert">{{ error }}</p>
    <div v-for="notice in notices" :key="notice.noticeId" class="account-recovery-notice">
      <strong>{{ taskLabel(notice.taskType) }}任务 {{ notice.taskId }} 已解除认领</strong>
      <span>该任务已退回原岗位队列。原认领时间保留在处理历史中。</span>
      <time :datetime="notice.recoveredAt">
        {{ new Date(notice.recoveredAt).toLocaleString('zh-CN') }}
      </time>
    </div>
  </section>
</template>
