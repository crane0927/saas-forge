import { Button as AntButton } from 'antd';
import { createTranslator, defineMessages } from '@saas-forge/i18n';
import type { MouseEventHandler, ReactNode } from 'react';

import enUS from './messages/foundation/en-US.json';
import zhCN from './messages/foundation/zh-CN.json';
import { useDesignSystemLocale } from './theme-provider';

export type DesignIconName =
  'check' | 'warning' | 'error' | 'empty' | 'search' | 'reload' | 'not-found';

export interface DesignIconProps {
  readonly name: DesignIconName;
  readonly label?: string;
  readonly size?: number;
}

export interface ButtonProps {
  readonly children: ReactNode;
  readonly onClick?: MouseEventHandler<HTMLButtonElement>;
  readonly variant?: 'primary' | 'secondary' | 'text' | 'danger';
  readonly disabled?: boolean;
  readonly loading?: boolean;
  readonly loadingLabel?: string;
  readonly type?: 'button' | 'submit' | 'reset';
}

export interface LinkProps {
  readonly children: ReactNode;
  readonly href: string;
  readonly external?: boolean;
}

export interface PageTitleProps {
  readonly children: ReactNode;
  readonly description?: ReactNode;
  readonly actions?: ReactNode;
  readonly headingId?: string;
}

export type PageLayoutWidth = 'standard' | 'wide';

export interface PageLayoutProps {
  readonly title: ReactNode;
  readonly children: ReactNode;
  readonly width?: PageLayoutWidth;
}

export interface ApplicationShellNavigationItem {
  readonly href: string;
  readonly label: string;
  readonly current?: boolean;
}

export interface ApplicationShellProps {
  readonly applicationName: string;
  readonly navigationLabel?: string;
  readonly navigationItems: readonly ApplicationShellNavigationItem[];
  readonly onNavigate: (href: string) => void;
  readonly actions?: ReactNode;
  readonly children: ReactNode;
}

export type ResponsiveGridIntent = 'content' | 'compact-statistics';

export interface ResponsiveGridProps {
  readonly children: ReactNode;
  readonly intent: ResponsiveGridIntent;
}

interface SplitLayoutContentProps {
  readonly primary: ReactNode;
  readonly auxiliary: ReactNode;
}

interface SplitLayoutLabelProps extends SplitLayoutContentProps {
  readonly auxiliaryLabel: string;
  readonly auxiliaryLabelledBy?: never;
}

interface SplitLayoutLabelledByProps extends SplitLayoutContentProps {
  readonly auxiliaryLabel?: never;
  readonly auxiliaryLabelledBy: string;
}

/** 辅助栏必须且只能通过直接名称或关联标题获得可访问名称。 */
export type SplitLayoutProps = SplitLayoutLabelProps | SplitLayoutLabelledByProps;

const foundationMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

function useFoundationTranslator() {
  return createTranslator({
    namespace: '@saas-forge/design-system/foundation',
    locale: useDesignSystemLocale(),
    messages: foundationMessages,
  });
}

const iconPaths: Record<DesignIconName, ReactNode> = {
  check: <path d="m6.5 12.5 3.5 3.5 7.5-8" />,
  warning: (
    <>
      <path d="M12 3 2.8 20h18.4L12 3Z" />
      <path d="M12 9v4.5M12 17h.01" />
    </>
  ),
  error: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m9 9 6 6m0-6-6 6" />
    </>
  ),
  empty: (
    <>
      <path d="M4 7.5 7 4h10l3 3.5V20H4V7.5Z" />
      <path d="M4 8h5l1.5 2h3L15 8h5" />
    </>
  ),
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m15.5 15.5 5 5" />
    </>
  ),
  reload: (
    <>
      <path d="M19 8a8 8 0 1 0 1 7" />
      <path d="M19 3v5h-5" />
    </>
  ),
  'not-found': (
    <>
      <path d="M5 3h10l4 4v14H5V3Z" />
      <path d="M15 3v5h4M9 13h6M9 17h4" />
    </>
  ),
};

