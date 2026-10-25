<script setup lang="ts">
defineProps<{ modelValue: boolean; decision: string; reason: string }>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'update:decision': [value: string]
  'update:reason': [value: string]
  save: []
}>()
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="记录审核结论"
    width="480px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="审核决定">
        <el-radio-group
          :model-value="decision"
          @update:model-value="emit('update:decision', $event)"
        >
          <el-radio value="APPROVE">通过</el-radio>
          <el-radio value="NEEDS_CORRECTION">退回补正</el-radio>
          <el-radio value="REJECT">拒绝</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="理由">
        <el-input
          :model-value="reason"
          type="textarea"
          :rows="4"
          placeholder="通过时可留空；补正和拒绝必须填写"
          @update:model-value="emit('update:reason', $event)"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="emit('save')">保存审核结论</el-button>
    </template>
  </el-dialog>
</template>
