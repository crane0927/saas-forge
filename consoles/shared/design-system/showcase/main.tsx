import {
  ActionMenu,
  Button,
  CheckboxField,
  DesignSystemProvider,
  EmptyDataState,
  FilteredEmptyState,
  FormErrorSummary,
  FormLayout,
  FormRow,
  InitialContentLoading,
  IrreversibleDangerDialog,
  Link,
  LoadFailureState,
  NotFoundState,
  PageLayout,
  PageTitle,
  PasswordField,
  PersistentError,
  RecoverableDangerDialog,
  RefreshingContent,
  ResponsiveGrid,
  SelectField,
  ServerTable,
  SplitLayout,
  StandardDialog,
  SuccessFeedback,
  TextField,
  UnsavedChangesDialog,
  useFormProblemFocus,
  useUnsavedChangesGuard,
  WarningFeedback,
  type FormErrorItem,
  type DesignSystemColorScheme,
  type DesignSystemLocale,
  type PageLayoutWidth,
  type ServerTableRequest,
  type ServerTableSort,
  type TenantBrandProfile,
} from '@saas-forge/design-system';
import { useEffect, useRef, useState } from 'react';

import './showcase.css';

const initialTenants = ['北辰科技', '云帆数据'];
const tenantTableRows = [
  { id: 'tenant-1', name: '北辰科技', owner: '林知远', status: '启用', createdAt: '2026-08-12' },
  { id: 'tenant-2', name: '云帆数据', owner: '周清禾', status: '启用', createdAt: '2026-08-10' },
  { id: 'tenant-3', name: '星河制造', owner: '沈安', status: '停用', createdAt: '2026-08-08' },
  { id: 'tenant-4', name: '海岳零售', owner: '陈予', status: '启用', createdAt: '2026-08-06' },
  { id: 'tenant-5', name: '青岚物流', owner: '方晨', status: '启用', createdAt: '2026-08-04' },
  { id: 'tenant-6', name: '明川教育', owner: '顾言', status: '停用', createdAt: '2026-08-02' },
  { id: 'tenant-7', name: '栖云健康', owner: '陆宁', status: '启用', createdAt: '2026-08-01' },
] as const;

type TenantTableRow = (typeof tenantTableRows)[number];
type PreviewState =
  'default' | 'loading' | 'disabled' | 'success' | 'error' | 'empty' | 'filtered' | 'not-found';

interface ProfileFormValues {
  readonly displayName: string;
  readonly account: string;
  readonly password: string;
  readonly role: string;
  readonly notifications: boolean;
}

type ProfileField = keyof ProfileFormValues;
type ProfileErrors = Partial<Record<ProfileField, string>>;

const showcaseTenantBrand: TenantBrandProfile = {
  displayName: 'Northstar Labs',
  logoUrl: '/tenant-assets/northstar.svg',
  faviconUrl: 'https://assets.example.test/northstar.ico',
  primaryColor: '#7C3AED',
  accentColor: '#C026D3',
};

const emptyProfile: ProfileFormValues = {
  displayName: '',
  account: '',
  password: '',
  role: '',
  notifications: false,
};

const demoProfile: ProfileFormValues = {
  displayName: '北辰科技管理员',
  account: 'admin@example.test',
  password: 'DemoPass8',
  role: 'administrator',
  notifications: true,
};

const profileFieldOrder: readonly ProfileField[] = [
  'displayName',
  'account',
  'password',
  'role',
  'notifications',
];

const profileFieldIds: Record<ProfileField, string> = {
  displayName: 'profile-display-name',
  account: 'profile-account',
  password: 'profile-password',
  role: 'profile-role',
  notifications: 'profile-notifications',
};

function validateProfileField(field: ProfileField, values: ProfileFormValues): string | undefined {
  switch (field) {
    case 'displayName':
      return values.displayName.trim() === '' ? '请输入显示名称。' : undefined;
    case 'account':
      return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(values.account)
        ? undefined
        : '请输入有效的邮箱地址。';
    case 'password':
      return values.password.length >= 8 ? undefined : '密码至少需要 8 个字符。';
    case 'role':
      return values.role === '' ? '请选择成员角色。' : undefined;
    case 'notifications':
      return undefined;
  }
}

function validateProfile(values: ProfileFormValues): ProfileErrors {
  const errors: ProfileErrors = {};
  for (const field of profileFieldOrder) {
    const error = validateProfileField(field, values);
    if (error !== undefined) {
      errors[field] = error;
    }
  }
  return errors;
}

