import type {
  AuthenticationProblem,
  AuthenticationRuntime,
  IdempotentOperationHandle,
  MembershipCandidate,
} from '@saas-forge/app-runtime';
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
  readonly reload?: () => void;
}

interface AuthenticationRootErrorBoundaryState {
  readonly failed: boolean;
}

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
      <ApplicationFatalError applicationName={this.props.applicationName} onReload={this.reload} />
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

  if (recoveryProblem !== undefined) {
    return (
      <RecoveryPage
        runtime={runtime}
        problem={recoveryProblem}
        onProblemChange={setRecoveryProblem}
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
              loadingLabel="正在退出登录"
              onClick={() => {
                logoutRequested.current = true;
                void runtime.logout();
              }}
            >
              退出登录
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
    return <LogoutPendingPage runtime={runtime} />;
  }

  return (
    <LoginPage
      applicationName={applicationName}
      runtime={runtime}
      passwordChanged={passwordChanged}
      tenantSessionEnded={tenantSessionEnded}
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
        <PageTitle description="当前路由已被隔离，未显示原始错误信息。">
          <span id="route-error-title">当前页面出现错误</span>
        </PageTitle>
        <Button variant="primary" onClick={this.props.onReturn}>
          返回首页
        </Button>
      </section>
    );
  }
}

function LogoutPendingPage({ runtime }: { readonly runtime: AuthenticationRuntime }) {
  const [retrying, setRetrying] = useState(false);
  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="logout-pending-title"
          title="退出结果尚未确认"
          description="本页面已停止使用当前会话，但服务端是否完成退出仍未知。"
        />
      }
    >
      <Button
        variant="primary"
        loading={retrying}
        loadingLabel="正在重试退出"
        onClick={() => {
          setRetrying(true);
          void runtime.logout().finally(() => {
            setRetrying(false);
          });
        }}
      >
        重试退出
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
}: {
  readonly runtime: AuthenticationRuntime;
  readonly problem: AuthenticationProblem;
  readonly onProblemChange: (problem: AuthenticationProblem | undefined) => void;
}) {
  const [retrying, setRetrying] = useState(false);
  return (
    <PageLayout
      title={
        <ShellPageTitle
          headingId="recovery-title"
          title="暂时无法恢复会话"
          description="当前会话状态无法确定，请重试恢复。"
        />
      }
    >
      <p role="status">恢复代码：{problem.code}</p>
      <Button
        variant="primary"
        loading={retrying}
        loadingLabel="正在重试恢复"
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
        重试恢复
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
}: {
  readonly applicationName: string;
  readonly runtime: AuthenticationRuntime;
  readonly passwordChanged: boolean;
  readonly tenantSessionEnded: boolean;
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
      title={<ShellPageTitle headingId="login-title" title={`登录 ${applicationName}`} />}
    >
      {passwordChanged ? <SuccessFeedback message="密码已更新，请使用新密码重新登录。" /> : null}
      {tenantSessionEnded ? (
        <PersistentError title="Tenant 会话已结束">
          切换后的目标 Tenant 会话无法恢复，请重新登录。
        </PersistentError>
      ) : null}
      {problem?.code === 'SESSION_SLOT_ALREADY_ACTIVE' ? (
        <PersistentError
          title={`当前 ${runtime.intent === 'PLATFORM' ? 'Platform' : 'Tenant'} 会话槽位已有活动会话。`}
          action={{
            label: `先登出当前 ${runtime.intent === 'PLATFORM' ? 'Platform' : 'Tenant'} 会话`,
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
        <PersistentError title="登录失败">错误代码：{problem.code}</PersistentError>
      )}
      <FormLayout
        ariaLabel={`登录 ${applicationName}`}
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
            label="邮箱"
            value={email}
            onValueChange={setEmail}
            autoComplete="username"
            required
          />
        </FormRow>
        <FormRow>
          <PasswordField
            id="authentication-password"
            label="密码"
            value={password}
            onValueChange={setPassword}
            autoComplete="current-password"
            required
          />
        </FormRow>
        <Button type="submit" variant="primary" loading={runtime.getState().transition === 'login'}>
          登录
        </Button>
      </FormLayout>
    </PageLayout>
  );
}

function InitialPasswordChangePage({
  runtime,
  onChanged,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly onChanged: () => void;
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
          title="设置新密码"
          description="首次进入 Platform Console 前必须更换初始密码。"
        />
      }
    >
      <FormLayout
        ariaLabel="设置新密码"
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
          <PersistentError title="密码更新失败">错误代码：{problem.code}</PersistentError>
        ) : null}
        <FormRow>
          <PasswordField
            id="authentication-new-password"
            label="新密码"
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
          loadingLabel="正在更新密码"
        >
          更新密码
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
