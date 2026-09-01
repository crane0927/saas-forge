import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import type { AuthenticationShellRoute } from '@saas-forge/react-shell';
import { useLocation } from 'react-router';

export const tenantAuthenticationRoutes: readonly AuthenticationShellRoute[] = [
  { path: '/', label: '工作台', element: <TenantWorkspace /> },
];

function TenantWorkspace() {
  const location = useLocation();
  return (
    <section aria-labelledby="tenant-workspace-title">
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle="Tenant 工作台"
        focusTargetId="tenant-workspace-title"
      />
      <h1 id="tenant-workspace-title" tabIndex={-1}>
        Tenant 工作台
      </h1>
      <p>当前会话已通过 Tenant 认证 Runtime 恢复或登录。</p>
    </section>
  );
}
