import type { JsonTreeNodeData } from './json-editor-types';

export const validateKeyUnique = (
  parent: JsonTreeNodeData,
  key: string,
  excludeId?: string
): string | null => {
  if (parent.type !== 'object') return null;
  const dup = parent.children.find((c) => c.key === key && c.id !== excludeId);
  if (dup) return '同一对象下字段名不能重复';
  return null;
};

export const validateKeyNotEmpty = (key: string): string | null => {
  if (!key.trim()) return '对象字段名不能为空';
  return null;
};

export const validateRootType = (node: JsonTreeNodeData): string | null => {
  if (node.type !== 'object' && node.type !== 'array') {
    return 'JSON 配置根节点必须是对象或数组';
  }
  return null;
};

export const validateNumber = (text: string): string | null => {
  if (text.trim() === '' || Number.isNaN(Number(text))) {
    return '数字格式不正确';
  }
  return null;
};
