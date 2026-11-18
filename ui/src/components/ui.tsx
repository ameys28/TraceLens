import type { ReactNode } from 'react';

export function PageHeader({ title, subtitle, right }: { title: string; subtitle?: ReactNode; right?: ReactNode }) {
  return (
    <div className="mb-6 flex items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-ink-100">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-400">{subtitle}</p>}
      </div>
      {right && <div className="flex items-center gap-2">{right}</div>}
    </div>
  );
}

export function Stat({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'bad' | 'good' | 'signal' }) {
  const color = tone === 'bad' ? 'text-bad' : tone === 'good' ? 'text-good' : tone === 'signal' ? 'text-signal' : 'text-ink-100';
  return (
    <div className="panel px-4 py-3">
      <div className="label">{label}</div>
      <div className={`mono mt-1 text-2xl ${color}`}>{value}</div>
      {hint && <div className="mt-0.5 text-xs text-ink-500">{hint}</div>}
    </div>
  );
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'bad' | 'good' | 'signal' }) {
  const cls = {
    neutral: 'border-ink-700 text-ink-300',
    bad: 'border-bad/40 bg-bad-soft text-bad',
    good: 'border-good/40 bg-good-soft text-good',
    signal: 'border-signal/40 bg-signal-soft text-signal',
  }[tone];
  return <span className={`inline-flex items-center rounded border px-1.5 py-px text-[11px] font-medium ${cls}`}>{children}</span>;
}

export function ErrorBox({ error }: { error: string | null }) {
  if (!error) return null;
  return (
    <div className="mb-4 rounded-md border border-bad/40 bg-bad-soft px-3 py-2 text-sm text-bad">
      {error}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="px-4 py-10 text-center text-sm text-ink-500">{children}</div>;
}

export function WindowSelect({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return (
    <select className="input" value={value} onChange={(e) => onChange(Number(e.target.value))}>
      {[5, 15, 60, 360, 1440].map((m) => (
        <option key={m} value={m}>
          Last {m < 60 ? `${m} min` : `${m / 60} h`}
        </option>
      ))}
    </select>
  );
}

export function ServiceDot({ color }: { color: string }) {
  return <span className="inline-block h-2 w-2 shrink-0 rounded-full" style={{ background: color }} />;
}

export function reasonTone(reason: string): 'bad' | 'signal' | 'neutral' {
  if (reason === 'ERROR') return 'bad';
  if (reason === 'SLOW') return 'signal';
  return 'neutral';
}
