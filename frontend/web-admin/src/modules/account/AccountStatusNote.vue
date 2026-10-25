<template>
  <div class="account-note">
    <el-icon><Clock /></el-icon>
    <span>
      组织审核通过后，成员还需获得组织管理员授权。切换组织空间时，服务端会重新检查账号、组织和成员状态。
    </span>
  </div>
</template>

<script setup lang="ts">
import { Clock } from '@element-plus/icons-vue'
</script>
