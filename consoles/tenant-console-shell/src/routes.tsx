import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import type { AuthenticationRuntime } from '@saas-forge/app-runtime';
import { useSyncExternalStore } from 'react';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import type { AuthenticationShellRoute } from '@saas-forge/react-shell';
import { useLocation } from 'react-router';

import { tenantMessages } from './messages';

export const tenantAuthenticationRoutes = createTenantAuthenticationRoutes('zh-CN');

export function createTenantAuthenticationRoutes(
  locale: SupportedLocale,
  runtime?: AuthenticationRuntime,
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
      element: (
        <TenantWorkspace
          title={workspaceTitle}
          description={workspaceDescription}
          runtime={runtime}
          currentCompanyLabel={translate.translate('currentCompany')}
        />
      ),
    },
  ];
}

function TenantWorkspace({
  title,
  description,
  runtime,
  currentCompanyLabel,
}: {
  readonly title: string;
  readonly description: string;
  readonly runtime?: AuthenticationRuntime;
  readonly currentCompanyLabel: string;
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
      {runtime === undefined ? null : (
        <CurrentCompany runtime={runtime} label={currentCompanyLabel} />
      )}
    </section>
  );
}

function CurrentCompany({
  runtime,
  label,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly label: string;
}) {
  const state = useSyncExternalStore(
    (listener) => runtime.subscribe(listener),
    () => runtime.getState(),
  );
  if (
    state.status !== 'authenticated' ||
    state.transition !== null ||
    state.tenantContext === undefined
  )
    return null;
  return (
    <dl>
      <dt>{label}</dt>
      <dd>{state.tenantContext.tenantDisplayName}</dd>
    </dl>
  );
}
