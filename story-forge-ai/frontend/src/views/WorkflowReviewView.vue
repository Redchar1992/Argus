<script setup lang="ts">
import {
  ArrowLeft,
  Check,
  CircleCheck,
  DocumentChecked,
  EditPen,
  MagicStick,
  RefreshRight,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import EmptyState from '@/components/EmptyState.vue'
import { useWorkflowStore } from '@/stores/workflow'
import type { CharacterCard, WorkflowReviewVersion } from '@/types'
import { getErrorMessage } from '@/utils/error'

const route = useRoute()
const router = useRouter()
const workflowStore = useWorkflowStore()
const loading = ref(true)
const loadError = ref('')
const activeCharacterId = ref('')
const selectedVersionNo = ref<number>()
const reviewNotes = ref('')

const taskId = computed(() => String(route.params.taskId))
const storyId = computed(() => String(route.params.storyId))
const review = computed(() => workflowStore.reviews[taskId.value])
const isCompleted = computed(() => review.value?.status === 'SUCCESS')
const activeCharacter = computed(
  () =>
    review.value?.characters.find((character) => character.id === activeCharacterId.value) ??
    review.value?.characters[0],
)
const selectedVersion = computed<WorkflowReviewVersion | undefined>(() =>
  review.value?.versions.find((version) => version.versionNo === selectedVersionNo.value),
)
const displayedOutline = computed(
  () => selectedVersion.value?.outline.length ? selectedVersion.value.outline : review.value?.outline ?? [],
)
const displayedScore = computed(() => selectedVersion.value?.score ?? review.value?.score)
const twistCount = computed(
  () => displayedOutline.value.filter((node) => node.isTwist).length,
)
const isExactOutline = computed(
  () =>
    displayedOutline.value.length === 20 &&
    displayedOutline.value.every((node, index) => node.nodeNo === index + 1),
)
const isValidCharacterPack = computed(() => {
  const count = review.value?.characters.length ?? 0
  return count >= 3 && count <= 6
})
const hasFiveScores = computed(() => displayedScore.value?.dimensions.length === 5)
const structureValid = computed(
  () => isExactOutline.value && isValidCharacterPack.value && hasFiveScores.value,
)
const isCurrentVersion = computed(
  () =>
    selectedVersionNo.value === undefined ||
    review.value?.versionNo === undefined ||
    selectedVersionNo.value === review.value.versionNo,
)
const canApprove = computed(
  () => !isCompleted.value && structureValid.value && isCurrentVersion.value,
)

const scoreTone = computed(() => {
  const score = displayedScore.value?.total ?? 0
  if (score >= 90) return 'excellent'
  if (score >= 80) return 'good'
  if (score >= 70) return 'pass'
  return 'risk'
})

const versionOptions = computed(() => {
  if (!review.value) return []
  const options = [...review.value.versions]
  if (
    review.value.versionNo !== undefined &&
    !options.some((version) => version.versionNo === review.value?.versionNo)
  ) {
    options.push({
      versionNo: review.value.versionNo,
      label: isCompleted.value ? '正式版本' : '当前审核版',
      outline: review.value.outline,
      score: review.value.score,
    })
  }
  return options.sort((left, right) => right.versionNo - left.versionNo)
})

function chooseCharacter(character: CharacterCard) {
  activeCharacterId.value = character.id
}

async function loadReview() {
  loading.value = true
  loadError.value = ''
  workflowStore.restoreTask(taskId.value, storyId.value)
  try {
    const result = await workflowStore.fetchReview(taskId.value)
    activeCharacterId.value = result.characters[0]?.id ?? ''
    selectedVersionNo.value = result.versionNo
    reviewNotes.value = result.reviewNotes ?? ''
  } catch (error) {
    loadError.value = getErrorMessage(error, '审核内容加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function submitDecision(approved: boolean) {
  if (isCompleted.value) return
  if (approved && !canApprove.value) {
    ElMessage.warning('当前产物未通过结构校验，暂时不能批准')
    return
  }
  const notes = reviewNotes.value.trim()
  if (!approved && notes.length < 8) {
    ElMessage.warning('请填写至少 8 个字符的具体修改意见')
    return
  }

  try {
    const nextTask = await workflowStore.submitReview(taskId.value, {
      approved,
      notes,
    })
    ElMessage.success(approved ? '已批准，正在保存正式版本' : '修改意见已提交，工作流继续执行')
    await router.replace({
      name: 'workflow-progress',
      params: {
        storyId: nextTask.storyId ?? storyId.value,
        taskId: nextTask.taskId,
      },
    })
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '审核决定提交失败，请稍后重试。'))
  }
}

watch(
  () => selectedVersionNo.value,
  () => {
    window.requestAnimationFrame(() => window.scrollTo({ top: 0, behavior: 'smooth' }))
  },
)

onMounted(loadReview)
</script>

<template>
  <div class="review-page">
    <div v-if="loading" class="review-loading">
      <div class="loading-mark"><DocumentChecked /></div>
      <strong>正在整理审核材料</strong>
      <span>加载人物卡、20 节点大纲和五维评分…</span>
    </div>

    <EmptyState
      v-else-if="loadError"
      title="暂时无法加载审核内容"
      :description="loadError"
      action-label="重新加载"
      @action="loadReview"
    />

    <template v-else-if="review">
      <header class="review-header">
        <button
          type="button"
          class="back-button"
          @click="
            router.push({
              name: 'workflow-progress',
              params: { storyId, taskId },
            })
          "
        >
          <el-icon><ArrowLeft /></el-icon>
          返回生成进度
        </button>

        <div class="header-main">
          <div>
            <span class="section-kicker">
              {{ isCompleted ? 'FINAL VERSION' : 'HUMAN REVIEW' }} · TASK #{{ taskId }}
            </span>
            <h2>{{ review.title || '完整故事创作方案' }}</h2>
            <p>
              {{
                isCompleted
                  ? '该方案已批准并保存为正式版本。人物卡、大纲、评分和历史版本保持只读。'
                  : 'AI 工作流已暂停。请检查人物动力、剧情因果和商业评分，再决定通过或修改。'
              }}
            </p>
          </div>
          <div class="header-tools">
            <el-select
              v-if="versionOptions.length > 1"
              v-model="selectedVersionNo"
              class="version-select"
              placeholder="选择版本"
            >
              <el-option
                v-for="version in versionOptions"
                :key="version.versionNo"
                :label="version.label || `大纲版本 V${version.versionNo}`"
                :value="version.versionNo"
              />
            </el-select>
            <span class="review-badge" :class="{ completed: isCompleted }">
              <i />
              {{ isCompleted ? '已批准 · 正式版本' : '等待你的审核' }}
            </span>
          </div>
        </div>

        <div class="review-facts">
          <div>
            <strong>{{ review.characters.length }}</strong>
            <span>人物角色</span>
          </div>
          <div :class="{ invalid: !isExactOutline }">
            <strong>{{ displayedOutline.length }}</strong>
            <span>剧情节点</span>
          </div>
          <div>
            <strong>{{ twistCount }}</strong>
            <span>有效反转</span>
          </div>
          <div>
            <strong>V{{ selectedVersionNo ?? review.versionNo ?? 1 }}</strong>
            <span>当前版本</span>
          </div>
        </div>
      </header>

      <div v-if="!structureValid" class="integrity-warning">
        <el-icon><Warning /></el-icon>
        <div>
          <strong>结构校验未通过</strong>
          <span>
            {{
              !isValidCharacterPack
                ? '人物数量必须为 3–6 名。'
                : !isExactOutline
                  ? '大纲必须包含编号连续的恰好 20 个节点。'
                  : '评分必须完整包含五个维度。'
            }}
            {{
              isCompleted
                ? '正式版本保持只读，请联系管理员处理异常产物。'
                : '你可以填写意见要求 AI 修订，但暂时不能批准。'
            }}
          </span>
        </div>
      </div>

      <div v-else-if="!isCurrentVersion" class="history-warning">
        <el-icon><DocumentChecked /></el-icon>
        <div>
          <strong>正在查看历史版本 V{{ selectedVersionNo }}</strong>
          <span>
            {{
              isCompleted
                ? '历史产物仅供比较，已批准的正式版本保持只读。'
                : '历史产物仅供比较。切换回当前版本后，才能提交审核决定。'
            }}
          </span>
        </div>
      </div>

      <main class="review-grid">
        <aside class="characters-column panel">
          <header class="panel-heading">
            <div class="panel-icon"><User /></div>
            <div>
              <span>CHARACTERS</span>
              <h3>人物卡</h3>
            </div>
            <strong>{{ review.characters.length }}</strong>
          </header>

          <div class="character-tabs">
            <button
              v-for="character in review.characters"
              :key="character.id"
              type="button"
              :class="{ active: activeCharacter?.id === character.id }"
              @click="chooseCharacter(character)"
            >
              <span class="character-avatar">{{ character.name.slice(0, 1) }}</span>
              <span>
                <strong>{{ character.name }}</strong>
                <small>{{ character.role }}</small>
              </span>
              <i />
            </button>
          </div>

          <div v-if="activeCharacter" class="character-detail">
            <div class="character-title">
              <span>{{ activeCharacter.role }}</span>
              <h4>{{ activeCharacter.name }}</h4>
              <p>{{ activeCharacter.publicIdentity || '公开身份待补充' }}</p>
            </div>

            <div v-if="activeCharacter.personality.length" class="traits">
              <span v-for="trait in activeCharacter.personality" :key="trait">{{ trait }}</span>
            </div>

            <dl>
              <div>
                <dt>核心欲望</dt>
                <dd>{{ activeCharacter.coreDesire || '—' }}</dd>
              </div>
              <div class="secret">
                <dt>隐藏秘密</dt>
                <dd>{{ activeCharacter.hiddenSecret || '—' }}</dd>
              </div>
              <div>
                <dt>最大恐惧</dt>
                <dd>{{ activeCharacter.greatestFear || '—' }}</dd>
              </div>
              <div>
                <dt>主角关系</dt>
                <dd>{{ activeCharacter.relationshipToProtagonist || '—' }}</dd>
              </div>
              <div>
                <dt>人物弧光</dt>
                <dd>{{ activeCharacter.characterArc || '—' }}</dd>
              </div>
            </dl>
          </div>
        </aside>

        <section class="outline-column panel">
          <header class="panel-heading outline-heading">
            <div class="panel-icon"><DocumentChecked /></div>
            <div>
              <span>20-NODE OUTLINE</span>
              <h3>剧情大纲</h3>
            </div>
            <span class="valid-chip" :class="{ invalid: !isExactOutline }">
              <el-icon v-if="isExactOutline"><Check /></el-icon>
              {{ isExactOutline ? '20 节点完整' : `${displayedOutline.length}/20` }}
            </span>
          </header>

          <div v-if="review.coreConflict || review.endingType" class="outline-meta">
            <div>
              <span>核心冲突</span>
              <strong>{{ review.coreConflict || '—' }}</strong>
            </div>
            <div>
              <span>结局类型</span>
              <strong>{{ review.endingType || '—' }}</strong>
            </div>
          </div>

          <div class="outline-list">
            <article
              v-for="node in displayedOutline"
              :key="`${selectedVersionNo}-${node.nodeNo}`"
              class="outline-node"
              :class="{ twist: node.isTwist }"
            >
              <div class="node-rail">
                <strong>{{ String(node.nodeNo).padStart(2, '0') }}</strong>
                <i />
              </div>
              <div class="node-content">
                <div class="node-topline">
                  <span class="stage">{{ node.stage }}</span>
                  <span v-if="node.isTwist" class="twist-chip">
                    <MagicStick />
                    有效反转
                  </span>
                  <span v-if="node.emotionalTarget" class="emotion">
                    {{ node.emotionalTarget }}
                  </span>
                </div>
                <h4>{{ node.event }}</h4>
                <p v-if="node.conflict"><b>冲突</b>{{ node.conflict }}</p>
                <div class="node-details">
                  <div v-if="node.protagonistGoal">
                    <span>主角目标</span>
                    <p>{{ node.protagonistGoal }}</p>
                  </div>
                  <div v-if="node.newInformation">
                    <span>新增信息</span>
                    <p>{{ node.newInformation }}</p>
                  </div>
                  <div v-if="node.cliffhanger">
                    <span>节点悬念</span>
                    <p>{{ node.cliffhanger }}</p>
                  </div>
                  <div v-if="node.setupOrPayoff">
                    <span>伏笔 / 回收</span>
                    <p>{{ node.setupOrPayoff }}</p>
                  </div>
                </div>
              </div>
            </article>
          </div>
        </section>

        <aside class="score-column panel">
          <header class="panel-heading">
            <div class="panel-icon"><MagicStick /></div>
            <div>
              <span>EDITOR SCORE</span>
              <h3>评分与建议</h3>
            </div>
          </header>

          <div v-if="displayedScore" class="score-content">
            <div
              class="total-score"
              :class="scoreTone"
              :style="{ '--score-angle': `${displayedScore.total * 3.6}deg` }"
            >
              <div>
                <strong>{{ displayedScore.total }}</strong>
                <span>总分 · {{ displayedScore.level }} 级</span>
              </div>
            </div>

            <div class="dimension-list">
              <article
                v-for="dimension in displayedScore.dimensions"
                :key="dimension.key"
              >
                <div class="dimension-heading">
                  <span>{{ dimension.label }}</span>
                  <strong>{{ dimension.score }}<small>/20</small></strong>
                </div>
                <i><b :style="{ width: `${dimension.score * 5}%` }" /></i>
                <p>{{ dimension.reason || '暂无评分说明' }}</p>
                <div v-if="dimension.suggestion" class="dimension-suggestion">
                  <span>建议</span>
                  {{ dimension.suggestion }}
                </div>
              </article>
            </div>

            <div v-if="displayedScore.fatalProblem" class="fatal-problem">
              <span><Warning /> 最致命问题</span>
              <p>{{ displayedScore.fatalProblem }}</p>
            </div>

            <div v-if="displayedScore.revisionPriority.length" class="priorities">
              <span>修改优先级</span>
              <ol>
                <li
                  v-for="priority in displayedScore.revisionPriority"
                  :key="priority"
                >
                  {{ priority }}
                </li>
              </ol>
            </div>
          </div>
        </aside>
      </main>

      <section v-if="isCompleted" class="final-panel">
        <el-icon><CircleCheck /></el-icon>
        <div>
          <span class="section-kicker">APPROVED</span>
          <h3>正式版本已保存</h3>
          <p>本页仅用于重新查看最终结果和历史版本，不再接受批准或修改操作。</p>
        </div>
      </section>

      <section v-else class="decision-panel">
        <div class="decision-heading">
          <div>
            <span class="section-kicker">YOUR DECISION</span>
            <h3>留下具体判断</h3>
            <p>不满意时，请指出节点和因果问题；工作流会在同一线程内继续修改。</p>
          </div>
          <span>{{ reviewNotes.length }}/1000</span>
        </div>
        <el-input
          v-model="reviewNotes"
          type="textarea"
          :rows="4"
          maxlength="1000"
          resize="none"
          placeholder="例如：节点 12 的认罪缺少动机，请在节点 8 提前铺垫她与反派的利益关系……"
        />
        <footer>
          <div class="decision-note">
            <el-icon><RefreshRight /></el-icon>
            <span>要求修改不会创建新故事，而是恢复当前工作流线程。</span>
          </div>
          <div class="decision-actions">
            <el-button
              size="large"
              :icon="EditPen"
              :disabled="!isCurrentVersion"
              :loading="workflowStore.submittingReview"
              @click="submitDecision(false)"
            >
              要求修改
            </el-button>
            <el-button
              type="primary"
              size="large"
              :icon="CircleCheck"
              :disabled="!canApprove"
              :loading="workflowStore.submittingReview"
              @click="submitDecision(true)"
            >
              批准大纲
            </el-button>
          </div>
        </footer>
      </section>
    </template>
  </div>
</template>

<style scoped>
.review-page {
  display: grid;
  gap: 18px;
}

.review-loading {
  display: grid;
  min-height: 520px;
  place-items: center;
  align-content: center;
  color: var(--sf-ink-muted);
}

.loading-mark {
  display: grid;
  width: 68px;
  height: 68px;
  margin-bottom: 17px;
  place-items: center;
  border-radius: 20px;
  color: var(--sf-primary);
  background: #fff;
  box-shadow: 0 15px 38px rgba(70, 51, 162, 0.12);
  animation: breathe 1.7s ease-in-out infinite;
}

.loading-mark svg {
  width: 26px;
}

.review-loading strong {
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.review-loading span {
  margin-top: 6px;
  font-size: 9px;
}

.review-header {
  overflow: hidden;
  padding: 20px 27px 0;
  border: 1px solid var(--sf-line);
  border-radius: 21px;
  background:
    radial-gradient(circle at 95% 0%, rgba(105, 83, 218, 0.07), transparent 28%),
    #fff;
}

.back-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  color: var(--sf-ink-muted);
  background: transparent;
  cursor: pointer;
  font-size: 9px;
}

.back-button:hover {
  color: var(--sf-primary);
}

.header-main {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 25px;
  margin: 22px 0 24px;
}

.section-kicker,
.panel-heading div > span {
  color: var(--sf-accent);
  font-size: 8px;
  font-weight: 800;
  letter-spacing: 2px;
}

.header-main h2 {
  margin: 7px 0 6px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: clamp(25px, 2.7vw, 34px);
  font-weight: 600;
}

.header-main p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 10px;
}

