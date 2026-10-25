<script setup lang="ts">
import { computed, ref } from 'vue'
import { authApi } from './api/auth'
import { setToken } from '../../platform/session/session'
import PublicHeader from '../../shared/ui/PublicHeader.vue'

type AuthMode = 'login' | 'register' | 'password-recovery'
type LoginMethod = 'sms' | 'password'

const props = defineProps<{ mode: AuthMode }>()
const emit = defineEmits<{ navigate: [path: string] }>()

const phone = ref('')
const password = ref('')
const displayName = ref('')
const code = ref('')
const newPassword = ref('')
const challengeId = ref('')
const challengeSent = ref(false)
const recoveryVerified = ref(false)
const resetTicket = ref<{ ticketId: string; resetToken: string }>()
const acceptedTerms = ref(false)
const loginMethod = ref<LoginMethod>('sms')
const busy = ref(false)
const message = ref('')
const mockCode = ref('')

const title = computed(
  () =>
    ({
      login: '登录劳务供需平台',
      register: '创建个人账号',
      'password-recovery': '找回登录密码',
    })[props.mode]
)

const activeRoute = computed(() => (props.mode === 'register' ? 'register' : 'login'))

async function startChallenge() {
  message.value = ''
  mockCode.value = ''
  if (!/^1\d{10}$/.test(phone.value.trim())) {
    message.value = '请输入有效的中国大陆手机号。'
    return
  }
  busy.value = true
  try {
    const purpose =
      props.mode === 'register'
        ? 'register'
        : props.mode === 'password-recovery'
          ? 'password-reset'
          : 'login'
    const challenge = await authApi.startChallenge(phone.value.trim(), purpose)
    challengeId.value = challenge.challengeId
    challengeSent.value = true
    if (import.meta.env.DEV) {
      try {
        mockCode.value = (await authApi.mockSms(challenge.challengeId)).code
      } catch {
        mockCode.value = ''
      }
    }
    message.value = challenge.message
  } catch (error) {
    message.value = error instanceof Error ? error.message : '验证码暂时无法发送，请稍后重试。'
  } finally {
    busy.value = false
  }
}

async function submitRegistration() {
  if (!challengeSent.value || !acceptedTerms.value) {
    message.value = '请先获取验证码并确认用户协议与隐私告知。'
    return
  }
  const result = await authApi.register({
    phone: phone.value.trim(),
    challengeId: challengeId.value,
    code: code.value.trim(),
    password: password.value,
    displayName: displayName.value.trim() || undefined,
    termsVersion: 'terms-v1',
    privacyVersion: 'privacy-v1',
  })
  setToken(result.accessToken)
  emit('navigate', '/account')
}

async function submitLogin() {
  const result =
    loginMethod.value === 'password'
      ? await authApi.loginByPassword(phone.value.trim(), password.value)
      : challengeId.value
        ? await authApi.loginBySms(challengeId.value, code.value.trim())
        : undefined
  if (!result) {
    message.value = '请先获取短信验证码。'
    return
  }
  setToken(result.accessToken)
  emit('navigate', '/account')
}

async function submitPasswordRecovery() {
  if (!challengeSent.value) {
    message.value = '请先获取短信验证码。'
    return
  }
  if (!recoveryVerified.value) {
    const ticket = await authApi.verifyPasswordReset(challengeId.value, code.value.trim())
    resetTicket.value = { ticketId: ticket.ticketId, resetToken: ticket.resetToken }
    recoveryVerified.value = true
    message.value = '手机号验证通过，请设置新密码。'
    return
  }
  if (!resetTicket.value) {
    message.value = '重置凭据已失效，请重新验证手机号。'
    return
  }
  await authApi.completePasswordReset({
    ticketId: resetTicket.value.ticketId,
    resetToken: resetTicket.value.resetToken,
    newPassword: newPassword.value,
  })
  emit('navigate', '/login')
}

