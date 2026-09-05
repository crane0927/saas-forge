import type {
  AuthenticationProblem,
  AuthenticationRuntime,
  IdempotentOperationHandle,
  MembershipCandidate,
} from '@saas-forge/app-runtime';
import {
  createTranslator,
  defineMessages,
  type SupportedLocale,
  type Translator,
} from '@saas-forge/i18n';
import {
  ApplicationLoading,
  ApplicationFatalError,
  ApplicationShell,
  Button,
  FormLayout,
  FormRow,
  PageLayout,
  PageTitle,
  PasswordField,
  PersistentError,
  RouteFocusAnnouncement,
  SuccessFeedback,
  TextField,
} from '@saas-forge/design-system';
import {
  Component,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { matchPath, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';

import enUS from './messages/en-US.json';
import zhCN from './messages/zh-CN.json';
import { useConsoleLocale } from './console-locale';

export interface AuthenticationShellRoute {
  readonly path: string;
  readonly label: string;
  readonly element: ReactNode;
}

export interface AuthenticationShellProps {
  readonly applicationName: string;
  readonly runtime: AuthenticationRuntime;
  readonly defaultPath: string;
  readonly routes: readonly AuthenticationShellRoute[];
}

export interface AuthenticationRootErrorBoundaryProps {
  readonly applicationName: string;
  readonly children: ReactNode;
  readonly locale?: SupportedLocale;
  readonly reload?: () => void;
}

interface AuthenticationRootErrorBoundaryState {
  readonly failed: boolean;
}

const shellMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

type ShellTranslator = Translator<keyof (typeof shellMessages)['en-US']>;

export class AuthenticationRootErrorBoundary extends Component<
  AuthenticationRootErrorBoundaryProps,
  AuthenticationRootErrorBoundaryState
> {
  public state: AuthenticationRootErrorBoundaryState = { failed: false };

  public static getDerivedStateFromError(): AuthenticationRootErrorBoundaryState {
    return { failed: true };
  }

  public componentDidCatch(): void {
    // 根故障只提供安全重载；本票不引入新的错误上报平台。
  }

  public render(): ReactNode {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <ApplicationFatalError
        applicationName={this.props.applicationName}
        locale={this.props.locale}
        onReload={this.reload}
      />
    );
  }

  private readonly reload = (): void => {
    if (this.props.reload !== undefined) {
      this.props.reload();
      return;
    }
    window.location.reload();
  };
}

export function AuthenticationShell({
  applicationName,
  runtime,
  defaultPath,
  routes,
}: AuthenticationShellProps) {
  const { locale } = useConsoleLocale();
  const translate = createShellTranslator(locale);
  const location = useLocation();
  const navigate = useNavigate();
  const returnPath = useRef<string | undefined>(undefined);
  const logoutRequested = useRef(false);
  const state = useSyncExternalStore(
    (listener) => runtime.subscribe(listener),
    () => runtime.getState(),
  );
  const [recoveryComplete, setRecoveryComplete] = useState(false);
  const [recoveryProblem, setRecoveryProblem] = useState<AuthenticationProblem>();
  const [passwordChanged, setPasswordChanged] = useState(false);
  const [tenantSwitchOpen, setTenantSwitchOpen] = useState(false);
  const [tenantSwitchProblem, setTenantSwitchProblem] = useState<AuthenticationProblem>();
  const [tenantSwitchHandle, setTenantSwitchHandle] = useState<IdempotentOperationHandle>();
  const [tenantSwitchMembershipId, setTenantSwitchMembershipId] = useState<string>();
  const [tenantSwitchRetryMembershipId, setTenantSwitchRetryMembershipId] = useState<string>();
  const [tenantSessionEnded, setTenantSessionEnded] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void runtime.recover(controller.signal).then((result) => {
      if (!controller.signal.aborted) {
        if (!result.ok && isRecoverableRecoveryProblem(result.problem)) {
          setRecoveryProblem(result.problem);
        }
        setRecoveryComplete(true);
      }
    });
    return () => {
      controller.abort();
    };
  }, [runtime]);

  useEffect(() => {
    if (!recoveryComplete || recoveryProblem !== undefined || state.transition !== null) {
      return;
    }
    if (state.status === 'anonymous' && location.pathname !== '/login') {
      if (logoutRequested.current) {
        logoutRequested.current = false;
        returnPath.current = undefined;
        void navigate('/login', { replace: true });
        return;
      }
      const candidate = location.pathname + location.search + location.hash;
      if (
        !isAuthenticationPath(location.pathname) &&
        isValidApplicationPath(candidate, routes, defaultPath)
      ) {
        returnPath.current = candidate;
      }
      void navigate('/login', { replace: true });
      return;
    }
    if (state.status === 'passwordChangeRequired' && location.pathname !== '/change-password') {
      void navigate('/change-password', { replace: true });
      return;
    }
    if (state.status === 'contextSelectionRequired' && location.pathname !== '/select-context') {
      void navigate('/select-context', { replace: true });
    }
  }, [defaultPath, location, navigate, recoveryComplete, recoveryProblem, routes, state]);

  if (!recoveryComplete || state.transition === 'recover') {
    return <ApplicationLoading applicationName={applicationName} />;
  }

  if (state.status === 'authenticated' && state.transition === 'sessionSync') {
    return state.synchronizationProblem === undefined ? (
      <ApplicationLoading applicationName={applicationName} />
    ) : (
      <RecoveryPage
        runtime={runtime}
        problem={state.synchronizationProblem}
        onProblemChange={() => undefined}
        translate={translate}
      />
    );
  }

  if (recoveryProblem !== undefined) {
    return (
      <RecoveryPage
        runtime={runtime}
        problem={recoveryProblem}
        onProblemChange={setRecoveryProblem}
        translate={translate}
      />
    );
  }

  if (state.status === 'authenticated' && state.transition === 'tenantSwitchRefresh') {
    return (
      <TenantSwitchRefreshPage
        runtime={runtime}
        problem={tenantSwitchProblem}
        onResult={(result) => {
          if (result.ok) {
            setTenantSwitchProblem(undefined);
            setTenantSwitchHandle(undefined);
            setTenantSwitchRetryMembershipId(undefined);
            setTenantSwitchOpen(false);
          } else {
            setTenantSwitchProblem(result.problem);
            if (runtime.getState().status === 'anonymous') setTenantSessionEnded(true);
          }
        }}
      />
    );
  }

  if (state.status === 'authenticated') {
    const destination = returnPath.current ?? defaultPath;
    if (isAuthenticationPath(location.pathname) && location.pathname !== destination) {
      return <Navigate to={destination} replace />;
    }
    return (
      <ApplicationShell
        applicationName={applicationName}
        navigationLabel={translate.translate('globalNavigation', { applicationName })}
        navigationItems={routes.map((route) => ({
          href: route.path,
          label: route.label,
          current: matchPath({ path: route.path, end: true }, location.pathname) !== null,
        }))}
        onNavigate={(href) => {
          void navigate(href);
        }}
        actions={
          <>
            {runtime.intent === 'TENANT' &&
            state.tenantContext !== undefined &&
            state.tenantContext.accessibleMemberships.length > 1 ? (
              <Button
                onClick={() => {
                  setTenantSwitchOpen(true);
                  setTenantSwitchProblem(undefined);
                  setTenantSwitchHandle(undefined);
                  setTenantSwitchRetryMembershipId(undefined);
                }}
              >
                切换 Tenant
              </Button>
            ) : null}
            <Button
              loading={state.transition === 'logout'}
              loadingLabel={translate.translate('logoutLoading')}
              onClick={() => {
                logoutRequested.current = true;
                void runtime.logout();
              }}
            >
              {translate.translate('logout')}
            </Button>
          </>
        }
      >
        {tenantSwitchOpen && state.tenantContext !== undefined ? (
          <TenantSwitchPage
            currentMembershipId={state.tenantContext.membershipId}
            memberships={state.tenantContext.accessibleMemberships}
            problem={tenantSwitchProblem}
            selectedMembershipId={tenantSwitchMembershipId}
            retryMembershipId={tenantSwitchRetryMembershipId}
            onCancel={() => {
              setTenantSwitchOpen(false);
              setTenantSwitchProblem(undefined);
              setTenantSwitchHandle(undefined);
              setTenantSwitchRetryMembershipId(undefined);
            }}
            onSwitch={(membershipId) => {
              setTenantSwitchMembershipId(membershipId);
              setTenantSwitchProblem(undefined);
              void runtime
                .switchTenantContext({
                  membershipId,
                  ...(tenantSwitchHandle === undefined
                    ? {}
                    : { operationHandle: tenantSwitchHandle }),
                })
                .then((result) => {
                  if (result.ok) {
                    setTenantSwitchOpen(false);
                    setTenantSwitchHandle(undefined);
                    setTenantSwitchRetryMembershipId(undefined);
                    return;
                  }
                  setTenantSwitchProblem(result.problem);
                  setTenantSwitchHandle(result.operationHandle);
                  setTenantSwitchRetryMembershipId(
                    result.operationHandle === undefined ? undefined : membershipId,
                  );
                  if (runtime.getState().status === 'anonymous') setTenantSessionEnded(true);
                })
                .finally(() => {
                  setTenantSwitchMembershipId(undefined);
                });
            }}
          />
        ) : (
          <RouteErrorBoundary
            key={location.key}
            translate={translate}
            onReturn={() => {
              void navigate(defaultPath, { replace: true });
            }}
          >
            <Routes>
              {routes.map((route) => (
                <Route key={route.path} path={route.path} element={route.element} />
              ))}
              <Route path="*" element={<Navigate to={defaultPath} replace />} />
            </Routes>
          </RouteErrorBoundary>
        )}
      </ApplicationShell>
    );
  }

  if (state.status === 'passwordChangeRequired') {
    return (
      <InitialPasswordChangePage
        runtime={runtime}
        translate={translate}
        onChanged={() => {
          setPasswordChanged(true);
        }}
      />
    );
  }

  if (state.status === 'contextSelectionRequired') {
    return <ContextSelectionPage runtime={runtime} memberships={state.memberships} />;
  }

  if (state.status === 'logoutPending') {
    return <LogoutPendingPage runtime={runtime} translate={translate} />;
  }

  return (
    <LoginPage
      applicationName={applicationName}
      runtime={runtime}
      passwordChanged={passwordChanged}
      tenantSessionEnded={tenantSessionEnded}
      translate={translate}
    />
  );
}