.header-tools {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 9px;
}

.version-select {
  width: 155px;
}

.review-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border-radius: 999px;
  color: #ad5d45;
  background: #fff0e8;
  font-size: 8px;
  font-weight: 750;
}

.review-badge i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #dc785b;
  box-shadow: 0 0 0 3px rgba(220, 120, 91, 0.11);
}

.review-badge.completed {
  color: #287b5d;
  background: #eaf7f1;
}

.review-badge.completed i {
  background: #309774;
  box-shadow: 0 0 0 3px rgba(48, 151, 116, 0.12);
}

.review-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(100px, 1fr));
  margin-inline: -27px;
  border-top: 1px solid #efecf2;
  background: #faf9fb;
}

.review-facts > div {
  display: grid;
  min-height: 61px;
  place-content: center;
  border-right: 1px solid #eeebf0;
  text-align: center;
}

.review-facts > div:last-child {
  border-right: 0;
}

.review-facts strong {
  color: var(--sf-ink-strong);
  font-family: Georgia, serif;
  font-size: 16px;
}

.review-facts span {
  color: var(--sf-ink-muted);
  font-size: 7px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.review-facts .invalid strong {
  color: #c44d48;
}

.integrity-warning {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 15px;
  border: 1px solid #edc9c4;
  border-radius: 12px;
  color: #ad4c47;
  background: #fff3f1;
}

.history-warning {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 15px;
  border: 1px solid #d9d2ed;
  border-radius: 12px;
  color: #5f50b6;
  background: #f6f3ff;
}

.history-warning > .el-icon {
  margin-top: 1px;
  font-size: 18px;
}

.history-warning div {
  display: grid;
  gap: 2px;
}

.history-warning strong {
  font-size: 10px;
}

.history-warning span {
  color: #7a7390;
  font-size: 9px;
}

.integrity-warning > .el-icon {
  margin-top: 1px;
  font-size: 18px;
}

.integrity-warning div {
  display: grid;
  gap: 2px;
}

.integrity-warning strong {
  font-size: 10px;
}

.integrity-warning span {
  color: #97706d;
  font-size: 9px;
  line-height: 1.5;
}

.review-grid {
  display: grid;
  grid-template-columns: minmax(230px, 0.72fr) minmax(430px, 1.45fr) minmax(260px, 0.83fr);
  gap: 14px;
  align-items: start;
}

.panel {
  overflow: hidden;
  border: 1px solid var(--sf-line);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 7px 24px rgba(45, 34, 84, 0.035);
}

.panel-heading {
  display: flex;
  min-height: 66px;
  align-items: center;
  gap: 10px;
  padding: 13px 15px;
  border-bottom: 1px solid #efecf2;
}

.panel-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  border-radius: 10px;
  color: var(--sf-primary);
  background: #f0edff;
}

