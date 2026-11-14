<script setup lang="ts">
/**
 * LoginView.vue — 登录 / 注册 / 游客入口（同页 tab 切换）
 *
 * 表单校验与 docs/05 §1 保持一致；接口失败展示后端 message，
 * 后端不可达时给出「无法连接服务器」和离线进入画布的兜底入口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError, describeApiError } from '../auth/api'
import {
  currentUser,
  ensureGuest,
  isGuest,
  isLoggedIn,
  login,
  register,
  upgrade,
  type RegisterInput,
} from '../auth/session'
import { boardId as newBoardId } from '../kernel/ids'

type Mode = 'login' | 'register'
type FieldName = 'username' | 'password' | 'nickname'

const USERNAME_PATTERN = /^[a-zA-Z0-9_]{4,32}$/

const route = useRoute()
const router = useRouter()

const mode = ref<Mode>('login')
const form = reactive({ username: '', password: '', nickname: '' })
const errors = reactive<Record<FieldName, string>>({ username: '', password: '', nickname: '' })
const serverError = ref('')
const submitting = ref(false)
const guestLoading = ref(false)
const offlineFallback = ref(false)

/** 游客状态用 ref 跟踪，401 清 token 后能及时从「升级」改回「注册」 */
const guestSession = ref(isGuest())
const loggedInUser = ref(currentUser())

const isRegister = computed(() => mode.value === 'register')
const guestButtonLabel = computed(() =>
  loggedInUser.value && !loggedInUser.value.guest ? '进入我的作品库' : '以游客身份进入',
)
const registeredNotice = computed(() => {
  const user = loggedInUser.value
  if (!user || user.guest) return ''
  return `当前已登录：${user.nickname}`
})

onMounted(() => {
  if (route.query.mode === 'register') mode.value = 'register'
  guestSession.value = isGuest()
  loggedInUser.value = currentUser()
})

function clearField(field: FieldName): void {
  errors[field] = ''
  serverError.value = ''
  offlineFallback.value = false
}

function clearErrors(): void {
  errors.username = ''
  errors.password = ''
  errors.nickname = ''
  serverError.value = ''
  offlineFallback.value = false
}

function switchMode(next: Mode): void {
  if (submitting.value) return
  mode.value = next
  clearErrors()
}

function validatePassword(value: string): boolean {
  return value.length >= 8 && /[A-Za-z]/.test(value) && /\d/.test(value)
}

function validate(): boolean {
  clearErrors()
  const username = form.username.trim()
  if (!username) {
    errors.username = '请输入用户名'
  } else if (!USERNAME_PATTERN.test(username)) {
    errors.username = '用户名为 4–32 位字母、数字或下划线'
  }

  if (!form.password) {
    errors.password = '请输入密码'
  } else if (!validatePassword(form.password)) {
    errors.password = '密码至少 8 位，且同时包含字母和数字'
  }

  if (isRegister.value) {
    const nickname = form.nickname.trim()
    const nicknameLength = Array.from(nickname).length
    if (nicknameLength === 0) {
      errors.nickname = '请输入昵称'
    } else if (nicknameLength > 20) {
      errors.nickname = '昵称最多 20 个字符'
    }
  }

  return !errors.username && !errors.password && !errors.nickname
}

/** 只接受站内路径，避免 ?redirect= 被用作开放跳转 */
function safeRedirect(fallback: string): string {
  const raw = route.query.redirect
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')) {
    return value
  }
  return fallback
}

async function handleSubmit(): Promise<void> {
  if (submitting.value || !validate()) return

  submitting.value = true
  serverError.value = ''
  offlineFallback.value = false
  try {
    if (isRegister.value) {
      const input: RegisterInput = {
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim() || form.username.trim(),
      }
      // 已有游客会话时走升级接口，保留 user.id 与此前参与的板（docs/05 §1.4）
      if (guestSession.value) {
        await upgrade(input)
      } else {
        await register(input)
      }
    } else {
      await login(form.username.trim(), form.password)
    }

    guestSession.value = false
    loggedInUser.value = currentUser()
    await router.replace(safeRedirect('/home'))
  } catch (error) {
    serverError.value = describeApiError(error)
    guestSession.value = isGuest()
    loggedInUser.value = currentUser()
    if (error instanceof ApiError && error.network) offlineFallback.value = true
  } finally {
    submitting.value = false
  }
}