function TenantSwitchPage({
  currentMembershipId,
  memberships,
  problem,
  selectedMembershipId,
  retryMembershipId,
  onCancel,
  onSwitch,
}: {
  readonly currentMembershipId: string;
  readonly memberships: readonly MembershipCandidate[];
  readonly problem?: AuthenticationProblem;
  readonly selectedMembershipId?: string;
  readonly retryMembershipId?: string;
  readonly onCancel: () => void;
  readonly onSwitch: (membershipId: string) => void;
}) {
  const candidates = memberships.filter(
    (membership) => membership.membershipId !== currentMembershipId,
  );
  const resultUnknown = problem?.code === 'NETWORK_UNAVAILABLE' || problem?.status === 503;
  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="tenant-switch-title"
          title="切换 Tenant"
          description="选择当前 Identity 可进入的另一个 Accessible Membership。"
        />
      }
    >
      {problem === undefined ? null : (
        <PersistentError title={resultUnknown ? 'Tenant 切换结果未知' : 'Tenant 切换被拒绝'}>
          <p>错误代码：{problem.code}</p>
          {resultUnknown ? <p>请使用同一次操作重试，不要重复发起新的切换。</p> : null}
        </PersistentError>
      )}
      <ul aria-label="Accessible Membership">
        {candidates.map((membership) => (
          <li key={membership.membershipId}>
            <span>{membership.tenantDisplayName}</span>
            <Button
              variant="primary"
              loading={selectedMembershipId === membership.membershipId}
              loadingLabel={`正在切换到 ${membership.tenantDisplayName}`}
              disabled={
                selectedMembershipId !== undefined ||
                (retryMembershipId !== undefined && retryMembershipId !== membership.membershipId)
              }
              onClick={() => {
                onSwitch(membership.membershipId);
              }}
            >
              {retryMembershipId === membership.membershipId ? '重试切换到' : '切换到'}{' '}
              {membership.tenantDisplayName}
            </Button>
          </li>
        ))}
      </ul>
      <Button disabled={selectedMembershipId !== undefined} onClick={onCancel}>
        取消
      </Button>
    </PageLayout>
  );
}