.panel-icon svg {
  width: 16px;
}

.panel-heading > div:nth-child(2) {
  display: grid;
  min-width: 0;
  gap: 1px;
}

.panel-heading div > span {
  font-size: 6px;
  letter-spacing: 1.2px;
}

.panel-heading h3 {
  margin: 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 15px;
}

.panel-heading > strong {
  margin-left: auto;
  color: var(--sf-ink-muted);
  font-family: Georgia, serif;
  font-size: 15px;
}

.character-tabs {
  display: grid;
  padding: 10px;
  border-bottom: 1px solid #efecf2;
}

.character-tabs button {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 5px;
  gap: 9px;
  align-items: center;
  padding: 8px;
  border: 0;
  border-radius: 10px;
  color: inherit;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.character-tabs button:hover {
  background: #faf9fb;
}

.character-tabs button.active {
  background: #f4f1ff;
}

.character-avatar {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: linear-gradient(145deg, #8574e5, #5845c4);
  font-family: 'STSong', serif;
  font-size: 13px;
}

.character-tabs button:nth-child(2n) .character-avatar {
  background: linear-gradient(145deg, #e69c6d, #c85f55);
}

.character-tabs button > span:nth-child(2) {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.character-tabs strong {
  overflow: hidden;
  color: var(--sf-ink-strong);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.character-tabs small {
  color: var(--sf-ink-muted);
  font-size: 7px;
}

.character-tabs button > i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: transparent;
}

.character-tabs button.active > i {
  background: var(--sf-primary);
}

.character-detail {
  padding: 17px 16px 20px;
}

.character-title > span {
  color: var(--sf-accent);
  font-size: 7px;
  font-weight: 800;
  letter-spacing: 1px;
}

.character-title h4 {
  margin: 4px 0;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 20px;
}

.character-title p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 9px;
}

.traits {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 13px 0;
}

.traits span {
  padding: 4px 7px;
  border-radius: 6px;
  color: #6659ae;
  background: #f1eeff;
  font-size: 7px;
}

.character-detail dl {
  display: grid;
  gap: 9px;
  margin: 15px 0 0;
}

.character-detail dl > div {
  padding: 9px 10px;
  border-radius: 9px;
  background: #faf9fb;
}

.character-detail dl > div.secret {
  background: #fff7f1;
}

.character-detail dt {
  margin-bottom: 4px;
  color: var(--sf-ink-muted);
  font-size: 7px;
  font-weight: 750;
  letter-spacing: 0.5px;
}

.character-detail dd {
  margin: 0;
  color: #554f61;
  font-size: 8px;
  line-height: 1.55;
}

.outline-column {
  min-width: 0;
}

.valid-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 5px 7px;
  border-radius: 6px;
  color: #287b5d;
  background: #ebf7f2;
  font-size: 7px;
  font-weight: 750;
}

.valid-chip.invalid {
  color: #b94b48;
  background: #fff0ef;
}

.outline-meta {
  display: grid;
  grid-template-columns: 1fr 0.55fr;
  gap: 1px;
  background: #ebe8ef;
}

.outline-meta > div {
  display: grid;
  gap: 3px;
  padding: 11px 14px;
  background: #faf9fb;
}

.outline-meta span {
  color: var(--sf-ink-muted);
  font-size: 6px;
  font-weight: 750;
  letter-spacing: 0.7px;
}

.outline-meta strong {
  color: var(--sf-ink-strong);
  font-size: 8px;
  line-height: 1.45;
}

.outline-list {
  padding: 17px 15px 8px;
}

.outline-node {
  display: grid;
  grid-template-columns: 35px minmax(0, 1fr);
}

.node-rail {
  display: flex;
  align-items: center;
  flex-direction: column;
}

.node-rail strong {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid #ded9e6;
  border-radius: 9px;
  color: #8d8795;
  background: #faf9fb;
  font-family: Georgia, serif;
  font-size: 9px;
}

.outline-node.twist .node-rail strong {
  border-color: #e7b98e;
  color: #b65c45;
  background: #fff6ec;
}

.node-rail i {
  width: 1px;
  flex: 1;
  min-height: 25px;
  background: #e6e2ea;
}

.outline-node:last-child .node-rail i {
  display: none;
}

.node-content {
  padding: 1px 0 20px 8px;
}

.node-topline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
}

.stage,
.twist-chip,
.emotion {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 6px;
  border-radius: 5px;
  font-size: 6px;
  font-weight: 750;
}

.stage {
  color: #6558ad;
  background: #f0edff;
}

.twist-chip {
  color: #af5a43;
  background: #fff0e8;
}

.twist-chip svg {
  width: 8px;
}

.emotion {
  color: #777181;
  background: #f4f2f5;
}

.node-content h4 {
  margin: 7px 0 6px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 13px;
  line-height: 1.5;
}

.node-content > p {
  display: flex;
  gap: 6px;
  margin: 0;
  color: #716b78;
  font-size: 8px;
  line-height: 1.5;
}

.node-content > p b {
  flex: 0 0 auto;
  color: var(--sf-accent);
  font-size: 7px;
}

.node-details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 5px;
  margin-top: 9px;
}

