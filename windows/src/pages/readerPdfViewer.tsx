import { GlobalWorkerOptions, getDocument } from "pdfjs-dist";
import type {
  PDFDocumentLoadingTask,
  PDFDocumentProxy,
  PDFPageProxy,
  RenderTask,
} from "pdfjs-dist";
import PdfWorker from "pdfjs-dist/build/pdf.worker.min.mjs?worker";
import { useCallback, useEffect, useRef, useState } from "react";

import { tr } from "../lib/ipc";

// Single module-level worker shared by every PDF viewer instance for the whole
// app lifetime. The worker port is never terminated on unmount; only the
// document is destroyed. In dev Vite instantiates the worker from a blob: URL,
// in production it is a 'self' chunk, so the CSP must allow worker-src 'self' blob:.
GlobalWorkerOptions.workerPort = new PdfWorker();

export interface ReaderPdfViewerProps {
  /** Restricted reader protocol URL carrying only a book UUID; never a filesystem path. */
  assetUrl: string;
  /** 0-based page index, aligned with ReaderPage's pageIndex. */
  pageIndex: number;
  /** Base zoom percentage (50–300). */
  zoomPercent: number;
  language: "zh-CN" | "en";
  /** Optional forced page background (applied only when both colors are set). */
  background?: string;
  /** Optional forced page foreground (applied only when both colors are set). */
  foreground?: string;
  onPageCountChanged?: (count: number) => void;
  onPageRendered?: (pageIndex: number) => void;
  onRenderFailed?: (error: unknown) => void;
}

type PdfStatus = "loading" | "ready" | "error";

