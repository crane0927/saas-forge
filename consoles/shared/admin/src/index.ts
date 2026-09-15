import 'virtual:uno.css';
export { default as AdminLayout } from './vendor/soybean/materials/libs/admin-layout';
export { default as ConsoleLayout } from './layouts/ConsoleLayout.vue';
export { useAuthenticationRuntime } from './runtime/authentication';

export { mountConsole } from './application/mount';
export {
  consoleContextKey,
  useConsole,
  useShellText,
  useRead,
  useMutation,
  useFormExitGuard,
  readAll,
} from './runtime/context';
export { default as Problem } from './components/Problem.vue';
export { default as OperationRecovery } from './components/OperationRecovery.vue';
export { default as PageHeading } from './components/PageHeading.vue';