.node-details > div {
  padding: 7px 8px;
  border-radius: 7px;
  background: #faf9fb;
}

.node-details span {
  color: var(--sf-ink-muted);
  font-size: 6px;
  font-weight: 750;
}

.node-details p {
  margin: 3px 0 0;
  color: #696372;
  font-size: 7px;
  line-height: 1.45;
}

.score-column {
  position: sticky;
  top: 110px;
}

.score-content {
  padding: 18px 15px 20px;
}

.total-score {
  position: relative;
  display: grid;
  width: 112px;
  height: 112px;
  margin: 2px auto 21px;
  place-items: center;
  border-radius: 50%;
  background: conic-gradient(
    var(--score-color, var(--sf-primary)) var(--score-angle),
    #ece9f0 0
  );
}

.total-score::before {
  position: absolute;
  width: 93px;
  height: 93px;
  border-radius: 50%;
  background: #fff;
  content: '';
}

.total-score > div {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
}

.total-score strong {
  color: var(--sf-ink-strong);
  font-family: Georgia, serif;
  font-size: 31px;
  line-height: 1;
}

.total-score span {
  margin-top: 4px;
  color: var(--sf-ink-muted);
  font-size: 7px;
  font-weight: 750;
}

.total-score.excellent {
  --score-color: #d87855;
}

