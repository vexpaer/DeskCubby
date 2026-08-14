import type { PDFDocumentProxy } from "pdfjs-dist";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Document, Outline, Page, PasswordResponses, pdfjs } from "react-pdf";
import "react-pdf/dist/Page/AnnotationLayer.css";
import "react-pdf/dist/Page/TextLayer.css";

import {
  openExternalHttpUrl,
  safeExternalHttpUrl,
} from "../lib/externalLinkApi";
import { recolorPdfCanvas } from "../lib/readerAppearance";
import type {
  ReaderPdfColorMode,
  ReaderPdfScrollMode,
} from "../lib/readerApi";
import { tr } from "../lib/ipc";

pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  "pdfjs-dist/build/pdf.worker.min.mjs",
  import.meta.url,
).toString();

const PDF_ASSET_BASE = new URL("pdfjs-assets/", globalThis.document.baseURI).toString();
const PDF_OPTIONS = {
  cMapUrl: `${PDF_ASSET_BASE}cmaps/`,
  cMapPacked: true,
  standardFontDataUrl: `${PDF_ASSET_BASE}standard_fonts/`,
  wasmUrl: `${PDF_ASSET_BASE}wasm/`,
  iccUrl: `${PDF_ASSET_BASE}iccs/`,
};
const MAX_SEARCH_RESULTS = 5_000;
const SEARCH_YIELD_INTERVAL = 8;
const CONTINUOUS_OVERSCAN_PAGES = 3;

export interface ReaderPdfSearchMatch {
  pageIndex: number;
  occurrenceIndex: number;
}

export interface ReaderPdfViewerProps {
  /** Restricted reader protocol URL carrying only a book UUID; never a filesystem path. */
  assetUrl: string;
  /** 0-based page index, aligned with ReaderPage's saved progress. */
  pageIndex: number;
  zoomPercent: number;
  language: "zh-CN" | "en";
  background: string;
  foreground: string;
  colorMode: ReaderPdfColorMode;
  scrollMode: ReaderPdfScrollMode;
  pageGapPx: number;
  pagePaddingPx: number;
  rotation: number;
  searchQuery: string;
  showOutline: boolean;
  onOutlineClose: () => void;
  onPageCountChanged?: (count: number) => void;
  onVisiblePageChanged?: (pageIndex: number) => void;
  onSearchChanged?: (matches: ReaderPdfSearchMatch[], searching: boolean) => void;
  onPageRendered?: (pageIndex: number) => void;
  onRenderFailed?: (error: unknown) => void;
}

function clampPage(page: number, count: number): number {
  return Math.min(Math.max(Math.floor(page), 0), Math.max(count - 1, 0));
}

