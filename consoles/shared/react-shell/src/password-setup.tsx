import type { AuthenticationRuntime } from '@saas-forge/app-runtime';
import {
  Button,
  FormLayout,
  FormRow,
  PageLayout,
  PageTitle,
  PasswordField,
  PersistentError,
  RouteFocusAnnouncement,
  SuccessFeedback,
} from '@saas-forge/design-system';
import { createTranslator } from '@saas-forge/i18n';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { useConsoleLocale } from './console-locale';
import { shellMessages } from './messages';

export function PasswordSetupPage({ runtime }: { readonly runtime: AuthenticationRuntime }) {
  const { locale } = useConsoleLocale();
  const translate = createTranslator({
    namespace: '@saas-forge/react-shell',
    locale,
    messages: shellMessages,
  });
  const location = useLocation();
  const navigate = useNavigate();
  const captured = useRef(false);
  const challenge = useRef<string | undefined>(undefined);
  const request = useRef<AbortController | undefined>(undefined);
  const [valid, setValid] = useState(false);
  const [password, setPassword] = useState('');
  const [passwordRejected, setPasswordRejected] = useState(false);
  const [status, setStatus] = useState<'ready' | 'pending' | 'success' | 'failed' | 'unknown'>(
    'ready',
  );
  useEffect(() => {
    if (captured.current) return;
    captured.current = true;
    const token = new URLSearchParams(location.hash.slice(1)).get('token');
    if (token !== null && /^[A-Za-z0-9_-]{43}$/.test(token)) {
      challenge.current = token;
      setValid(true);
    }
    void navigate('/password-setup', { replace: true });
  }, [location.hash, navigate]);
  useEffect(
    () => () => {
      request.current?.abort();
    },
    [],
  );

  return (
    <PageLayout
      title={
        <>
          <RouteFocusAnnouncement
            routeKey={location.key}
            pageTitle={translate.translate('passwordSetupTitle')}
            focusTargetId="password-setup-title"
          />
          <PageTitle headingId="password-setup-title">
            {translate.translate('passwordSetupTitle')}
          </PageTitle>
        </>
      }
    >
      {status === 'success' ? (
        <SuccessFeedback message={translate.translate('passwordSetupSuccess')} />
      ) : status === 'unknown' ? (
        <PersistentError title={translate.translate('passwordSetupUnknown')} />
      ) : !valid || status === 'failed' ? (
        <PersistentError title={translate.translate('passwordSetupUnavailable')} />
      ) : (
        <FormLayout
          ariaLabel={translate.translate('passwordSetupTitle')}
          onSubmit={(event) => {
            event.preventDefault();
            if (request.current !== undefined || challenge.current === undefined) return;
            const token = challenge.current;
            challenge.current = undefined;
            const newPassword = password;
            setPassword('');
            setPasswordRejected(false);
            setStatus('pending');
            const controller = new AbortController();
            request.current = controller;
            void runtime
              .establishPassword({ token, newPassword, signal: controller.signal })
              .then((result) => {
                if (controller.signal.aborted) return;
                request.current = undefined;
                if (
                  !result.ok &&
                  result.problem.status === 400 &&
                  [
                    'PASSWORD_TOO_SHORT',
                    'PASSWORD_TOO_LONG',
                    'PASSWORD_WHITESPACE_NOT_ALLOWED',
                    'PASSWORD_COMPROMISED',
                  ].includes(result.problem.code)
                ) {
                  challenge.current = token;
                  setPasswordRejected(true);
                  setStatus('ready');
                  return;
                }
                setStatus(
                  result.ok
                    ? 'success'
                    : result.problem.status === undefined || result.problem.status >= 500
                      ? 'unknown'
                      : 'failed',
                );
              });
          }}
        >
          <FormRow>
            <PasswordField
              id="password-setup-new-password"
              label={translate.translate('newPasswordLabel')}
              value={password}
              onValueChange={setPassword}
              autoComplete="new-password"
              error={passwordRejected ? translate.translate('passwordSetupPolicy') : undefined}
              required
              disabled={status === 'pending'}
            />
          </FormRow>
          <Button
            type="submit"
            variant="primary"
            loading={status === 'pending'}
            loadingLabel={translate.translate('passwordUpdateLoading')}
          >
            {translate.translate('passwordSetupTitle')}
          </Button>
        </FormLayout>
      )}
      <Button
        onClick={() => {
          challenge.current = undefined;
          void navigate('/login', { replace: true });
        }}
      >
        {translate.translate('signIn')}
      </Button>
    </PageLayout>
  );
}