function FormShowcase({ onResult }: { readonly onResult: (result: string) => void }) {
  const [values, setValues] = useState<ProfileFormValues>(emptyProfile);
  const [savedValues, setSavedValues] = useState<ProfileFormValues>(emptyProfile);
  const editedFields = useRef(new Set<ProfileField>());
  const [errors, setErrors] = useState<ProfileErrors>({});
  const [serviceError, setServiceError] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [saveAttempts, setSaveAttempts] = useState(0);
  const [successSequence, setSuccessSequence] = useState(0);
  const [visible, setVisible] = useState(true);
  const { summaryRef, focusFirstProblem } = useFormProblemFocus();
  const dirty = JSON.stringify(values) !== JSON.stringify(savedValues);
  const guard = useUnsavedChangesGuard(dirty);

  const updateValue = <Field extends ProfileField>(
    field: Field,
    value: ProfileFormValues[Field],
  ) => {
    setValues((current) => ({ ...current, [field]: value }));
    editedFields.current.add(field);
    setServiceError(undefined);
  };

  const validateEditedField = (
    field: ProfileField,
    fieldValue: ProfileFormValues[ProfileField],
  ) => {
    if (!editedFields.current.has(field)) {
      return;
    }
    const error = validateProfileField(field, { ...values, [field]: fieldValue });
    setErrors((current) => ({ ...current, [field]: error }));
  };

  const resetForm = () => {
    setValues(savedValues);
    editedFields.current.clear();
    setErrors({});
    setServiceError(undefined);
  };

  const requestLeave = (destination: string, close = false) => {
    guard.requestDiscard(() => {
      resetForm();
      setVisible(!close);
      onResult(`已放弃修改并${destination}。`);
    });
  };

  if (!visible) {
    return (
      <div className="sf-showcase-preview">
        <p>共享表单已关闭。</p>
        <Button
          variant="primary"
          onClick={() => {
            setVisible(true);
            onResult('已重新打开共享表单。');
          }}
        >
          重新打开表单
        </Button>
      </div>
    );
  }

  const summaryErrors: FormErrorItem[] =
    serviceError === undefined
      ? profileFieldOrder.flatMap((field) => {
          const error = errors[field];
          return error === undefined ? [] : [{ fieldId: profileFieldIds[field], message: error }];
        })
      : [{ message: serviceError }];

  return (
    <div className="sf-showcase-form-preview">
      <p className="sf-showcase-form-note">
        字段离开后校验；首次有效保存模拟依赖服务失败，保留内容后再次保存成功。
      </p>
      <FormLayout
        ariaLabel="成员资料完整表单"
        onSubmit={(event) => {
          event.preventDefault();
          if (submitting) {
            return;
          }
          const nextErrors = validateProfile(values);
          setErrors(nextErrors);
          setServiceError(undefined);
          const firstInvalidField = profileFieldOrder.find(
            (field) => nextErrors[field] !== undefined,
          );
          if (firstInvalidField !== undefined) {
            focusFirstProblem(profileFieldIds[firstInvalidField]);
            return;
          }

          setSubmitting(true);
          const nextAttempt = saveAttempts + 1;
          setSaveAttempts(nextAttempt);
          window.setTimeout(() => {
            setSubmitting(false);
            if (nextAttempt === 1) {
              setServiceError('成员服务暂时不可用，输入内容已保留，请重试。');
              focusFirstProblem();
              return;
            }
            setSavedValues(values);
            setSuccessSequence((current) => current + 1);
            onResult('成员资料已保存。');
          }, 600);
        }}
      >
        <FormErrorSummary ref={summaryRef} errors={summaryErrors} />
        <FormRow>
          <TextField
            id={profileFieldIds.displayName}
            label="显示名称"
            value={values.displayName}
            required
            autoComplete="name"
            error={errors.displayName}
            onValueChange={(value) => {
              updateValue('displayName', value);
            }}
            onBlur={(value) => {
              validateEditedField('displayName', value);
            }}
          />
          <TextField
            id={profileFieldIds.account}
            label="登录邮箱"
            value={values.account}
            required
            autoComplete="email"
            error={errors.account}
            onValueChange={(value) => {
              updateValue('account', value);
            }}
            onBlur={(value) => {
              validateEditedField('account', value);
            }}
          />
        </FormRow>
        <PasswordField
          id={profileFieldIds.password}
          label="初始密码"
          value={values.password}
          required
          autoComplete="new-password"
          error={errors.password}
          onValueChange={(value) => {
            updateValue('password', value);
          }}
          onBlur={(value) => {
            validateEditedField('password', value);
          }}
        />
        <SelectField
          id={profileFieldIds.role}
          label="成员角色"
          value={values.role === '' ? undefined : values.role}
          required
          placeholder="请选择角色"
          error={errors.role}
          options={[
            { value: 'administrator', label: '管理员' },
            { value: 'auditor', label: '审计员' },
          ]}
          onValueChange={(value) => {
            updateValue('role', value);
          }}
          onBlur={(value) => {
            validateEditedField('role', value);
          }}
        />
        <CheckboxField
          id={profileFieldIds.notifications}
          label="接收成员状态更新"
          checked={values.notifications}
          onCheckedChange={(checked) => {
            updateValue('notifications', checked);
          }}
        />
        <div className="sf-showcase-form-actions">
          <Button
            onClick={() => {
              setValues(demoProfile);
              editedFields.current = new Set(profileFieldOrder);
              setErrors({});
              setServiceError(undefined);
            }}
          >
            填入演示数据
          </Button>
          <Button
            type="submit"
            variant="primary"
            loading={submitting}
            loadingLabel="正在保存成员资料"
          >
            保存成员
          </Button>
          <Button
            onClick={() => {
              requestLeave('关闭表单', true);
            }}
          >
            关闭表单
          </Button>
          <Button
            variant="text"
            onClick={() => {
              requestLeave('返回上一页');
            }}
          >
            返回上一页
          </Button>
          <Button
            variant="text"
            onClick={() => {
              requestLeave('切换到成员列表');
            }}
          >
            切换到成员列表
          </Button>
        </div>
        {successSequence === 0 ? null : (
          <SuccessFeedback key={successSequence} message="成员资料已保存。" />
        )}
      </FormLayout>
      <UnsavedChangesDialog
        open={guard.confirmationOpen}
        onContinueEditing={guard.continueEditing}
        onDiscard={guard.discardChanges}
      />
    </div>
  );
}

