import { useMemo, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../lib/api';
import { fmtBytes, fmtNum, fmtPct, fmtTime } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { ErrorBox, PageHeader, Stat, WindowSelect } from '../components/ui';

const COLORS: Record<string, string> = {
  ERROR: '#f0565a', SLOW: '#f5a524', BASELINE: '#5aa9e6', DROPPED: '#2a3038', LATE_KEPT: '#7fdbca', LATE_DROPPED: '#1c2127',
};
const ORDER = ['ERROR', 'SLOW', 'BASELINE', 'LATE_KEPT', 'DROPPED', 'LATE_DROPPED'];

export default function Sampling() {
  const [win, setWin] = useState(60);
  const { data, error } = usePoll(() => api.sampling(win), 10000, [win]);

  const series = useMemo(() => {
    const byMinute = new Map<number, Record<string, number | string>>();
    (data?.series ?? []).forEach((p) => {
      const row = byMinute.get(p.minuteEpochMs) ?? { t: fmtTime(p.minuteEpochMs).slice(0, 5) };
      row[p.reason] = p.traces;
      byMinute.set(p.minuteEpochMs, row);
    });
    return [...byMinute.entries()].sort(([a], [b]) => a - b).map(([, r]) => r);
  }, [data]);
  const reasons = ORDER.filter((r) => (data?.totals ?? []).some((t) => t.reason === r));

  return (
    <>
      <PageHeader
        title="Sampling & storage"
        subtitle="Tail sampling decides per trace after it completes. Metrics above count everything; only kept traces are stored."
        right={<WindowSelect value={win} onChange={setWin} />}
      />
      <ErrorBox error={error} />
      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Stat label="Traces seen" value={fmtNum(data?.tracesSeen)} />
        <Stat label="Traces kept" value={fmtNum(data?.tracesKept)} hint={data && data.tracesSeen ? fmtPct(data.tracesKept / data.tracesSeen) : undefined} />
        <Stat label="Span storage saved" value={fmtPct(data?.storageReduction ?? 0, 0)} tone="signal" hint={`${fmtNum(data?.spansKept)} of ${fmtNum(data?.spansSeen)} spans written`} />
        <Stat label="Traces in decision buffer" value={fmtNum(data?.pendingTraces)} hint="waiting in Redis" />
        <Stat label="Learned slow thresholds" value={fmtNum(data?.learnedThresholds)} hint="per-operation p99" />
      </div>

      <div className="grid gap-6 xl:grid-cols-3">
        <section className="panel px-4 pb-2 pt-3 xl:col-span-2">
          <div className="mb-2 text-sm font-medium text-ink-100">Sampling decisions per minute</div>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={series}>
              <CartesianGrid stroke="#1c2127" />
              <XAxis dataKey="t" stroke="#5b6573" fontSize={11} minTickGap={24} />
              <YAxis stroke="#5b6573" fontSize={11} width={44} />
              <Tooltip contentStyle={{ background: '#161a1f', border: '1px solid #2a3038', borderRadius: 6, fontSize: 12 }} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              {reasons.map((r) => <Bar key={r} dataKey={r} stackId="s" fill={COLORS[r]} isAnimationActive={false} />)}
            </BarChart>
          </ResponsiveContainer>
        </section>
        <section className="panel">
          <div className="border-b border-ink-800 px-4 py-3 text-sm font-medium text-ink-100">Decisions by reason</div>
          <table className="w-full">
            <thead><tr><th className="th">Reason</th><th className="th text-right">Traces</th><th className="th text-right">Spans</th></tr></thead>
            <tbody>
              {ORDER.map((r) => data?.totals.find((t) => t.reason === r)).filter(Boolean).map((t) => (
                <tr key={t!.reason}>
                  <td className="td"><span className="mr-2 inline-block h-2 w-2 rounded-sm" style={{ background: COLORS[t!.reason] }} />{t!.reason}</td>
                  <td className="td mono text-right">{fmtNum(t!.traces)}</td>
                  <td className="td mono text-right">{fmtNum(t!.spans)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>

      <section className="panel mt-6">
        <div className="border-b border-ink-800 px-4 py-3 text-sm font-medium text-ink-100">
          Span partitions <span className="text-xs font-normal text-ink-500">daily range partitions; expired days are dropped, not deleted</span>
        </div>
        <table className="w-full">
          <thead><tr><th className="th">Partition</th><th className="th text-right">Est. rows</th><th className="th text-right">Size</th></tr></thead>
          <tbody>
            {(data?.partitions ?? []).map((p) => (
              <tr key={p.name}>
                <td className="td mono text-xs">{p.name}</td>
                <td className="td mono text-right">{fmtNum(p.estimatedRows)}</td>
                <td className="td mono text-right">{fmtBytes(p.bytes)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}
