import type { JsonTreeNodeData } from './json-editor-types';

export interface SearchMatch {
  nodeId: string;
}

export const searchNodes = (
  root: JsonTreeNodeData,
  keyword: string
): SearchMatch[] => {
  const kw = keyword.toLowerCase();
  const results: SearchMatch[] = [];

  const walk = (node: JsonTreeNodeData): void => {
    const matchKey = node.key.toLowerCase().includes(kw);
    const matchVal =
      node.type !== 'object' &&
      node.type !== 'array' &&
      String(node.value).toLowerCase().includes(kw);
    if (matchKey || matchVal) {
      results.push({ nodeId: node.id });
    }
    node.children.forEach(walk);
  };

  walk(root);
  return results;
};

export const expandAncestors = (
  root: JsonTreeNodeData,
  nodeId: string
): void => {
  const findAndExpand = (node: JsonTreeNodeData, targetId: string): boolean => {
    if (node.id === targetId) return true;
    return node.children.some((child) => {
      if (findAndExpand(child, targetId)) {
        node.expanded = true;
        return true;
      }
      return false;
    });
  };
  findAndExpand(root, nodeId);
};
