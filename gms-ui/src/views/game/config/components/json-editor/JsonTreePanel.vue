<template>
  <div class="json-tree-panel">
    <!-- 工具栏 -->
    <div class="json-tree-toolbar">
      <a-button size="small" @click="$emit('expandAll')">
        {{ $t('config.json.expand') }}
      </a-button>
      <a-button size="small" @click="$emit('collapseAll')">
        {{ $t('config.json.collapse') }}
      </a-button>
      <a-button
        size="small"
        :type="editMode ? 'primary' : 'outline'"
        @click="$emit('toggleEditMode')"
      >
        {{
          editMode ? $t('config.json.doneEdit') : $t('config.json.startEdit')
        }}
      </a-button>
      <a-divider direction="vertical" />
      <a-button size="small" :disabled="!canUndo" @click="$emit('undo')">
        {{ $t('config.json.undo') }}
      </a-button>
      <a-button size="small" :disabled="!canRedo" @click="$emit('redo')">
        {{ $t('config.json.redo') }}
      </a-button>
      <a-divider direction="vertical" />
      <a-input-search
        :model-value="searchKeyword"
        size="small"
        allow-clear
        :style="{ width: '200px' }"
        :placeholder="$t('config.json.searchPlaceholder')"
        @search="$emit('search', $event)"
        @clear="$emit('search', '')"
      />
      <a-button
        size="small"
        type="text"
        :disabled="searchResultCount === 0"
        @click="$emit('prevResult')"
      >
        ◀
      </a-button>
      <a-button
        size="small"
        type="text"
        :disabled="searchResultCount === 0"
        @click="$emit('nextResult')"
      >
        ▶
      </a-button>
      <span v-if="searchResultCount > 0" class="json-tree-search-count">
        {{ searchIndex + 1 }}/{{ searchResultCount }}
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
          @change="$emit('treeChange')"
          @duplicate="$emit('duplicateNode', $event)"
          @remove="$emit('removeNode', $event)"
        />
        <template v-if="rootNode.expanded">
          <TreeNodeChildren
            v-for="child in rootNode.children"
            :key="child.id"
            :node="child"
            :edit-mode="editMode"
            :parent="rootNode"
            :parent-is-array="rootNode.type === 'array'"
            @change="$emit('treeChange')"
            @duplicate="$emit('duplicateNode', $event)"
            @remove="$emit('removeNode', $event)"
          />
        </template>
      </template>
      <a-empty v-else :description="$t('config.json.invalid')" />
    </div>
  </div>
</template>

<script setup lang="ts">
  import JsonTreeNodeRow from './JsonTreeNodeRow.vue';
  import TreeNodeChildren from './TreeNodeChildren.vue';
  import type { JsonTreeNodeData } from './json-editor-types';

  defineProps<{
    rootNode: JsonTreeNodeData | null;
    editMode: boolean;
    canUndo: boolean;
    canRedo: boolean;
    searchKeyword: string;
    searchResultCount: number;
    searchIndex: number;
  }>();

  defineEmits<{
    expandAll: [];
    collapseAll: [];
    toggleEditMode: [];
    undo: [];
    redo: [];
    search: [keyword: string];
    prevResult: [];
    nextResult: [];
    treeChange: [];
    duplicateNode: [nodeId: string];
    removeNode: [nodeId: string];
  }>();
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

  .json-tree-mode-hint {
    margin-bottom: 6px;
    flex-shrink: 0;
  }

  .json-tree-search-count {
    font-size: 12px;
    color: var(--color-text-3);
    white-space: nowrap;
  }

  .json-tree-scroll {
    flex: 1;
    overflow: auto;
    border: 1px solid var(--color-border-2);
    border-radius: 4px;
    padding: 4px;
    background: var(--color-bg-1);
  }
</style>
