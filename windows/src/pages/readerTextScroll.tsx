import { useEffect, useMemo, useRef, useState } from "react";
import type { ReaderTextDocumentV3 } from "../lib/readerApi";

/**
 * Infinite-scroll TXT reader body.
 *
 * The Windows reader no longer paginates TXT like a physical book: every
 * paragraph of every logical page is rendered as one continuous flow, and the
 * viewport follows the scroll position. Logical pages still exist underneath
 * (they carry the paragraph->page mapping used by search, contents and backup
 * progress), but they are only used as windowing units so very large books
 * never materialize all paragraphs as DOM nodes at once.
 */
export interface ReaderTextScrollPosition {
  pageIndex: number;
  paragraphIndex: number;
  pageOffsetPercent: number;
}

export interface ReaderTextScrollMetrics {
  fontSizePx: number;
  lineHeight: number;
  contentWidthPx: number;
  paragraphSpacingPx: number;
  pagePaddingPx: number;
}

export interface ReaderTextScrollMatch {
  pageIndex: number;
  startIndex: number;
  endIndex: number;
}

interface Props {
  document: ReaderTextDocumentV3;
  matches: ReaderTextScrollMatch[];
  currentMatchIndex: number;
  metrics: ReaderTextScrollMetrics;
  initialPageIndex: number;
  initialParagraphIndex: number;
  jumpToken: number;
  jumpPageIndex: number;
  onPositionChanged: (position: ReaderTextScrollPosition) => void;
  onInitialRestored: () => void;
}

const WINDOW_PAGES_BEFORE = 2;
const WINDOW_PAGES_AFTER = 3;

function paragraphList(text: string): string[] {
  // Mirrors the page text layout produced by the Rust reader: paragraphs are
  // separated by one blank line.
  return text.split("\n\n");
}

function estimateLines(text: string, metrics: ReaderTextScrollMetrics): number {
  const charactersPerLine = Math.max(
    1,
    Math.floor(metrics.contentWidthPx / Math.max(metrics.fontSizePx * 0.52, 1)),
  );
  const paragraphs = paragraphList(text);
  let linesCount = 0;
  for (const paragraph of paragraphs) {
    linesCount += Math.max(1, Math.ceil(paragraph.length / charactersPerLine));
  }
  return linesCount;
}

function estimatePageHeight(
  page: { text: string },
  metrics: ReaderTextScrollMetrics,
): number {
  const paragraphs = paragraphList(page.text);
  const textHeight =
    estimateLines(page.text, metrics) * metrics.fontSizePx * metrics.lineHeight;
  return (
    textHeight +
    paragraphs.length * metrics.paragraphSpacingPx +
    metrics.pagePaddingPx * 2
  );
}

/** Estimated top offset of a paragraph inside its page, in px. */
function estimateParagraphTopInPage(
  pageText: string,
  paragraphInPage: number,
  metrics: ReaderTextScrollMetrics,
): number {
  const paragraphs = paragraphList(pageText);
  const charactersPerLine = Math.max(
    1,
    Math.floor(metrics.contentWidthPx / Math.max(metrics.fontSizePx * 0.52, 1)),
  );
  let top = metrics.pagePaddingPx;
  for (let index = 0; index < Math.min(paragraphInPage, paragraphs.length); index += 1) {
    const linesCount = Math.max(1, Math.ceil(paragraphs[index].length / charactersPerLine));
    top +=
      linesCount * metrics.fontSizePx * metrics.lineHeight +
      metrics.paragraphSpacingPx;
  }
  return top;
}

