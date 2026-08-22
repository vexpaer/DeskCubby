/**
 * Dependency-free tiny SVG charts shared by the statistics pages
 * (统计中心 / 手机使用时间 / 健康). Colors consume only --dc-* tokens.
 * Series are plain number arrays aligned with `labels`; missing values (NaN)
 * leave gaps instead of being treated as 0.
 */
import { useState } from "react";
import { tr } from "../i18n/tr";

export interface ChartSeries {
  values: number[];
  color?: string;
  label?: string;
}

const PALETTE = ["var(--dc-primary)", "var(--dc-secondary)", "var(--dc-tertiary)"];

function niceMax(values: number[]): number {
  const finite = values.filter((v) => Number.isFinite(v));
  const max = finite.length > 0 ? Math.max(...finite) : 0;
  if (max <= 0) return 1;
  return max;
}

function formatNumber(v: number): string {
  if (!Number.isFinite(v)) return "—";
  if (Math.abs(v - Math.round(v)) < 0.01) return String(Math.round(v));
  return v.toFixed(1);
}

function Tooltip(props: { text: string; x: number; y: number }) {
  return (
    <text
      x={props.x} y={props.y}
      fontSize={11} textAnchor="middle"
      fill="var(--dc-on-surface)"
      style={{ pointerEvents: "none" }}
    >
      {props.text}
    </text>
  );
}

/** Vertical bar chart. Bars highlight the hovered index; empty values render nothing. */
export function BarChart(props: {
  series: ChartSeries[];
  labels: string[];
  height?: number;
  /** Formats the tooltip value; defaults to plain numbers. */
  formatValue?: (v: number) => string;
}) {
  const height = props.height ?? 160;
  const width = 100; // viewBox units; scales with container width
  const series = props.series.length > 0 ? props.series : [{ values: [] }];
  const count = Math.max(props.labels.length, ...series.map((s) => s.values.length));
  const [hover, setHover] = useState<number | null>(null);

  const flat = series.flatMap((s) => s.values);
  const max = niceMax(flat);
  const slot = count > 0 ? width / count : width;
  const barWidth = Math.max(1.2, slot * (count > 24 ? 0.72 : 0.6));
  const fmt = props.formatValue ?? formatNumber;

  return (
    <svg
      role="img"
      aria-label={tr("柱状图", "Bar chart")}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      style={{ width: "100%", height }}
      onMouseLeave={() => setHover(null)}
    >
      {/* baseline */}
      <line x1={0} x2={width} y1={height - 4} y2={height - 4} stroke="var(--dc-outline-variant)" strokeWidth={1} vectorEffect="non-scaling-stroke" />
      {series.map((s, si) => {
        const color = s.color ?? PALETTE[si % PALETTE.length];
        return s.values.map((v, i) => {
          if (!Number.isFinite(v) || v <= 0) return null;
          const barH = Math.max(1, ((height - 8) * v) / max);
          const x = i * slot + (slot - barWidth) / 2;
          const y = height - 4 - barH;
          return (
            <rect
              key={`${si}-${i}`}
              x={x} y={y} width={barWidth} height={barH} rx={Math.min(2, barWidth / 2)}
              fill={color}
              opacity={hover === null || hover === i ? 1 : 0.45}
              onMouseEnter={() => setHover(i)}
            />
          );
        });
      })}
      {hover !== null && hover >= 0 && hover < count && (() => {
        const parts = series
          .map((s) => (Number.isFinite(s.values[hover]) ? fmt(s.values[hover]) : null))
          .filter((x): x is string => x !== null);
        const label = props.labels[hover] ?? "";
        const text = `${label} ${parts.join(" / ")}`.trim();
        return <Tooltip text={text} x={Math.min(width - 20, Math.max(20, hover * slot + slot / 2))} y={10} />;
      })()}
    </svg>
  );
}

/** Multi-series line chart with small dots on the hovered point. */
export function LineChart(props: {
  series: ChartSeries[];
  labels: string[];
  height?: number;
  formatValue?: (v: number) => string;
}) {
  const height = props.height ?? 160;
  const width = 100;
  const series = props.series.length > 0 ? props.series : [{ values: [] }];
  const count = Math.max(props.labels.length, ...series.map((s) => s.values.length));
  const [hover, setHover] = useState<number | null>(null);

  const flat = series.flatMap((s) => s.values);
  const max = niceMax(flat);
  const fmt = props.formatValue ?? formatNumber;

  const pointX = (i: number) => (count <= 1 ? width / 2 : (width * i) / (count - 1));
  const pointY = (v: number) => height - 6 - ((height - 12) * v) / max;

  return (
    <svg
      role="img"
      aria-label={tr("折线图", "Line chart")}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      style={{ width: "100%", height }}
      onMouseLeave={() => setHover(null)}
      onMouseMove={(e) => {
        const rect = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;
        setHover(Math.max(0, Math.min(count - 1, Math.round(ratio * (count - 1)))));
      }}
    >
      <line x1={0} x2={width} y1={height - 6} y2={height - 6} stroke="var(--dc-outline-variant)" strokeWidth={1} vectorEffect="non-scaling-stroke" />
      {series.map((s, si) => {
        const color = s.color ?? PALETTE[si % PALETTE.length];
        const pts = s.values
          .map((v, i) => (Number.isFinite(v) ? `${pointX(i)},${pointY(v)}` : null))
          .filter((x): x is string => x !== null);
        if (pts.length === 0) return null;
        return (
          <polyline
            key={si}
            points={pts.join(" ")}
            fill="none" stroke={color} strokeWidth={2}
            strokeLinejoin="round" strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
          />
        );
      })}
      {hover !== null && series.map((s, si) => {
        const v = s.values[hover];
        if (!Number.isFinite(v as number)) return null;
        return (
          <circle
            key={si}
            cx={pointX(hover)} cy={pointY(v as number)} r={2.4}
            fill={s.color ?? PALETTE[si % PALETTE.length]}
            stroke="var(--dc-surface-container)"
            strokeWidth={1} vectorEffect="non-scaling-stroke"
          />
        );
      })}
      {hover !== null && (() => {
        const parts = series
          .map((s) => (Number.isFinite(s.values[hover]) ? fmt(s.values[hover]) : null))
          .filter((x): x is string => x !== null);
        const text = `${props.labels[hover] ?? ""} ${parts.join(" / ")}`.trim();
        return <Tooltip text={text} x={Math.min(width - 22, Math.max(22, pointX(hover)))} y={10} />;
      })()}
    </svg>
  );
}
