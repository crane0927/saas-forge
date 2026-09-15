import { readAll } from '@saas-forge/admin';
import type { ConsoleApiClient, ConsoleApiResult } from '@saas-forge/app-runtime';
export async function readPlanGuard(
  client: ConsoleApiClient,
  signal: AbortSignal,
  targetId?: string,
  code = '',
): Promise<ConsoleApiResult<boolean>> {
  const result = await readAll(
    (cursor) => client.listPlanOperations({ cursor, limit: 100, signal }),
    signal,
  );
  if (!result.ok) return result;
  return {
    ok: true,
    value: !result.value.some(
      (operation) =>
        operation.state !== 'COMMITTED' &&
        (targetId === undefined
          ? operation.operation === 'CREATE' &&
            (code === '' || operation.code === undefined || operation.code === code)
          : operation.operation === 'ACTIVATE' && operation.planId === targetId),
    ),
  };
}
