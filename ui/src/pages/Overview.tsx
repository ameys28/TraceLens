import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api';
import { errorColor, fmtAgo, fmtDuration, fmtNum, fmtPct, serviceColor } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Badge, Empty, ErrorBox, PageHeader, ServiceDot, Stat, WindowSelect } from '../components/ui';

export default function Overview() {
  const [win, setWin] = useState(15);
  const services = usePoll(() => api.services(win), 10000, [win]);
  const alerts = usePoll(() => api.alerts('OPEN'), 10000);
  const errors = usePoll(() => api.traces({ status: 'error', lookbackMinutes: win, limit: 8 }), 10000, [win]);
  const sampling = usePoll(() => api.sampling(win), 15000, [win]);

  const list = services.data ?? [];
  const edge = list.find((s) => s.service === 'gateway') ?? null;
  const totalReq = list.reduce((a, s) => a + s.stats.requests, 0);
  const totalErr = list.reduce((a, s) => a + s.stats.errors, 0);

  return (
    <>
      <PageHeader
        title="Overview"
        subtitle="RED metrics per service, computed from every trace before sampling."
        right={<WindowSelect value={win} onChange={setWin} />}
      />
      <ErrorBox error={services.error} />

      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Stat label="Edge throughput" value={edge ? `${edge.stats.rps.toFixed(1)}/s` : '–'} hint="gateway requests" />
        <Stat label="Edge p95" value={fmtDuration(edge?.stats.p95Us)} hint={`p99 ${fmtDuration(edge?.stats.p99Us)}`} />
        <Stat
          label="Error rate (all spans)"
          value={fmtPct(totalReq ? totalErr / totalReq : 0)}
          tone={totalReq && totalErr / totalReq > 0.05 ? 'bad' : undefined}
          hint={`${fmtNum(totalErr)} of ${fmtNum(totalReq)} entry spans`}
        />
        <Stat
          label="Open alerts"
          value={alerts.data?.length ?? '–'}
          tone={(alerts.data?.length ?? 0) > 0 ? 'bad' : 'good'}
        />
        <Stat
          label="Storage saved by sampling"
          value={fmtPct(sampling.data?.storageReduction ?? 0, 0)}
          tone="signal"
          hint={sampling.data ? `${fmtNum(sampling.data.tracesKept)} of ${fmtNum(sampling.data.tracesSeen)} traces kept` : undefined}
        />
      </div>

      <div className="grid gap-6 xl:grid-cols-3">
        <section className="panel xl:col-span-2">
          <div className="border-b border-ink-800 px-4 py-3 text-sm font-medium text-ink-100">Services</div>
          {list.length === 0 ? (
            <Empty>No data yet. Traffic appears about 15 seconds after the stack starts.</Empty>
          ) : (
            <table className="w-full">
              <thead>
                <tr>
                  <th className="th">Service</th><th className="th text-right">Req/s</th><th className="th text-right">Errors</th>
                  <th className="th text-right">p50</th><th className="th text-right">p95</th><th className="th text-right">p99</th>
                </tr>
              </thead>
              <tbody>
                {list.map((s) => (
                  <tr key={s.service} className="hover:bg-ink-850">
                    <td className="td">
                      <Link to={`/services/${encodeURIComponent(s.service)}`} className="flex items-center gap-2 text-ink-100 hover:text-signal">
                        <ServiceDot color={serviceColor(s.service)} /> {s.service}
                      </Link>
                    </td>
                    <td className="td mono text-right">{s.stats.rps.toFixed(2)}</td>
                    <td className="td mono text-right" style={{ color: errorColor(s.stats.errorRate) }}>{fmtPct(s.stats.errorRate)}</td>
                    <td className="td mono text-right">{fmtDuration(s.stats.p50Us)}</td>
                    <td className="td mono text-right">{fmtDuration(s.stats.p95Us)}</td>
                    <td className="td mono text-right">{fmtDuration(s.stats.p99Us)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <div className="flex flex-col gap-6">
          <section className="panel">
            <div className="flex items-center justify-between border-b border-ink-800 px-4 py-3">
              <span className="text-sm font-medium text-ink-100">Open alerts</span>
              <Link to="/alerts" className="text-xs text-ink-400 hover:text-signal">All alerts →</Link>
            </div>
            {(alerts.data ?? []).length === 0 ? (
              <Empty>All quiet.</Empty>
            ) : (
              <ul>
                {alerts.data!.slice(0, 5).map((a) => (
                  <li key={a.id} className="border-t border-ink-800 px-4 py-2.5 first:border-t-0">
                    <div className="flex items-center gap-2">
                      <Badge tone={a.severity === 'CRITICAL' ? 'bad' : 'signal'}>{a.type === 'LATENCY' ? 'Latency' : 'Errors'}</Badge>
                      <span className="text-xs text-ink-500">{fmtAgo(a.openedAtMs)}</span>
                    </div>
                    <div className="mt-1 text-sm text-ink-200">{a.message}</div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="panel">
            <div className="flex items-center justify-between border-b border-ink-800 px-4 py-3">
              <span className="text-sm font-medium text-ink-100">Recent failed requests</span>
              <Link to="/traces?status=error" className="text-xs text-ink-400 hover:text-signal">Search →</Link>
            </div>
            {(errors.data ?? []).length === 0 ? (
              <Empty>No failed traces in this window.</Empty>
            ) : (
              <ul>
                {errors.data!.map((t) => (
                  <li key={t.traceId + t.startEpochMs} className="border-t border-ink-800 first:border-t-0">
                    <Link to={`/traces/${t.traceId}`} className="flex items-center justify-between px-4 py-2 hover:bg-ink-850">
                      <span className="truncate text-sm text-ink-200">{t.rootOperation}</span>
                      <span className="mono ml-3 shrink-0 text-xs text-ink-500">{fmtDuration(t.durationUs)}</span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>
    </>
  );
}