.total-score.good {
  --score-color: #5f4fd0;
}

.total-score.pass {
  --score-color: #369477;
}

.total-score.risk {
  --score-color: #c55852;
}

.dimension-list {
  display: grid;
  gap: 14px;
}

.dimension-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dimension-heading span {
  color: #55505f;
  font-size: 8px;
  font-weight: 700;
}

.dimension-heading strong {
  color: var(--sf-ink-strong);
  font-family: Georgia, serif;
  font-size: 11px;
}

.dimension-heading small {
  color: var(--sf-ink-muted);
  font-size: 6px;
}

.dimension-list article > i {
  display: block;
  overflow: hidden;
  height: 3px;
  margin: 5px 0;
  border-radius: 999px;
  background: #ebe8ef;
}

.dimension-list article > i b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--sf-primary), #9c8ae9);
}

.dimension-list article > p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 7px;
  line-height: 1.45;
}

.dimension-suggestion {
  margin-top: 5px;
  padding: 6px 7px;
  border-radius: 6px;
  color: #756e7c;
  background: #faf9fb;
  font-size: 7px;
  line-height: 1.45;
}

.dimension-suggestion span {
  margin-right: 4px;
  color: var(--sf-primary);
  font-weight: 750;
}

.fatal-problem {
  margin-top: 18px;
  padding: 10px;
  border: 1px solid #f0d2ca;
  border-radius: 9px;
  background: #fff6f2;
}

