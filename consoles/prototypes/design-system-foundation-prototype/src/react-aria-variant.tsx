import {
  Button,
  Cell,
  CheckboxButton,
  CheckboxField,
  Column,
  Dialog,
  FieldError,
  Form,
  Heading,
  Input,
  Label,
  ListBox,
  ListBoxItem,
  Menu,
  MenuItem,
  MenuTrigger,
  Modal,
  ModalOverlay,
  Popover,
  Row,
  Select,
  SelectValue,
  Table,
  TableBody,
  TableHeader,
  TextField,
  type Selection,
  type SortDescriptor,
} from 'react-aria-components';
import { useMemo, useRef, useState } from 'react';

import { PANEL_LABELS, TENANTS, type PrototypePanel, type TenantRow } from './model';

interface VariantProps {
  readonly panel: PrototypePanel;
  readonly onPanelChange: (panel: PrototypePanel) => void;
}

export function ReactAriaVariant({ panel, onPanelChange }: VariantProps) {
  return (
    <main className="candidate-shell candidate-react-aria">
      <CandidateHeader panel={panel} onPanelChange={onPanelChange} />
      {panel === 'table' ? <ReactAriaTableScenario /> : null}
      {panel === 'form' ? <ReactAriaFormScenario /> : null}
      {panel === 'danger' ? <ReactAriaDangerScenario /> : null}
    </main>
  );
}

