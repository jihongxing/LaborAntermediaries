<script setup lang="ts">
import type { OrganizationCorrectionItem, OrganizationForm } from '../onboarding/api/types'

const props = defineProps<{
  form: OrganizationForm
  organizationRole: OrganizationForm['organizationRole']
  isCorrection: boolean
  correctionReason: string
  correctionItems: OrganizationCorrectionItem[]
  actionBusy: boolean
}>()

const emit = defineEmits<{
  'update:form': [form: OrganizationForm]
  submit: []
  cancel: []
}>()

function updateField(key: keyof OrganizationForm, event: Event) {
  const value = (event.target as HTMLInputElement).value
  emit('update:form', { ...props.form, [key]: value })
}
</script>

<template>
  <form class="account-inline-form" @submit.prevent="emit('submit')">
    <div class="section-heading-row compact">
      <div>
        <span class="eyebrow">组织申请</span>
        <h2>
          {{
            isCorrection
              ? '补正组织主体资料'
              : organizationRole === 'EMPLOYER'
                ? '用工方主体资料'
                : '劳务供应商主体资料'
          }}
        </h2>
      </div>
    </div>
    <section v-if="isCorrection" class="account-correction-list" role="status">
      <strong>平台补正意见：{{ correctionReason }}</strong>
      <article v-for="item in correctionItems" :key="item.correctionItemId">
        <span>{{ item.title }}</span>
        <p>{{ item.description }}</p>
        <small>请完成：{{ item.applicantAction }}</small>
      </article>
    </section>
    <div class="account-form-grid">
      <label>
        <span>组织法定名称</span>
        <input
          :value="form.legalName"
          required
          maxlength="200"
          @input="updateField('legalName', $event)"
        />
      </label>
      <label>
        <span>统一社会信用代码</span>
        <input
          :value="form.registrationId"
          required
          maxlength="100"
          @input="updateField('registrationId', $event)"
        />
      </label>
      <label>
        <span>经办授权材料编号</span>
        <input
          :value="form.authorizationReference"
          required
          maxlength="200"
          @input="updateField('authorizationReference', $event)"
        />
      </label>
      <label v-if="organizationRole === 'LABOR_SUPPLIER'">
        <span>供应商资质材料编号</span>
        <input
          :value="form.qualificationReference"
          required
          maxlength="200"
          @input="updateField('qualificationReference', $event)"
        />
      </label>
    </div>
    <div class="account-form-actions">
      <button class="public-outline" type="button" @click="emit('cancel')">取消</button>
      <button class="public-primary" type="submit" :disabled="actionBusy">
        {{ actionBusy ? '正在提交…' : isCorrection ? '保存补正并重新提交审核' : '保存组织草稿' }}
      </button>
    </div>
  </form>
</template>
