import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis, Bar, BarChart } from 'recharts';
import { api } from '../lib/api';
import { errorColor, fmtDuration, fmtPct, fmtTime, serviceColor } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Empty, ErrorBox, PageHeader, ServiceDot, WindowSelect } from '../components/ui';

const axis = { stroke: '#5b6573', fontSize: 11 };
const tooltip = { contentStyle: { background: '#161a1f', border: '1px solid #2a3038', borderRadius: 6, fontSize: 12 }, labelStyle: { color: '#b4bcc8' } };

export default function ServiceDetail() {
  const { service = '' } = useParams();
  const [win, setWin] = useState(60);
  const [operation, setOperation] = useState<string>('');
  const ops = usePoll(() => api.operations(service, win), 10000, [service, win]);
  const series = usePoll(() => api.timeseries(service, operation || undefined, win), 10000, [service, operation, win]);

  const points = (series.data ?? []).map((p) => ({
    t: fmtTime(p.minuteEpochMs).slice(0, 5),
    requests: p.requests,
    errors: p.errors,
    p50: +(p.p50Us / 1000).toFixed(1),
    p95: +(p.p95Us / 1000).toFixed(1),
    p99: +(p.p99Us / 1000).toFixed(1),
  }));

  return (
    <>
      <PageHeader
        title={service}
        subtitle={<span className="flex items-center gap-2"><ServiceDot color={serviceColor(service)} /> Operations measured at the service's entry spans</span>}
        right={<>
          <Link className="btn" to={`/traces?service=${encodeURIComponent(service)}`}>Traces</Link>
          <WindowSelect value={win} onChange={setWin} />
        </>}
      />
      <ErrorBox error={ops.error ?? series.error} />

      <div className="mb-6 grid gap-4 lg:grid-cols-2">
        <section className="panel px-4 pb-2 pt-3">
          <div className="mb-2 text-sm font-medium text-ink-100">Latency {operation ? `· ${operation}` : '· all operations'} (ms)</div>
          <ResponsiveContainer width="100%" height={220}>
            <LineChart data={points}>
              <CartesianGrid stroke="#1c2127" />
              <XAxis dataKey="t" {...axis} minTickGap={24} />
              <YAxis {...axis} width={44} />
              <Tooltip {...tooltip} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Line dataKey="p50" stroke="#5aa9e6" dot={false} strokeWidth={1.5} isAnimationActive={false} />
              <Line dataKey="p95" stroke="#f5a524" dot={false} strokeWidth={1.5} isAnimationActive={false} />
              <Line dataKey="p99" stroke="#f0565a" dot={false} strokeWidth={1.5} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </section>
        <section className="panel px-4 pb-2 pt-3">
          <div className="mb-2 text-sm font-medium text-ink-100">Requests and errors per minute</div>
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={points}>
              <CartesianGrid stroke="#1c2127" />
              <XAxis dataKey="t" {...axis} minTickGap={24} />
              <YAxis {...axis} width={44} />
              <Tooltip {...tooltip} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <Bar dataKey="requests" stackId="a" fill="#3a424c" isAnimationActive={false} />
              <Bar dataKey="errors" fill="#f0565a" isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </section>
      </div>

      <section className="panel">
        <div className="border-b border-ink-800 px-4 py-3 text-sm font-medium text-ink-100">Operations <span className="text-xs font-normal text-ink-500">(click to chart)</span></div>
        {(ops.data ?? []).length === 0 ? <Empty>No requests in this window.</Empty> : (
          <table className="w-full">
            <thead><tr>
              <th className="th">Operation</th><th className="th text-right">Requests</th><th className="th text-right">Errors</th>
              <th className="th text-right">Avg</th><th className="th text-right">p50</th><th className="th text-right">p95</th>
              <th className="th text-right">p99</th><th className="th text-right">Max</th><th className="th" />
            </tr></thead>
            <tbody>
              {ops.data!.map((o) => (
                <tr key={o.operation} onClick={() => setOperation(operation === o.operation ? '' : o.operation)}
                    className={`cursor-pointer ${operation === o.operation ? 'bg-ink-800' : 'hover:bg-ink-850'}`}>
                  <td className="td text-ink-100">{o.operation}</td>
                  <td className="td mono text-right">{o.stats.requests.toLocaleString()}</td>
                  <td className="td mono text-right" style={{ color: errorColor(o.stats.errorRate) }}>{fmtPct(o.stats.errorRate)}</td>
                  <td className="td mono text-right">{fmtDuration(o.stats.avgUs)}</td>
                  <td className="td mono text-right">{fmtDuration(o.stats.p50Us)}</td>
                  <td className="td mono text-right">{fmtDuration(o.stats.p95Us)}</td>
                  <td className="td mono text-right">{fmtDuration(o.stats.p99Us)}</td>
                  <td className="td mono text-right">{fmtDuration(o.stats.maxUs)}</td>
                  <td className="td text-right">
                    <Link onClick={(e) => e.stopPropagation()} className="text-xs text-ink-400 hover:text-signal"
                          to={`/traces?service=${encodeURIComponent(service)}&minDurationMs=${Math.round(o.stats.p95Us / 1000)}`}>
                      slow traces →
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
