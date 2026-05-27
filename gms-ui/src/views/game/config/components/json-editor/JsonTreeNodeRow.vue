<template>
  <div class="json-tree-row" :class="{ 'json-tree-row--root': root }">
    <!-- 拖拽手柄 -->
    <span v-if="editMode" class="json-tree-drag-handle" title="拖动排序"
      >⋮⋮</span
    >
    <span
      v-else
      class="json-tree-drag-handle json-tree-drag-handle--disabled"
    ></span>

    <!-- 缩进 -->
    <span v-for="i in node.level" :key="i" class="json-tree-indent"></span>

    <!-- 展开/折叠 -->
    <span v-if="isContainer" class="json-tree-toggle" @click="toggleExpand">
      <icon-down v-if="node.expanded" />
      <icon-right v-else />
    </span>
    <span v-else class="json-tree-toggle json-tree-toggle--leaf"></span>

    <!-- key / index -->
    <template v-if="!root">
      <span v-if="parentIsArray" class="json-tree-index">{{ node.key }}</span>
      <template v-else>
        <a-input
          v-if="editMode && !node.readonlyKey"
          v-model="editKey"
          class="json-tree-key-edit"
          size="mini"
          @blur="commitKey"
          @keydown.enter="($event.target as HTMLInputElement).blur()"
        />
        <span v-else class="json-tree-key">{{ node.key }}</span>
      </template>
      <span class="json-tree-colon">:</span>
    </template>

    <!-- 值 / 摘要 -->
    <!-- 容器：显示摘要 -->
    <span v-if="isContainer" class="json-tree-summary">
      <template v-if="node.type === 'object'">
        object {{ '{' }}{{ node.children.length }}{{ '}' }}
      </template>
      <template v-else> {{ node.key }} [{{ node.children.length }}] </template>
    </span>
    <!-- string -->
    <template v-else-if="node.type === 'string'">
      <a-input
        v-if="editMode"
        v-model="editValue"
        class="json-tree-value-edit"
        size="mini"
        @blur="commitValue"
        @keydown.enter="($event.target as HTMLInputElement).blur()"
      />
      <span v-else class="json-tree-value json-tree-value--string"
        >"{{ node.value }}"</span
      >
    </template>
    <!-- number -->
    <template v-else-if="node.type === 'number'">
      <a-input
        v-if="editMode"
        v-model="editValue"
        class="json-tree-value-edit"
        size="mini"
        @blur="commitNumber"
        @keydown.enter="($event.target as HTMLInputElement).blur()"
      />
      <span v-else class="json-tree-value json-tree-value--number">{{
        node.value
      }}</span>
    </template>
    <!-- boolean -->
    <template v-else-if="node.type === 'boolean'">
      <a-switch
        v-if="editMode"
        :model-value="node.value === true"
        size="mini"
        @change="commitBoolean"
      />
      <span v-else class="json-tree-value json-tree-value--boolean">{{
        node.value
      }}</span>
    </template>
    <!-- null -->
    <span v-else class="json-tree-value json-tree-value--null">null</span>

    <!-- 右侧操作按钮 -->
    <span v-if="editMode" class="json-tree-actions">
      <a-dropdown v-if="!root" trigger="click">
        <a-button size="mini" type="text" title="类型">
          {{ typeLabel }}
        </a-button>
        <template #content>
          <a-doption value="string" @click="changeType('string')"
            >A 字符串</a-doption
          >
          <a-doption value="number" @click="changeType('number')"
            >123 数字</a-doption
          >
          <a-doption value="boolean" @click="changeType('boolean')"
            >T/F 布尔</a-doption
          >
          <a-doption value="null" @click="changeType('null')"
            >null 空值</a-doption
          >
          <a-doption value="object" @click="changeType('object')">{{
            '{} 对象'
          }}</a-doption>
          <a-doption value="array" @click="changeType('array')"
            >[] 数组</a-doption
          >
        </template>
      </a-dropdown>
      <a-button
        v-if="!root"
        size="mini"
        type="text"
        title="复制"
        @click="$emit('duplicate', node.id)"
      >
        <icon-copy />
      </a-button>
      <a-button
        v-if="!root"
        size="mini"
        type="text"
        title="删除"
        @click="$emit('remove', node.id)"
      >
        <icon-delete />
      </a-button>
    </span>
  </div>
</template>

