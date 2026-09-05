import {
  Button,
  PageLayout,
  PageTitle,
  ResponsiveGrid,
  SplitLayout,
  SuccessFeedback,
  TextField,
} from '@saas-forge/design-system';
import { createTranslator, defineMessages, type SupportedLocale } from '@saas-forge/i18n';
import { useState } from 'react';

import enUS from './locales/en-US.json';
import zhCN from './locales/zh-CN.json';

const remoteMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});
const contentItemKeys = ['contentSharedTheme', 'contentPublicEntry', 'contentResponsive'] as const;
const statisticItems = [
  ['statisticThemeEntry', 'statisticThemeEntryValue'],
  ['statisticGlobalStyleEntry', 'statisticGlobalStyleEntryValue'],
  ['statisticLayoutIntents', 'statisticLayoutIntentsValue'],
  ['statisticAuxiliaryPanel', 'statisticAuxiliaryPanelValue'],
] as const;

export interface DesignSystemConsumerRemoteProps {
  readonly locale: SupportedLocale;
}

export function DesignSystemConsumerRemote({ locale }: DesignSystemConsumerRemoteProps) {
  const [name, setName] = useState('Remote');
  const [submittedName, setSubmittedName] = useState<string>();
  const translate = createTranslator({
    namespace: '@saas-forge/design-system-consumer-fixture',
    locale,
    messages: remoteMessages,
  });

  return (
    <PageLayout
      width="wide"
      title={
        <PageTitle description={translate.translate('pageDescription')}>
          {translate.translate('pageTitle')}
        </PageTitle>
      }
    >
      <section aria-labelledby="remote-content-title">
        <h2 id="remote-content-title">{translate.translate('contentTitle')}</h2>
        <ResponsiveGrid intent="content">
          {contentItemKeys.map((key) => (
            <article data-testid="remote-content-item" key={key}>
              <h3>{translate.translate(key)}</h3>
              <p>{translate.translate('contentItemDescription')}</p>
            </article>
          ))}
        </ResponsiveGrid>
      </section>

      <section aria-labelledby="remote-statistics-title">
        <h2 id="remote-statistics-title">{translate.translate('statisticsTitle')}</h2>
        <ResponsiveGrid intent="compact-statistics">
          {statisticItems.map(([termKey, valueKey]) => (
            <dl data-testid="remote-statistics-item" key={termKey}>
              <dt>{translate.translate(termKey)}</dt>
              <dd>{translate.translate(valueKey)}</dd>
            </dl>
          ))}
        </ResponsiveGrid>
      </section>

      <SplitLayout
        primary={
          <section aria-labelledby="remote-verification-title">
            <h2 id="remote-verification-title">{translate.translate('verificationTitle')}</h2>
            <form
              aria-label={translate.translate('verificationFormLabel')}
              onSubmit={(event) => {
                event.preventDefault();
                setSubmittedName(name.trim() === '' ? 'Remote' : name.trim());
              }}
            >
              <TextField
                id="remote-name"
                label={translate.translate('displayNameLabel')}
                value={name}
                onValueChange={setName}
              />
              <Button type="submit" variant="primary">
                {translate.translate('verifyFeedbackAction')}
              </Button>
            </form>
            {submittedName === undefined ? null : (
              <SuccessFeedback
                stableKey={submittedName}
                message={translate.translate('successMessage', { name: submittedName })}
              />
            )}
          </section>
        }
        auxiliary={
          <section>
            <h2>{translate.translate('layoutTitle')}</h2>
            <p>{translate.translate('layoutDescription')}</p>
            <Button onClick={() => undefined}>{translate.translate('viewLayoutAction')}</Button>
          </section>
        }
        auxiliaryLabel={translate.translate('layoutRegionLabel')}
      />
    </PageLayout>
  );
}