function TenantSwitchRefreshPage({
  runtime,
  problem,
  onResult,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly problem?: AuthenticationProblem;
  readonly onResult: (
    result: Awaited<ReturnType<AuthenticationRuntime['retryTenantSwitchRefresh']>>,
  ) => void;
}) {
  const [retrying, setRetrying] = useState(false);
  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="tenant-switch-refresh-title"
          title="Tenant 切换已提交"
          description="旧访问令牌已停止使用，正在等待目标 Tenant 会话恢复。"
        />
      }
    >
      <p role="status">切换已提交；在完成前不会显示任何 Tenant 业务页面。</p>
      {problem === undefined ? null : (
        <PersistentError title="目标 Tenant 会话暂时无法恢复">
          错误代码：{problem.code}
        </PersistentError>
      )}
      <Button
        variant="primary"
        loading={retrying}
        loadingLabel="正在重试完成切换"
        onClick={() => {
          setRetrying(true);
          void runtime
            .retryTenantSwitchRefresh()
            .then(onResult)
            .finally(() => {
              setRetrying(false);
            });
        }}
      >
        重试完成切换
      </Button>
    </PageLayout>
  );
}

interface RouteErrorBoundaryProps {
  readonly children: ReactNode;
  readonly onReturn: () => void;
  readonly translate: ShellTranslator;
}

