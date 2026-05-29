import type {
  JsonDescriptionSchema,
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
  if (!node.id) {
    node.id = createNodeId();
  }
  if (parent?.type === 'array' && index !== undefined) {
    node.key = String(index);
    node.readonlyKey = true;
  } else if (parent?.type === 'object') {
    node.readonlyKey = false;
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

export const cloneNodeWithNewIds = (
  node: JsonTreeNodeData
): JsonTreeNodeData => {
  const cloned = JSON.parse(JSON.stringify(node)) as JsonTreeNodeData;
  const refreshIds = (current: JsonTreeNodeData): void => {
    current.id = createNodeId();
    current.children.forEach(refreshIds);
  };
  refreshIds(cloned);
  return cloned;
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

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  Object.prototype.toString.call(value) === '[object Object]';

const JSON_DESC_SELF_KEY = '_self';
const JSON_DESC_ITEM_KEY = '_item';
const JSON_DESC_COLUMNS_KEY = '_columns';

const readText = (value: unknown): string => {
  if (typeof value === 'string') return value;
  if (isPlainObject(value) && typeof value[JSON_DESC_SELF_KEY] === 'string') {
    return value[JSON_DESC_SELF_KEY];
  }
  if (isPlainObject(value) && typeof value[JSON_DESC_ITEM_KEY] === 'string') {
    return value[JSON_DESC_ITEM_KEY];
  }
  return '';
};

export const parseDescriptionSchema = (
  text?: string
): JsonDescriptionSchema | null => {
  const trimmed = (text || '').trim();
  if (!trimmed) return null;
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (isPlainObject(parsed)) return parsed;
  } catch {
    return { [JSON_DESC_SELF_KEY]: trimmed };
  }
  return { [JSON_DESC_SELF_KEY]: trimmed };
};

const tokenizePath = (path: string): string[] => {
  const tokens: string[] = [];
  path.replace(/([^[.\]]+)|\[(\d+)\]/g, (_, key: string, index: string) => {
    tokens.push(key ?? index);
    return '';
  });
  return tokens;
};

export const resolveNodeDescription = (
  schema: JsonDescriptionSchema | null,
  node: JsonTreeNodeData
): string => {
  if (!schema) return '';
  if (!node.path) return readText(schema[JSON_DESC_SELF_KEY]);

  const tokens = tokenizePath(node.path);
  let context: unknown = schema;

  for (let i = 0; i < tokens.length; i += 1) {
    if (!isPlainObject(context)) return readText(context);

    const token = tokens[i];
    const nextToken = tokens[i + 1];
    const prevToken = tokens[i - 1];
    const last = i === tokens.length - 1;
    const nextIsArrayIndex = nextToken !== undefined && /^\d+$/.test(nextToken);

    const tokenIsArrayIndex = /^\d+$/.test(token);
    const prevIsArrayIndex = prevToken !== undefined && /^\d+$/.test(prevToken);

    if (tokenIsArrayIndex && last) {
      const columns = context[JSON_DESC_COLUMNS_KEY];
      if (prevIsArrayIndex && isPlainObject(columns)) {
        return readText(columns[token]);
      }
      return readText(context);
    }

    if (!tokenIsArrayIndex) {
      const directValue = context[token];
      if (last) return readText(directValue);
      context = nextIsArrayIndex
        ? context[`${token}Item`] ?? directValue
        : directValue;
    }
  }

  return '';
};

export const updateNodeDescription = (
  schema: JsonDescriptionSchema | null,
  node: JsonTreeNodeData,
  description: string
): JsonDescriptionSchema => {
  const nextSchema = JSON.parse(
    JSON.stringify(schema ?? {})
  ) as JsonDescriptionSchema;
  const trimmed = description.trim();
  if (!node.path) {
    if (trimmed) {
      nextSchema[JSON_DESC_SELF_KEY] = trimmed;
    } else {
      delete nextSchema[JSON_DESC_SELF_KEY];
    }
    return nextSchema;
  }

  const tokens = tokenizePath(node.path);
  let context: Record<string, unknown> = nextSchema;
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    const nextToken = tokens[i + 1];
    const prevToken = tokens[i - 1];
    const last = i === tokens.length - 1;
    const tokenIsArrayIndex = /^\d+$/.test(token);
    const nextIsArrayIndex = nextToken !== undefined && /^\d+$/.test(nextToken);
    const prevIsArrayIndex = prevToken !== undefined && /^\d+$/.test(prevToken);

    if (tokenIsArrayIndex) {
      if (last) {
        if (prevIsArrayIndex) {
          const columns = isPlainObject(context[JSON_DESC_COLUMNS_KEY])
            ? (context[JSON_DESC_COLUMNS_KEY] as Record<string, unknown>)
            : {};
          if (trimmed) {
            columns[token] = trimmed;
            context[JSON_DESC_COLUMNS_KEY] = columns;
          } else {
            delete columns[token];
            if (Object.keys(columns).length === 0) {
              delete context[JSON_DESC_COLUMNS_KEY];
            }
          }
        } else if (trimmed) {
          context[JSON_DESC_ITEM_KEY] = trimmed;
        } else {
          delete context[JSON_DESC_ITEM_KEY];
        }
      }
    } else if (last) {
      if (trimmed) {
        context[token] = trimmed;
      } else {
        delete context[token];
      }
    } else {
      const nextKey = nextIsArrayIndex ? `${token}Item` : token;
      if (!isPlainObject(context[nextKey])) {
        context[nextKey] = {};
      }
      context = context[nextKey] as Record<string, unknown>;
    }
  }

  return nextSchema;
};
