import {
  Alert,
  App,
  Button,
  Checkbox,
  ConfigProvider,
  Dropdown,
  Form,
  Input,
  Modal,
  Select,
  Table,
  Tag,
  theme,
  type TableProps,
} from 'antd';
import { useMemo, useState, useSyncExternalStore } from 'react';

import { PANEL_LABELS, TENANTS, type PrototypePanel, type TenantRow } from './model';

interface VariantProps {
  readonly panel: PrototypePanel;
  readonly onPanelChange: (panel: PrototypePanel) => void;
}

export function AntDesignVariant({ panel, onPanelChange }: VariantProps) {
  const isDark = useSystemDark();
  return (
    <ConfigProvider
      theme={{
        algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: {
          colorPrimary: '#2563EB',
          borderRadius: 6,
          fontFamily:
            'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif',
        },
      }}
    >
      <App>
        <main className="candidate-shell candidate-ant">
          <CandidateHeader
            candidate="Ant Design 6.6.2"
            panel={panel}
            onPanelChange={onPanelChange}
          />
          {panel === 'table' ? <AntTableScenario /> : null}
          {panel === 'form' ? <AntFormScenario /> : null}
          {panel === 'danger' ? <AntDangerScenario /> : null}
        </main>
      </App>
    </ConfigProvider>
  );
}

function CandidateHeader({
  candidate,
  panel,
  onPanelChange,
}: {
  readonly candidate: string;
  readonly panel: PrototypePanel;
  readonly onPanelChange: (panel: PrototypePanel) => void;
}) {
  return (
    <header className="candidate-header">
      <div>
        <p className="candidate-eyebrow">PROTOTYPE · 候选 A</p>
        <h1 tabIndex={-1}>Design System 基础能力比较</h1>
        <p>当前实现：{candidate}。数据和业务规则均为内存假数据。</p>
      </div>
      <nav className="candidate-tabs" aria-label="原型场景">
        {(Object.keys(PANEL_LABELS) as PrototypePanel[]).map((item) => (
          <Button
            key={item}
            type={panel === item ? 'primary' : 'default'}
            aria-pressed={panel === item}
            onClick={() => {
              onPanelChange(item);
            }}
          >
            {PANEL_LABELS[item]}
          </Button>
        ))}
      </nav>
    </header>
  );
}