interface RouteErrorBoundaryState {
  readonly failed: boolean;
}

class RouteErrorBoundary extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  public state: RouteErrorBoundaryState = { failed: false };

  public static getDerivedStateFromError(): RouteErrorBoundaryState {
    return { failed: true };
  }

  public componentDidCatch(): void {
    // 路由故障只在当前 Shell 内隔离；本票不引入新的错误上报平台。
  }

  public render(): ReactNode {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <section aria-labelledby="route-error-title">
        <ShellPageTitle
          headingId="route-error-title"
          title={this.props.translate.translate('routeErrorTitle')}
          description={this.props.translate.translate('routeErrorDescription')}
        />
        <Button variant="primary" onClick={this.props.onReturn}>
          {this.props.translate.translate('returnHome')}
        </Button>
      </section>
    );
  }
}

function LogoutPendingPage({
  runtime,
  translate,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly translate: ShellTranslator;
}) {
  const [retrying, setRetrying] = useState(false);
  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="logout-pending-title"
          title={translate.translate('logoutPendingTitle')}
          description={translate.translate('logoutPendingDescription')}
        />
      }
    >
      <Button
        variant="primary"
        loading={retrying}
        loadingLabel={translate.translate('retryLogoutLoading')}
        onClick={() => {
          setRetrying(true);
          void runtime.logout().finally(() => {
            setRetrying(false);
          });
        }}
      >
        {translate.translate('retryLogout')}
      </Button>
    </PageLayout>
  );
}

const AUTHENTICATION_PATHS = new Set(['/login', '/change-password', '/select-context', '/recover']);

function isAuthenticationPath(pathname: string): boolean {
  return AUTHENTICATION_PATHS.has(pathname);
}

function isValidApplicationPath(
  candidate: string,
  routes: readonly AuthenticationShellRoute[],
  defaultPath: string,
): boolean {
  if (!candidate.startsWith('/') || candidate.startsWith('//') || candidate.includes('\\')) {
    return false;
  }
  const parsed = new URL(candidate, 'https://console.invalid');
  if (parsed.origin !== 'https://console.invalid') {
    return false;
  }
  return (
    parsed.pathname === defaultPath ||
    routes.some((route) => matchPath({ path: route.path, end: false }, parsed.pathname) !== null)
  );
}

function RecoveryPage({
  runtime,
  problem,
  onProblemChange,
  translate,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly problem: AuthenticationProblem;
  readonly onProblemChange: (problem: AuthenticationProblem | undefined) => void;
  readonly translate: ShellTranslator;
}) {
  const [retrying, setRetrying] = useState(false);
  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="recovery-title"
          title={translate.translate('recoveryTitle')}
          description={translate.translate('recoveryDescription')}
        />
      }
    >
      <p role="status">{translate.translate('recoveryCode', { code: problem.code })}</p>
      <Button
        variant="primary"
        loading={retrying}
        loadingLabel={translate.translate('recoveryRetryLoading')}
        onClick={() => {
          setRetrying(true);
          void runtime.retryRecovery().then((result) => {
            setRetrying(false);
            if (result.ok) {
              onProblemChange(undefined);
              return;
            }
            onProblemChange(result.problem);
          });
        }}
      >
        {translate.translate('recoveryRetry')}
      </Button>
    </PageLayout>
  );
}