function escapeMarkup(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function highlightTextItem(value: string, rawQuery: string): string {
  const query = rawQuery.trim();
  if (!query) return escapeMarkup(value);
  const foldedValue = value.toLocaleLowerCase();
  const foldedQuery = query.toLocaleLowerCase();
  const parts: string[] = [];
  let cursor = 0;
  while (cursor < value.length) {
    const match = foldedValue.indexOf(foldedQuery, cursor);
    if (match < 0) break;
    parts.push(escapeMarkup(value.slice(cursor, match)));
    parts.push(`<mark>${escapeMarkup(value.slice(match, match + query.length))}</mark>`);
    cursor = match + Math.max(query.length, 1);
  }
  parts.push(escapeMarkup(value.slice(cursor)));
  return parts.join("");
}

export default function ReaderPdfViewer({
  assetUrl,
  pageIndex,
  zoomPercent,
  language,
  background,
  foreground,
  colorMode,
  scrollMode,
  pageGapPx,
  pagePaddingPx,
  rotation,
  searchQuery,
  showOutline,
  onOutlineClose,
  onPageCountChanged,
  onVisiblePageChanged,
  onSearchChanged,
  onPageRendered,
  onRenderFailed,
}: ReaderPdfViewerProps) {
  const copy = useCallback((zh: string, en: string) => tr(language, zh, en), [language]);
  const callbacksRef = useRef({
    onPageCountChanged,
    onVisiblePageChanged,
    onSearchChanged,
    onPageRendered,
    onRenderFailed,
  });
  callbacksRef.current = {
    onPageCountChanged,
    onVisiblePageChanged,
    onSearchChanged,
    onPageRendered,
    onRenderFailed,
  };

  const scrollRef = useRef<HTMLDivElement>(null);
  const canvasRefs = useRef(new Map<number, HTMLCanvasElement>());
  const lastReportedPageRef = useRef<number | null>(null);
  const requestedPageRef = useRef(pageIndex);
  requestedPageRef.current = pageIndex;
  const scrollFrameRef = useRef<number | null>(null);
  const passwordCallbackRef = useRef<((password: string | null) => void) | null>(null);
  const file = useMemo(() => ({ url: assetUrl }), [assetUrl]);
  const [documentProxy, setDocumentProxy] = useState<PDFDocumentProxy | null>(null);
  const [pageCount, setPageCount] = useState(0);
  const [containerWidth, setContainerWidth] = useState(0);
  const [pageRatio, setPageRatio] = useState(Math.SQRT2);
  const [virtualPage, setVirtualPage] = useState(pageIndex);
  const [outlineCount, setOutlineCount] = useState<number | null>(null);
  const [password, setPassword] = useState("");
  const [passwordReason, setPasswordReason] = useState<number | null>(null);
  const [retryToken, setRetryToken] = useState(0);

  useEffect(() => {
    const node = scrollRef.current;
    if (!node) return;
    const measure = () => setContainerWidth(node.clientWidth);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [documentProxy, showOutline]);

  useEffect(() => () => {
    if (scrollFrameRef.current !== null) cancelAnimationFrame(scrollFrameRef.current);
  }, []);

  const fitWidth = Math.max(containerWidth - pagePaddingPx * 2, 260);
  const pageWidth = Math.max(260, Math.round(fitWidth * zoomPercent / 100));
  const rotatedRatio = rotation % 180 === 0 ? pageRatio : 1 / pageRatio;
  const pageHeight = Math.max(320, Math.round(pageWidth * rotatedRatio));
  const itemHeight = pageHeight + pageGapPx;
  const safePageIndex = clampPage(pageIndex, pageCount);

  useEffect(() => {
    if (!documentProxy || pageCount < 1) return;
    const target = clampPage(pageIndex, pageCount);
    setVirtualPage(target);
    if (scrollMode !== "continuous") return;
    if (lastReportedPageRef.current === target) {
      lastReportedPageRef.current = null;
      return;
    }
    const node = scrollRef.current;
    if (node) {
      const top = target * itemHeight;
      if (typeof node.scrollTo === "function") node.scrollTo({ top, behavior: "auto" });
      else node.scrollTop = top;
    }
  }, [documentProxy, itemHeight, pageCount, pageIndex, scrollMode]);

  useEffect(() => {
    if (!documentProxy) return;
    const query = [...searchQuery.trim()].slice(0, 128).join("");
    let cancelled = false;
    callbacksRef.current.onSearchChanged?.([], Boolean(query));
    if (!query) return;
    const foldedQuery = query.toLocaleLowerCase();
    const run = async () => {
      const matches: ReaderPdfSearchMatch[] = [];
      try {
        for (let pageNumber = 1; pageNumber <= documentProxy.numPages; pageNumber += 1) {
          if (cancelled) return;
          if (matches.length >= MAX_SEARCH_RESULTS) break;
          const page = await documentProxy.getPage(pageNumber);
          const textContent = await page.getTextContent();
          const text = textContent.items
            .map((item) => ("str" in item ? item.str : ""))
            .join(" ")
            .toLocaleLowerCase();
          let cursor = 0;
          let occurrenceIndex = 0;
          while (matches.length < MAX_SEARCH_RESULTS) {
            const found = text.indexOf(foldedQuery, cursor);
            if (found < 0) break;
            matches.push({ pageIndex: pageNumber - 1, occurrenceIndex });
            occurrenceIndex += 1;
            cursor = found + Math.max(foldedQuery.length, 1);
          }
          if (pageNumber % SEARCH_YIELD_INTERVAL === 0) {
            await new Promise<void>((resolve) => setTimeout(resolve, 0));
          }
        }
        if (!cancelled) callbacksRef.current.onSearchChanged?.(matches, false);
      } catch (reason) {
        if (!cancelled) {
          callbacksRef.current.onSearchChanged?.([], false);
          callbacksRef.current.onRenderFailed?.(reason);
        }
      }
    };
    void run();
    return () => {
      cancelled = true;
    };
  }, [documentProxy, searchQuery]);

  const onDocumentLoad = useCallback((pdf: PDFDocumentProxy) => {
    setDocumentProxy(pdf);
    setPageCount(pdf.numPages);
    setVirtualPage(clampPage(requestedPageRef.current, pdf.numPages));
    setOutlineCount(null);
    callbacksRef.current.onPageCountChanged?.(pdf.numPages);
    void pdf.getPage(1).then((page) => {
      // Default rotation keeps the page's own /Rotate attribute, so the aspect
      // ratio matches what the Page renderer will actually display.
      const viewport = page.getViewport({ scale: 1 });
      if (viewport.width > 0 && viewport.height > 0) {
        setPageRatio(viewport.height / viewport.width);
      }
    }).catch(() => {
      // A malformed first-page size will be reported by the Page renderer.
    });
  }, []);

  const onPassword = useCallback((callback: (password: string | null) => void, reason: number) => {
    passwordCallbackRef.current = callback;
    setPassword("");
    setPasswordReason(reason);
  }, []);

  const submitPassword = (event: React.FormEvent) => {
    event.preventDefault();
    if (!password) return;
    const callback = passwordCallbackRef.current;
    passwordCallbackRef.current = null;
    setPasswordReason(null);
    callback?.(password);
  };

  const openPdfExternalLink = (event: React.MouseEvent<HTMLDivElement>) => {
    if (!(event.target instanceof Element)) return;
    const anchor = event.target.closest("a");
    if (!anchor || !event.currentTarget.contains(anchor)) return;
    const rawHref = anchor.getAttribute("href") ?? "";
    // Leave PDF.js' internal destinations alone. Every other annotation URL
    // is prevented from navigating the WebView and must pass the same
    // renderer + authoritative Rust validation used by Markdown links.
    if (!rawHref || rawHref.startsWith("#")) return;
    event.preventDefault();
    event.stopPropagation();
    const url = safeExternalHttpUrl(rawHref);
    if (!url) return;
    void openExternalHttpUrl(url).catch((reason) => {
      callbacksRef.current.onRenderFailed?.(reason);
    });
  };

  const onScroll = () => {
    if (scrollMode !== "continuous" || scrollFrameRef.current !== null) return;
    scrollFrameRef.current = requestAnimationFrame(() => {
      scrollFrameRef.current = null;
      const node = scrollRef.current;
      if (!node || pageCount < 1) return;
      const candidate = clampPage(
        Math.floor((node.scrollTop + Math.min(node.clientHeight * 0.35, itemHeight * 0.45)) / itemHeight),
        pageCount,
      );
      setVirtualPage(candidate);
      if (candidate !== pageIndex) {
        lastReportedPageRef.current = candidate;
        callbacksRef.current.onVisiblePageChanged?.(candidate);
      }
    });
  };

  const renderPage = (index: number) => (
    <div
      className="reader-pdf-page-slot"
      data-page-index={index}
      key={`${index}-${zoomPercent}-${rotation}-${colorMode}-${background}-${foreground}`}
      style={{ minHeight: `${pageHeight}px` }}
      aria-label={copy(`第 ${index + 1} 页`, `Page ${index + 1}`)}
    >
      <Page
        pageNumber={index + 1}
        width={pageWidth}
        // rotate={0} would override each page's own /Rotate attribute, which
        // made PDFs with rotated pages (e.g. scans with alternating /Rotate
        // values) render "one page right, the next upside down". Only pass a
        // rotation when the user actually rotated the view.
        rotate={rotation % 360 === 0 ? undefined : rotation}
        renderTextLayer
        renderAnnotationLayer
        renderForms
        customTextRenderer={({ str }) => highlightTextItem(str, searchQuery)}
        canvasRef={(node) => {
          if (node) canvasRefs.current.set(index, node);
          else canvasRefs.current.delete(index);
        }}
        loading={<div className="reader-pdf-page-placeholder"><span className="spinner" aria-hidden="true" /></div>}
        error={<div className="reader-pdf-page-placeholder reader-pdf-error">{copy("这一页无法渲染", "This page could not be rendered")}</div>}
        onRenderError={(reason) => callbacksRef.current.onRenderFailed?.(reason)}
        onRenderSuccess={() => {
          const canvas = canvasRefs.current.get(index);
          if (colorMode === "readingColors" && canvas) {
            try {
              recolorPdfCanvas(canvas, background, foreground);
            } catch (reason) {
              callbacksRef.current.onRenderFailed?.(reason);
              return;
            }
          }
          callbacksRef.current.onPageRendered?.(index);
        }}
      />
      <span className="reader-pdf-page-badge">{index + 1}</span>
    </div>
  );

  const virtualStart = Math.max(virtualPage - CONTINUOUS_OVERSCAN_PAGES, 0);
  const virtualEnd = Math.min(virtualPage + CONTINUOUS_OVERSCAN_PAGES, pageCount - 1);
  const virtualPages = [];
  for (let index = virtualStart; index <= virtualEnd; index += 1) virtualPages.push(index);

  return (
    <div
      className="reader-pdf-viewer"
      role="region"
      aria-label={copy("PDF 阅读区域", "PDF reader area")}
      onClickCapture={openPdfExternalLink}
      onAuxClickCapture={openPdfExternalLink}
    >
      <Document
        key={`${assetUrl}-${retryToken}`}
        file={file}
        options={PDF_OPTIONS}
        externalLinkRel="noopener noreferrer nofollow"
        externalLinkTarget="_blank"
        loading={<div className="reader-pdf-state"><span className="spinner" aria-hidden="true" /><p>{copy("正在加载 PDF…", "Loading PDF…")}</p></div>}
        error={
          <div className="reader-pdf-state reader-pdf-error" role="alert">
            <p>{copy("PDF 无法加载。文件可能损坏、不受支持或密码不正确。", "The PDF could not be loaded. It may be damaged, unsupported, or use an incorrect password.")}</p>
            <button className="button-ghost button-small" type="button" onClick={() => {
              setDocumentProxy(null);
              setPageCount(0);
              setRetryToken((token) => token + 1);
            }}>{copy("重试", "Retry")}</button>
          </div>
        }
        onLoadSuccess={onDocumentLoad}
        onLoadError={(reason) => callbacksRef.current.onRenderFailed?.(reason)}
        onPassword={onPassword}
        onItemClick={({ pageIndex: target }) => callbacksRef.current.onVisiblePageChanged?.(target)}
      >
        {documentProxy ? (
          <div className={`reader-pdf-layout${showOutline ? " has-outline" : ""}`}>
            {showOutline ? (
              <aside className="reader-pdf-outline panel" aria-label={copy("PDF 目录", "PDF outline")}>
                <div className="reader-pdf-outline-header">
                  <strong>{copy("目录", "Contents")}</strong>
                  <small>{outlineCount === null ? copy("读取中…", "Loading…") : copy(`${outlineCount} 个顶层条目`, `${outlineCount} top-level items`)}</small>
                  <button className="icon-button" type="button" onClick={onOutlineClose} aria-label={copy("收起目录", "Hide contents")}>×</button>
                </div>
                <Outline
                  onLoadSuccess={(outline) => setOutlineCount(outline?.length ?? 0)}
                  onItemClick={({ pageIndex: target }) => callbacksRef.current.onVisiblePageChanged?.(target)}
                />
                {outlineCount === 0 ? <p className="reader-toc-empty">{copy("这份 PDF 没有内嵌目录。", "This PDF has no embedded outline.")}</p> : null}
              </aside>
            ) : null}
            <div
              className={`reader-pdf-scroll mode-${scrollMode}`}
              ref={scrollRef}
              onScroll={onScroll}
              style={{
                "--reader-pdf-page-gap": `${pageGapPx}px`,
                "--reader-pdf-page-padding": `${pagePaddingPx}px`,
              } as React.CSSProperties}
            >
              {scrollMode === "singlePage" ? renderPage(safePageIndex) : (
                <div className="reader-pdf-virtual-stack">
                  <div aria-hidden="true" style={{ height: `${virtualStart * itemHeight}px` }} />
                  {virtualPages.map(renderPage)}
                  <div aria-hidden="true" style={{ height: `${Math.max(pageCount - virtualEnd - 1, 0) * itemHeight}px` }} />
                </div>
              )}
            </div>
          </div>
        ) : null}
      </Document>

      {passwordReason !== null ? (
        <div className="reader-pdf-password-backdrop" role="presentation">
          <form className="panel reader-pdf-password" onSubmit={submitPassword} aria-label={copy("输入 PDF 密码", "Enter PDF password")}>
            <h2>{copy("此 PDF 受密码保护", "This PDF is password-protected")}</h2>
            <p>{passwordReason === PasswordResponses.INCORRECT_PASSWORD ? copy("密码不正确，请重试。", "That password was incorrect. Try again.") : copy("密码仅用于本次打开，不会保存。", "The password is used for this session only and is never saved.")}</p>
            <label className="field"><span className="field-label">{copy("PDF 密码", "PDF password")}</span><input autoFocus type="password" value={password} maxLength={1024} onChange={(event) => setPassword(event.target.value)} /></label>
            <div className="row">
              <button className="button-secondary" type="button" onClick={() => { const callback = passwordCallbackRef.current; passwordCallbackRef.current = null; setPasswordReason(null); callback?.(null); }}>{copy("取消", "Cancel")}</button>
              <button className="button-primary" type="submit" disabled={!password}>{copy("解锁", "Unlock")}</button>
            </div>
          </form>
        </div>
      ) : null}
    </div>
  );
}