function AntTableScenario() {
  const { message } = App.useApp();
  const [draftQuery, setDraftQuery] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [sortOrder, setSortOrder] = useState<'ascend' | 'descend' | null>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [dangerTenant, setDangerTenant] = useState<TenantRow | null>(null);
  const [confirmation, setConfirmation] = useState('');

  const rows = useMemo(() => {
    const filtered = TENANTS.filter((tenant) => tenant.name.includes(query.trim()));
    return [...filtered].sort((left, right) => {
      if (sortOrder === null) {
        return 0;
      }
      const comparison = left.name.localeCompare(right.name, 'zh-CN');
      return sortOrder === 'ascend' ? comparison : -comparison;
    });
  }, [query, sortOrder]);

  const applyQuery = () => {
    setQuery(draftQuery);
    setPage(1);
    setSelectedKeys([]);
  };

  const columns: TableProps<TenantRow>['columns'] = [
    {
      title: '租户名称',
      dataIndex: 'name',
      key: 'name',
      sorter: true,
      sortOrder,
      width: 220,
    },
    { title: '套餐', dataIndex: 'plan', key: 'plan', width: 150 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (status: TenantRow['status']) => (
        <Tag color={status === '启用' ? 'green' : 'default'}>{status}</Tag>
      ),
    },
    { title: '成员数', dataIndex: 'members', key: 'members', align: 'right', width: 120 },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 220,
      render: (_, tenant) => (
        <div className="row-actions">
          <Button type="link" onClick={() => void message.info(`查看 ${tenant.name}`)}>
            查看
          </Button>
          <Button type="link" onClick={() => void message.info(`编辑 ${tenant.name}`)}>
            编辑
          </Button>
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                { key: 'disable', label: tenant.status === '启用' ? '停用' : '恢复' },
                { type: 'divider' },
                { key: 'delete', label: '永久删除', danger: true },
              ],
              onClick: ({ key }) => {
                if (key === 'delete') {
                  setConfirmation('');
                  setDangerTenant(tenant);
                  return;
                }
                void message.success(`${tenant.name} 状态操作已模拟完成`);
              },
            }}
          >
            <Button>更多</Button>
          </Dropdown>
        </div>
      ),
    },
  ];

  return (
    <section className="scenario-panel" aria-labelledby="ant-table-title">
      <div className="scenario-heading">
        <div>
          <h2 id="ant-table-title">租户列表</h2>
          <p>服务端分页、单列排序、当前页选择和固定操作列的模拟。</p>
        </div>
        <Button type="primary">创建租户</Button>
      </div>

      <div className="query-bar" role="search">
        <label htmlFor="ant-tenant-query">租户名称</label>
        <Input
          id="ant-tenant-query"
          value={draftQuery}
          placeholder="输入租户名称"
          onChange={(event) => {
            setDraftQuery(event.target.value);
          }}
          onPressEnter={applyQuery}
        />
        <Button type="primary" onClick={applyQuery}>
          查询
        </Button>
        <Button
          onClick={() => {
            setDraftQuery('');
            setQuery('');
            setPage(1);
            setSelectedKeys([]);
          }}
        >
          重置
        </Button>
      </div>

      <p className="scenario-state" aria-live="polite">
        第 {page} 页 · 已选择 {selectedKeys.length} 条 · 排序：
        {sortOrder === null ? '默认' : sortOrder === 'ascend' ? '名称升序' : '名称降序'}
      </p>

      <Table<TenantRow>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        scroll={{ x: 820 }}
        locale={{ emptyText: query === '' ? '暂无租户' : '没有符合条件的结果' }}
        rowSelection={{
          selectedRowKeys: selectedKeys,
          onChange: setSelectedKeys,
        }}
        pagination={{
          current: page,
          pageSize: 3,
          total: rows.length,
          showSizeChanger: false,
          onChange: (nextPage) => {
            setPage(nextPage);
            setSelectedKeys([]);
          },
        }}
        onChange={(_, __, sorter, extra) => {
          if (extra.action === 'sort' && !Array.isArray(sorter)) {
            setSortOrder(sorter.order ?? null);
            setPage(1);
            setSelectedKeys([]);
          }
        }}
      />

      <Modal
        title="永久删除租户"
        open={dangerTenant !== null}
        okText="永久删除"
        cancelText="取消"
        okButtonProps={{
          danger: true,
          disabled: confirmation !== dangerTenant?.name,
        }}
        onCancel={() => {
          setDangerTenant(null);
        }}
        onOk={() => {
          if (dangerTenant !== null) {
            void message.success(`${dangerTenant.name} 删除操作已模拟完成`);
          }
          setDangerTenant(null);
        }}
        afterOpenChange={(open) => {
          if (open) {
            document.getElementById('ant-table-danger-cancel')?.focus();
          }
        }}
        footer={(_, { OkBtn }) => (
          <>
            <Button
              id="ant-table-danger-cancel"
              onClick={() => {
                setDangerTenant(null);
              }}
            >
              取消
            </Button>
            <OkBtn />
          </>
        )}
      >
        <p>
          此操作不可恢复。请输入 <strong>{dangerTenant?.name}</strong> 后继续。
        </p>
        <Input
          aria-label="输入租户名称确认永久删除"
          value={confirmation}
          onChange={(event) => {
            setConfirmation(event.target.value);
          }}
        />
      </Modal>
    </section>
  );
}