.fatal-problem > span {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #ae5847;
  font-size: 7px;
  font-weight: 800;
}

.fatal-problem svg {
  width: 10px;
}

.fatal-problem p {
  margin: 6px 0 0;
  color: #806e69;
  font-size: 7px;
  line-height: 1.5;
}

.priorities {
  margin-top: 15px;
}

.priorities > span {
  color: var(--sf-ink-muted);
  font-size: 7px;
  font-weight: 750;
}

.priorities ol {
  display: grid;
  gap: 5px;
  margin: 7px 0 0;
  padding-left: 17px;
}

.priorities li {
  color: #6f6977;
  font-size: 7px;
  line-height: 1.45;
}

.decision-panel {
  padding: 20px 23px 16px;
  border: 1px solid #dcd5f0;
  border-radius: 18px;
  background: linear-gradient(112deg, #f8f6ff, #fff);
  box-shadow: 0 10px 30px rgba(57, 42, 126, 0.05);
}

.final-panel {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 19px 21px;
  border: 1px solid #c9e3d8;
  border-radius: 17px;
  color: #287b5d;
  background: linear-gradient(105deg, #edf9f4, #fff);
}

.final-panel > .el-icon {
  flex: 0 0 auto;
  font-size: 30px;
}

.final-panel h3 {
  margin: 4px 0 3px;
  color: #245f4b;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.final-panel p {
  margin: 0;
  color: #5f796f;
  font-size: 9px;
}

.decision-heading {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 13px;
}

.decision-heading h3 {
  margin: 4px 0 3px;
  color: var(--sf-ink-strong);
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 18px;
}

.decision-heading p {
  margin: 0;
  color: var(--sf-ink-muted);
  font-size: 8px;
}

.decision-heading > span {
  color: var(--sf-ink-muted);
  font-size: 8px;
}

:deep(.decision-panel .el-textarea__inner) {
  padding: 12px 13px;
  border: 0;
  border-radius: 10px;
  box-shadow: 0 0 0 1px #ddd8e8 inset;
  line-height: 1.7;
}

.decision-panel footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-top: 13px;
}

.decision-note {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--sf-ink-muted);
  font-size: 7px;
}

