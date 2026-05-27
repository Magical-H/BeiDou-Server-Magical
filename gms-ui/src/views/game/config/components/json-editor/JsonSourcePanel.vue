<template>
  <div class="json-source-panel">
    <div class="json-source-toolbar">
      <a-button size="small" @click="$emit('format')">
        {{ $t('config.json.format') }}
      </a-button>
      <a-button size="small" @click="$emit('compact')">
        {{ $t('config.json.compact') }}
      </a-button>
      <a-button size="small" type="primary" @click="$emit('parse')">
        {{ $t('config.json.parseJson') }}
      </a-button>
    </div>
    <a-alert
      v-if="parseError"
      class="json-source-error"
      type="error"
      show-icon
      closable
    >
      {{ parseError }}
    </a-alert>
    <a-textarea
      :model-value="modelValue"
      class="json-source-textarea"
      spellcheck="false"
      :auto-size="false"
      @input="onInput"
    />
  </div>
</template>

<script setup lang="ts">
  defineProps<{
    modelValue: string;
    parseError: string;
  }>();

  const emit = defineEmits<{
    'update:modelValue': [value: string];
    'format': [];
    'compact': [];
    'parse': [];
  }>();

  const onInput = (value: string) => {
    emit('update:modelValue', value);
  };
</script>

<script lang="ts">
  export default {
    name: 'JsonSourcePanel',
  };
</script>

<style scoped lang="less">
  .json-source-panel {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0;
  }

  .json-source-toolbar {
    display: flex;
    flex-direction: row;
    flex-wrap: nowrap;
    align-items: center;
    gap: 4px;
    padding: 4px 0 6px;
    flex-shrink: 0;
  }

  .json-source-error {
    margin-bottom: 6px;
    flex-shrink: 0;
  }

  .json-source-textarea {
    flex: 1;
    font-family: Consolas, Monaco, 'Courier New', monospace;
    font-size: 13px;
    line-height: 20px;

    :deep(textarea) {
      height: 100% !important;
      resize: none;
      white-space: pre;
      overflow: auto;
      tab-size: 2;
    }
  }
</style>
