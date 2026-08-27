/** Accent color parsing (hex / RGB / CMYK) and CSS variable application. */

export const DEFAULT_ACCENT = "#a8c7fa";

export type Rgb = { r: number; g: number; b: number };
export type Cmyk = { c: number; m: number; y: number; k: number };

export function clampByte(n: number): number {
  if (!Number.isFinite(n)) return 0;
  return Math.max(0, Math.min(255, Math.round(n)));
}

export function clampPercent(n: number): number {
  if (!Number.isFinite(n)) return 0;
  return Math.max(0, Math.min(100, Math.round(n)));
}

export function rgbToHex({ r, g, b }: Rgb): string {
  const h = (n: number) => clampByte(n).toString(16).padStart(2, "0");
  return `#${h(r)}${h(g)}${h(b)}`;
}

export function hexToRgb(input: string): Rgb | null {
  let s = input.trim().replace(/^#/, "");
  if (/^[0-9a-f]{3}$/i.test(s)) {
    s = s
      .split("")
      .map((c) => c + c)
      .join("");
  }
  if (/^[0-9a-f]{6}$/i.test(s)) {
    return {
      r: parseInt(s.slice(0, 2), 16),
      g: parseInt(s.slice(2, 4), 16),
      b: parseInt(s.slice(4, 6), 16),
    };
  }
  if (/^[0-9a-f]{8}$/i.test(s)) {
    // Ignore alpha
    return {
      r: parseInt(s.slice(0, 2), 16),
      g: parseInt(s.slice(2, 4), 16),
      b: parseInt(s.slice(4, 6), 16),
    };
  }
  return null;
}

/** Parse flexible hex / rgb() / rgba() / "r,g,b" strings. */
export function parseColorInput(raw: string): Rgb | null {
  const t = raw.trim();
  if (!t) return null;

  const hex = hexToRgb(t);
  if (hex) return hex;

  const rgbFn = t.match(
    /^rgba?\(\s*([0-9.]+)\s*[, ]\s*([0-9.]+)\s*[, ]\s*([0-9.]+)(?:\s*[,/]\s*[0-9.%]+)?\s*\)$/i,
  );
  if (rgbFn) {
    return {
      r: clampByte(Number(rgbFn[1])),
      g: clampByte(Number(rgbFn[2])),
      b: clampByte(Number(rgbFn[3])),
    };
  }

  const parts = t.split(/[\s,;/]+/).filter(Boolean);
  if (parts.length === 3 && parts.every((p) => /^-?\d+(\.\d+)?%?$/.test(p))) {
    const nums = parts.map((p) => {
      if (p.endsWith("%")) return (parseFloat(p) / 100) * 255;
      return Number(p);
    });
    return { r: clampByte(nums[0]), g: clampByte(nums[1]), b: clampByte(nums[2]) };
  }

  return null;
}

export function rgbToCmyk({ r, g, b }: Rgb): Cmyk {
  const rr = clampByte(r) / 255;
  const gg = clampByte(g) / 255;
  const bb = clampByte(b) / 255;
  const k = 1 - Math.max(rr, gg, bb);
  if (k >= 1 - 1e-9) return { c: 0, m: 0, y: 0, k: 100 };
  const c = (1 - rr - k) / (1 - k);
  const m = (1 - gg - k) / (1 - k);
  const y = (1 - bb - k) / (1 - k);
  return {
    c: clampPercent(c * 100),
    m: clampPercent(m * 100),
    y: clampPercent(y * 100),
    k: clampPercent(k * 100),
  };
}

export function cmykToRgb({ c, m, y, k }: Cmyk): Rgb {
  const C = clampPercent(c) / 100;
  const M = clampPercent(m) / 100;
  const Y = clampPercent(y) / 100;
  const K = clampPercent(k) / 100;
  return {
    r: clampByte(255 * (1 - C) * (1 - K)),
    g: clampByte(255 * (1 - M) * (1 - K)),
    b: clampByte(255 * (1 - Y) * (1 - K)),
  };
}

function relativeLuminance({ r, g, b }: Rgb): number {
  const lin = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return {
    r: clampByte(a.r + (b.r - a.r) * t),
    g: clampByte(a.g + (b.g - a.g) * t),
    b: clampByte(a.b + (b.b - a.b) * t),
  };
}

/** Derive on-primary / container tones and write CSS variables. */
export function applyAccent(hexOrRgb: string | Rgb): string {
  const rgb = typeof hexOrRgb === "string" ? parseColorInput(hexOrRgb) : hexOrRgb;
  if (!rgb) return DEFAULT_ACCENT;
  const hex = rgbToHex(rgb);
  const onPrimary = relativeLuminance(rgb) > 0.45 ? "#0b0e14" : "#f4f6fa";
  const container = mix(rgb, { r: 11, g: 14, b: 20 }, 0.55);
  const containerHex = rgbToHex(container);

  const root = document.documentElement;
  root.style.setProperty("--md-sys-color-primary", hex);
  root.style.setProperty("--md-sys-color-on-primary", onPrimary);
  root.style.setProperty("--md-sys-color-primary-container", containerHex);
  root.style.setProperty("--term-accent", hex);
  root.style.setProperty("--accent", hex);

  return hex;
}

export function normalizeAccent(raw: string | null | undefined): string {
  const rgb = parseColorInput(raw || DEFAULT_ACCENT);
  return rgb ? rgbToHex(rgb) : DEFAULT_ACCENT;
}
