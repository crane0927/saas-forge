import type { ConsoleApiClient } from '@saas-forge/app-runtime';
import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import { createTranslator, type SupportedLocale } from '@saas-forge/i18n';
import type { AuthenticationShellRoute } from '@saas-forge/react-shell';
import { useLocation } from 'react-router';

import { TenantRoutes } from './tenants';

import { CurrentSessionPanel } from './current-session';
import { platformMessages } from './messages';

export function createPlatformAuthenticationRoutes(
  locale: SupportedLocale,
  client: ConsoleApiClient,
): readonly AuthenticationShellRoute[] {
  const translate = createTranslator({
    namespace: '@saas-forge/platform-console',
    locale,
    messages: platformMessages,
  });
  const overviewTitle = translate.translate('platformOverviewTitle');
  const overviewDescription = translate.translate('platformOverviewDescription');
  const oauthClientsTitle = translate.translate('oauthClientsTitle');
  const oauthClientsDescription = translate.translate('oauthClientsDescription');

  return [
    {
      path: '/',
      label: translate.translate('navigationHome'),
      element: (
        <PlatformOverview
          title={overviewTitle}
          description={overviewDescription}
          client={client}
          locale={locale}
        />
      ),
    },
    {
      path: '/tenants/*',
      navigationPath: '/tenants',
      label: translate.translate('tenantsTitle'),
      element: <TenantRoutes client={client} locale={locale} />,
    },
    {
      path: '/oauth-clients',
      label: 'OAuth Client',
      element: <OAuthClientsPage title={oauthClientsTitle} description={oauthClientsDescription} />,
    },
  ];
}

function PlatformOverview({
  title,
  description,
  client,
  locale,
}: {
  readonly title: string;
  readonly description: string;
  readonly client: ConsoleApiClient;
  readonly locale: SupportedLocale;
}) {
  const location = useLocation();
  return (
    <section aria-labelledby="platform-overview-title">
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="platform-overview-title"
      />
      <h1 id="platform-overview-title" tabIndex={-1}>
        {title}
      </h1>
      <p>{description}</p>
      <CurrentSessionPanel client={client} locale={locale} />
    </section>
  );
}

function OAuthClientsPage({
  title,
  description,
}: {
  readonly title: string;
  readonly description: string;
}) {
  const location = useLocation();
  return (
    <section aria-labelledby="oauth-clients-title">
      <RouteFocusAnnouncement
        routeKey={location.key}
        pageTitle={title}
        focusTargetId="oauth-clients-title"
      />
      <h1 id="oauth-clients-title" tabIndex={-1}>
        {title}
      </h1>
      <p>{description}</p>
    </section>
  );
}
