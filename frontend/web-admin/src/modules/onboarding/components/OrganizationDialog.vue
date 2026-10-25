<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { OrganizationForm } from '../api/types'

const props = defineProps<{
  modelValue: boolean
  initialValue: OrganizationForm
  editing: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  save: [form: OrganizationForm]
}>()
const form = reactive<OrganizationForm>({
  organizationRole: 'EMPLOYER',
  legalName: '',
  registrationId: '',
  authorizationReference: '',
  qualificationReference: '',
})

watch(
  () => props.modelValue,
  visible => {
    if (visible) {
      Object.assign(form, props.initialValue)
    }
  }
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="组织申请资料"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="组织角色">
        <el-radio-group v-model="form.organizationRole" :disabled="editing">
          <el-radio value="EMPLOYER">用工方</el-radio>
          <el-radio value="LABOR_SUPPLIER">劳务公司</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="登记名称"><el-input v-model="form.legalName" /></el-form-item>
      <el-form-item label="登记标识"><el-input v-model="form.registrationId" /></el-form-item>
      <el-form-item label="控制权/授权引用">
        <el-input v-model="form.authorizationReference" />
      </el-form-item>
      <el-form-item label="资质/备案引用">
        <el-input v-model="form.qualificationReference" placeholder="劳务公司必填，填写受控引用" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="emit('save', { ...form })">保存资料</el-button>
    </template>
  </el-dialog>
</template>