function isRecoverableRecoveryProblem(problem: AuthenticationProblem): boolean {
  return problem.code === 'NETWORK_UNAVAILABLE' || problem.status === 409 || problem.status === 503;
}

function LoginPage({
  applicationName,
  runtime,
  passwordChanged,
  tenantSessionEnded,
  translate,
}: {
  readonly applicationName: string;
  readonly runtime: AuthenticationRuntime;
  readonly passwordChanged: boolean;
  readonly tenantSessionEnded: boolean;
  readonly translate: ShellTranslator;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [problem, setProblem] = useState<AuthenticationProblem>();
  const requestController = useRef<AbortController | undefined>(undefined);

  useEffect(
    () => () => {
      requestController.current?.abort();
    },
    [],
  );

  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="login-title"
          title={translate.translate('loginTitle', { applicationName })}
        />
      }
    >
      {passwordChanged ? (
        <SuccessFeedback
          message={translate.translate('passwordChanged')}
          stableKey="password-changed"
        />
      ) : null}
      {tenantSessionEnded ? (
        <PersistentError title="Tenant 会话已结束">
          切换后的目标 Tenant 会话无法恢复，请重新登录。
        </PersistentError>
      ) : null}
      {problem?.code === 'SESSION_SLOT_ALREADY_ACTIVE' ? (
        <PersistentError
          title={translate.translate('sessionSlotAlreadyActive', {
            contextName: runtime.intent === 'PLATFORM' ? 'Platform' : 'Tenant',
          })}
          action={{
            label: translate.translate('sessionSlotLogout', {
              contextName: runtime.intent === 'PLATFORM' ? 'Platform' : 'Tenant',
            }),
            onAction: () => {
              void runtime.logout().then((result) => {
                if (result.ok) {
                  setProblem(undefined);
                }
              });
            },
          }}
        />
      ) : problem?.code === 'ACCESS_CONTEXT_UNAVAILABLE' && runtime.intent === 'TENANT' ? (
        <PersistentError title="无法进入 Tenant">
          <p>当前 Identity 没有可进入的 Tenant，请联系 Tenant 管理员。</p>
          <p>错误代码：{problem.code}</p>
        </PersistentError>
      ) : problem?.code === 'ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED' ? (
        <PersistentError title="无法显示 Membership 候选">
          <p>Accessible Membership 数量超过当前选择上限，请联系平台管理员。</p>
          <p>错误代码：{problem.code}</p>
        </PersistentError>
      ) : problem === undefined ? null : (
        <PersistentError title={translate.translate('signInFailed')}>
          {translate.translate('errorCode', { code: problem.code })}
        </PersistentError>
      )}
      <FormLayout
        ariaLabel={translate.translate('loginFormLabel', { applicationName })}
        onSubmit={(event) => {
          event.preventDefault();
          const submittedPassword = password;
          requestController.current?.abort();
          const controller = new AbortController();
          requestController.current = controller;
          setPassword('');
          void runtime
            .login({ email, password: submittedPassword, signal: controller.signal })
            .then((result) => {
              if (!controller.signal.aborted) {
                setProblem(result.ok ? undefined : result.problem);
              }
            })
            .finally(() => {
              if (requestController.current === controller) {
                requestController.current = undefined;
              }
            });
        }}
      >
        <FormRow>
          <TextField
            id="authentication-email"
            label={translate.translate('emailLabel')}
            value={email}
            onValueChange={setEmail}
            autoComplete="username"
            required
          />
        </FormRow>
        <FormRow>
          <PasswordField
            id="authentication-password"
            label={translate.translate('passwordLabel')}
            value={password}
            onValueChange={setPassword}
            autoComplete="current-password"
            required
          />
        </FormRow>
        <Button
          type="submit"
          variant="primary"
          loading={runtime.getState().transition === 'login'}
          loadingLabel={translate.translate('signInLoading')}
        >
          {translate.translate('signIn')}
        </Button>
      </FormLayout>
    </PageLayout>
  );
}

