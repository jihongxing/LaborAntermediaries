<script setup lang="ts">
import { Refresh, UserFilled } from '@element-plus/icons-vue'

defineProps<{
  token: string
  organizationId: string
  organizationName: string
  loading: boolean
}>()
const emit = defineEmits<{
  'update:token': [value: string]
  'update:organizationId': [value: string]
  refresh: []
  clear: []
}>()
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <div class="brand-mark">劳</div>
      <div>
        <h1>劳务供应链工作台</h1>
        <p>首批准入与需求发布纵向切片</p>
      </div>
    </div>
    <div class="row-actions">
      <el-tag v-if="organizationName !== '未选择组织'" effect="dark" type="success">
        {{ organizationName }}
      </el-tag>
      <el-button
text
bg
:icon="UserFilled"
@click="emit('clear')">清除会话</el-button>
    </div>
  </header>
  <form class="session-bar" @submit.prevent="emit('refresh')">
    <el-input
      :model-value="token"
      type="password"
      show-password
      placeholder="粘贴外部身份服务签发的 JWT（仅内存）"
      aria-label="外部身份令牌"
      @update:model-value="emit('update:token', $event)"
    />
    <el-input
      :model-value="organizationId"
      placeholder="当前或目标组织编号"
      aria-label="当前或目标组织编号"
      @update:model-value="emit('update:organizationId', $event)"
    />
    <el-button
native-type="submit"
type="primary"
:loading="loading"
:icon="Refresh">
      连接并刷新
    </el-button>
  </form>
</template>
