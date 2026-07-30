<script setup lang="ts">
import type * as Monaco from 'monaco-editor/editor/editor.api'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    readonly?: boolean
    placeholder?: string
  }>(),
  {
    readonly: false,
    placeholder: '',
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'selection-change': [selection: { start: number; end: number }]
  ready: []
}>()

const container = ref<HTMLElement>()
const loading = ref(true)
let monaco: typeof Monaco | undefined
let editor: Monaco.editor.IStandaloneCodeEditor | undefined
let model: Monaco.editor.ITextModel | undefined
let resizeObserver: ResizeObserver | undefined
let applyingExternalValue = false

function offsetAt(position: Monaco.Position) {
  return model?.getOffsetAt(position) ?? 0
}

function emitSelection() {
  const range = editor?.getSelection()
  if (!range) return
  emit('selection-change', {
    start: offsetAt(range.getStartPosition()),
    end: offsetAt(range.getEndPosition()),
  })
}

function applyExternalValue(value: string) {
  if (!model || !monaco || model.getValue() === value) return
  applyingExternalValue = true
  try {
    const current = model.getValue()
    if (value.startsWith(current)) {
      const end = model.getFullModelRange().getEndPosition()
      model.applyEdits([
        {
          range: new monaco.Range(end.lineNumber, end.column, end.lineNumber, end.column),
          text: value.slice(current.length),
        },
      ])
      return
    }

    const selection = editor?.getSelection()
    const scrollTop = editor?.getScrollTop()
    const scrollLeft = editor?.getScrollLeft()
    model.setValue(value)
    if (selection) editor?.setSelection(selection)
    if (scrollTop !== undefined) editor?.setScrollTop(scrollTop)
    if (scrollLeft !== undefined) editor?.setScrollLeft(scrollLeft)
  } finally {
    applyingExternalValue = false
  }
}

onMounted(async () => {
  const [{ default: EditorWorker }, monacoModule] = await Promise.all([
    import('monaco-editor/editor/editor.worker?worker'),
    import('monaco-editor/editor/editor.api'),
  ])
  const monacoApi: typeof Monaco = monacoModule
  monaco = monacoApi
  self.MonacoEnvironment = {
    getWorker: () => new EditorWorker(),
  }

  model = monacoApi.editor.createModel(props.modelValue, 'plaintext')
  editor = monacoApi.editor.create(container.value!, {
    model,
    readOnly: props.readonly,
    automaticLayout: false,
    ariaLabel: '章节正文编辑器',
    lineNumbers: 'off',
    glyphMargin: false,
    folding: false,
    lineDecorationsWidth: 18,
    lineNumbersMinChars: 0,
    minimap: { enabled: false },
    overviewRulerLanes: 0,
    overviewRulerBorder: false,
    hideCursorInOverviewRuler: true,
    wordWrap: 'on',
    wrappingIndent: 'same',
    scrollBeyondLastLine: false,
    renderLineHighlight: 'none',
    renderWhitespace: 'selection',
    smoothScrolling: true,
    cursorSmoothCaretAnimation: 'on',
    padding: { top: 28, bottom: 40 },
    fontFamily: "'STSong', 'Songti SC', 'Noto Serif SC', serif",
    fontSize: 15,
    lineHeight: 31,
    letterSpacing: 0.15,
    colorDecorators: false,
    contextmenu: true,
    quickSuggestions: false,
    suggestOnTriggerCharacters: false,
    acceptSuggestionOnEnter: 'off',
    tabSize: 2,
    theme: 'vs',
  })

  editor.onDidChangeModelContent(() => {
    if (!applyingExternalValue) emit('update:modelValue', model!.getValue())
  })
  editor.onDidChangeCursorSelection(emitSelection)
  resizeObserver = new ResizeObserver(() => editor?.layout())
  resizeObserver.observe(container.value!)
  loading.value = false
  await nextTick()
  editor.layout()
  emit('ready')
})

watch(
  () => props.modelValue,
  (value) => applyExternalValue(value),
)

watch(
  () => props.readonly,
  (readOnly) => editor?.updateOptions({ readOnly }),
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  editor?.dispose()
  model?.dispose()
})

defineExpose({
  focus: () => editor?.focus(),
})
</script>

<template>
  <div class="monaco-shell">
    <div ref="container" class="monaco-editor-host" />
    <span v-if="loading" class="editor-loading">正在加载 Monaco 编辑器…</span>
    <span v-else-if="!modelValue" class="editor-placeholder">{{ placeholder }}</span>
  </div>
</template>

<style scoped>
.monaco-shell {
  position: relative;
  min-height: 650px;
  background:
    linear-gradient(90deg, transparent 0, transparent 39px, rgba(84, 70, 137, 0.04) 40px, transparent 41px),
    #fff;
}

.monaco-editor-host {
  position: absolute;
  inset: 0;
}

.editor-loading,
.editor-placeholder {
  position: absolute;
  z-index: 1;
  top: 31px;
  left: clamp(38px, 5vw, 72px);
  color: #aaa5b2;
  font-family: 'STSong', 'Songti SC', serif;
  font-size: 14px;
  pointer-events: none;
}

.editor-loading {
  color: var(--sf-primary);
  font-size: 10px;
}

@media (max-width: 620px) {
  .monaco-shell {
    min-height: 500px;
  }
}
</style>
