import type { AuthenticationProblem, AuthenticationRuntime } from '@saas-forge/app-runtime';
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
        }
      >
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

  if (state.status === 'logoutPending') {
    return <LogoutPendingPage runtime={runtime} />;
  }

  return (
    <LoginPage
      applicationName={applicationName}
      runtime={runtime}
      passwordChanged={passwordChanged}
    />
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

const AUTHENTICATION_PATHS = new Set(['/login', '/change-password', '/recover']);

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
}: {
  readonly applicationName: string;
  readonly runtime: AuthenticationRuntime;
  readonly passwordChanged: boolean;
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
      {problem?.code === 'SESSION_SLOT_ALREADY_ACTIVE' ? (
        <PersistentError
          title="当前 Platform 会话槽位已有活动会话。"
          action={{
            label: '先登出当前会话',
            onAction: () => {
              void runtime.logout().then((result) => {
                if (result.ok) {
                  setProblem(undefined);
                }
              });
            },
          }}
        />
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
