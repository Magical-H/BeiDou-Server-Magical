import type { JsonEditorSnapshot, JsonTreeNodeData } from './json-editor-types';
import {
  buildTree,
  collectExpandedPaths,
  restoreExpanded,
} from './json-editor-utils';

export const createSnapshot = (
  sourceText: string,
  rootNode: JsonTreeNodeData | null,
  activePath: string,
  reason: string
): JsonEditorSnapshot | null => {
  if (!rootNode) return null;
  return {
    sourceText,
    jsonValue: JSON.parse(JSON.stringify(rootNode.value)),
    expandedPaths: collectExpandedPaths(rootNode),
    activePath,
    reason,
    createdAt: Date.now(),
  };
};

export const applySnapshot = (
  snapshot: JsonEditorSnapshot
): { sourceText: string; rootNode: JsonTreeNodeData } => {
  const rootNode = buildTree(snapshot.jsonValue);
  restoreExpanded(rootNode, new Set(snapshot.expandedPaths));
  return {
    sourceText: snapshot.sourceText,
    rootNode,
  };
};

export class JsonHistoryManager {
  private stack: JsonEditorSnapshot[] = [];

  private index = -1;

  get canUndo(): boolean {
    return this.index > 0;
  }

  get canRedo(): boolean {
    return this.index < this.stack.length - 1;
  }

  push(snapshot: JsonEditorSnapshot): void {
    this.stack = this.stack.slice(0, this.index + 1);
    this.stack.push(snapshot);
    this.index = this.stack.length - 1;
  }

  undo(): JsonEditorSnapshot | null {
    if (!this.canUndo) return null;
    this.index -= 1;
    return this.stack[this.index];
  }

  redo(): JsonEditorSnapshot | null {
    if (!this.canRedo) return null;
    this.index += 1;
    return this.stack[this.index];
  }

  clear(): void {
    this.stack = [];
    this.index = -1;
  }
}
