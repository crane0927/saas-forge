import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import { createTranslator, defineMessages, type SupportedLocale } from '@saas-forge/i18n';
import type { AuthenticationShellRoute } from '@saas-forge/react-shell';
import { useLocation } from 'react-router';

import enUS from './messages/en-US.json';
import zhCN from './messages/zh-CN.json';

const tenantMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

export const tenantAuthenticationRoutes = createTenantAuthenticationRoutes('zh-CN');

export function createTenantAuthenticationRoutes(
  locale: SupportedLocale,
): readonly AuthenticationShellRoute[] {
  const translate = createTranslator({
    namespace: '@saas-forge/tenant-console-shell',
    locale,
    messages: tenantMessages,
  });
  const workspaceTitle = translate.translate('workspaceTitle');
  const workspaceDescription = translate.translate('workspaceDescription');

  return [
    {
      path: '/',
      label: translate.translate('navigationWorkspace'),
      element: <TenantWorkspace title={workspaceTitle} description={workspaceDescription} />,
    },
  ];
}

function TenantWorkspace({
  title,
  description,
}: {
  readonly title: string;
  readonly description: string;
}) {
  const location = useLocation();
  return (
    <section aria-labelledby="tenant-workspace-title">
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="tenant-workspace-title"
      />
      <h1 id="tenant-workspace-title" tabIndex={-1}>
        {title}
      </h1>
      <p>{description}</p>
    </section>
  );
}