export function DesignIcon({ name, label, size = 20 }: DesignIconProps) {
  return (
    <svg
      className="sf-icon"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label === undefined ? undefined : 'img'}
      aria-label={label}
      aria-hidden={label === undefined ? true : undefined}
    >
      {iconPaths[name]}
    </svg>
  );
}

export function Button({
  children,
  onClick,
  variant = 'secondary',
  disabled = false,
  loading = false,
  loadingLabel,
  type = 'button',
}: ButtonProps) {
  const translate = useFoundationTranslator();
  return (
    <AntButton
      type={
        variant === 'primary' || variant === 'danger'
          ? 'primary'
          : variant === 'text'
            ? 'text'
            : 'default'
      }
      danger={variant === 'danger'}
      disabled={disabled}
      loading={loading}
      htmlType={type}
      onClick={onClick}
      aria-label={loading ? (loadingLabel ?? translate.translate('buttonProcessing')) : undefined}
    >
      {children}
    </AntButton>
  );
}

export function Link({ children, href, external = false }: LinkProps) {
  return (
    <a
      className="sf-link"
      href={href}
      target={external ? '_blank' : undefined}
      rel={external ? 'noreferrer' : undefined}
    >
      {children}
    </a>
  );
}

export function PageTitle({ children, description, actions, headingId }: PageTitleProps) {
  return (
    <header className="sf-page-title">
      <div>
        <h1 id={headingId} tabIndex={-1}>
          {children}
        </h1>
        {description === undefined ? null : <p>{description}</p>}
      </div>
      {actions === undefined ? null : <div className="sf-page-title-actions">{actions}</div>}
    </header>
  );
}

export function PageLayout({ title, children, width = 'standard' }: PageLayoutProps) {
  return (
    <main
      className={width === 'wide' ? 'sf-page-layout sf-page-layout-wide' : 'sf-page-layout'}
      data-layout-width={width}
    >
      {title}
      <div className="sf-page-content">{children}</div>
    </main>
  );
}

export function ApplicationShell({
  applicationName,
  navigationLabel,
  navigationItems,
  onNavigate,
  actions,
  children,
}: ApplicationShellProps) {
  const translate = useFoundationTranslator();
  return (
    <div className="sf-application-shell">
      <header className="sf-application-header">
        <strong className="sf-application-name">{applicationName}</strong>
        <nav
          aria-label={
            navigationLabel ?? translate.translate('applicationNavigation', { applicationName })
          }
        >
          <ul className="sf-application-navigation">
            {navigationItems.map((item) => (
              <li key={item.href}>
                <a
                  className="sf-application-navigation-link"
                  href={item.href}
                  aria-current={item.current ? 'page' : undefined}
                  onClick={(event) => {
                    event.preventDefault();
                    onNavigate(item.href);
                  }}
                >
                  {item.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
        {actions === undefined ? null : <div className="sf-application-actions">{actions}</div>}
      </header>
      <main className="sf-application-content">{children}</main>
    </div>
  );
}

export function ResponsiveGrid({ children, intent }: ResponsiveGridProps) {
  return (
    <div className="sf-responsive-grid-container" data-layout-intent={intent}>
      <div className={`sf-responsive-grid sf-responsive-grid-${intent}`}>{children}</div>
    </div>
  );
}

export function SplitLayout({
  primary,
  auxiliary,
  auxiliaryLabel,
  auxiliaryLabelledBy,
}: SplitLayoutProps) {
  const hasLabel = auxiliaryLabel !== undefined && auxiliaryLabel.trim() !== '';
  const hasLabelledBy = auxiliaryLabelledBy !== undefined && auxiliaryLabelledBy.trim() !== '';

  if (hasLabel === hasLabelledBy) {
    throw new Error('SplitLayout 辅助栏必须且只能提供一种可访问名称。');
  }

  return (
    <div className="sf-split-layout-container">
      <div className="sf-split-layout">
        <div className="sf-split-layout-primary">{primary}</div>
        <aside
          className="sf-split-layout-auxiliary"
          aria-label={hasLabel ? auxiliaryLabel : undefined}
          aria-labelledby={hasLabelledBy ? auxiliaryLabelledBy : undefined}
        >
          {auxiliary}
        </aside>
      </div>
    </div>
  );
}
