<script setup lang="ts">
/**
 * VersionDrawer.vue — 版本历史抽屉（docs/07 §1 / §4.2）
 *
 * 能力：
 *   - GET  /api/boards/{id}/versions?page=&size=  时间轴（AUTO / MANUAL / PRE_RESTORE 中文标签 + 缩略图）
 *   - POST /api/boards/{id}/versions              存为版本（可填备注）
 *   - POST /api/boards/{id}/versions/{vid}/restore 恢复此版本（二次确认）
 *
 * 后端接口未上线 / 不可达时展示错误态与重试按钮，不会白屏。
 * 恢复成功后父页面负责刷新列表；若当前在房间里则由父页面重连以同步画布。
 */
import { computed, ref, watch } from 'vue'
import { ApiError, describeApiError } from '../auth/api'
import { formatDateTime, formatRelativeTime } from '../utils/time'
import {
  createVersion,
  listVersions,
  restoreVersion,
  versionKindLabel,
  type BoardVersion,
} from '../works/boardsApi'

const props = defineProps<{
  /** 目标白板 id（抽屉打开时按它拉版本） */
  boardId: string
  /** 白板名，仅用于标题 */
  boardName?: string
  /** owner 才能回滚（docs/07 §1.4） */
  canRestore?: boolean
  /** editor+ 才能手动存档（游客不可） */
  canCreate?: boolean
  /** 只读/无权限时给按钮的补充说明 */
  disabledReason?: string
}>()

const open = defineModel<boolean>('open', { default: false })

const emit = defineEmits<{
  (e: 'restored', version: BoardVersion): void
  (e: 'created', version: BoardVersion | null): void
  (e: 'notice', payload: { kind: 'info' | 'error'; text: string }): void
}>()

const PAGE_SIZE = 20

const loading = ref(false)
const loadingMore = ref(false)
const saving = ref(false)
const restoring = ref(false)
const errorText = ref('')
const createError = ref('')
const versions = ref<BoardVersion[]>([])
const total = ref(0)
const page = ref(1)
const labelDraft = ref('')
const confirmTarget = ref<BoardVersion | null>(null)

/** 记录请求序号，避免快速切换板子时旧响应覆盖新列表 */
let requestSeq = 0

const hasMore = computed(() => versions.value.length < total.value)
const title = computed(() => (props.boardName ? `版本历史 · ${props.boardName}` : '版本历史'))
const canSave = computed(() => props.canCreate !== false)
const canRestoreVersion = computed(() => props.canRestore === true)

function describeLoadError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404 || error.status === 405 || error.code === 'HTTP_404') {
      return '后端暂未提供版本接口（404），接口上线后这里会显示自动 / 手动版本。'
    }
  }
  return describeApiError(error)
}