function AntFormScenario() {
  const { message } = App.useApp();
  const [form] = Form.useForm();
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [summary, setSummary] = useState<string | null>(null);
  const [leaveOpen, setLeaveOpen] = useState(false);

  const save = async () => {
    setSaving(true);
    setSummary(null);
    await new Promise((resolve) => setTimeout(resolve, 500));
    setSaving(false);
    setDirty(false);
    void message.success('租户已保存');
  };

  return (
    <section className="scenario-panel form-width" aria-labelledby="ant-form-title">
      <div className="scenario-heading">
        <div>
          <h2 id="ant-form-title">创建租户</h2>
          <p>字段离开时校验，提交失败后聚焦第一个问题。</p>
        </div>
      </div>

      {summary === null ? null : <Alert type="error" showIcon title={summary} />}

      <Form
        form={form}
        layout="vertical"
        validateTrigger="onBlur"
        requiredMark="optional"
        onValuesChange={() => {
          setDirty(true);
        }}
        onFinish={() => void save()}
        onFinishFailed={({ errorFields }) => {
          setSummary(`请修正 ${String(errorFields.length)} 个字段后再保存。`);
          form.scrollToField(errorFields[0].name, { focus: true });
        }}
      >
        <Form.Item
          label="租户名称"
          name="name"
          rules={[{ required: true, message: '请输入租户名称' }]}
        >
          <Input placeholder="例如：北辰科技" />
        </Form.Item>

        <Form.Item
          label="管理员邮箱"
          name="email"
          rules={[
            { required: true, message: '请输入管理员邮箱' },
            { type: 'email', message: '请输入有效的邮箱地址' },
          ]}
        >
          <Input placeholder="admin@example.com" />
        </Form.Item>

        <Form.Item
          label="初始套餐"
          name="plan"
          rules={[{ required: true, message: '请选择初始套餐' }]}
        >
          <Select
            placeholder="请选择"
            options={[
              { label: 'Free', value: 'free' },
              { label: 'Professional', value: 'professional' },
            ]}
          />
        </Form.Item>

        <Form.Item
          name="confirmed"
          valuePropName="checked"
          rules={[
            {
              validator: (_, value) =>
                value ? Promise.resolve() : Promise.reject(new Error('请确认初始化影响')),
            },
          ]}
        >
          <Checkbox>我已了解创建租户将初始化管理员流程</Checkbox>
        </Form.Item>

        <div className="form-actions">
          <Button type="primary" htmlType="submit" loading={saving}>
            {saving ? '正在保存' : '保存'}
          </Button>
          <Button
            onClick={() => {
              if (dirty) {
                setLeaveOpen(true);
              } else {
                void message.info('没有未保存修改');
              }
            }}
          >
            返回
          </Button>
        </div>
      </Form>

      <p className="scenario-state" aria-live="polite">
        表单状态：{saving ? '正在保存' : dirty ? '存在未保存修改' : '未修改'}
      </p>

      <Modal
        title="放弃未保存的修改？"
        open={leaveOpen}
        okText="放弃修改"
        cancelText="继续编辑"
        okButtonProps={{ danger: true }}
        onCancel={() => {
          setLeaveOpen(false);
        }}
        onOk={() => {
          form.resetFields();
          setDirty(false);
          setLeaveOpen(false);
        }}
      >
        <p>离开后，本次填写的内容不会保留。</p>
      </Modal>
    </section>
  );
}

function AntDangerScenario() {
  const { message } = App.useApp();
  const [open, setOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const tenantName = '北辰科技';

  return (
    <section className="scenario-panel form-width" aria-labelledby="ant-danger-title">
      <div className="scenario-heading">
        <div>
          <h2 id="ant-danger-title">危险操作确认</h2>
          <p>默认焦点位于取消，Enter 不执行永久删除，关闭后返回触发按钮。</p>
        </div>
      </div>
      <Alert type="warning" showIcon title="租户停用可以恢复；永久删除不可恢复。" />
      <div className="danger-actions">
        <Button>停用租户</Button>
        <Button
          danger
          onClick={() => {
            setConfirmation('');
            setOpen(true);
          }}
        >
          永久删除租户
        </Button>
      </div>
      <Modal
        title="永久删除租户"
        open={open}
        okText="永久删除"
        cancelText="取消"
        okButtonProps={{ danger: true, disabled: confirmation !== tenantName }}
        onCancel={() => {
          setOpen(false);
        }}
        onOk={() => {
          setOpen(false);
          void message.success('删除操作已模拟完成');
        }}
        afterOpenChange={(nextOpen) => {
          if (nextOpen) {
            document.getElementById('ant-danger-cancel')?.focus();
          }
        }}
        footer={(_, { OkBtn }) => (
          <>
            <Button
              id="ant-danger-cancel"
              onClick={() => {
                setOpen(false);
              }}
            >
              取消
            </Button>
            <OkBtn />
          </>
        )}
      >
        <p>
          将永久删除租户及其配置。请输入 <strong>{tenantName}</strong> 确认。
        </p>
        <Input
          aria-label="输入租户名称确认永久删除"
          value={confirmation}
          onChange={(event) => {
            setConfirmation(event.target.value);
          }}
        />
      </Modal>
    </section>
  );
}

function useSystemDark() {
  return useSyncExternalStore(
    (listener) => {
      const media = window.matchMedia('(prefers-color-scheme: dark)');
      media.addEventListener('change', listener);
      return () => {
        media.removeEventListener('change', listener);
      };
    },
    () => window.matchMedia('(prefers-color-scheme: dark)').matches,
    () => false,
  );
}