function TableShowcase({ onResult }: { readonly onResult: (result: string) => void }) {
  const [draftName, setDraftName] = useState('');
  const [appliedName, setAppliedName] = useState('');
  const [page, setPage] = useState(1);
  const [sort, setSort] = useState<ServerTableSort>();
  const [refreshing, setRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [disabledIds, setDisabledIds] = useState<readonly string[]>([]);
  const [dangerTarget, setDangerTarget] = useState<TenantTableRow>();
  const requestTimer = useRef<number | undefined>(undefined);
  const pendingRequest = useRef({
    name: '',
    request: { page: 1, pageSize: 3 } satisfies ServerTableRequest,
  });

  useEffect(
    () => () => {
      window.clearTimeout(requestTimer.current);
    },
    [],
  );

  const simulateRequest = (name: string, request: ServerTableRequest, fail = false) => {
    window.clearTimeout(requestTimer.current);
    pendingRequest.current = { name, request };
    setRefreshing(true);
    setLoadError(undefined);
    requestTimer.current = window.setTimeout(() => {
      setRefreshing(false);
      if (fail) {
        setLoadError('模拟租户服务暂时不可用；当前查询条件已保留。');
        return;
      }
      setAppliedName(name);
      setPage(request.page);
      setSort(request.sort);
      onResult(`服务端模拟已返回第 ${String(request.page)} 页。`);
    }, 350);
  };

  const matchingRows = tenantTableRows.filter((row) => row.name.includes(appliedName.trim()));
  const sortedRows = [...matchingRows].sort((left, right) => {
    if (sort === undefined) {
      return 0;
    }
    const comparison = left[sort.field === 'createdAt' ? 'createdAt' : 'name'].localeCompare(
      right[sort.field === 'createdAt' ? 'createdAt' : 'name'],
      'zh-CN',
    );
    return sort.direction === 'asc' ? comparison : -comparison;
  });
  const currentRows = sortedRows.slice((page - 1) * 3, page * 3);

  return (
    <div className="sf-showcase-table-preview">
      <div className="sf-showcase-table-tools">
        <Button
          onClick={() => {
            simulateRequest(appliedName, { page, pageSize: 3, sort }, true);
          }}
        >
          模拟加载失败
        </Button>
      </div>
      <ServerTable
        ariaLabel="租户服务端列表"
        query={
          <TextField
            id="table-tenant-name"
            label="租户名称"
            value={draftName}
            placeholder="输入名称后查询"
            onValueChange={setDraftName}
          />
        }
        onQuery={() => {
          const nextName = draftName.trim();
          simulateRequest(nextName, { page: 1, pageSize: 3, sort });
        }}
        onReset={() => {
          setDraftName('');
          simulateRequest('', { page: 1, pageSize: 3 });
        }}
        rows={currentRows}
        rowKey={(row) => row.id}
        columns={[
          {
            key: 'name',
            title: '租户名称',
            render: (row) => row.name,
            sortable: true,
            fixed: 'left',
            width: 180,
          },
          { key: 'owner', title: '管理员', render: (row) => row.owner, width: 140 },
          {
            key: 'status',
            title: '状态',
            render: (row) => (disabledIds.includes(row.id) ? '停用' : row.status),
            width: 100,
          },
          {
            key: 'createdAt',
            title: '创建日期',
            render: (row) => row.createdAt,
            sortable: true,
            width: 140,
          },
        ]}
        actions={[
          {
            key: 'view',
            label: '查看',
            onAction: (row) => {
              onResult(`正在查看 ${row.name}。`);
            },
          },
          {
            key: 'edit',
            label: '编辑',
            onAction: (row) => {
              onResult(`正在编辑 ${row.name}。`);
            },
          },
          {
            key: 'copy',
            label: '复制名称',
            onAction: (row) => {
              onResult(`已复制 ${row.name} 的名称。`);
            },
          },
          {
            key: 'disable',
            label: '停用',
            danger: true,
            disabled: (row) => disabledIds.includes(row.id) || row.status === '停用',
            onAction: setDangerTarget,
          },
        ]}
        page={page}
        pageSize={3}
        total={matchingRows.length}
        sort={sort}
        filtered={appliedName !== ''}
        refreshing={refreshing}
        loadError={loadError}
        onRetry={() => {
          simulateRequest(pendingRequest.current.name, pendingRequest.current.request);
        }}
        onTableChange={(request) => {
          simulateRequest(appliedName, request);
        }}
        selectionLabel={(row) => `选择 ${row.name}`}
        onSelectionChange={(selectedRows) => {
          if (selectedRows.length > 0) {
            onResult(`当前页已选择 ${String(selectedRows.length)} 个租户。`);
          }
        }}
      />
      <RecoverableDangerDialog
        open={dangerTarget !== undefined}
        title="停用租户"
        objectName={dangerTarget?.name ?? ''}
        consequence="停用后成员暂时无法登录，管理员可以随时恢复。"
        actionLabel="停用租户"
        onCancel={() => {
          setDangerTarget(undefined);
        }}
        onConfirm={() => {
          if (dangerTarget !== undefined) {
            setDisabledIds((current) => [...current, dangerTarget.id]);
            onResult(`${dangerTarget.name} 已停用。`);
          }
          setDangerTarget(undefined);
        }}
      />
    </div>
  );
}

export function ThemeLocaleMatrix() {
  const variants: readonly {
    scheme: DesignSystemColorScheme;
    locale: DesignSystemLocale;
    brand: 'platform' | 'tenant';
  }[] = [
    { scheme: 'light', locale: 'zh-CN', brand: 'platform' },
    { scheme: 'dark', locale: 'zh-CN', brand: 'platform' },
    { scheme: 'light', locale: 'en-US', brand: 'tenant' },
    { scheme: 'dark', locale: 'en-US', brand: 'tenant' },
  ];

  return (
    <div className="sf-showcase-theme-matrix" data-testid="theme-locale-matrix">
      {variants.map(({ scheme, locale, brand }) => {
        const english = locale === 'en-US';
        return (
          <div className="sf-showcase-theme-sample" key={`${scheme}-${locale}-${brand}`}>
            <DesignSystemProvider
              forcedColorScheme={scheme}
              locale={locale}
              tenantBrand={brand === 'tenant' ? showcaseTenantBrand : undefined}
            >
              <article aria-label={`${locale} ${scheme} ${brand}`}>
                <p className="sf-showcase-theme-label">
                  {locale} · {scheme} · {brand}
                </p>
                <h3>{english ? 'Member overview' : '成员概览'}</h3>
                <p>
                  {english
                    ? 'Stable controls and semantic states share one token system.'
                    : '稳定控件与语义状态共享同一套 Token。'}
                </p>
                <div className="sf-showcase-controls">
                  <Button variant="primary">{english ? 'Primary action' : '主要操作'}</Button>
                  <Button>{english ? 'Secondary action' : '次要操作'}</Button>
                  <Button disabled>{english ? 'Disabled' : '禁用'}</Button>
                  <Button loading loadingLabel={english ? 'Saving' : '正在保存'}>
                    {english ? 'Save' : '保存'}
                  </Button>
                </div>
                <TextField
                  id={`${scheme}-${locale}-${brand}-query`}
                  label={english ? 'Search members' : '查询成员'}
                  value=""
                  placeholder={english ? 'Press Enter to search' : '按 Enter 查询'}
                  onValueChange={() => undefined}
                />
                <PersistentError title={english ? 'Save failed' : '保存失败'}>
                  {english ? 'Review the highlighted fields and retry.' : '请检查标记字段后重试。'}
                </PersistentError>
                <EmptyDataState
                  title={english ? 'No members' : '暂无成员'}
                  description={
                    english ? 'Create the first member to begin.' : '创建首位成员后开始使用。'
                  }
                />
              </article>
            </DesignSystemProvider>
          </div>
        );
      })}
    </div>
  );
}

const contentExamples = [
  ['成员目录', '查看成员身份、状态与最近活动。'],
  ['访问策略', '集中检查角色与权限分配。'],
  ['安全事件', '跟进需要处理的风险与告警。'],
] as const;

const statisticExamples = [
  ['活跃租户', '28'],
  ['待处理邀请', '6'],
  ['本周告警', '3'],
  ['可用席位', '124'],
] as const;

function LayoutGridExamples() {
  return (
    <div className="sf-showcase-layout-examples">
      <section aria-labelledby="responsive-content-title">
        <h3 id="responsive-content-title">普通内容</h3>
        <p>单项建议至少 18rem，组件空间允许时最多排列三列。</p>
        <ResponsiveGrid intent="content">
          {contentExamples.map(([title, description], index) => (
            <article
              className="sf-showcase-layout-card"
              data-testid={`content-grid-item-${String(index + 1)}`}
              key={title}
            >
              <h4>{title}</h4>
              <p>{description}</p>
              <Button>查看{title}</Button>
            </article>
          ))}
        </ResponsiveGrid>
      </section>
      <section aria-labelledby="responsive-statistics-title">
        <h3 id="responsive-statistics-title">紧凑统计</h3>
        <p>单项建议至少 12rem，组件空间允许时最多排列四列。</p>
        <ResponsiveGrid intent="compact-statistics">
          {statisticExamples.map(([label, value], index) => (
            <dl
              className="sf-showcase-statistic"
              data-testid={`statistics-grid-item-${String(index + 1)}`}
              key={label}
            >
              <dt>{label}</dt>
              <dd>{value}</dd>
            </dl>
          ))}
        </ResponsiveGrid>
      </section>
    </div>
  );
}

export function LayoutShowcase({ width }: { readonly width: PageLayoutWidth }) {
  return (
    <PageLayout
      width={width}
      title={
        <PageTitle description="比较页面宽度与按组件实际空间自动换列的内容栅格。">
          {width === 'standard' ? '标准宽度页面' : '全宽管理页面'}
        </PageTitle>
      }
    >
      <LayoutGridExamples />
    </PageLayout>
  );
}

export function SplitLayoutShowcase() {
  return (
    <PageLayout
      width="wide"
      title={
        <PageTitle description="主任务始终先于辅助信息，组件空间不足时按同一顺序纵向排列。">
          语义化主辅分栏
        </PageTitle>
      }
    >
      <SplitLayout
        primary={
          <section className="sf-showcase-split-primary" aria-labelledby="member-task-title">
            <h2 id="member-task-title">编辑成员资料</h2>
            <p>完成成员身份、角色和联系信息维护。</p>
            <TextField
              id="split-member-name"
              label="成员名称"
              value="林知远"
              onValueChange={() => undefined}
            />
            <Button variant="primary">保存成员资料</Button>
          </section>
        }
        auxiliary={
          <section className="sf-showcase-split-auxiliary">
            <h2 id="member-help-title">操作提示</h2>
            <p>角色变更会在成员下次刷新会话后生效。</p>
            <Button>查看角色说明</Button>
          </section>
        }
        auxiliaryLabelledBy="member-help-title"
      />
    </PageLayout>
  );
}

export function DesignSystemShowcase() {
  const [result, setResult] = useState('请选择一个场景进行操作。');
  const [pageWidth, setPageWidth] = useState<PageLayoutWidth>('standard');
  const [standardOpen, setStandardOpen] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [profile, setProfile] = useState('平台管理员');
  const [recoverableOpen, setRecoverableOpen] = useState(false);
  const [tenants, setTenants] = useState(initialTenants);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [previewState, setPreviewState] = useState<PreviewState>('default');
  const [successSequence, setSuccessSequence] = useState(0);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());
  const tableHeadingRef = useRef<HTMLHeadingElement>(null);
  const emptyStateRef = useRef<HTMLParagraphElement>(null);
  const targetIndex = deleteTarget === null ? -1 : tenants.indexOf(deleteTarget);
  const nextTenant = targetIndex < 0 ? undefined : tenants[targetIndex + 1];

  return (
    <PageLayout
      width={pageWidth}
      title={
        <div className="sf-showcase-header">
          <p className="sf-showcase-eyebrow">PRIVATE COMPONENT SHOWCASE</p>
          <PageTitle description="验证页面结构、状态反馈、恢复动作、加载范围与共享浮层行为。">
            SaaS Forge Design System
          </PageTitle>
          <ActionMenu
            label="展示菜单"
            items={[
              { key: 'preview', label: '预览公共浮层' },
              { key: 'remove', label: '危险菜单项', danger: true, separatorBefore: true },
            ]}
            onAction={(key) => {
              setResult(key === 'preview' ? '已从菜单选择预览。' : '已选择危险菜单项，尚未执行。');
            }}
          />
        </div>
      }
    >
      <div className="sf-showcase">
        <p className="sf-showcase-result" role="status">
          {result}
        </p>

        <section className="sf-showcase-section" aria-labelledby="layout-preview-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="layout-preview-title">页面宽度与响应式内容栅格</h2>
              <p>切换真实 PageLayout，并比较普通内容与紧凑统计在当前可用空间中的排列。</p>
            </div>
            <div className="sf-showcase-controls" aria-label="选择页面宽度">
              <Button
                variant={pageWidth === 'standard' ? 'primary' : 'secondary'}
                onClick={() => {
                  setPageWidth('standard');
                }}
              >
                标准宽度
              </Button>
              <Button
                variant={pageWidth === 'wide' ? 'primary' : 'secondary'}
                onClick={() => {
                  setPageWidth('wide');
                }}
              >
                全宽页面
              </Button>
            </div>
          </div>
          <p className="sf-showcase-width-status" role="status">
            当前页面：{pageWidth === 'standard' ? '标准宽度（最大 76rem）' : '全宽管理页面'}
          </p>
          <LayoutGridExamples />
        </section>

        <section className="sf-showcase-section" aria-labelledby="split-layout-preview-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="split-layout-preview-title">语义化主辅分栏</h2>
              <p>桌面右侧显示辅助信息，组件空间不足时按主内容、辅助内容的顺序堆叠。</p>
            </div>
          </div>
          <SplitLayout
            primary={<div className="sf-showcase-split-primary">主任务内容</div>}
            auxiliary={<div className="sf-showcase-split-auxiliary">辅助说明内容</div>}
            auxiliaryLabel="当前页面辅助说明"
          />
        </section>

        <section className="sf-showcase-section" aria-labelledby="theme-matrix-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="theme-matrix-title">主题、品牌与双语矩阵</h2>
              <p>固定呈现平台与 Tenant 品牌的中英文、浅色、深色和公共状态。</p>
            </div>
          </div>
          <ThemeLocaleMatrix />
          <p className="sf-showcase-motion-note" role="status">
            减少动画模式由操作系统偏好驱动，展示册浏览器测试会固定验证动画时长。
          </p>
        </section>

        <section className="sf-showcase-section" aria-labelledby="table-preview-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="table-preview-title">共享服务端表格完整流程</h2>
              <p>查询仅在提交时应用，并统一演示分页、单列排序、当前页选择和危险操作。</p>
            </div>
          </div>
          <TableShowcase onResult={setResult} />
        </section>

        <section className="sf-showcase-section" aria-labelledby="form-preview-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="form-preview-title">共享表单完整流程</h2>
              <p>实际填写并验证离开校验、提交聚焦、失败保留、放弃保护与防重复提交。</p>
            </div>
          </div>
          <FormShowcase onResult={setResult} />
        </section>

        <section className="sf-showcase-section" aria-labelledby="state-preview-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="state-preview-title">页面状态与反馈</h2>
              <p>公共入口提供可区分的说明、作用范围和恢复动作。</p>
            </div>
            <Link href="#overlay-preview-title">查看浮层能力</Link>
          </div>
          <div className="sf-showcase-controls" aria-label="选择展示状态">
            {(
              [
                ['default', '默认'],
                ['loading', '加载'],
                ['disabled', '禁用'],
                ['success', '成功'],
                ['error', '错误'],
                ['empty', '无数据'],
                ['filtered', '筛选无结果'],
                ['not-found', '404'],
              ] as const
            ).map(([state, label]) => (
              <Button
                key={state}
                variant={previewState === state ? 'primary' : 'secondary'}
                onClick={() => {
                  setPreviewState(state);
                  if (state === 'success') {
                    setSuccessSequence((current) => current + 1);
                  }
                }}
              >
                {label}
              </Button>
            ))}
          </div>
          <div className="sf-showcase-preview">
            {previewState === 'loading' ? (
              <>
                <InitialContentLoading />
                <RefreshingContent refreshing>
                  <p>现有成员列表在局部刷新期间保持可见。</p>
                </RefreshingContent>
                <Button loading loadingLabel="正在保存成员">
                  保存成员
                </Button>
              </>
            ) : null}
            {previewState === 'disabled' ? <Button disabled>不可用操作</Button> : null}
            {previewState === 'success' ? (
              <SuccessFeedback key={successSequence} message="成员已保存，提示将自动消失。" />
            ) : null}
            {previewState === 'error' ? (
              <PersistentError
                title="成员加载失败"
                action={{
                  label: '重试',
                  onAction: () => {
                    setResult('已保留筛选条件并重试。');
                    setPreviewState('default');
                  },
                }}
                onClose={() => {
                  setPreviewState('default');
                }}
              >
                请检查网络后重试；错误会持续显示，直到恢复或关闭。
              </PersistentError>
            ) : null}
            {previewState === 'empty' ? (
              <EmptyDataState
                description="创建第一个成员后将在这里显示。"
                action={{
                  label: '新增成员',
                  onAction: () => {
                    setResult('已选择新增成员。');
                  },
                }}
              />
            ) : null}
            {previewState === 'filtered' ? (
              <FilteredEmptyState
                onReset={() => {
                  setResult('已重置筛选条件。');
                  setPreviewState('default');
                }}
              />
            ) : null}
            {previewState === 'not-found' ? (
              <NotFoundState
                returnLabel="返回默认状态"
                onReturn={() => {
                  setPreviewState('default');
                }}
              />
            ) : null}
            {previewState === 'default' ? (
              <>
                <WarningFeedback title="配额即将用尽">剩余 5 个成员席位。</WarningFeedback>
                <p>默认内容可继续操作，页面未被全屏加载层阻断。</p>
                <Button
                  variant="primary"
                  onClick={() => {
                    setPreviewState('success');
                  }}
                >
                  保存并显示成功反馈
                </Button>
                <LoadFailureState
                  description="此处单独演示加载失败；重试不会清空当前筛选条件。"
                  onRetry={() => {
                    setResult('已保留筛选条件并重试。');
                  }}
                />
              </>
            ) : null}
          </div>
        </section>

        <section className="sf-showcase-section" aria-labelledby="overlay-preview-title">
          <div className="sf-showcase-section-heading">
            <div>
              <h2 id="overlay-preview-title">共享浮层与危险操作确认</h2>
              <p>验证普通关闭、未保存保护和两级危险确认的键盘与焦点行为。</p>
            </div>
          </div>
          <div className="sf-showcase-grid">
            <section className="sf-showcase-card" aria-labelledby="standard-title">
              <h2 id="standard-title">普通弹窗</h2>
              <p>关闭后焦点返回原触发按钮。</p>
              <button
                type="button"
                onClick={() => {
                  setStandardOpen(true);
                }}
              >
                打开普通弹窗
              </button>
            </section>

            <section className="sf-showcase-card" aria-labelledby="unsaved-title">
              <h2 id="unsaved-title">未保存保护</h2>
              <p>存在修改时，关闭动作只打开最上层放弃确认。</p>
              <button
                type="button"
                onClick={() => {
                  setEditorOpen(true);
                }}
              >
                编辑成员资料
              </button>
            </section>

            <section className="sf-showcase-card" aria-labelledby="recoverable-title">
              <h2 id="recoverable-title">可恢复危险操作</h2>
              <p>明确显示对象、后果和安全默认操作。</p>
              <button
                type="button"
                onClick={() => {
                  setRecoverableOpen(true);
                }}
              >
                停用北辰科技
              </button>
            </section>

            <section className="sf-showcase-card" aria-labelledby="irreversible-title">
              <h2 id="irreversible-title" ref={tableHeadingRef} tabIndex={-1}>
                不可恢复危险操作
              </h2>
              <p>只有精确输入对象名称后，永久删除才可执行。</p>
              <div className="sf-showcase-rows">
                {tenants.length === 0 ? (
                  <p ref={emptyStateRef} tabIndex={-1}>
                    暂无租户
                  </p>
                ) : (
                  tenants.map((tenant) => (
                    <div className="sf-showcase-row" key={tenant}>
                      <span>{tenant}</span>
                      <button
                        type="button"
                        ref={(element) => {
                          if (element === null) {
                            rowButtons.current.delete(tenant);
                          } else {
                            rowButtons.current.set(tenant, element);
                          }
                        }}
                        onClick={() => {
                          setDeleteTarget(tenant);
                        }}
                      >
                        永久删除 {tenant}
                      </button>
                    </div>
                  ))
                )}
              </div>
            </section>
          </div>
        </section>

        <StandardDialog
          open={standardOpen}
          title="成员详情"
          onClose={() => {
            setStandardOpen(false);
          }}
        >
          <p>成员状态正常，最近一次登录来自受信任设备。</p>
        </StandardDialog>

        <StandardDialog
          open={editorOpen}
          title="编辑成员资料"
          onClose={() => {
            if (profile === '平台管理员') {
              setEditorOpen(false);
            } else {
              setLeaveOpen(true);
            }
          }}
          closeLabel="尝试关闭"
        >
          <label className="sf-showcase-field">
            <span>显示名称</span>
            <input
              value={profile}
              onChange={(event) => {
                setProfile(event.target.value);
              }}
            />
          </label>
          <label className="sf-showcase-field">
            <span>备注</span>
            <textarea defaultValue="Enter 只输入换行。" rows={3} />
          </label>
        </StandardDialog>

        <UnsavedChangesDialog
          open={leaveOpen}
          onContinueEditing={() => {
            setLeaveOpen(false);
          }}
          onDiscard={() => {
            setProfile('平台管理员');
            setLeaveOpen(false);
            setEditorOpen(false);
            setResult('已放弃未保存的修改。');
          }}
        />

        <RecoverableDangerDialog
          open={recoverableOpen}
          title="停用租户"
          objectName="北辰科技"
          consequence="停用后成员暂时无法登录，管理员可以随时恢复。"
          actionLabel="停用租户"
          onCancel={() => {
            setRecoverableOpen(false);
          }}
          onConfirm={() => {
            setRecoverableOpen(false);
            setResult('北辰科技已停用，可以恢复。');
          }}
        />

        <IrreversibleDangerDialog
          open={deleteTarget !== null}
          title="永久删除租户"
          objectName={deleteTarget ?? ''}
          consequence="租户及其全部配置将永久删除，此操作不可恢复。"
          actionLabel="永久删除"
          onCancel={() => {
            setDeleteTarget(null);
          }}
          onConfirm={() => {
            if (deleteTarget !== null) {
              setTenants((current) => current.filter((tenant) => tenant !== deleteTarget));
              setResult(`${deleteTarget} 已永久删除。`);
            }
            setDeleteTarget(null);
          }}
          removedObjectFocus={{
            nextRow: () =>
              nextTenant === undefined ? null : (rowButtons.current.get(nextTenant) ?? null),
            tableHeading: () => tableHeadingRef.current,
            emptyState: () => emptyStateRef.current,
          }}
        />
      </div>
    </PageLayout>
  );
}
