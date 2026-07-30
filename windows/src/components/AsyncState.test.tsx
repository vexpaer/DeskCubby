import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useAppStore } from "../store/appStore";
import { EmptyState, ErrorState, LoadingState } from "./AsyncState";

describe("asynchronous state panels", () => {
  beforeEach(() => {
    useAppStore.setState({
      appearance: {
        language: "zh-CN",
        visualTheme: "material",
        colorMode: "system",
        fontScale: 1,
        compactMode: false,
      },
    });
  });

  it("announces loading and empty states in Chinese", () => {
    const { rerender } = render(<LoadingState />);

    expect(screen.getByRole("status")).toHaveTextContent("正在加载");

    rerender(<EmptyState description="选择目录后即可开始" />);

    expect(screen.getByRole("status")).toHaveTextContent("这里还没有内容");
    expect(screen.getByRole("status")).toHaveTextContent("选择目录后即可开始");
  });

  it("renders an English error state and supports keyboard retry", async () => {
    const retry = vi.fn();
    const user = userEvent.setup();
    useAppStore.setState((state) => ({
      appearance: { ...state.appearance, language: "en" },
    }));

    render(<ErrorState description="The request failed." retry={retry} />);

    expect(screen.getByRole("status")).toHaveTextContent(
      "The action could not be completed",
    );
    const retryButton = screen.getByRole("button", { name: "Retry" });
    retryButton.focus();
    await user.keyboard("{Enter}");

    expect(retry).toHaveBeenCalledTimes(1);
  });
});
