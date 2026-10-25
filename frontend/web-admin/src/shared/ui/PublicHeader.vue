<script setup lang="ts">
import { ArrowRight, User } from '@element-plus/icons-vue'

defineProps<{ active?: 'home' | 'demands' | 'account' }>()
const emit = defineEmits<{
  navigate: [path: string]
  account: []
}>()
</script>

<template>
  <header class="public-header">
    <button class="public-brand" type="button" @click="emit('navigate', '/')">
      <span class="public-brand-mark">劳</span>
      <span>
        <strong>劳务供需平台</strong>
        <small>让需求与合规供给更快协作</small>
      </span>
    </button>
    <nav class="public-nav" aria-label="公开导航">
      <button
        type="button"
        :class="{ active: active === 'home' || active === 'demands' }"
        @click="emit('navigate', '/demands')"
      >
        需求大厅
      </button>
      <button type="button" @click="emit('navigate', '/#how-it-works')">平台如何协作</button>
      <button type="button" @click="emit('navigate', '/account')">组织入驻</button>
    </nav>
    <div class="public-header-actions">
      <button class="public-login" type="button" @click="emit('account')">
        <el-icon><User /></el-icon>
        登录
      </button>
      <button class="public-primary" type="button" @click="emit('account')">
        注册 / 组织入驻
        <el-icon><ArrowRight /></el-icon>
      </button>
    </div>
  </header>
</template>
