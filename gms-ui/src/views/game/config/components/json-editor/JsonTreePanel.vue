<template>
  <div class="json-tree-panel">
    <!-- 工具栏 -->
    <div class="json-tree-toolbar">
      <a-button size="mini" @click="emit('expandAll')">
        {{ $t('config.json.expand') }}
      </a-button>
      <a-button size="mini" @click="emit('collapseAll')">
        {{ $t('config.json.collapse') }}
      </a-button>
      <a-button
        size="mini"
        :type="editMode ? 'primary' : 'outline'"
        @click="emit('toggleEditMode')"
      >
        {{
          editMode ? $t('config.json.doneEdit') : $t('config.json.startEdit')
        }}
      </a-button>
      <a-divider direction="vertical" />
      <a-button size="mini" :disabled="!canUndo" @click="emit('undo')">
        {{ $t('config.json.undo') }}
      </a-button>
      <a-button size="mini" :disabled="!canRedo" @click="emit('redo')">
        {{ $t('config.json.redo') }}
      </a-button>
      <a-divider direction="vertical" />
      <a-input-search
        v-model="localKeyword"
        size="mini"
        allow-clear
        :style="{ width: '200px' }"
        :placeholder="$t('config.json.searchPlaceholder')"
        @search="emit('search', localKeyword)"
        @press-enter="emit('search', localKeyword)"
        @clear="emit('search', '')"
      />
      <a-button
        size="mini"
        type="text"
        :disabled="searchResultIds.length === 0"
        @click="emit('prevResult')"
        >▲</a-button
      >
      <a-button
        size="mini"
        type="text"
        :disabled="searchResultIds.length === 0"
        @click="emit('nextResult')"
        >▼</a-button
      >
      <span v-if="searchResultIds.length > 0" class="json-tree-search-count">
        {{ searchIndex + 1 }}/{{ searchResultIds.length }}
      </span>
    </div>

    <!-- 修改模式提示 -->
    <a-alert v-if="!editMode" class="json-tree-mode-hint" type="info" show-icon>
      {{ $t('config.json.readOnlyHint') }}
    </a-alert>

    <!-- 树视图 -->
    <div class="json-tree-scroll">
      <template v-if="rootNode">
        <JsonTreeNodeRow
          :node="rootNode"
          :edit-mode="editMode"
          :root="true"
          :search-hit="searchResultSet.has(rootNode.id)"
          :search-active="rootNode.id === activeSearchId"
          :search-keyword="searchKeyword"
          :description-schema="descriptionSchema"
          @change="emit('treeChange')"
          @description-change="emit('descriptionChange', rootNode, $event)"
          @duplicate="emit('duplicateNode', $event)"
          @remove="emit('removeNode', $event)"
        />
        <template v-if="rootNode.expanded">
          <TreeNodeChildren
            v-for="child in rootNode.children"
            :key="child.id"
            :node="child"
            :edit-mode="editMode"
            :parent="rootNode"
            :parent-is-array="rootNode.type === 'array'"
            :search-result-set="searchResultSet"
            :active-search-id="activeSearchId"
            :search-keyword="searchKeyword"
            :description-schema="descriptionSchema"
            @change="emit('treeChange')"
            @description-change="
              emit('descriptionChange', $event.node, $event.description)
            "
            @duplicate="emit('duplicateNode', $event)"
            @remove="emit('removeNode', $event)"
          />
        </template>
        <div
          v-if="editMode && isRootContainer && rootNode.expanded"
          class="json-tree-add-root-child"
          @click="emit('addRootChild')"
        >
          + {{ $t('config.json.addChild') }}
        </div>
      </template>
      <a-empty v-else :description="$t('config.json.invalid')" />
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue';
  import JsonTreeNodeRow from './JsonTreeNodeRow.vue';
  import TreeNodeChildren from './TreeNodeChildren.vue';
  import type {
    JsonDescriptionSchema,
    JsonTreeNodeData,
  } from './json-editor-types';

  const props = defineProps<{
    rootNode: JsonTreeNodeData | null;
    editMode: boolean;
    canUndo: boolean;
    canRedo: boolean;
    searchKeyword: string;
    searchResultIds: string[];
    searchIndex: number;
    descriptionSchema: JsonDescriptionSchema | null;
  }>();

  const emit = defineEmits<{
    (e: 'expandAll'): void;
    (e: 'collapseAll'): void;
    (e: 'toggleEditMode'): void;
    (e: 'undo'): void;
    (e: 'redo'): void;
    (e: 'search', keyword: string): void;
    (e: 'prevResult'): void;
    (e: 'nextResult'): void;
    (e: 'treeChange'): void;
    (e: 'duplicateNode', nodeId: string): void;
    (e: 'removeNode', nodeId: string): void;
    (e: 'addRootChild'): void;
    (e: 'descriptionChange', node: JsonTreeNodeData, description: string): void;
  }>();

  const localKeyword = ref(props.searchKeyword);
  watch(
    () => props.searchKeyword,
    (v) => {
      localKeyword.value = v;
    }
  );

  const searchResultSet = computed(() => new Set(props.searchResultIds));
  const activeSearchId = computed(
    () => props.searchResultIds[props.searchIndex] ?? ''
  );
  const isRootContainer = computed(
    () => props.rootNode?.type === 'object' || props.rootNode?.type === 'array'
  );
</script>

<script lang="ts">
  export default {
    name: 'JsonTreePanel',
  };
</script>

<style scoped lang="less">
  .json-tree-panel {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
  }

  .json-tree-toolbar {
    display: flex;
    flex-direction: row;
    flex-wrap: nowrap;
    align-items: center;
    gap: 4px;
    padding: 4px 0 6px;
    flex-shrink: 0;
    overflow-x: auto;
  }

  .json-tree-toolbar :deep(.arco-input-search) {
    flex: 0 0 200px;
  }

  .json-tree-mode-hint {
    margin-bottom: 6px;
    flex-shrink: 0;
  }

  .json-tree-search-count {
    font-size: 12px;
    color: var(--color-text-3);
    white-space: nowrap;
  }

  .json-tree-add-root-child {
    padding: 2px 0 2px 40px;
    color: var(--color-text-3);
    font-size: 12px;
    cursor: pointer;
    user-select: none;

    &:hover {
      color: rgb(var(--primary-6));
    }
  }

  .json-tree-scroll {
    flex: 1;
    overflow: auto;
    border: 1px solid var(--color-border-2);
    border-radius: 4px;
    padding: 4px;
    background: var(--color-bg-1);
  }

  @media (max-width: 768px) {
    .json-tree-toolbar {
      flex-wrap: wrap;
      overflow-x: visible;
    }

    .json-tree-toolbar :deep(.arco-input-search) {
      flex: 1 1 180px;
      min-width: 0;
    }
  }
</style>
