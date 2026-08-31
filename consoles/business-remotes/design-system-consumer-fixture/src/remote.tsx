import {
  Button,
  PageLayout,
  PageTitle,
  SuccessFeedback,
  TextField,
} from '@saas-forge/design-system';
import { useState } from 'react';

export function DesignSystemConsumerRemote() {
  const [name, setName] = useState('Remote');
  const [submittedName, setSubmittedName] = useState<string>();

  return (
    <PageLayout
      title={
        <PageTitle description="由宿主提供当前主题；Remote 只消费公共组件。">
          Design System Remote 消费夹具
        </PageTitle>
      }
    >
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
    </PageLayout>
  );
}
