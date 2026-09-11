import { Button as AntButton } from 'antd';
import { createTranslator } from '@saas-forge/i18n';
import {
  useId,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type MouseEventHandler,
  type ReactNode,
} from 'react';
import { useTopLayerEscape } from './overlay-behavior';

import { foundationMessages } from './messages/foundation';
import { useDesignSystemLocale } from './theme-provider';

export type DesignIconName =
  | 'check'
  | 'warning'
  | 'error'
  | 'empty'
  | 'search'
  | 'reload'
  | 'not-found'
  | 'menu'
  | 'home'
  | 'building'
  | 'key'
  | 'plus';

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
  /** 已在 ApplicationShell 的 main 内时使用 section，避免重复主地标。 */
  readonly as?: 'main' | 'section';
}

export interface ApplicationShellNavigationItem {
  readonly icon?: DesignIconName;
  readonly href: string;
  readonly label: string;
  readonly current?: boolean;
}

export interface ApplicationShellProps {
  readonly applicationName: string;
  readonly applicationLogoUrl?: string;
  readonly applicationLogoAlt?: string;
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

function useFoundationTranslator() {
  return createTranslator({
    namespace: '@saas-forge/design-system/foundation',
    locale: useDesignSystemLocale(),
    messages: foundationMessages,
  });
}

const iconPaths: Record<DesignIconName, ReactNode> = {
  menu: <path d="M4 5h16M4 12h16M4 19h16M8 5v14" />,
  home: <path d="m3 10 9-7 9 7M5 9v12h5v-7h4v7h5V9" />,
  building: <path d="M4 21V3h11v18M4 21h17V10h-6M8 7h3M8 11h3M8 15h3M18 14v1M18 18v1" />,
  key: (
    <path d="M14 3a7 7 0 0 0-6.4 9.8L3 17.4V21h3.6l1.5-1.5v-2h2l1.9-1.9A7 7 0 1 0 14 3ZM16 7h.01" />
  ),
  plus: <path d="M12 5v14M5 12h14" />,
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

export function PageLayout({
  title,
  children,
  width = 'standard',
  as: Element = 'main',
}: PageLayoutProps) {
  return (
    <Element
      data-embedded={Element === 'section' ? true : undefined}
      className={width === 'wide' ? 'sf-page-layout sf-page-layout-wide' : 'sf-page-layout'}
      data-layout-width={width}
    >
      {title}
      <div className="sf-page-content">{children}</div>
    </Element>
  );
}

const compactNavigationQuery = '(max-width: 45rem)';
function subscribeCompactNavigation(listener: () => void) {
  if (typeof window.matchMedia !== 'function') return () => undefined;
  const query = window.matchMedia(compactNavigationQuery);
  query.addEventListener('change', listener);
  return () => {
    query.removeEventListener('change', listener);
  };
}
function readCompactNavigation() {
  return (
    typeof window.matchMedia === 'function' && window.matchMedia(compactNavigationQuery).matches
  );
}

export function ApplicationShell({
  applicationName,
  applicationLogoUrl,
  applicationLogoAlt,
  navigationLabel,
  navigationItems,
  onNavigate,
  actions,
  children,
}: ApplicationShellProps) {
  const translate = useFoundationTranslator();
  const compact = useSyncExternalStore(
    subscribeCompactNavigation,
    readCompactNavigation,
    () => false,
  );
  const [collapsed, setCollapsed] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const navigationId = useId();
  const open = compact && drawerOpen;
  const close = () => {
    setDrawerOpen(false);
  };
  useTopLayerEscape(open, close);
  useLayoutEffect(() => {
    if (open) {
      dialog.current?.showModal();
      closeButton.current?.focus();
    } else if (dialog.current?.open) {
      dialog.current.close();
      trigger.current?.focus();
    }
  }, [open]);
  const label =
    navigationLabel ?? translate.translate('applicationNavigation', { applicationName });
  const navigation = (iconOnly: boolean) => (
    <nav aria-label={label}>
      <ul className="sf-application-navigation">
        {navigationItems.map((item) => (
          <li key={item.href}>
            <a
              className="sf-application-navigation-link"
              href={item.href}
              aria-current={item.current ? 'page' : undefined}
              title={iconOnly ? item.label : undefined}
              onClick={(event) => {
                event.preventDefault();
                if (open) close();
                onNavigate(item.href);
              }}
            >
              <DesignIcon name={item.icon ?? 'home'} size={18} />
              <span className={iconOnly ? 'sf-visually-hidden' : undefined}>{item.label}</span>
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
  const identity = (
    <ApplicationIdentity
      applicationName={applicationName}
      logoUrl={applicationLogoUrl}
      logoAlt={applicationLogoAlt}
    />
  );
  return (
    <div className="sf-application-shell" data-collapsed={collapsed}>
      <aside className="sf-application-sidebar" hidden={compact}>
        <div className="sf-application-brand" title={applicationName}>
          {compact ? null : identity}
        </div>
        <div id={compact ? undefined : navigationId}>{compact ? null : navigation(collapsed)}</div>
      </aside>
      <header className="sf-application-header">
        <button
          ref={trigger}
          type="button"
          className="sf-navigation-toggle"
          aria-label={translate.translate(
            compact ? 'navigationOpen' : collapsed ? 'navigationExpand' : 'navigationCollapse',
          )}
          aria-expanded={compact ? open : !collapsed}
          aria-controls={navigationId}
          onClick={() => {
            if (compact) setDrawerOpen(true);
            else setCollapsed((value) => !value);
          }}
        >
          <DesignIcon name="menu" size={18} />
        </button>
        {compact ? <div className="sf-application-compact-brand">{identity}</div> : null}
        <span className="sf-application-current">
          {navigationItems.find((item) => item.current)?.label}
        </span>
        {actions === undefined ? null : <div className="sf-application-actions">{actions}</div>}
      </header>
      <main className="sf-application-content">{children}</main>
      <dialog
        ref={dialog}
        className="sf-navigation-drawer"
        aria-label={label}
        onKeyDown={(event) => {
          if (event.key !== 'Tab') return;
          const controls = dialog.current?.querySelectorAll<HTMLElement>('button, a[href]');
          if (!controls?.length) return;
          const first = controls[0];
          const last = controls[controls.length - 1];
          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
          }
        }}
        onCancel={(event) => {
          event.preventDefault();
          close();
        }}
      >
        <button ref={closeButton} type="button" className="sf-navigation-close" onClick={close}>
          {translate.translate('navigationClose')}
        </button>
        <div id={compact ? navigationId : undefined}>{compact ? navigation(false) : null}</div>
      </dialog>
    </div>
  );
}

export function ContentPanel({
  title,
  description,
  actions,
  children,
}: {
  readonly title?: string;
  readonly description?: ReactNode;
  readonly actions?: ReactNode;
  readonly children?: ReactNode;
}) {
  const headingId = useId();
  return (
    <section
      className="sf-content-panel"
      aria-labelledby={title === undefined ? undefined : headingId}
    >
      {title === undefined && description === undefined && actions === undefined ? null : (
        <header className="sf-content-panel-header">
          <div>
            {title === undefined ? null : <h2 id={headingId}>{title}</h2>}
            {description === undefined ? null : <p>{description}</p>}
          </div>
          {actions}
        </header>
      )}
      {children === undefined ? null : <div className="sf-content-panel-body">{children}</div>}
    </section>
  );
}

export function StatusTag({
  children,
  tone,
}: {
  readonly children: ReactNode;
  readonly tone: 'success' | 'warning' | 'danger' | 'neutral';
}) {
  return <span className={`sf-status-tag sf-status-tag-${tone}`}>{children}</span>;
}

export function DescriptionList({
  items,
}: {
  readonly items: readonly { readonly label: string; readonly value: ReactNode }[];
}) {
  return (
    <dl className="sf-description-list">
      {items.map((item) => (
        <div key={item.label}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function ApplicationIdentity({
  applicationName,
  logoUrl,
  logoAlt,
}: {
  readonly applicationName: string;
  readonly logoUrl?: string;
  readonly logoAlt?: string;
}) {
  return (
    <span className="sf-application-identity">
      {logoUrl === undefined ? null : (
        <img className="sf-application-logo" src={logoUrl} alt={logoAlt ?? ''} />
      )}
      <strong className="sf-application-name">{applicationName}</strong>
    </span>
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