async function handleGuest(): Promise<void> {
  if (guestLoading.value) return
  serverError.value = ''
  offlineFallback.value = false

  // 已是正式登录：直接回主界面，不重复建游客
  if (isLoggedIn() && currentUser() && currentUser()?.guest !== true) {
    await router.replace(safeRedirect('/home'))
    return
  }

  guestLoading.value = true
  try {
    const nickname = `游客${Math.floor(1000 + Math.random() * 9000)}`
    await ensureGuest(nickname)
    guestSession.value = isGuest()
    loggedInUser.value = currentUser()

    const target = safeRedirect('')
    await router.replace(target || '/home')
  } catch (error) {
    serverError.value = describeApiError(error)
    offlineFallback.value = true
    guestSession.value = isGuest()
  } finally {
    guestLoading.value = false
  }
}

function enterOfflineBoard(): void {
  void router.replace(`/board/${newBoardId()}`)
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-wrap">
      <header class="auth-brand">
        <div class="brand__mark" aria-hidden="true">画</div>
        <div>
          <h1>协同白板</h1>
          <p>登录后创建、管理并协作你的白板作品</p>
        </div>
      </header>

      <section class="auth-card">
        <div v-if="registeredNotice" class="notice notice--inline">
          {{ registeredNotice }}
        </div>

        <div class="auth-tabs" role="tablist" aria-label="登录或注册">
          <button
            class="auth-tab"
            :class="{ 'is-active': mode === 'login' }"
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            class="auth-tab"
            :class="{ 'is-active': mode === 'register' }"
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            @click="switchMode('register')"
          >
            注册
          </button>
        </div>

        <p v-if="guestSession" class="notice">
          当前为游客身份。注册后将保留你的协作记录，并可直接进入作品库。
        </p>

        <form novalidate @submit.prevent="handleSubmit">
          <div class="field">
            <label class="field__label" for="auth-username">用户名</label>
            <input
              id="auth-username"
              v-model="form.username"
              class="input"
              :class="{ 'is-invalid': errors.username }"
              type="text"
              autocomplete="username"
              maxlength="32"
              placeholder="4–32 位字母、数字或下划线"
              @input="clearField('username')"
            />
            <p v-if="errors.username" class="field__error">{{ errors.username }}</p>
          </div>

          <div class="field">
            <label class="field__label" for="auth-password">密码</label>
            <input
              id="auth-password"
              v-model="form.password"
              class="input"
              :class="{ 'is-invalid': errors.password }"
              type="password"
              :autocomplete="isRegister ? 'new-password' : 'current-password'"
              placeholder="至少 8 位，含字母与数字"
              @input="clearField('password')"
            />
            <p v-if="errors.password" class="field__error">{{ errors.password }}</p>
          </div>

          <div v-if="isRegister" class="field">
            <label class="field__label" for="auth-nickname">昵称</label>
            <input
              id="auth-nickname"
              v-model="form.nickname"
              class="input"
              :class="{ 'is-invalid': errors.nickname }"
              type="text"
              autocomplete="nickname"
              maxlength="20"
              placeholder="1–20 个字符（缺省取用户名）"
              @input="clearField('nickname')"
            />
            <p v-if="errors.nickname" class="field__error">{{ errors.nickname }}</p>
          </div>

          <p v-if="serverError" class="form-alert" role="alert">{{ serverError }}</p>

          <button class="btn btn--primary auth-submit" type="submit" :disabled="submitting">
            {{ submitting ? '请稍候…' : isRegister ? '注册并进入作品库' : '登录' }}
          </button>
        </form>

        <div class="auth-divider"><span>或</span></div>

        <button
          class="btn btn--block"
          type="button"
          :disabled="guestLoading || submitting"
          @click="handleGuest"
        >
          {{ guestLoading ? '正在创建游客会话…' : guestButtonLabel }}
        </button>
        <p class="field__hint auth-hint">
          游客可加入公开白板协作，但不能创建或保存作品。
        </p>

        <div v-if="offlineFallback" class="offline-cta">
          <p>后端接口不可达，你也可以先离线体验画布（内容不会保存）。</p>
          <button class="btn btn--xs" type="button" @click="enterOfflineBoard">离线进入画布</button>
        </div>

        <p class="auth-foot">
          接口不可达时页面会显示错误提示；已登录用户可直接回到
          <RouterLink to="/home">主界面</RouterLink>。
        </p>
      </section>
    </div>
  </div>
</template>