function escapeMarkup(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

export default function TextScrollView({
  document,
  matches,
  currentMatchIndex,
  metrics,
  initialPageIndex,
  initialParagraphIndex,
  jumpToken,
  jumpPageIndex,
  onPositionChanged,
  onInitialRestored,
}: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const pageElements = useRef(new Map<number, HTMLDivElement>());
  const measuredHeights = useRef<number[]>([]);
  const initialRestored = useRef(false);
  const [window, setWindow] = useState(() => ({
    start: Math.max(0, initialPageIndex - WINDOW_PAGES_BEFORE),
    end: Math.min(document.pages.length - 1, initialPageIndex + WINDOW_PAGES_AFTER),
  }));

  const estimatedHeights = useMemo(
    () =>
      document.pages.map((page) =>
        Math.max(240, estimatePageHeight(page, metrics)),
      ),
    [document, metrics],
  );

  // Keep the estimate table aligned with measured heights for rendered pages.
  const heights = useMemo(() => {
    const table = estimatedHeights.slice();
    for (let index = 0; index < table.length; index += 1) {
      const measured = measuredHeights.current[index];
      if (measured !== undefined && measured > 0) table[index] = measured;
    }
    return table;
  }, [estimatedHeights]);

  function pageTop(index: number): number {
    let top = 0;
    const limit = Math.min(index, heights.length);
    for (let i = 0; i < limit; i += 1) top += heights[i];
    return top;
  }

  function totalHeight(): number {
    let total = 0;
    for (const height of heights) total += height;
    return total;
  }

  function findPageAt(scrollTop: number): number {
    let low = 0;
    let high = Math.max(document.pages.length - 1, 0);
    while (low < high) {
      const middle = Math.floor((low + high + 1) / 2);
      if (pageTop(middle) <= scrollTop) low = middle;
      else high = middle - 1;
    }
    return low;
  }

  function scrollToPage(pageIndex: number, smooth = false) {
    const node = containerRef.current;
    if (!node) return;
    const target = Math.min(Math.max(pageIndex, 0), document.pages.length - 1);
    const top = pageTop(target);
    if (smooth && typeof node.scrollTo === "function") {
      try {
        node.scrollTo({ top, behavior: "smooth" });
        return;
      } catch {
        // jsdom and a few embedders do not implement scrollTo(options); fall
        // through to the direct scrollTop assignment below.
      }
    }
    node.scrollTop = top;
  }

  // Restore the saved position once the layout is available.
  useEffect(() => {
    if (initialRestored.current) return;
    const node = containerRef.current;
    if (!node || document.pages.length === 0) return;
    const page = Math.min(Math.max(initialPageIndex, 0), document.pages.length - 1);
    const pageText = document.pages[page]?.text ?? "";
    const firstParagraph = document.pages[page]?.firstParagraphIndex ?? 0;
    const paragraphInPage = Math.max(initialParagraphIndex - firstParagraph, 0);
    node.scrollTop =
      pageTop(page) + estimateParagraphTopInPage(pageText, paragraphInPage, metrics);
    initialRestored.current = true;
    onInitialRestored();
    // Force a position report after layout settles.
    const frame = requestAnimationFrame(() => {
      const nodeNow = containerRef.current;
      if (nodeNow) reportPosition(nodeNow.scrollTop);
    });
    return () => cancelAnimationFrame(frame);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [document]);

  // External jumps (contents, search results, toolbar pager).
  useEffect(() => {
    if (jumpToken <= 0) return;
    scrollToPage(jumpPageIndex, true);
    // Some environments (jsdom, embedders without smooth scrolling) do not
    // fire a scroll event after a programmatic scrollTop change, so report the
    // position directly on the next frame as well.
    const frame = requestAnimationFrame(() => {
      const node = containerRef.current;
      if (node) reportPosition(node.scrollTop);
    });
    return () => cancelAnimationFrame(frame);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jumpToken, jumpPageIndex]);

  function reportPosition(scrollTop: number) {
    const node = containerRef.current;
    if (!node || document.pages.length === 0) return;
    const pageIndex = findPageAt(scrollTop + Math.min(node.clientHeight * 0.3, 320));
    const page = document.pages[pageIndex];
    const top = pageTop(pageIndex);
    const inPage = Math.min(
      Math.max(scrollTop + node.clientHeight * 0.3 - top, 0),
      heights[pageIndex] || 1,
    );
    const paragraphs = paragraphList(page?.text ?? "");
    if (!paragraphs.length) return;
    const charactersPerLine = Math.max(
      1,
      Math.floor(metrics.contentWidthPx / Math.max(metrics.fontSizePx * 0.52, 1)),
    );
    const paragraphHeight = (line: number) =>
      line * metrics.fontSizePx * metrics.lineHeight + metrics.paragraphSpacingPx;
    let accumulated = 0;
    let paragraphInPage = 0;
    for (let index = 0; index < paragraphs.length; index += 1) {
      const lineCount = Math.max(1, Math.ceil(paragraphs[index].length / charactersPerLine));
      const height = paragraphHeight(lineCount);
      if (accumulated + height > inPage) {
        paragraphInPage = index;
        break;
      }
      accumulated += height;
      paragraphInPage = index;
    }
    const paragraphIndex = (page?.firstParagraphIndex ?? 0) + paragraphInPage;
    // Android Reader schema v7 quantizes the in-page position to 0/5/…/95;
    // mirror it here so saved progress stays format-aligned.
    const pageOffsetPercent = paragraphs.length <= 1
      ? 0
      : Math.min(95, Math.round((paragraphInPage / (paragraphs.length - 1)) * 95 / 5) * 5);
    onPositionChanged({ pageIndex, paragraphIndex, pageOffsetPercent });
  }

  useEffect(() => {
    const node = containerRef.current;
    if (!node) return;
    let frame: number | null = null;
    const onScroll = () => {
      if (frame !== null) return;
      frame = requestAnimationFrame(() => {
        frame = null;
        const current = findPageAt(node.scrollTop + Math.min(node.clientHeight * 0.3, 320));
        reportPosition(node.scrollTop);
        setWindow((previous) => {
          const start = Math.max(0, current - WINDOW_PAGES_BEFORE);
          const end = Math.min(document.pages.length - 1, current + WINDOW_PAGES_AFTER);
          if (start === previous.start && end === previous.end) return previous;
          return { start, end };
        });
      });
    };
    node.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      node.removeEventListener("scroll", onScroll);
      if (frame !== null) cancelAnimationFrame(frame);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [document, heights]);

  // Measure rendered pages so later scroll mapping uses real heights.
  function bindPageElement(pageIndex: number) {
    return (node: HTMLDivElement | null) => {
      if (node) {
        pageElements.current.set(pageIndex, node);
        const height = node.offsetHeight;
        if (height > 0 && measuredHeights.current[pageIndex] !== height) {
          measuredHeights.current[pageIndex] = height;
        }
      } else {
        pageElements.current.delete(pageIndex);
      }
    };
  }

  const renderedPages: number[] = [];
  for (let index = window.start; index <= window.end; index += 1) renderedPages.push(index);

  return (
    <div
      className="reader-text-scroll"
      ref={containerRef}
      tabIndex={-1}
      role="region"
      aria-label="TXT body"
    >
      <div aria-hidden="true" style={{ height: pageTop(window.start) + "px" }} />
      {renderedPages.map((pageIndex) => {
        const page = document.pages[pageIndex];
        if (!page) return null;
        const paragraphs = paragraphList(page.text);
        const pageMatches = matches
          .filter((match) => match.pageIndex === pageIndex)
          .map((match, index) => ({ ...match, resultIndex: index }));
        let offset = 0;
        return (
          <div
            className="reader-text-scroll-page"
            key={pageIndex}
            ref={bindPageElement(pageIndex)}
          >
            {paragraphs.map((paragraph, paragraphInPage) => {
              const start = offset;
              const end = start + paragraph.length;
              offset = end + 2;
              const contained = pageMatches.filter(
                (match) => match.startIndex >= start && match.endIndex <= end,
              );
              let content: string;
              if (!contained.length) {
                content = escapeMarkup(paragraph);
              } else {
                let cursor = start;
                const parts: string[] = [];
                const current = matches[currentMatchIndex];
                for (const match of contained) {
                  if (match.startIndex > cursor) {
                    parts.push(escapeMarkup(page.text.slice(cursor, match.startIndex)));
                  }
                  const highlighted = page.text.slice(match.startIndex, match.endIndex);
                  const isCurrent =
                    current !== undefined &&
                    current.pageIndex === pageIndex &&
                    current.startIndex === match.startIndex &&
                    current.endIndex === match.endIndex;
                  parts.push(
                    '<mark class="' +
                    (isCurrent ? "is-current" : "") +
                    '">' +
                    escapeMarkup(highlighted) +
                    "</mark>"
                  );
                  cursor = match.endIndex;
                }
                if (cursor < end) parts.push(escapeMarkup(page.text.slice(cursor, end)));
                content = parts.join("");
              }
              return (
                <p
                  key={paragraphInPage}
                  style={{ marginBlockEnd: metrics.paragraphSpacingPx + "px" }}
                  // The escaped, highlighted markup is static and built from the
                  // page text only, so dangerouslySetInnerHTML is safe here.
                  dangerouslySetInnerHTML={{ __html: content }}
                />
              );
            })}
          </div>
        );
      })}
      <div
        aria-hidden="true"
        style={{ height: Math.max(totalHeight() - pageTop(window.end + 1), 0) + "px" }}
      />
    </div>
  );
}