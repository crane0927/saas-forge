import { ApplicationFatalError } from '@saas-forge/design-system';
import { Component, type ReactNode } from 'react';

interface RootErrorBoundaryProps {
  readonly children: ReactNode;
  readonly reload?: () => void;
}

interface RootErrorBoundaryState {
  readonly failed: boolean;
}

export class RootErrorBoundary extends Component<RootErrorBoundaryProps, RootErrorBoundaryState> {
  public state: RootErrorBoundaryState = { failed: false };

  public static getDerivedStateFromError(): RootErrorBoundaryState {
    return { failed: true };
  }

  public componentDidCatch(): void {
    // 预留 React 错误边界生命周期；本切片不声称或实现遥测上报。
  }

  public render(): ReactNode {
    if (!this.state.failed) {
      return this.props.children;
    }

    return <ApplicationFatalError applicationName="Platform Console" onReload={this.reload} />;
  }

  private readonly reload = (): void => {
    if (this.props.reload !== undefined) {
      this.props.reload();
      return;
    }
    window.location.reload();
  };
}
