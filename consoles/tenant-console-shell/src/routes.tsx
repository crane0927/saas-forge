import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import { Outlet, Route, Routes, useLocation } from 'react-router';

export function TenantLocalRouteOutlet() {
  return <Outlet />;
}

export function TenantRoutes() {
  return (
    <Routes>
      <Route path="/" element={<TenantShellRoot />}>
        <Route element={<TenantLocalRouteOutlet />}>
          <Route index element={<NotFound />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

function TenantShellRoot() {
  return <Outlet />;
}

function NotFound() {
  const location = useLocation();
  return (
    <main className="sf-runtime-surface">
      <section className="sf-runtime-panel" aria-labelledby="not-found-title">
        <RouteFocusAnnouncement
          routeKey={location.key}
          pageTitle="页面不存在"
          focusTargetId="not-found-title"
        />
        <h1 id="not-found-title" tabIndex={-1}>
          页面不存在
        </h1>
        <p>Tenant Console 尚未提供此路由。</p>
        <p className="sf-runtime-code">404</p>
      </section>
    </main>
  );
}
