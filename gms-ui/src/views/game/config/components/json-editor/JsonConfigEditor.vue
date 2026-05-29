<template>
  <div class="json-editor">
    <div v-if="!mobile" class="json-editor-body">
      <div class="json-editor-left">
        <JsonSourcePanel
          v-model="sourceText"
          :parse-error="parseError"
          @format="formatSource"
          @compact="compactSource"
          @parse="parseSource"
        />
      </div>
      <div class="json-editor-divider" />
      <div class="json-editor-right">
        <JsonTreePanel
          :root-node="rootNode"
          :edit-mode="editMode"
          :can-undo="history.canUndo"
          :can-redo="history.canRedo"
          :search-keyword="searchKeyword"
          :search-result-ids="searchResultIds"
          :search-index="searchIndex"
          :description-schema="descriptionSchema"
          @expand-all="expandAll"
          @collapse-all="collapseAll"
          @toggle-edit-mode="toggleEditMode"
          @undo="handleUndo"
          @redo="handleRedo"
          @search="handleSearch"
          @prev-result="prevResult"
          @next-result="nextResult"
          @tree-change="handleTreeChange"
          @duplicate-node="handleDuplicateNode"
          @remove-node="handleRemoveNode"
          @add-root-child="handleAddRootChild"
          @description-change="handleDescriptionChange"
        />
      </div>
    </div>

    <a-tabs
      v-else
      class="json-editor-mobile-tabs"
      default-active-key="tree"
      type="capsule"
    >
      <a-tab-pane key="tree" :title="$t('config.json.treeTab')">
        <JsonTreePanel
          :root-node="rootNode"
          :edit-mode="editMode"
          :can-undo="history.canUndo"
          :can-redo="history.canRedo"
          :search-keyword="searchKeyword"
          :search-result-ids="searchResultIds"
          :search-index="searchIndex"
          :description-schema="descriptionSchema"
          @expand-all="expandAll"
          @collapse-all="collapseAll"
          @toggle-edit-mode="toggleEditMode"
          @undo="handleUndo"
          @redo="handleRedo"
          @search="handleSearch"
          @prev-result="prevResult"
          @next-result="nextResult"
          @tree-change="handleTreeChange"
          @duplicate-node="handleDuplicateNode"
          @remove-node="handleRemoveNode"
          @add-root-child="handleAddRootChild"
          @description-change="handleDescriptionChange"
        />
      </a-tab-pane>
      <a-tab-pane key="source" :title="$t('config.json.sourceTab')">
        <JsonSourcePanel
          v-model="sourceText"
          :parse-error="parseError"
          @format="formatSource"
          @compact="compactSource"
          @parse="parseSource"
        />
      </a-tab-pane>
    </a-tabs>

    <!-- 状态条 -->
    <div class="json-editor-status">
      <span class="json-editor-status-item">
        {{ $t('config.json.path') }}：{{ activePath || '-' }}
      </span>
      <span class="json-editor-status-item">
        {{ editMode ? $t('config.json.editMode') : $t('config.json.readMode') }}
      </span>
      <span class="json-editor-status-item"> JSON {{ jsonSize }} </span>
      <span class="json-editor-status-item">
        {{ validationMsg }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, nextTick, onMounted, ref, watch } from 'vue';
  import { Message } from '@arco-design/web-vue';
  import JsonSourcePanel from './JsonSourcePanel.vue';
  import JsonTreePanel from './JsonTreePanel.vue';
  import type { JsonNodeType, JsonTreeNodeData } from './json-editor-types';
  import {
    JsonHistoryManager,
    createSnapshot,
    applySnapshot,
  } from './json-editor-history';
  import { searchNodes, expandAncestors } from './json-editor-search';
  import {
    buildTree,
    compactJson,
    formatJson,
    getJsonNodeType,
    parseJsonText,
    refreshNodeMeta,
    treeToJson,
    createDefaultValueByType,
    createCopyKey,
    cloneNodeWithNewIds,
    parseDescriptionSchema,
    updateNodeDescription,
  } from './json-editor-utils';

  const props = defineProps<{
    modelValue: string;
    description?: string;
    mobile?: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'update:modelValue', value: string): void;
    (e: 'update:description', value: string): void;
  }>();

  // ---- 核心状态 ----
  const sourceText = ref('');
  const rootNode = ref<JsonTreeNodeData | null>(null);
  const parseError = ref('');
  const editMode = ref(false);
  const activePath = ref('');

  // ---- 历史 ----
  const history = new JsonHistoryManager();

  // ---- 搜索 ----
  const searchKeyword = ref('');
  const searchResultIds = ref<string[]>([]);
  const searchIndex = ref(-1);

  // ---- 内部更新标志（防止 v-model 循环触发 rebuild） ----
  const isInternalUpdate = ref(false);
  const descriptionText = ref(props.description || '');

  // ---- 计算属性 ----
  const jsonSize = computed(() => {
    const text = sourceText.value;
    if (text.length < 1024) return `${text.length} B`;
    return `${(text.length / 1024).toFixed(1)} KB`;
  });

  const validationMsg = computed(() => {
    if (parseError.value) return parseError.value;
    if (!rootNode.value) return '无数据';
    return '校验通过';
  });

  const descriptionSchema = computed(() =>
    parseDescriptionSchema(descriptionText.value)
  );

  const mobile = computed(() => props.mobile === true);

  // ---- 初始化 ----
  const initFromString = (text: string) => {
    const trimmed = (text || '').trim();
    if (!trimmed) {
      sourceText.value = formatJson({});
      rootNode.value = buildTree({});
      parseError.value = '';
      pushHistory('init');
      return;
    }
    try {
      const val = parseJsonText(trimmed);
      const type = getJsonNodeType(val);
      if (type !== 'object' && type !== 'array') {
        sourceText.value = trimmed;
        parseError.value = 'JSON 配置根节点必须是对象或数组';
        rootNode.value = buildTree({});
        return;
      }
      sourceText.value = formatJson(val);
      rootNode.value = buildTree(val);
      parseError.value = '';
      pushHistory('init');
    } catch (e: unknown) {
      sourceText.value = trimmed;
      const msg = e instanceof Error ? e.message : '请检查 JSON 格式是否正确';
      parseError.value = `格式错误：${msg}`;
      rootNode.value = buildTree({});
    }
  };

  // ---- 历史 ----
  function pushHistory(reason: string) {
    const snapshot = createSnapshot(
      sourceText.value,
      rootNode.value,
      activePath.value,
      reason
    );
    if (snapshot) history.push(snapshot);
  }

  watch(
    () => props.modelValue,
    (val) => {
      if (isInternalUpdate.value) return;
      initFromString(val);
    },
    { immediate: true }
  );

  watch(
    () => props.description,
    (val) => {
      descriptionText.value = val || '';
    }
  );

  onMounted(() => {
    if (props.modelValue) {
      initFromString(props.modelValue);
    }
  });

  const handleUndo = () => {
    const snap = history.undo();
    if (!snap) return;
    const restored = applySnapshot(snap);
    sourceText.value = restored.sourceText;
    rootNode.value = restored.rootNode;
    parseError.value = '';
    syncToParent();
  };

  const handleRedo = () => {
    const snap = history.redo();
    if (!snap) return;
    const restored = applySnapshot(snap);
    sourceText.value = restored.sourceText;
    rootNode.value = restored.rootNode;
    parseError.value = '';
    syncToParent();
  };

  // ---- 源码操作 ----
  const formatSource = () => {
    try {
      const val = parseJsonText(sourceText.value);
      sourceText.value = formatJson(val);
      parseError.value = '';
    } catch (e: unknown) {
      parseError.value = `格式错误：${e instanceof Error ? e.message : e}`;
    }
  };

  const compactSource = () => {
    try {
      const val = parseJsonText(sourceText.value);
      sourceText.value = compactJson(val);
      parseError.value = '';
    } catch (e: unknown) {
      parseError.value = `格式错误：${e instanceof Error ? e.message : e}`;
    }
  };

  const parseSource = () => {
    try {
      const val = parseJsonText(sourceText.value);
      rootNode.value = buildTree(val);
      parseError.value = '';
      pushHistory('parse');
      Message.success('解析成功');
    } catch (e: unknown) {
      parseError.value = `格式错误：${e instanceof Error ? e.message : e}`;
    }
  };

  // ---- 树操作 ----
  const expandAll = () => {
    if (!rootNode.value) return;
    walkNodes(rootNode.value, (n) => {
      if (n.type === 'object' || n.type === 'array') n.expanded = true;
    });
    handleTreeChange();
  };

  const collapseAll = () => {
    if (!rootNode.value) return;
    walkNodes(rootNode.value, (n) => {
      if (n.type === 'object' || (n.type === 'array' && n !== rootNode.value))
        n.expanded = false;
    });
    handleTreeChange();
  };

  const walkNodes = (
    node: JsonTreeNodeData,
    fn: (n: JsonTreeNodeData) => void
  ) => {
    fn(node);
    node.children.forEach((child) => walkNodes(child, fn));
  };

  const toggleEditMode = () => {
    editMode.value = !editMode.value;
  };

  const handleTreeChange = () => {
    if (!rootNode.value) return;
    refreshNodeMeta(rootNode.value);
    const val = treeToJson(rootNode.value);
    sourceText.value = formatJson(val);
    parseError.value = '';
    pushHistory('edit');
    syncToParent();
  };

  const handleDuplicateNode = (nodeId: string) => {
    if (!rootNode.value) return;
    const parent = findParent(rootNode.value, nodeId);
    if (!parent) return;
    const idx = parent.children.findIndex((c) => c.id === nodeId);
    if (idx === -1) return;
    const orig = parent.children[idx];
    const cloned = cloneNodeWithNewIds(orig);
    if (parent.type === 'object') {
      cloned.key = createCopyKey(
        orig.key,
        parent.children.map((c) => c.key)
      );
    }
    parent.children.splice(idx + 1, 0, cloned);
    refreshNodeMeta(rootNode.value);
    handleTreeChange();
  };

  const handleRemoveNode = (nodeId: string) => {
    if (!rootNode.value) return;
    const parent = findParent(rootNode.value, nodeId);
    if (!parent) return;
    const idx = parent.children.findIndex((c) => c.id === nodeId);
    if (idx === -1) return;
    parent.children.splice(idx, 1);
    refreshNodeMeta(rootNode.value);
    handleTreeChange();
  };

  const handleAddRootChild = () => {
    if (!rootNode.value) return;
    const childType: JsonNodeType = 'string';
    const childKey =
      rootNode.value.type === 'array'
        ? String(rootNode.value.children.length)
        : 'newKey';
    const childNode = buildTree(createDefaultValueByType(childType), {
      key: childKey,
      parentId: rootNode.value.id,
      path: rootNode.value.type === 'array' ? `[${childKey}]` : childKey,
      level: rootNode.value.level + 1,
      readonlyKey: rootNode.value.type === 'array',
    });
    rootNode.value.children.push(childNode);
    refreshNodeMeta(rootNode.value);
    handleTreeChange();
  };

  const handleDescriptionChange = (
    node: JsonTreeNodeData,
    description: string
  ) => {
    const schema = updateNodeDescription(
      descriptionSchema.value,
      node,
      description
    );
    descriptionText.value = JSON.stringify(schema);
    emit('update:description', descriptionText.value);
  };

  const findParent = (
    node: JsonTreeNodeData,
    targetId: string
  ): JsonTreeNodeData | null => {
    if (node.children.some((c) => c.id === targetId)) return node;
    return node.children.reduce<JsonTreeNodeData | null>(
      (found, child) => found || findParent(child, targetId),
      null
    );
  };

  // ---- 搜索 ----
  const handleSearch = (kw: string) => {
    searchKeyword.value = kw;
    if (!kw.trim() || !rootNode.value) {
      searchResultIds.value = [];
      searchIndex.value = -1;
      return;
    }
    const matches = searchNodes(rootNode.value, kw);
    searchResultIds.value = matches.map((m) => m.nodeId);
    searchIndex.value = matches.length > 0 ? 0 : -1;
    if (searchIndex.value >= 0) {
      expandAncestors(rootNode.value, searchResultIds.value[0]);
    }
  };

  const prevResult = () => {
    if (searchResultIds.value.length === 0) return;
    searchIndex.value =
      (searchIndex.value - 1 + searchResultIds.value.length) %
      searchResultIds.value.length;
  };

  const nextResult = () => {
    if (searchResultIds.value.length === 0) return;
    searchIndex.value = (searchIndex.value + 1) % searchResultIds.value.length;
  };

  // ---- 同步 ----
  const syncToParent = () => {
    if (!rootNode.value) return;
    isInternalUpdate.value = true;
    emit('update:modelValue', compactJson(treeToJson(rootNode.value)));
    nextTick(() => {
      isInternalUpdate.value = false;
    });
  };

  // ---- 对外方法 ----
  const getSubmitValue = (): string | undefined => {
    if (!rootNode.value) {
      Message.error('JSON 配置未通过校验，请修正后再保存');
      return undefined;
    }
    try {
      return compactJson(treeToJson(rootNode.value));
    } catch {
      Message.error('JSON 配置未通过校验，请修正后再保存');
      return undefined;
    }
  };

  defineExpose({ getSubmitValue });
