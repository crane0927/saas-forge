import { RouteFocusAnnouncement } from '@saas-forge/design-system';
import { createTranslator, defineMessages, type SupportedLocale } from '@saas-forge/i18n';
import type { AuthenticationShellRoute } from '@saas-forge/react-shell';
import { useLocation } from 'react-router';

import enUS from './messages/en-US.json';
import zhCN from './messages/zh-CN.json';

const platformMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

export const platformAuthenticationRoutes = createPlatformAuthenticationRoutes('zh-CN');

export function createPlatformAuthenticationRoutes(
  locale: SupportedLocale,
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
      element: <PlatformOverview title={overviewTitle} description={overviewDescription} />,
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
}: {
  readonly title: string;
  readonly description: string;
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