.decision-note .el-icon {
  color: var(--sf-primary);
}

.decision-actions {
  display: flex;
  gap: 8px;
}

.decision-actions .el-button {
  min-width: 125px;
  border-radius: 10px;
}

@keyframes breathe {
  50% {
    transform: scale(1.08);
  }
}

@media (max-width: 1250px) {
  .review-grid {
    grid-template-columns: minmax(230px, 0.72fr) minmax(440px, 1.28fr);
  }

  .score-column {
    position: static;
    grid-column: 1 / -1;
  }

  .score-content {
    display: grid;
    grid-template-columns: 140px minmax(300px, 1fr) minmax(200px, 0.7fr);
    gap: 20px;
    align-items: start;
  }

  .fatal-problem {
    margin-top: 0;
  }
}

@media (max-width: 800px) {
  .header-main,
  .decision-panel footer {
    align-items: flex-start;
    flex-direction: column;
  }

  .header-tools,
  .version-select {
    width: 100%;
  }

  .review-grid {
    grid-template-columns: 1fr;
  }

  .score-column {
    grid-column: auto;
  }

  .score-content {
    display: block;
  }

  .fatal-problem {
    margin-top: 18px;
  }

  .decision-actions,
  .decision-actions .el-button {
    width: 100%;
  }

  .decision-actions .el-button {
    flex: 1;
  }
}

@media (max-width: 540px) {
  .review-header {
    padding-inline: 18px;
  }

  .review-facts {
    grid-template-columns: repeat(2, 1fr);
    margin-inline: -18px;
  }

  .review-facts > div:nth-child(2) {
    border-right: 0;
  }

  .review-facts > div:nth-child(-n + 2) {
    border-bottom: 1px solid #eeebf0;
  }

  .node-details {
    grid-template-columns: 1fr;
  }

  .decision-actions {
    flex-direction: column-reverse;
  }
}
</style>
