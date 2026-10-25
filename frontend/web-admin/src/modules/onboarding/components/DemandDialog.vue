<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { DemandForm } from '../api/types'

const props = defineProps<{ modelValue: boolean; initialValue: DemandForm; editing: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; save: [form: DemandForm] }>()
const form = reactive<DemandForm>({
  relatedPartyRelation: '',
  serviceScope: '',
  location: '',
  startsAt: '',
  endsAt: '',
  title: '',
  quantity: '',
  quantityUnit: '',
  target: '',
  pricingMode: '',
  measurementBasis: '',
  settlementCycle: '',
  bidDeadline: '',
  confirmationDeadline: '',
  selectionRule: '',
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
    class="demand-drawer"
    :title="editing ? '补正需求版本' : '创建需求第一版'"
    width="720px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-row :gutter="14">
        <el-col :span="16">
          <el-form-item label="需求标题"><el-input v-model="form.title" /></el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="数量">
            <el-input v-model="form.quantity">
              <template #append>{{ form.quantityUnit }}</template>
            </el-input>
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="数量单位"><el-input v-model="form.quantityUnit" /></el-form-item>
      <el-form-item label="服务范围">
        <el-input v-model="form.serviceScope" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="服务地点"><el-input v-model="form.location" /></el-form-item>
      <el-form-item label="相关主体关系">
        <el-input v-model="form.relatedPartyRelation" />
      </el-form-item>
      <el-form-item label="目标与响应条件">
        <el-input v-model="form.target" type="textarea" :rows="2" />
      </el-form-item>
      <el-row :gutter="14">
        <el-col :span="8">
          <el-form-item label="开始时间">
            <el-date-picker
              v-model="form.startsAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="结束时间">
            <el-date-picker
              v-model="form.endsAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="应标截止">
            <el-date-picker
              v-model="form.bidDeadline"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="供应商确认截止">
            <el-date-picker
              v-model="form.confirmationDeadline"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="14">
        <el-col :span="8">
          <el-form-item label="报价模式"><el-input v-model="form.pricingMode" /></el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="计量口径"><el-input v-model="form.measurementBasis" /></el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="结算周期"><el-input v-model="form.settlementCycle" /></el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="选标规则">
        <el-input v-model="form.selectionRule" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="emit('save', { ...form })">保存版本</el-button>
    </template>
  </el-dialog>
</template>
