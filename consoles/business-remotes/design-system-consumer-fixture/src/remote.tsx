import {
  Button,
  PageLayout,
  PageTitle,
  ResponsiveGrid,
  SplitLayout,
  SuccessFeedback,
  TextField,
} from '@saas-forge/design-system';
import { useState } from 'react';

export function DesignSystemConsumerRemote() {
  const [name, setName] = useState('Remote');
  const [submittedName, setSubmittedName] = useState<string>();

  return (
    <PageLayout
      width="wide"
      title={
        <PageTitle description="由宿主提供当前主题；Remote 只消费公共组件。">
          Design System Remote 消费夹具
        </PageTitle>
      }
    >
      <section aria-labelledby="remote-content-title">
        <h2 id="remote-content-title">Remote 内容概览</h2>
        <ResponsiveGrid intent="content">
          {['共享主题', '公共根入口', '容器响应'].map((item) => (
            <article data-testid="remote-content-item" key={item}>
              <h3>{item}</h3>
              <p>该内容由统一布局根据 Remote 实际获得的空间排列。</p>
            </article>
          ))}
        </ResponsiveGrid>
      </section>

      <section aria-labelledby="remote-statistics-title">
        <h2 id="remote-statistics-title">共享消费状态</h2>
        <ResponsiveGrid intent="compact-statistics">
          {[
            ['主题入口', '1'],
            ['全局样式入口', '1'],
            ['布局意图', '2'],
            ['辅助栏', '可见'],
          ].map(([term, value]) => (
            <dl data-testid="remote-statistics-item" key={term}>
              <dt>{term}</dt>
              <dd>{value}</dd>
            </dl>
          ))}
        </ResponsiveGrid>
      </section>

      <SplitLayout
        primary={
          <section aria-labelledby="remote-verification-title">
            <h2 id="remote-verification-title">验证共享交互</h2>
            <form
              aria-label="Remote 消费验证"
              onSubmit={(event) => {
                event.preventDefault();
                setSubmittedName(name.trim() === '' ? 'Remote' : name.trim());
              }}
            >
              <TextField id="remote-name" label="显示名称" value={name} onValueChange={setName} />
              <Button type="submit" variant="primary">
                验证共享反馈
              </Button>
            </form>
            {submittedName === undefined ? null : (
              <SuccessFeedback message={`${submittedName} 已继承宿主主题。`} />
            )}
          </section>
        }
        auxiliary={
          <section>
            <h2>布局说明</h2>
            <p>主内容始终先于辅助信息，窄屏不会隐藏本区域。</p>
            <Button onClick={() => undefined}>查看布局说明</Button>
          </section>
        }
        auxiliaryLabel="Remote 布局说明"
      />
    </PageLayout>
  );
}
