<script setup lang="ts">
import { ArrowRight } from '@element-plus/icons-vue'
import type { AccountSummary } from './api/auth'

defineProps<{
  account?: AccountSummary
}>()

const emit = defineEmits<{
  navigate: [path: string]
  logout: []
}>()
</script>

<template>
  <aside class="account-sidebar">
    <button class="account-nav active" type="button">账号与组织</button>
    <button class="account-nav" type="button" @click="emit('navigate', '/demands')">
      公开需求
    </button>
    <div class="account-sidebar-help">
      <strong>个人账号</strong>
      <span>
        {{ account?.displayName || '已验证手机号账号' }} · {{ account?.status || '读取中' }}
      </span>
      <button class="text-action" type="button" @click="emit('logout')">
        退出登录
        <el-icon><ArrowRight /></el-icon>
      </button>
    </div>
  </aside>
</template>
