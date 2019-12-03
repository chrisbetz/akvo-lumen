import { isImmutable } from 'immutable';

import { ensurePushIntoArray } from './utils';

export function isTransformationColumn(c) {
  const n = c.get('columnName');
  return n && n.charAt(0) === 'd' && parseInt(n.substring(1), 10) > 0;
}

export function datasetHasQuestionGroups(columns) {
  return columns.filter(c => (c.get('groupName') !== null && c.get('groupName') !== undefined) || isTransformationColumn(c)).size > 0;
}

export const flowCommonColumnNames = new Set(['identifier', 'instance_id', 'display_name', 'submitter', 'submitted_at', 'surveyal_time', 'device_id']);

export const reducerGroup = (metadataI18n, transformationsI18n) => (accumulator, c, idx) => {
  const column = c.set('idx', idx);
  const groupName = column.get('groupName');
  const columnName = column.get('columnName') || column.get('value');
  if (groupName === 'null' || groupName === 'undefined' || groupName === null || groupName === undefined) {
    if (isTransformationColumn(column)) {
      return ensurePushIntoArray(accumulator, transformationsI18n, column);
    } else if (flowCommonColumnNames.has(columnName)) {
      return ensurePushIntoArray(accumulator, metadataI18n, column);
    }
    return ensurePushIntoArray(accumulator, ' ', column);
  }
  return ensurePushIntoArray(accumulator, groupName, column);
};

/*
# arguments
columns: an array of objects, each of which has a "type" property
acceptableTypes: a single string or an array of strings

This could be extended in the future to support filtering on props other than type
*/
export function filterColumns(columns = [], acceptableTypes = []) {
  const types = Array.isArray(acceptableTypes) ? acceptableTypes : [acceptableTypes];
  return columns.filter(column => types.some(type => type === (isImmutable(columns) ? column.get('type') : column.type)));
}

// eslint-disable-next-line max-len
export const findColumn = (columns, columnName) => columns.filter(column => column.columnName === columnName)[0];
export const findColumnI = (columns, columnName) => columns.find(column => column.get('columnName') === columnName);

export const columnSelectSelectedOption = (columnValue, columns) => {
  const l = columns.find(c => (c.get('value') || c.get('columnName')) === columnValue);
  return l ? { value: l.get('value') || l.get('columnName'), label: l.get('label') || l.get('title') } : {};
};

export const columnSelectOptions = (intl, columns) => {
  function extractColumnOptions(cc) {
    return cc.map((c) => {
      const value = c.get('value') || c.get('columnName');
      const label = c.get('label') || c.get('title');
      const labelId = c.get('labelId');
      return {
        label: labelId ? intl.formatMessage({ id: labelId }) : label,
        value,
      };
    });
  }
  if (columns.size > 0 && datasetHasQuestionGroups(columns)) {
    const groups = columns.reduce(reducerGroup(intl.formatMessage({ id: 'form_metadata' }), intl.formatMessage({ id: 'transformations' })), {});
    const reducer2 = (accumulator, k) => {
      const columnsGroup = groups[k];
      accumulator.push({ label: k, options: extractColumnOptions(columnsGroup) });
      return accumulator;
    };
    return Object.keys(groups).reduce(reducer2, []);
  }
  const res = extractColumnOptions(columns);
  return isImmutable(res) ? res.toArray() : res;
};
