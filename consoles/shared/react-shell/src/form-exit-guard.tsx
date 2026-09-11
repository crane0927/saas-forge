import { UnsavedChangesDialog, useUnsavedChangesGuard } from '@saas-forge/design-system';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

const FormExitContext = createContext<{
  setDirty: (dirty: boolean) => void;
  requestExit: (action: () => void) => void;
}>({
  setDirty: () => undefined,
  requestExit: (action: () => void) => {
    action();
  },
});

/** 主动结束会话也会卸载表单，必须在改变认证状态前确认。路由导航仍由路由器保护。 */
export function FormExitGuardProvider({ children }: { readonly children: ReactNode }) {
  const [dirty, setDirty] = useState(false);
  const guard = useUnsavedChangesGuard(dirty);
  return (
    <FormExitContext.Provider value={{ setDirty, requestExit: guard.requestDiscard }}>
      {children}
      <UnsavedChangesDialog
        open={guard.confirmationOpen}
        onContinueEditing={guard.continueEditing}
        onDiscard={guard.discardChanges}
      />
    </FormExitContext.Provider>
  );
}

export function useFormExitGuard(dirty: boolean): void {
  const { setDirty } = useContext(FormExitContext);
  useEffect(() => {
    setDirty(dirty);
    return () => {
      setDirty(false);
    };
  }, [dirty, setDirty]);
}

export function useRequestFormExit(): (action: () => void) => void {
  return useContext(FormExitContext).requestExit;
}
