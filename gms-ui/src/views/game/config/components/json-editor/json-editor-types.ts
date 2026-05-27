/* eslint-disable no-use-before-define */
export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonObject | JsonArray;
export interface JsonObject {
  [key: string]: JsonValue;
}
export type JsonArray = JsonValue[];

export type JsonNodeType =
  | 'string'
  | 'number'
  | 'boolean'
  | 'null'
  | 'object'
  | 'array';

export interface JsonTreeNodeData {
  id: string;
  key: string;
  value: JsonValue;
  type: JsonNodeType;
  children: JsonTreeNodeData[];
  expanded: boolean;
  parentId?: string;
  path: string;
  level: number;
  readonlyKey: boolean;
  error?: string;
}

export interface JsonEditorSnapshot {
  sourceText: string;
  jsonValue: JsonValue;
  expandedPaths: string[];
  activePath: string;
  reason: string;
  createdAt: number;
}

export interface JsonDocumentState {
  sourceText: string;
  rootNode: JsonTreeNodeData | null;
  editMode: boolean;
  sourceDirty: boolean;
  activePath: string;
  parseError: string;
  searchKeyword: string;
  searchResultIds: string[];
  searchIndex: number;
}