function CandidateHeader({
  panel,
  onPanelChange,
}: {
  readonly panel: PrototypePanel;
  readonly onPanelChange: (panel: PrototypePanel) => void;
}) {
  return (
    <header className="candidate-header">
      <div>
        <p className="candidate-eyebrow">PROTOTYPE · 候选 B</p>
        <h1 tabIndex={-1}>Design System 基础能力比较</h1>
        <p>当前实现：React Aria Components 1.20.0。数据和业务规则均为内存假数据。</p>
      </div>
      <nav className="candidate-tabs" aria-label="原型场景">
        {(Object.keys(PANEL_LABELS) as PrototypePanel[]).map((item) => (
          <Button
            key={item}
            className="ra-button"
            data-variant={panel === item ? 'primary' : 'default'}
            aria-pressed={panel === item}
            onPress={() => {
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

function ReactAriaTableScenario() {
  const [draftQuery, setDraftQuery] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [sortDescriptor, setSortDescriptor] = useState<SortDescriptor>({
    column: 'name',
    direction: 'ascending',
  });
  const [selectedKeys, setSelectedKeys] = useState<Selection>(new Set());
  const [dangerTenant, setDangerTenant] = useState<TenantRow | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  const rows = useMemo(() => {
    const filtered = TENANTS.filter((tenant) => tenant.name.includes(query.trim()));
    return [...filtered].sort((left, right) => {
      const comparison = left.name.localeCompare(right.name, 'zh-CN');
      return sortDescriptor.direction === 'ascending' ? comparison : -comparison;
    });
  }, [query, sortDescriptor]);

  const pageCount = Math.max(1, Math.ceil(rows.length / 3));
  const pageRows = rows.slice((page - 1) * 3, page * 3);
  const selectedCount = selectedKeys === 'all' ? pageRows.length : selectedKeys.size;

  const applyQuery = () => {
    setQuery(draftQuery);
    setPage(1);
    setSelectedKeys(new Set());
  };

  return (
    <section className="scenario-panel" aria-labelledby="ra-table-title">
      <div className="scenario-heading">
        <div>
          <h2 id="ra-table-title">租户列表</h2>
          <p>服务端分页、单列排序、当前页选择和固定操作列的模拟。</p>
        </div>
        <Button className="ra-button" data-variant="primary">
          创建租户
        </Button>
      </div>

      <div className="query-bar" role="search">
        <TextField
          value={draftQuery}
          onChange={setDraftQuery}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              applyQuery();
            }
          }}
        >
          <Label>租户名称</Label>
          <Input placeholder="输入租户名称" />
        </TextField>
        <Button className="ra-button" data-variant="primary" onPress={applyQuery}>
          查询
        </Button>
        <Button
          className="ra-button"
          onPress={() => {
            setDraftQuery('');
            setQuery('');
            setPage(1);
            setSelectedKeys(new Set());
          }}
        >
          重置
        </Button>
      </div>

      <p className="scenario-state" aria-live="polite">
        第 {page} 页 · 已选择 {selectedCount} 条 · 排序：
        {sortDescriptor.direction === 'ascending' ? '名称升序' : '名称降序'}
      </p>

      <div className="ra-table-scroll">
        <Table
          aria-label="租户列表"
          selectionMode="multiple"
          selectedKeys={selectedKeys}
          onSelectionChange={setSelectedKeys}
          sortDescriptor={sortDescriptor}
          onSortChange={(nextSort) => {
            setSortDescriptor(nextSort);
            setPage(1);
            setSelectedKeys(new Set());
          }}
        >
          <TableHeader>
            <Column width={52}>
              <CheckboxField slot="selection">
                <CheckboxButton aria-label="选择当前页全部租户">
                  <span className="ra-checkbox-box" aria-hidden="true">
                    ✓
                  </span>
                </CheckboxButton>
              </CheckboxField>
            </Column>
            <Column id="name" isRowHeader allowsSorting width={220}>
              租户名称
            </Column>
            <Column width={150}>套餐</Column>
            <Column width={120}>状态</Column>
            <Column width={120}>成员数</Column>
            <Column width={220}>操作</Column>
          </TableHeader>
          <TableBody
            items={pageRows}
            renderEmptyState={() => (query === '' ? '暂无租户' : '没有符合条件的结果')}
          >
            {(tenant) => (
              <Row id={tenant.id}>
                <Cell>
                  <CheckboxField slot="selection">
                    <CheckboxButton aria-label={`选择 ${tenant.name}`}>
                      <span className="ra-checkbox-box" aria-hidden="true">
                        ✓
                      </span>
                    </CheckboxButton>
                  </CheckboxField>
                </Cell>
                <Cell>{tenant.name}</Cell>
                <Cell>{tenant.plan}</Cell>
                <Cell>
                  <span className="status-tag" data-status={tenant.status}>
                    {tenant.status}
                  </span>
                </Cell>
                <Cell>{tenant.members}</Cell>
                <Cell>
                  <div className="row-actions">
                    <Button
                      className="ra-link-button"
                      onPress={() => {
                        setNotice(`查看 ${tenant.name}`);
                      }}
                    >
                      查看
                    </Button>
                    <Button
                      className="ra-link-button"
                      onPress={() => {
                        setNotice(`编辑 ${tenant.name}`);
                      }}
                    >
                      编辑
                    </Button>
                    <MenuTrigger>
                      <Button className="ra-button">更多</Button>
                      <Popover className="ra-popover">
                        <Menu
                          aria-label={`${tenant.name} 更多操作`}
                          onAction={(key) => {
                            if (key === 'delete') {
                              setConfirmation('');
                              setDangerTenant(tenant);
                              return;
                            }
                            setNotice(`${tenant.name} 状态操作已模拟完成`);
                          }}
                        >
                          <MenuItem id="disable">
                            {tenant.status === '启用' ? '停用' : '恢复'}
                          </MenuItem>
                          <MenuItem id="delete" className="ra-danger-menu-item">
                            永久删除
                          </MenuItem>
                        </Menu>
                      </Popover>
                    </MenuTrigger>
                  </div>
                </Cell>
              </Row>
            )}
          </TableBody>
        </Table>
      </div>

      <nav className="pagination" aria-label="租户列表分页">
        <Button
          className="ra-button"
          isDisabled={page <= 1}
          onPress={() => {
            setPage((current) => Math.max(1, current - 1));
            setSelectedKeys(new Set());
          }}
        >
          上一页
        </Button>
        <span>
          {page} / {pageCount}
        </span>
        <Button
          className="ra-button"
          isDisabled={page >= pageCount}
          onPress={() => {
            setPage((current) => Math.min(pageCount, current + 1));
            setSelectedKeys(new Set());
          }}
        >
          下一页
        </Button>
      </nav>

      <StatusNotice message={notice} />

      <ModalOverlay
        className="ra-modal-overlay"
        isOpen={dangerTenant !== null}
        isDismissable
        onOpenChange={(open) => {
          if (!open) {
            setDangerTenant(null);
          }
        }}
      >
        <Modal className="ra-modal">
          <Dialog role="alertdialog" className="ra-dialog">
            <Heading slot="title">永久删除租户</Heading>
            <p>
              此操作不可恢复。请输入 <strong>{dangerTenant?.name}</strong> 后继续。
            </p>
            <TextField value={confirmation} onChange={setConfirmation}>
              <Label>租户名称</Label>
              <Input />
            </TextField>
            <div className="dialog-actions">
              <Button
                className="ra-button"
                autoFocus
                onPress={() => {
                  setDangerTenant(null);
                }}
              >
                取消
              </Button>
              <Button
                className="ra-button"
                data-variant="danger"
                isDisabled={confirmation !== dangerTenant?.name}
                onPress={() => {
                  setNotice(`${dangerTenant?.name ?? ''} 删除操作已模拟完成`);
                  setDangerTenant(null);
                }}
              >
                永久删除
              </Button>
            </div>
          </Dialog>
        </Modal>
      </ModalOverlay>
    </section>
  );
}

function ReactAriaFormScenario() {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [plan, setPlan] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [touched, setTouched] = useState({
    name: false,
    email: false,
    plan: false,
    confirmed: false,
  });
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [summary, setSummary] = useState<string | null>(null);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);

  const nameInvalid = touched.name && name.trim() === '';
  const emailInvalid = touched.email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email);
  const planInvalid = touched.plan && plan === null;
  const confirmedInvalid = touched.confirmed && !confirmed;

  const submit = async () => {
    const allTouched = { name: true, email: true, plan: true, confirmed: true };
    setTouched(allTouched);
    const invalidName = name.trim() === '';
    const invalidEmail = !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email);
    const invalidPlan = plan === null;
    const invalidConfirmed = !confirmed;
    const errorCount = [invalidName, invalidEmail, invalidPlan, invalidConfirmed].filter(
      Boolean,
    ).length;
    if (errorCount > 0) {
      setSummary(`请修正 ${String(errorCount)} 个字段后再保存。`);
      if (invalidName) {
        nameRef.current?.focus();
      } else if (invalidEmail) {
        emailRef.current?.focus();
      }
      return;
    }
    setSummary(null);
    setSaving(true);
    await new Promise((resolve) => setTimeout(resolve, 500));
    setSaving(false);
    setDirty(false);
    setNotice('租户已保存');
  };

  const reset = () => {
    setName('');
    setEmail('');
    setPlan(null);
    setConfirmed(false);
    setTouched({ name: false, email: false, plan: false, confirmed: false });
    setDirty(false);
    setSummary(null);
  };

  return (
    <section className="scenario-panel form-width" aria-labelledby="ra-form-title">
      <div className="scenario-heading">
        <div>
          <h2 id="ra-form-title">创建租户</h2>
          <p>字段离开时校验，提交失败后聚焦第一个问题。</p>
        </div>
      </div>

      {summary === null ? null : (
        <div className="persistent-alert" role="alert">
          {summary}
        </div>
      )}

      <Form
        className="ra-form"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <TextField
          isRequired
          isInvalid={nameInvalid}
          value={name}
          onChange={(value) => {
            setName(value);
            setDirty(true);
          }}
          onBlur={() => {
            setTouched((current) => ({ ...current, name: true }));
          }}
        >
          <Label>租户名称</Label>
          <Input ref={nameRef} placeholder="例如：北辰科技" />
          <FieldError>{nameInvalid ? '请输入租户名称' : ''}</FieldError>
        </TextField>

        <TextField
          isRequired
          isInvalid={emailInvalid}
          value={email}
          onChange={(value) => {
            setEmail(value);
            setDirty(true);
          }}
          onBlur={() => {
            setTouched((current) => ({ ...current, email: true }));
          }}
        >
          <Label>管理员邮箱</Label>
          <Input ref={emailRef} placeholder="admin@example.com" />
          <FieldError>{emailInvalid ? '请输入有效的邮箱地址' : ''}</FieldError>
        </TextField>

        <Select
          isRequired
          isInvalid={planInvalid}
          value={plan}
          onChange={(key) => {
            setPlan(String(key));
            setDirty(true);
          }}
          onBlur={() => {
            setTouched((current) => ({ ...current, plan: true }));
          }}
        >
          <Label>初始套餐</Label>
          <Button className="ra-select-button">
            <SelectValue />
            <span aria-hidden="true">▾</span>
          </Button>
          <FieldError>{planInvalid ? '请选择初始套餐' : ''}</FieldError>
          <Popover className="ra-popover">
            <ListBox>
              <ListBoxItem id="free">Free</ListBoxItem>
              <ListBoxItem id="professional">Professional</ListBoxItem>
            </ListBox>
          </Popover>
        </Select>

        <div>
          <CheckboxField
            isSelected={confirmed}
            onChange={(value) => {
              setConfirmed(value);
              setDirty(true);
              setTouched((current) => ({ ...current, confirmed: true }));
            }}
          >
            <CheckboxButton>
              <span className="ra-checkbox-box" aria-hidden="true">
                ✓
              </span>
              我已了解创建租户将初始化管理员流程
            </CheckboxButton>
          </CheckboxField>
          {confirmedInvalid ? (
            <p className="field-error" role="alert">
              请确认初始化影响
            </p>
          ) : null}
        </div>

        <div className="form-actions">
          <Button className="ra-button" data-variant="primary" type="submit" isDisabled={saving}>
            {saving ? '正在保存' : '保存'}
          </Button>
          <Button
            className="ra-button"
            type="button"
            onPress={() => {
              if (dirty) {
                setLeaveOpen(true);
              } else {
                setNotice('没有未保存修改');
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
      <StatusNotice message={notice} />

      <ModalOverlay
        className="ra-modal-overlay"
        isOpen={leaveOpen}
        isDismissable
        onOpenChange={setLeaveOpen}
      >
        <Modal className="ra-modal">
          <Dialog role="alertdialog" className="ra-dialog">
            <Heading slot="title">放弃未保存的修改？</Heading>
            <p>离开后，本次填写的内容不会保留。</p>
            <div className="dialog-actions">
              <Button
                className="ra-button"
                autoFocus
                onPress={() => {
                  setLeaveOpen(false);
                }}
              >
                继续编辑
              </Button>
              <Button
                className="ra-button"
                data-variant="danger"
                onPress={() => {
                  reset();
                  setLeaveOpen(false);
                }}
              >
                放弃修改
              </Button>
            </div>
          </Dialog>
        </Modal>
      </ModalOverlay>
    </section>
  );
}

function ReactAriaDangerScenario() {
  const [open, setOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const tenantName = '北辰科技';

  return (
    <section className="scenario-panel form-width" aria-labelledby="ra-danger-title">
      <div className="scenario-heading">
        <div>
          <h2 id="ra-danger-title">危险操作确认</h2>
          <p>默认焦点位于取消，Enter 不执行永久删除，关闭后返回触发按钮。</p>
        </div>
      </div>
      <div className="persistent-alert" data-kind="warning">
        租户停用可以恢复；永久删除不可恢复。
      </div>
      <div className="danger-actions">
        <Button className="ra-button">停用租户</Button>
        <Button
          className="ra-button"
          data-variant="danger"
          onPress={() => {
            setConfirmation('');
            setOpen(true);
          }}
        >
          永久删除租户
        </Button>
      </div>
      <StatusNotice message={notice} />

      <ModalOverlay className="ra-modal-overlay" isOpen={open} isDismissable onOpenChange={setOpen}>
        <Modal className="ra-modal">
          <Dialog role="alertdialog" className="ra-dialog">
            <Heading slot="title">永久删除租户</Heading>
            <p>
              将永久删除租户及其配置。请输入 <strong>{tenantName}</strong> 确认。
            </p>
            <TextField value={confirmation} onChange={setConfirmation}>
              <Label>租户名称</Label>
              <Input />
            </TextField>
            <div className="dialog-actions">
              <Button
                className="ra-button"
                autoFocus
                onPress={() => {
                  setOpen(false);
                }}
              >
                取消
              </Button>
              <Button
                className="ra-button"
                data-variant="danger"
                isDisabled={confirmation !== tenantName}
                onPress={() => {
                  setOpen(false);
                  setNotice('删除操作已模拟完成');
                }}
              >
                永久删除
              </Button>
            </div>
          </Dialog>
        </Modal>
      </ModalOverlay>
    </section>
  );
}

function StatusNotice({ message }: { readonly message: string | null }) {
  return message === null ? null : (
    <div className="prototype-toast" role="status">
      {message}
    </div>
  );
}
