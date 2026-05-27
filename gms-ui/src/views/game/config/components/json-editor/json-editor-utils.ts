import type {
  JsonNodeType,
  JsonTreeNodeData,
  JsonValue,
} from './json-editor-types';

let idSeed = 0;

export const createNodeId = (): string => {
  idSeed += 1;
  return `jn-${Date.now()}-${idSeed}`;
};

export const resetIdSeed = (): void => {
  idSeed = 0;
};

export const getJsonNodeType = (value: JsonValue): JsonNodeType => {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'object') return 'object';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  return 'string';
};

export const buildTree = (
  value: JsonValue,
  options: {
    key?: string;
    parentId?: string;
    path?: string;
    level?: number;
    readonlyKey?: boolean;
  } = {}
): JsonTreeNodeData => {
  const type = getJsonNodeType(value);
  const node: JsonTreeNodeData = {
    id: createNodeId(),
    key: options.key ?? '',
    value,
    type,
    children: [],
    expanded: true,
    parentId: options.parentId,
    path: options.path ?? '',
    level: options.level ?? 0,
    readonlyKey: options.readonlyKey ?? false,
  };

  if (type === 'object') {
    node.children = Object.entries(value as Record<string, JsonValue>).map(
      ([childKey, childValue]) =>
        buildTree(childValue, {
          key: childKey,
          parentId: node.id,
          path: options.path ? `${options.path}.${childKey}` : childKey,
          level: node.level + 1,
        })
    );
  }

  if (type === 'array') {
    node.children = (value as JsonValue[]).map((childValue, index) =>
      buildTree(childValue, {
        key: String(index),
        parentId: node.id,
        path: `${options.path ?? ''}[${index}]`,
        level: node.level + 1,
        readonlyKey: true,
      })
    );
  }

  return node;
};

export const treeToJson = (node: JsonTreeNodeData): JsonValue => {
  if (node.type === 'object') {
    return node.children.reduce<Record<string, JsonValue>>((result, child) => {
      result[child.key] = treeToJson(child);
      return result;
    }, {});
  }
  if (node.type === 'array') {
    return node.children.map((child) => treeToJson(child));
  }
  return node.value as JsonValue;
};

export const refreshNodeMeta = (
  node: JsonTreeNodeData,
  parent?: JsonTreeNodeData,
  index?: number
): void => {
  if (parent?.type === 'array' && index !== undefined) {
    node.key = String(index);
    node.readonlyKey = true;
  }
  node.parentId = parent?.id;
  node.level = parent ? parent.level + 1 : 0;
  if (!parent) {
    node.path = '';
  } else if (parent.type === 'array') {
    node.path = `${parent.path}[${node.key}]`;
  } else {
    node.path = parent.path ? `${parent.path}.${node.key}` : node.key;
  }
  node.children.forEach((child, childIndex) => {
    refreshNodeMeta(child, node, childIndex);
  });
};

export const createDefaultValueByType = (type: JsonNodeType): JsonValue => {
  switch (type) {
    case 'object':
      return {};
    case 'array':
      return [];
    case 'number':
      return 0;
    case 'boolean':
      return false;
    case 'null':
      return null;
    case 'string':
    default:
      return '';
  }
};

export const createCopyKey = (key: string, siblingKeys: string[]): string => {
  let copyKey = `${key}_copy`;
  let idx = 2;
  while (siblingKeys.includes(copyKey)) {
    copyKey = `${key}_copy_${idx}`;
    idx += 1;
  }
  return copyKey;
};

export const countDescendants = (node: JsonTreeNodeData): number => {
  let count = node.children.length;
  node.children.forEach((child) => {
    count += countDescendants(child);
  });
  return count;
};

export const collectExpandedPaths = (node: JsonTreeNodeData): string[] => {
  const paths: string[] = [];
  if (node.expanded) {
    paths.push(node.path);
    node.children.forEach((child) => {
      paths.push(...collectExpandedPaths(child));
    });
  }
  return paths;
};

export const restoreExpanded = (
  node: JsonTreeNodeData,
  expandedPaths: Set<string>
): void => {
  node.expanded = expandedPaths.has(node.path);
  node.children.forEach((child) => restoreExpanded(child, expandedPaths));
};

export const formatJson = (value: JsonValue): string =>
  JSON.stringify(value, null, 2);

export const compactJson = (value: JsonValue): string => JSON.stringify(value);

export const parseJsonText = (text: string): JsonValue =>
  JSON.parse(text) as JsonValue;