<script setup lang="ts">
  /* eslint-disable vue/no-mutating-props */
  import { computed, ref, watch } from 'vue';
  import {
    IconDown,
    IconRight,
    IconCopy,
    IconDelete,
  } from '@arco-design/web-vue/es/icon';
  import type { JsonNodeType, JsonTreeNodeData } from './json-editor-types';
  import { createDefaultValueByType } from './json-editor-utils';

  const props = defineProps<{
    node: JsonTreeNodeData;
    editMode: boolean;
    root?: boolean;
    parentIsArray?: boolean;
  }>();

  const emit = defineEmits<{
    change: [];
    duplicate: [nodeId: string];
    remove: [nodeId: string];
  }>();

  const isContainer = computed(
    () => props.node.type === 'object' || props.node.type === 'array'
  );

  const typeLabel = computed(() => {
    switch (props.node.type) {
      case 'string':
        return 'A';
      case 'number':
        return '123';
      case 'boolean':
        return 'T/F';
      case 'null':
        return '∅';
      case 'object':
        return '{}';
      case 'array':
        return '[]';
      default:
        return '?';
    }
  });

  const editKey = ref(props.node.key);
  const editValue = ref(
    props.node.type === 'string' || props.node.type === 'number'
      ? String(props.node.value ?? '')
      : ''
  );

  watch(
    () => props.node.key,
    (v) => {
      editKey.value = v;
    }
  );
  watch(
    () => props.node.value,
    (v) => {
      if (props.node.type === 'string' || props.node.type === 'number') {
        editValue.value = v === null || v === undefined ? '' : String(v);
      }
    }
  );

  const toggleExpand = () => {
    if (!isContainer.value) return;
    props.node.expanded = !props.node.expanded;
    emit('change');
  };

  const commitKey = () => {
    const trimmed = editKey.value.trim();
    if (!trimmed || trimmed === props.node.key) {
      editKey.value = props.node.key;
      return;
    }
    props.node.key = trimmed;
    emit('change');
  };

  const commitValue = () => {
    props.node.value = editValue.value;
    emit('change');
  };

  const commitNumber = () => {
    const t = editValue.value.trim();
    if (t === '' || Number.isNaN(Number(t))) {
      editValue.value = String(props.node.value ?? '');
      return;
    }
    props.node.value = Number(t);
    emit('change');
  };

  const commitBoolean = (val: boolean) => {
    props.node.value = val;
    emit('change');
  };

  const changeType = (newType: JsonNodeType) => {
    if (newType === props.node.type) return;
    props.node.type = newType;
    props.node.value = createDefaultValueByType(newType);
    props.node.children = [];
    emit('change');
  };
</script>

<script lang="ts">
  export default {
    name: 'JsonTreeNodeRow',
  };
</script>

<style scoped lang="less">
  .json-tree-row {
    display: flex;
    align-items: center;
    gap: 1px;
    min-height: 26px;
    padding: 1px 0;
    font-size: 13px;
    line-height: 1.4;
    cursor: default;

    &:hover {
      background: var(--color-fill-2);
    }
  }

  .json-tree-drag-handle {
    display: inline-flex;
    align-items: center;
    width: 14px;
    flex-shrink: 0;
    color: var(--color-text-4);
    font-size: 11px;
    letter-spacing: -2px;
    cursor: grab;
    user-select: none;

    &--disabled {
      cursor: default;
      opacity: 0.3;
    }
  }

  .json-tree-indent {
    display: inline-block;
    width: 16px;
    flex-shrink: 0;
  }

  .json-tree-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 14px;
    height: 14px;
    flex-shrink: 0;
    cursor: pointer;
    color: var(--color-text-3);
    font-size: 10px;

    &--leaf {
      cursor: default;
    }
  }

  .json-tree-index {
    color: var(--color-text-3);
    font-size: 12px;
    flex-shrink: 0;
  }

  .json-tree-key {
    color: var(--color-text-2);
    flex-shrink: 0;
  }

  .json-tree-key-edit {
    width: 100px;
    flex-shrink: 0;
  }

  .json-tree-colon {
    margin: 0 3px;
    color: var(--color-text-3);
    flex-shrink: 0;
  }

  .json-tree-summary {
    color: var(--color-text-1);
    font-weight: 600;
    font-size: 12px;
  }

  .json-tree-value {
    &--string {
      color: #00b42a;
    }
    &--number {
      color: #165dff;
    }
    &--boolean {
      color: #7b61ff;
    }
    &--null {
      color: var(--color-text-4);
      font-style: italic;
    }
  }

  .json-tree-value-edit {
    width: 180px;
    flex-shrink: 0;
  }

  .json-tree-actions {
    display: flex;
    gap: 0;
    margin-left: auto;
    flex-shrink: 0;
  }
</style>
