import { Component, type ReactNode } from "react";

interface ErrorBoundaryProps {
  children: ReactNode;
  fallback: (reset: () => void, error: Error | null) => ReactNode;
}

interface ErrorBoundaryState {
  failed: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { failed: false, error: null };

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    return {
      failed: true,
      error: error instanceof Error ? error : new Error("unexpected_error"),
    };
  }

  componentDidCatch() {
    // IPC and private content are intentionally not logged here. The UI exposes a
    // retry path while Rust returns stable, non-sensitive error codes.
  }

  render() {
    if (this.state.failed) {
      return this.props.fallback(
        () => this.setState({ failed: false, error: null }),
        this.state.error,
      );
    }
    return this.props.children;
  }
}
