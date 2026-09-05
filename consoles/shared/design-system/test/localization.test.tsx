import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  Button,
  DesignSystemProvider,
  FormErrorSummary,
  LoadFailureState,
  PersistentError,
  ServerTable,
  UnsavedChangesDialog,
} from '../src';

afterEach(cleanup);

const rows = [{ id: 'tenant-1', name: 'Northstar' }];

describe('Design System component-owned messages', () => {
  it('derives English form, feedback, state, overlay, and loading labels from the Provider', () => {
    render(
      <DesignSystemProvider locale="en-US">
        <FormErrorSummary errors={[{ message: 'Member name is required.' }]} />
        <Button loading>Save</Button>
        <PersistentError title="Save failed" onClose={() => undefined} />
        <LoadFailureState onRetry={() => undefined} />
        <UnsavedChangesDialog
          open
          onContinueEditing={() => undefined}
          onDiscard={() => undefined}
        />
      </DesignSystemProvider>,
    );

    expect(screen.getByText('Resolve the following issues')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Processing' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Close error message' })).toBeTruthy();
    expect(screen.getByRole('alert', { name: 'Loading failed' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
    expect(screen.getByRole('dialog', { name: 'Discard unsaved changes?' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Continue editing' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Discard changes' })).toBeTruthy();
  });

  it('renders English singular/plural counts and retranslates semantic selection state', () => {
    const onSelectionChange = vi.fn();
    const table = (locale: 'en-US' | 'zh-CN', total: number) => (
      <DesignSystemProvider locale={locale}>
        <ServerTable
          ariaLabel={locale === 'en-US' ? 'Tenant table' : '租户表格'}
          rows={rows}
          rowKey={(row) => row.id}
          columns={[{ key: 'name', title: 'Name', render: (row) => row.name }]}
          page={1}
          pageSize={10}
          total={total}
          onTableChange={() => undefined}
          onSelectionChange={onSelectionChange}
        />
      </DesignSystemProvider>
    );
    const { rerender } = render(table('en-US', 1));

    expect(screen.getByText('1 item')).toBeTruthy();
    rerender(table('en-US', 2));
    expect(screen.getByText('2 items')).toBeTruthy();

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select this page row' }));
    expect(screen.getByRole('status').textContent).toBe('1 current-page item selected.');

    rerender(table('zh-CN', 2));
    expect(screen.getByText('共 2 项')).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe('已选择 1 项当前页记录。');
  });
});