</script>

<script lang="ts">
  export default {
    name: 'JsonConfigEditor',
  };
</script>

<style scoped lang="less">
  .json-editor {
    display: flex;
    flex-direction: column;
    width: 100%;
  }

  .json-editor-body {
    display: flex;
    gap: 0;
    height: 520px;
  }

  .json-editor-mobile-tabs {
    display: block;
  }

  .json-editor-left {
    flex: 48;
    display: flex;
    flex-direction: column;
    min-width: 360px;
  }

  .json-editor-divider {
    width: 1px;
    margin: 0 8px;
    background: var(--color-border-2);
    flex-shrink: 0;
  }

  .json-editor-right {
    flex: 52;
    display: flex;
    flex-direction: column;
    min-width: 480px;
  }

  .json-editor-status {
    display: flex;
    gap: 16px;
    margin-top: 6px;
    padding: 4px 8px;
    font-size: 12px;
    color: var(--color-text-3);
    background: var(--color-fill-1);
    border-radius: 4px;
    flex-shrink: 0;
  }

  .json-editor-status-item {
    white-space: nowrap;
  }

  @media (max-width: 768px) {
    .json-editor-mobile-tabs {
      :deep(.arco-tabs-content) {
        height: calc(100vh - 230px);
        min-height: 420px;
      }

      :deep(.arco-tabs-content-list),
      :deep(.arco-tabs-pane) {
        height: 100%;
      }
    }

    .json-editor-status {
      flex-wrap: wrap;
      gap: 6px 10px;
      max-height: 72px;
      overflow: auto;
    }
  }
</style>
