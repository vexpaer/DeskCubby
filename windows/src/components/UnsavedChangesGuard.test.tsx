import { fireEvent, render, screen } from "@testing-library/react";
import {
  createMemoryRouter,
  Link,
  RouterProvider,
} from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { useAppStore } from "../store/appStore";
import { UnsavedChangesGuard } from "./UnsavedChangesGuard";

describe("UnsavedChangesGuard", () => {
  beforeEach(() => {
    useAppStore.setState({
      appearance: {
        language: "zh-CN",
        visualTheme: "material",
        colorMode: "system",
        fontScale: 1,
        compactMode: false,
      },
      dirtyScopes: [],
    });
  });

  it("keeps the page until discarding is explicitly confirmed", async () => {
    const router = createMemoryRouter(
      [
        {
          path: "/",
          element: (
            <>
              <span>编辑页面</span>
              <Link to="/next">离开</Link>
              <UnsavedChangesGuard when scope="test-editor" />
            </>
          ),
        },
        { path: "/next", element: <span>目标页面</span> },
      ],
      { initialEntries: ["/"] },
    );
    render(<RouterProvider router={router} />);

    fireEvent.click(screen.getByRole("link", { name: "离开" }));
    expect(
      await screen.findByRole("heading", { name: "放弃未保存的更改？" }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.getByText("编辑页面")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("link", { name: "离开" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "放弃更改" }),
    );
    expect(await screen.findByText("目标页面")).toBeInTheDocument();
  });
});
