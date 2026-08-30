import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RootErrorBoundary } from '../src/error-boundary';

afterEach(cleanup);

describe('RootErrorBoundary', () => {
  it('replaces an unhandled render failure with a stable reload surface', () => {
    const reload = vi.fn();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    render(
      <RootErrorBoundary reload={reload}>
        <BrokenView />
      </RootErrorBoundary>,
    );

    expect(screen.getByText('APPLICATION_FATAL')).toBeTruthy();
    expect(screen.queryByText('render detail that must stay hidden')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }));
    expect(reload).toHaveBeenCalledOnce();

    consoleError.mockRestore();
  });
});

function BrokenView(): never {
  throw new Error('render detail that must stay hidden');
}