export default function ReaderPdfViewer({
  assetUrl,
  pageIndex,
  zoomPercent,
  language,
  background,
  foreground,
  onPageCountChanged,
  onPageRendered,
  onRenderFailed,
}: ReaderPdfViewerProps) {
  const copy = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  // Keep the latest callbacks in a ref so document loading never restarts
  // merely because ReaderPage re-rendered with new inline function identities.
  const callbacksRef = useRef({ onPageCountChanged, onPageRendered, onRenderFailed });
  callbacksRef.current = { onPageCountChanged, onPageRendered, onRenderFailed };

  const scrollWrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const documentRef = useRef<PDFDocumentProxy | null>(null);
  const loadTaskRef = useRef<PDFDocumentLoadingTask | null>(null);
  const renderTaskRef = useRef<RenderTask | null>(null);
  const mountedRef = useRef(true);

  const [status, setStatus] = useState<PdfStatus>("loading");
  const [pageCount, setPageCount] = useState(0);
  const [containerWidth, setContainerWidth] = useState(0);
  const [rendering, setRendering] = useState(false);
  const [retryToken, setRetryToken] = useState(0);

  // Load the document through the restricted URL. pdf.js issues HTTP Range
  // requests against the Rust reader protocol, so large files are fetched on
  // demand instead of being copied into the frontend as bytes or blob URLs.
  // pdf.js v6 no longer uses eval at all, so the former isEvalSupported option
  // (and the CSP conflict it guarded against) is gone by design.
  useEffect(() => {
    mountedRef.current = true;
    let cancelled = false;
    const load = async () => {
      setStatus("loading");
      setPageCount(0);
      renderTaskRef.current?.cancel();
      renderTaskRef.current = null;
      await loadTaskRef.current?.destroy();
      loadTaskRef.current = null;
      documentRef.current = null;
      try {
        const task = getDocument({ url: assetUrl });
        loadTaskRef.current = task;
        const doc = await task.promise;
        if (cancelled || !mountedRef.current) return;
        documentRef.current = doc;
        setPageCount(doc.numPages);
        callbacksRef.current.onPageCountChanged?.(doc.numPages);
        setStatus("ready");
      } catch (reason) {
        if (!cancelled && mountedRef.current) {
          setStatus("error");
          callbacksRef.current.onRenderFailed?.(reason);
        }
      }
    };
    void load();
    return () => {
      cancelled = true;
      mountedRef.current = false;
      renderTaskRef.current?.cancel();
      renderTaskRef.current = null;
      void loadTaskRef.current?.destroy();
      loadTaskRef.current = null;
      documentRef.current = null;
    };
  }, [assetUrl, retryToken]);

  // Track the width of the scroll container so the page can be fitted. The
  // container only exists while the document is ready, so observe it then.
  useEffect(() => {
    if (status !== "ready") return;
    const node = scrollWrapRef.current;
    if (!node) return;
    const measure = () => setContainerWidth(node.clientWidth);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [status]);

  // Render the requested page at the requested zoom into the canvas.
  useEffect(() => {
    const doc = documentRef.current;
    const canvas = canvasRef.current;
    if (status !== "ready" || !doc || !canvas || containerWidth <= 0) return;
    const safePageIndex = Math.min(Math.max(pageIndex, 0), Math.max(pageCount - 1, 0));
    let cancelled = false;
    setRendering(true);
    const run = async () => {
      try {
        const page: PDFPageProxy = await doc.getPage(safePageIndex + 1);
        if (cancelled) return;
        const baseViewport = page.getViewport({ scale: 1 });
        const baseScale = containerWidth / baseViewport.width;
        const scale = baseScale * (Math.min(Math.max(zoomPercent, 1), 1000) / 100);
        const viewport = page.getViewport({ scale });
        const dpr = Math.max(globalThis.devicePixelRatio || 1, 1);
        canvas.width = Math.max(1, Math.floor(viewport.width * dpr));
        canvas.height = Math.max(1, Math.floor(viewport.height * dpr));
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        const canvasContext = canvas.getContext("2d");
        if (!canvasContext) {
          throw new Error(copy("无法创建画布上下文。", "Canvas 2D context unavailable."));
        }
        canvasContext.setTransform(dpr, 0, 0, dpr, 0, 0);
        const parameters: Parameters<PDFPageProxy["render"]>[0] = {
          canvasContext,
          canvas,
          viewport,
        };
        if (background && foreground) {
          parameters.pageColors = { background, foreground };
        }
        const renderTask = page.render(parameters);
        renderTaskRef.current = renderTask;
        await renderTask.promise;
        if (renderTaskRef.current === renderTask) renderTaskRef.current = null;
        if (cancelled) return;
        callbacksRef.current.onPageRendered?.(safePageIndex);
      } catch (reason) {
        if (!cancelled) {
          setStatus("error");
          callbacksRef.current.onRenderFailed?.(reason);
        }
      } finally {
        if (!cancelled) setRendering(false);
      }
    };
    void run();
    return () => {
      cancelled = true;
      renderTaskRef.current?.cancel();
      renderTaskRef.current = null;
    };
  }, [background, containerWidth, copy, foreground, pageCount, pageIndex, status, zoomPercent]);

  const safePageIndex = Math.min(Math.max(pageIndex, 0), Math.max(pageCount - 1, 0));

  return (
    <div className="reader-pdf-viewer" role="region" aria-label={copy("PDF 阅读区域", "PDF reader area")}>
      {status === "loading" ? (
        <div className="reader-pdf-state">
          <span className="spinner" aria-hidden="true" />
          <p>{copy("正在加载 PDF…", "Loading PDF…")}</p>
        </div>
      ) : null}
      {status === "error" ? (
        <div className="reader-pdf-state reader-pdf-error" role="alert">
          <p>{copy("PDF 加载或渲染失败。", "The PDF could not be loaded or rendered.")}</p>
          <button className="button-ghost button-small" type="button" onClick={() => setRetryToken((token) => token + 1)}>
            {copy("重试", "Retry")}
          </button>
        </div>
      ) : null}
      {status === "ready" ? (
        <>
          {rendering ? (
            <div className="reader-pdf-page-loading" role="status">
              <span className="spinner" aria-hidden="true" />
            </div>
          ) : null}
          <div className="reader-pdf-canvas-wrap" ref={scrollWrapRef}>
            <canvas ref={canvasRef} aria-label={copy(`第 ${safePageIndex + 1} 页`, `Page ${safePageIndex + 1}`)} />
          </div>
        </>
      ) : null}
    </div>
  );
}
