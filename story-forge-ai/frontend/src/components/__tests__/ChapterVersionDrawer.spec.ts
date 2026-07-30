import { createApp, defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import ChapterVersionDrawer from '@/components/ChapterVersionDrawer.vue'
import type { ChapterVersion, EntityId } from '@/types'

const mountedApps: Array<ReturnType<typeof createApp>> = []

const DrawerStub = defineComponent({
  setup(_, { slots }) {
    return () => h('section', [slots.header?.(), slots.default?.()])
  },
})

const SelectStub = defineComponent({
  props: {
    modelValue: {
      type: [String, Number],
      default: undefined,
    },
  },
  setup(props, { slots }) {
    return () =>
      h(
        'div',
        {
          class: 'select-stub',
          'data-model-value':
            props.modelValue === undefined ? '' : String(props.modelValue),
        },
        slots.default?.(),
      )
  },
})

const OptionStub = defineComponent({
  setup() {
    return () => null
  },
})

const ButtonStub = defineComponent({
  props: {
    disabled: Boolean,
  },
  setup(props, { attrs, slots }) {
    return () =>
      h(
        'button',
        {
          disabled: props.disabled,
          onClick: attrs.onClick as (() => void) | undefined,
        },
        slots.default?.(),
      )
  },
})

function version(id: EntityId, chapterId: EntityId, versionNo: number): ChapterVersion {
  return {
    id,
    chapterId,
    versionNo,
    sourceType: 'USER_EDIT',
    content: `chapter-${chapterId}-version-${versionNo}`,
    changeSummary: '',
  }
}

afterEach(() => {
  mountedApps.splice(0).forEach((app) => app.unmount())
  document.body.innerHTML = ''
})

describe('ChapterVersionDrawer', () => {
  it('replaces stale comparison IDs when the loaded chapter versions change', async () => {
    const versions = ref<ChapterVersion[]>([
      version(102, 10, 2),
      version(101, 10, 1),
    ])
    const currentVersionId = ref<EntityId>(102)
    const comparisons: Array<[EntityId, EntityId]> = []

    const Root = defineComponent({
      setup() {
        return () =>
          h(ChapterVersionDrawer, {
            modelValue: true,
            versions: versions.value,
            currentVersionId: currentVersionId.value,
            loading: false,
            comparing: false,
            restoring: false,
            restoreAllowed: true,
            onCompare: (fromVersionId: EntityId, toVersionId: EntityId) => {
              comparisons.push([fromVersionId, toVersionId])
            },
          })
      },
    })

    const app = createApp(Root)
    mountedApps.push(app)
    app.component('el-drawer', DrawerStub)
    app.component('el-select', SelectStub)
    app.component('el-option', OptionStub)
    app.component('el-button', ButtonStub)
    app.directive('loading', () => undefined)
    app.mount(document.body.appendChild(document.createElement('div')))
    await nextTick()

    let selects = [...document.querySelectorAll<HTMLElement>('.select-stub')]
    expect(selects.map((select) => select.dataset.modelValue)).toEqual(['101', '102'])

    versions.value = [
      version(202, 20, 2),
      version(201, 20, 1),
    ]
    currentVersionId.value = 202
    await nextTick()

    selects = [...document.querySelectorAll<HTMLElement>('.select-stub')]
    expect(selects.map((select) => select.dataset.modelValue)).toEqual(['201', '202'])

    const compareButton = [...document.querySelectorAll('button')].find(
      (button) => button.textContent?.trim() === '对比版本',
    )
    compareButton?.click()

    expect(comparisons).toEqual([[201, 202]])
  })
})
