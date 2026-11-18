export function fmtDuration(us: number | null | undefined): string {
  if (us === null || us === undefined || Number.isNaN(us)) return '–';
  if (us < 1000) return `${Math.round(us)}µs`;
  if (us < 1_000_000) return `${(us / 1000).toFixed(us < 10_000 ? 2 : us < 100_000 ? 1 : 0)}ms`;
  return `${(us / 1_000_000).toFixed(2)}s`;
}

export function fmtPct(x: number | null | undefined, digits = 1): string {
  if (x === null || x === undefined || Number.isNaN(x)) return '–';
  return `${(x * 100).toFixed(digits)}%`;
}

export function fmtNum(n: number | null | undefined): string {
  if (n === null || n === undefined) return '–';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}k`;
  return n.toLocaleString();
}

export function fmtBytes(b: number): string {
  if (b < 1024) return `${b} B`;
  if (b < 1024 ** 2) return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1024 ** 3) return `${(b / 1024 ** 2).toFixed(1)} MB`;
  return `${(b / 1024 ** 3).toFixed(2)} GB`;
}

export function fmtTime(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function fmtAgo(ms: number): string {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

// Stable, distinguishable colour per service name.
const PALETTE = ['#5aa9e6', '#3ecf8e', '#c084fc', '#f78c6c', '#7fdbca', '#e6c07b', '#ff7eb6', '#82aaff', '#c3e88d', '#89ddff'];
export function serviceColor(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

export function errorColor(rate: number): string {
  if (rate >= 0.1) return '#f0565a';
  if (rate >= 0.02) return '#f5a524';
  return '#3ecf8e';
}