async function submit() {
  message.value = ''
  busy.value = true
  try {
    if (props.mode === 'register') {
      await submitRegistration()
    } else if (props.mode === 'login') {
      await submitLogin()
    } else {
      await submitPasswordRecovery()
    }
  } catch (error) {
    message.value = error instanceof Error ? error.message : '操作暂时无法完成，请检查输入后重试。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="public-site auth-site">
    <PublicHeader
      :active="activeRoute"
      @navigate="emit('navigate', $event)"
      @account="emit('navigate', '/login')"
    />
    <main class="auth-main">
      <section class="auth-card">
        <span class="eyebrow">个人账号 · 手机号验证</span>
        <h1>{{ title }}</h1>
        <p class="auth-lede">
          个人账号与组织资格分别管理。登录后仍需通过组织审核和成员授权，才能进入业务空间。
        </p>

        <div v-if="mode === 'login'" class="auth-method-switch" aria-label="登录方式">
          <button
            type="button"
            :class="{ selected: loginMethod === 'sms' }"
            @click="loginMethod = 'sms'"
          >
            短信验证码
          </button>
          <button
            type="button"
            :class="{ selected: loginMethod === 'password' }"
            @click="loginMethod = 'password'"
          >
            备用密码
          </button>
        </div>

        <form class="auth-form" @submit.prevent="submit">
          <label>
            <span>手机号</span>
            <input
              v-model="phone"
              type="tel"
              inputmode="tel"
              autocomplete="tel"
              maxlength="32"
              placeholder="请输入中国大陆手机号"
              required
            />
          </label>

          <label v-if="mode === 'register'">
            <span>
              称呼
              <small>选填</small>
            </span>
            <input
              v-model="displayName"
              autocomplete="name"
              maxlength="100"
              placeholder="用于账号中心显示"
            />
          </label>

          <template v-if="mode !== 'login' || loginMethod === 'sms'">
            <label
              v-if="
                mode === 'register' ||
                mode === 'login' ||
                (mode === 'password-recovery' && !recoveryVerified)
              "
            >
              <span>短信验证码</span>
              <span class="auth-code-row">
                <input
                  v-model="code"
                  inputmode="numeric"
                  autocomplete="one-time-code"
                  maxlength="6"
                  placeholder="6 位验证码"
                  required
                />
                <button
                  class="auth-code-button"
                  type="button"
                  :disabled="busy"
                  @click="startChallenge"
                >
                  {{ challengeSent ? '重新获取' : '获取验证码' }}
                </button>
              </span>
            </label>
          </template>

          <label v-if="mode === 'register' || (mode === 'login' && loginMethod === 'password')">
            <span>{{ mode === 'register' ? '设置备用密码' : '备用密码' }}</span>
            <input
              v-model="password"
              type="password"
              :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
              minlength="8"
              maxlength="128"
              placeholder="至少 8 位，用于备用登录"
              required
            />
          </label>

          <label v-if="mode === 'password-recovery' && recoveryVerified">
            <span>设置新密码</span>
            <input
              v-model="newPassword"
              type="password"
              autocomplete="new-password"
              minlength="8"
              maxlength="128"
              placeholder="至少 8 位"
              required
            />
          </label>

          <label v-if="mode === 'register'" class="auth-consent">
            <input v-model="acceptedTerms" type="checkbox" required />
            <span>我已阅读并同意用户协议与隐私告知（当前版本 terms-v1 / privacy-v1）。</span>
          </label>

          <p v-if="message" class="auth-message" role="status">{{ message }}</p>
          <p v-if="mockCode" class="auth-mock-code" role="status">
            本地 Mock SMS 验证码：
            <strong>{{ mockCode }}</strong>
          </p>

          <button class="public-primary auth-submit" type="submit" :disabled="busy">
            {{
              busy
                ? '正在处理…'
                : mode === 'register'
                  ? '验证手机号并创建账号'
                  : mode === 'login'
                    ? '登录'
                    : recoveryVerified
                      ? '保存新密码'
                      : '验证手机号'
            }}
          </button>
        </form>

        <div class="auth-footer-links">
          <button v-if="mode !== 'login'" type="button" @click="emit('navigate', '/login')">
            返回登录
          </button>
          <button v-if="mode === 'login'" type="button" @click="emit('navigate', '/register')">
            创建个人账号
          </button>
          <button
            v-if="mode === 'login'"
            type="button"
            @click="emit('navigate', '/password-recovery')"
          >
            忘记密码？
          </button>
        </div>
      </section>
    </main>
  </div>
</template>
