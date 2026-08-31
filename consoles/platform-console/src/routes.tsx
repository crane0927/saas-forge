import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import { Outlet, Route, Routes, useLocation } from 'react-router';

export function PlatformPublicAreaOutlet() {
  return <Outlet />;
}

export function PlatformProtectedAreaOutlet() {
  return <Outlet />;
}

export function PlatformRoutes() {
  return (
    <Routes>
      <Route path="/" element={<PlatformApplicationRoot />}>
        <Route index element={<NotFound />} />
        <Route path="public" element={<PlatformPublicAreaOutlet />}>
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route path="protected" element={<PlatformProtectedAreaOutlet />}>
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

function PlatformApplicationRoot() {
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
        <p>Platform Console 尚未提供此路由。</p>
        <p className="sf-runtime-code">404</p>
      </section>
    </main>
  );
}
