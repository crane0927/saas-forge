import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import type { AuthenticationShellRoute } from '@saas-forge/react-shell';
import { useLocation } from 'react-router';

export const platformAuthenticationRoutes: readonly AuthenticationShellRoute[] = [
  { path: '/', label: '首页', element: <PlatformOverview /> },
  { path: '/oauth-clients', label: 'OAuth Client', element: <OAuthClientsPage /> },
];

function PlatformOverview() {
  const location = useLocation();
  return (
    <section aria-labelledby="platform-overview-title">
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle="Platform 总览"
        focusTargetId="platform-overview-title"
      />
      <h1 id="platform-overview-title" tabIndex={-1}>
        Platform 总览
      </h1>
      <p>当前会话已通过 Platform 认证 Runtime 恢复或登录。</p>
    </section>
  );
}

function OAuthClientsPage() {
  const location = useLocation();
  return (
    <section aria-labelledby="oauth-clients-title">
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle="OAuth Client 管理"
        focusTargetId="oauth-clients-title"
      />
      <h1 id="oauth-clients-title" tabIndex={-1}>
        OAuth Client 管理
      </h1>
      <p>该入口只注册 Platform 本地路由，不加载动态 Remote。</p>
    </section>
  );
}
