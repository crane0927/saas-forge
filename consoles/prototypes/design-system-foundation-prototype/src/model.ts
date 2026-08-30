export interface TenantRow {
  readonly id: string;
  readonly name: string;
  readonly plan: 'Free' | 'Professional';
  readonly status: '启用' | '停用';
  readonly members: number;
}

export const TENANTS: readonly TenantRow[] = [
  { id: 'tenant-001', name: '北辰科技', plan: 'Professional', status: '启用', members: 46 },
  { id: 'tenant-002', name: '远山制造', plan: 'Free', status: '启用', members: 12 },
  { id: 'tenant-003', name: '青禾零售', plan: 'Professional', status: '停用', members: 31 },
  { id: 'tenant-004', name: '云帆咨询', plan: 'Free', status: '启用', members: 8 },
  { id: 'tenant-005', name: '星港物流', plan: 'Professional', status: '启用', members: 73 },
  { id: 'tenant-006', name: '明川教育', plan: 'Free', status: '停用', members: 19 },
];

export type PrototypePanel = 'table' | 'form' | 'danger';
export type PrototypeVariant = 'antd' | 'react-aria';

export const PANEL_LABELS: Readonly<Record<PrototypePanel, string>> = {
  table: '表格',
  form: '表单',
  danger: '危险确认',
};