async function load(reset = true): Promise<void> {
  if (!props.boardId) return
  const seq = ++requestSeq
  if (reset) {
    loading.value = true
    errorText.value = ''
  } else {
    loadingMore.value = true
  }

  const targetPage = reset ? 1 : page.value + 1
  try {
    const data = await listVersions(props.boardId, { page: targetPage, size: PAGE_SIZE })
    if (seq !== requestSeq) return
    versions.value = reset ? data.items : [...versions.value, ...data.items]
    total.value = data.total
    page.value = targetPage
    errorText.value = ''
  } catch (error) {
    if (seq !== requestSeq) return
    const message = describeLoadError(error)
    if (reset) {
      versions.value = []
      total.value = 0
      errorText.value = message
    } else {
      emit('notice', { kind: 'error', text: message })
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

async function submitCreate(): Promise<void> {
  if (!canSave.value || saving.value) return
  saving.value = true
  createError.value = ''
  try {
    const version = await createVersion(props.boardId, {
      label: labelDraft.value.trim() || undefined,
    })
    labelDraft.value = ''
    emit('notice', { kind: 'info', text: '已存为版本' })
    emit('created', version)
    await load(true)
  } catch (error) {
    createError.value = describeApiError(error)
  } finally {
    saving.value = false
  }
}

function askRestore(version: BoardVersion): void {
  if (!canRestoreVersion.value || restoring.value) return
  confirmTarget.value = version
}

function cancelRestore(): void {
  if (restoring.value) return
  confirmTarget.value = null
}

async function confirmRestore(): Promise<void> {
  const target = confirmTarget.value
  if (!target || restoring.value) return
  restoring.value = true
  try {
    await restoreVersion(props.boardId, target.id)
    confirmTarget.value = null
    emit('notice', { kind: 'info', text: `已恢复到 v${target.versionNo ?? ''}，画布已重新同步` })
    emit('restored', target)
    // 回滚会新生成一条 PRE_RESTORE 版本，刷新时间轴
    await load(true)
  } catch (error) {
    emit('notice', { kind: 'error', text: describeApiError(error) })
  } finally {
    restoring.value = false
  }
}

function versionTitle(version: BoardVersion): string {
  const parts: string[] = []
  if (version.versionNo !== null) parts.push(`v${version.versionNo}`)
  if (version.docSeq !== null) parts.push(`seq ${version.docSeq}`)
  return parts.join(' · ')
}

// 打开时拉取；切换板子时重置
watch(
  () => [open.value, props.boardId] as const,
  ([isOpen, boardId], previous) => {
    if (!isOpen) {
      confirmTarget.value = null
      return
    }
    const boardChanged = !previous || previous[1] !== boardId
    if (boardChanged) {
      versions.value = []
      total.value = 0
      page.value = 1
      errorText.value = ''
      createError.value = ''
      labelDraft.value = ''
    }
    void load(true)
  },
  { immediate: true },
)
</script>

<template>
  <Teleport to="body">
    <transition name="drawer">
      <div v-if="open" class="drawer-backdrop" @click.self="open = false">
        <aside class="drawer drawer--wide" role="dialog" aria-modal="true" :aria-label="title">
          <header class="drawer__head">
            <div>
              <h2 class="drawer__title">{{ title }}</h2>
              <p class="drawer__sub">
                自动存档每 5 分钟一次；回滚不会删除任何历史（docs/07 §1）
              </p>
            </div>
            <button class="icon-btn" type="button" aria-label="关闭版本历史" @click="open = false">
              ✕
            </button>
          </header>

          <div class="drawer__body">
            <section class="version-save">
              <div class="setting-group__label">存为版本</div>
              <div class="version-save__row">
                <input
                  v-model="labelDraft"
                  class="input"
                  type="text"
                  maxlength="100"
                  placeholder="备注（可选），例如：线稿完成"
                  :disabled="!canSave || saving"
                  @keydown.enter.prevent="submitCreate"
                />
                <button
                  class="btn btn--primary"
                  type="button"
                  :disabled="!canSave || saving"
                  :title="canSave ? '立即存档当前文档' : disabledReason || '当前角色不能存档'"
                  @click="submitCreate"
                >
                  {{ saving ? '存档中…' : '存为版本' }}
                </button>
              </div>
              <p v-if="!canSave" class="setting-group__hint">
                {{ disabledReason || '当前角色没有存档权限（需 editor 及以上，游客不可）' }}
              </p>
              <p v-if="createError" class="form-error" role="alert">{{ createError }}</p>
            </section>

            <div v-if="loading" class="version-list" aria-label="版本加载中">
              <div v-for="index in 3" :key="index" class="version-skeleton" aria-hidden="true">
                <div class="version-skeleton__thumb" />
                <div class="version-skeleton__lines">
                  <div class="skeleton-line skeleton-line--title" />
                  <div class="skeleton-line skeleton-line--short" />
                </div>
              </div>
            </div>

            <section v-else-if="errorText" class="version-state">
              <div class="works-state__icon works-state__icon--error" aria-hidden="true">!</div>
              <p>{{ errorText }}</p>
              <button class="btn btn--xs" type="button" @click="load(true)">重试</button>
            </section>

            <section v-else-if="versions.length === 0" class="version-state">
              <div class="works-state__icon" aria-hidden="true">版</div>
              <p>还没有版本。画几笔后点「存为版本」，或等待自动存档。</p>
            </section>

            <ol v-else class="version-list">
              <li v-for="version in versions" :key="version.id" class="version-item">
                <div class="version-item__thumb">
                  <img v-if="version.thumbnail" :src="version.thumbnail" :alt="`v${version.versionNo ?? ''} 缩略图`" />
                  <span v-else class="version-item__empty" aria-hidden="true">画</span>
                </div>

                <div class="version-item__body">
                  <div class="version-item__head">
                    <span class="version-badge" :class="`version-badge--${version.kind.toLowerCase()}`">
                      {{ versionKindLabel(version.kind) }}
                    </span>
                    <span v-if="version.versionNo !== null" class="version-item__no">
                      v{{ version.versionNo }}
                    </span>
                    <span v-if="version.docSeq !== null" class="version-item__seq">seq {{ version.docSeq }}</span>
                  </div>
                  <p v-if="version.label" class="version-item__label">{{ version.label }}</p>
                  <p class="version-item__meta" :title="versionTitle(version)">
                    <span :title="formatDateTime(version.createdAt)">
                      {{ formatRelativeTime(version.createdAt) }}
                    </span>
                    <span class="dot-sep">·</span>
                    <span>{{ version.authorName || '系统' }}</span>
                  </p>
                </div>

                <button
                  class="btn btn--xs version-item__restore"
                  type="button"
                  :disabled="!canRestoreVersion || restoring"
                  :title="canRestoreVersion ? '回滚到此版本（会先保存当前状态）' : '仅房主可回滚'"
                  @click="askRestore(version)"
                >
                  恢复此版本
                </button>
              </li>
            </ol>

            <div v-if="!loading && !errorText && hasMore" class="version-more">
              <button class="btn btn--xs" type="button" :disabled="loadingMore" @click="load(false)">
                {{ loadingMore ? '加载中…' : `加载更多（${versions.length}/${total}）` }}
              </button>
            </div>
          </div>

          <footer class="drawer__foot">
            <span class="drawer__foot-hint">共 {{ total }} 个版本</span>
            <button class="btn btn--primary btn--xs" type="button" @click="open = false">关闭</button>
          </footer>
        </aside>
      </div>
    </transition>

    <!-- 回滚二次确认（docs/07 §4.2） -->
    <div v-if="confirmTarget" class="modal-backdrop modal-backdrop--top" @click.self="cancelRestore">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="restore-title">
        <h2 id="restore-title">恢复此版本？</h2>
        <p class="modal__desc">
          将把「{{ versionKindLabel(confirmTarget.kind) }}
          <template v-if="confirmTarget.versionNo !== null">v{{ confirmTarget.versionNo }}</template>
          <template v-if="confirmTarget.label">· {{ confirmTarget.label }}</template>」的内容覆盖当前画布。
          回滚前会自动把当前内容存为一条「回滚前存档」，历史不会丢失。
        </p>
        <p class="modal__desc">若房间里还有其他人在画，服务端会广播全量重同步。</p>
        <div class="modal__actions">
          <button class="btn" type="button" :disabled="restoring" @click="cancelRestore">取消</button>
          <button class="btn btn--danger" type="button" :disabled="restoring" @click="confirmRestore">
            {{ restoring ? '恢复中…' : '确认恢复' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
