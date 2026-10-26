<script setup lang="ts">
import { ref } from 'vue'
import { setToken } from '../../platform/session/session'
import { authApi } from './api/auth'

const emit = defineEmits<{ navigate: [path: string] }>()
const busy = ref(false)
const error = ref('')

async function enterPlatform() {
  error.value = ''
  busy.value = true
  try {
    const session = await authApi.selectPlatformContext()
    setToken(session.accessToken)
    emit('navigate', '/platform')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '当前账号没有有效的平台岗位授权。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="workspace-access-card platform-account-entry">
    <div>
      <span class="status-label">平台岗位</span>
      <h2>平台治理工作台</h2>
      <p>已获平台岗位授权的工作人员可进入本岗位工作空间；进入时由服务端重新校验。</p>
    </div>
    <button
      class="public-outline"
      :disabled="busy"
      type="button"
      @click="enterPlatform"
    >
      进入平台工作台
    </button>
    <p v-if="error" class="auth-message" role="alert">{{ error }}</p>
  </div>
</template>
