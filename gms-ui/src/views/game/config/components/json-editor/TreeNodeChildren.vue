<template>
  <!-- eslint-disable vue/no-mutating-props -->
  <div>
    <JsonTreeNodeRow
      :node="node"
      :edit-mode="editMode"
      :parent-is-array="parentIsArray"
      @change="$emit('change')"
      @duplicate="$emit('duplicate', $event)"
      @remove="$emit('remove', $event)"
    />
    <template v-if="isContainer && node.expanded">
      <draggable
        :list="node.children"
        item-key="id"
        handle=".json-tree-drag-handle"
        :disabled="!editMode"
        ghost-class="json-tree-drag-ghost"
        @change="handleDragChange"
      >
        <template #item="{ element, index }">
          <div class="json-tree-draggable-item" :data-node-id="element.id">
            <TreeNodeChildren
              :node="element"
              :edit-mode="editMode"
              :parent="node"
              :parent-is-array="node.type === 'array'"
              :sibling-index="index"
              :sibling-count="node.children.length"
              @change="$emit('change')"
              @duplicate="$emit('duplicate', $event)"
              @remove="$emit('remove', $event)"
            />
          </div>
        </template>
      </draggable>
      <!-- 新增子项按钮 -->
      <div v-if="editMode" class="json-tree-add-child" @click="addChild">
        + {{ $t('config.json.addChild') }}
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  /* eslint-disable vue/no-mutating-props */
  import { computed } from 'vue';
  import draggable from 'vuedraggable';
  import JsonTreeNodeRow from './JsonTreeNodeRow.vue';
  import type { JsonNodeType, JsonTreeNodeData } from './json-editor-types';
  import {
    buildTree,
    createDefaultValueByType,
    refreshNodeMeta,
  } from './json-editor-utils';

  const props = defineProps<{
    node: JsonTreeNodeData;
    editMode: boolean;
    parent?: JsonTreeNodeData;
    parentIsArray?: boolean;
    siblingIndex?: number;
    siblingCount?: number;
  }>();

  const emit = defineEmits<{
    change: [];
    duplicate: [nodeId: string];
    remove: [nodeId: string];
  }>();

  const isContainer = computed(
    () => props.node.type === 'object' || props.node.type === 'array'
  );

  const handleDragChange = () => {
    // vuedraggable 在 :list 模式下会自动调整 node.children 顺序。
    // 这里不能再次手动 splice，否则 DOM 顺序和数据顺序会短暂错位，
    // 嵌套拖拽场景下会触发 vuedraggable 内部 context 为 null。
    refreshNodeMeta(props.node, props.parent);
    emit('change');
  };

  const addChild = () => {
    const childType: JsonNodeType = 'string';
    const childValue = createDefaultValueByType(childType);
    const childKey =
      props.node.type === 'array'
        ? String(props.node.children.length)
        : 'newKey';
    let childPath: string;
    if (props.node.type === 'array') {
      childPath = `${props.node.path}[${childKey}]`;
    } else if (props.node.path) {
      childPath = `${props.node.path}.${childKey}`;
    } else {
      childPath = childKey;
    }
    const childNode = buildTree(childValue, {
      key: childKey,
      parentId: props.node.id,
      path: childPath,
      level: props.node.level + 1,
      readonlyKey: props.node.type === 'array',
    });
    props.node.children.push(childNode);
    refreshNodeMeta(props.node, props.parent);
    emit('change');
  };
</script>

<script lang="ts">
  export default {
    name: 'TreeNodeChildren',
  };
</script>

<style scoped lang="less">
  .json-tree-add-child {
    padding: 2px 0 2px 40px;
    color: var(--color-text-3);
    font-size: 12px;
    cursor: pointer;
    user-select: none;

    &:hover {
      color: rgb(var(--primary-6));
    }
  }

  .json-tree-draggable-item {
    min-width: max-content;
  }
</style>