function InitialPasswordChangePage({
  runtime,
  onChanged,
  translate,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly onChanged: () => void;
  readonly translate: ShellTranslator;
}) {
  const [newPassword, setNewPassword] = useState('');
  const [problem, setProblem] = useState<AuthenticationProblem>();
  const requestController = useRef<AbortController | undefined>(undefined);

  useEffect(
    () => () => {
      requestController.current?.abort();
    },
    [],
  );

  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="password-change-title"
          title={translate.translate('initialPasswordChangeTitle')}
          description={translate.translate('initialPasswordChangeDescription')}
        />
      }
    >
      <FormLayout
        ariaLabel={translate.translate('initialPasswordChangeFormLabel')}
        onSubmit={(event) => {
          event.preventDefault();
          const submittedPassword = newPassword;
          requestController.current?.abort();
          const controller = new AbortController();
          requestController.current = controller;
          setNewPassword('');
          setProblem(undefined);
          void runtime
            .changeInitialPassword({ newPassword: submittedPassword, signal: controller.signal })
            .then((result) => {
              if (result.ok) {
                onChanged();
              } else if (!controller.signal.aborted) {
                setProblem(result.problem);
              }
            })
            .finally(() => {
              if (requestController.current === controller) {
                requestController.current = undefined;
              }
            });
        }}
      >
        {problem !== undefined ? (
          <PersistentError title={translate.translate('passwordChangeFailed')}>
            {translate.translate('errorCode', { code: problem.code })}
          </PersistentError>
        ) : null}
        <FormRow>
          <PasswordField
            id="authentication-new-password"
            label={translate.translate('newPasswordLabel')}
            value={newPassword}
            onValueChange={setNewPassword}
            autoComplete="new-password"
            required
          />
        </FormRow>
        <Button
          type="submit"
          variant="primary"
          loading={runtime.getState().transition === 'passwordChange'}
          loadingLabel={translate.translate('passwordUpdateLoading')}
        >
          {translate.translate('passwordUpdate')}
        </Button>
      </FormLayout>
    </PageLayout>
  );
}

function ContextSelectionPage({
  runtime,
  memberships,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly memberships: readonly {
    readonly membershipId: string;
    readonly tenantDisplayName: string;
  }[];
}) {
  const [selectedMembershipId, setSelectedMembershipId] = useState<string>();
  const [problem, setProblem] = useState<AuthenticationProblem>();

  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="context-selection-title"
          title="选择 Tenant"
          description="选择本次会话要进入的 Accessible Membership。"
        />
      }
    >
      {problem === undefined ? null : (
        <PersistentError title="无法进入所选 Tenant">错误代码：{problem.code}</PersistentError>
      )}
      <ul aria-label="Accessible Membership">
        {memberships.map((membership) => (
          <li key={membership.membershipId}>
            <span>{membership.tenantDisplayName}</span>
            <Button
              variant="primary"
              loading={selectedMembershipId === membership.membershipId}
              loadingLabel={`正在进入 ${membership.tenantDisplayName}`}
              disabled={selectedMembershipId !== undefined}
              onClick={() => {
                setSelectedMembershipId(membership.membershipId);
                setProblem(undefined);
                void runtime
                  .selectAuthenticationContext({ membershipId: membership.membershipId })
                  .then((result) => {
                    if (!result.ok) {
                      setProblem(result.problem);
                    }
                  })
                  .finally(() => {
                    setSelectedMembershipId(undefined);
                  });
              }}
            >
              进入 {membership.tenantDisplayName}
            </Button>
          </li>
        ))}
      </ul>
    </PageLayout>
  );
}

function ShellPageTitle({
  headingId,
  title,
  description,
}: {
  readonly headingId: string;
  readonly title: string;
  readonly description?: string;
}) {
  const location = useLocation();
  return (
    <>
      <RouteFocusAnnouncement routeKey={location.key} pageTitle={title} focusTargetId={headingId} />
      <PageTitle headingId={headingId} description={description}>
        {title}
      </PageTitle>
    </>
  );
}

function createShellTranslator(locale: SupportedLocale): ShellTranslator {
  return createTranslator({
    namespace: '@saas-forge/react-shell',
    locale,
    messages: shellMessages,
  });
}
