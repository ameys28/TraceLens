import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../lib/api';
import { fmtDuration, fmtTime, serviceColor } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Badge, Empty, ErrorBox, PageHeader, ServiceDot, reasonTone } from '../components/ui';

export default function Traces() {
  const [params, setParams] = useSearchParams();
  const [form, setForm] = useState({
    service: params.get('service') ?? '',
    operation: params.get('operation') ?? '',
    status: params.get('status') ?? '',
    minDurationMs: params.get('minDurationMs') ?? '',
    lookbackMinutes: params.get('lookbackMinutes') ?? '60',
  });
  const [lookup, setLookup] = useState('');
  const navigate = useNavigate();
  const query = useMemo(() => Object.fromEntries(params.entries()), [params]);

  const services = usePoll(() => api.services(1440), 0);
  const traces = usePoll(
    () => api.traces({
      service: query.service, operation: query.operation, status: query.status,
      minDurationMs: query.minDurationMs ? Number(query.minDurationMs) : '',
      lookbackMinutes: Number(query.lookbackMinutes ?? 60), limit: 100,
    }),
    10000, [JSON.stringify(query)],
  );

  const apply = () => {
    const next: Record<string, string> = {};
    Object.entries(form).forEach(([k, v]) => { if (v) next[k] = v; });
    setParams(next);
  };
  const maxDur = Math.max(1, ...(traces.data ?? []).map((t) => t.durationUs));

  return (
    <>
      <PageHeader
        title="Traces"
        subtitle="Sampled traces: every error, every trace slower than its operation's p99, plus a baseline share of the rest."
        right={
          <form
            onSubmit={(e) => { e.preventDefault(); if (lookup.trim()) navigate(`/traces/${lookup.trim().toLowerCase()}`); }}
            className="flex gap-2"
          >
            <input className="input mono w-80" placeholder="Jump to trace ID…" value={lookup} onChange={(e) => setLookup(e.target.value)} />
          </form>
        }
      />

      <div className="panel mb-4 flex flex-wrap items-end gap-3 px-4 py-3">
        <label className="flex flex-col gap-1">
          <span className="label">Service</span>
          <select className="input" value={form.service} onChange={(e) => setForm({ ...form, service: e.target.value })}>
            <option value="">Any</option>
            {(services.data ?? []).map((s) => <option key={s.service}>{s.service}</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="label">Root operation</span>
          <input className="input w-56" placeholder="e.g. checkout" value={form.operation} onChange={(e) => setForm({ ...form, operation: e.target.value })} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="label">Status</span>
          <select className="input" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}>
            <option value="">Any</option><option value="error">Error</option><option value="ok">OK</option>
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="label">Min duration (ms)</span>
          <input className="input w-32" type="number" min={0} value={form.minDurationMs} onChange={(e) => setForm({ ...form, minDurationMs: e.target.value })} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="label">Lookback</span>
          <select className="input" value={form.lookbackMinutes} onChange={(e) => setForm({ ...form, lookbackMinutes: e.target.value })}>
            <option value="15">15 min</option><option value="60">1 hour</option><option value="360">6 hours</option><option value="1440">24 hours</option><option value="10080">7 days</option>
          </select>
        </label>
        <button className="btn-primary" onClick={apply}>Search</button>
      </div>

      <ErrorBox error={traces.error} />
      <div className="panel overflow-hidden">
        {(traces.data ?? []).length === 0 ? (
          <Empty>{traces.loading ? 'Loading…' : 'No traces match. Traces appear ~10 s after a request finishes (tail-sampling decision window).'}</Empty>
        ) : (
          <table className="w-full">
            <thead>
              <tr>
                <th className="th">Time</th><th className="th">Root operation</th><th className="th">Services</th>
                <th className="th w-1/4">Duration</th><th className="th text-right">Spans</th><th className="th">Kept because</th>
              </tr>
            </thead>
            <tbody>
              {traces.data!.map((t) => (
                <tr key={t.traceId + t.startEpochMs} className="hover:bg-ink-850">
                  <td className="td mono whitespace-nowrap text-xs text-ink-400">{fmtTime(t.startEpochMs)}</td>
                  <td className="td">
                    <Link to={`/traces/${t.traceId}`} className="text-ink-100 hover:text-signal">
                      <span className="mr-1.5 text-ink-500">{t.rootService}</span>{t.rootOperation}
                    </Link>
                    {t.errorCount > 0 && <span className="ml-2"><Badge tone="bad">{t.errorCount} error{t.errorCount > 1 ? 's' : ''}</Badge></span>}
                  </td>
                  <td className="td">
                    <div className="flex gap-1">{t.services.map((s) => <span key={s} title={s}><ServiceDot color={serviceColor(s)} /></span>)}</div>
                  </td>
                  <td className="td">
                    <div className="flex items-center gap-2">
                      <div className="h-1.5 flex-1 rounded bg-ink-800">
                        <div className={`h-1.5 rounded ${t.errorCount ? 'bg-bad' : 'bg-ink-400'}`} style={{ width: `${Math.max(2, (t.durationUs / maxDur) * 100)}%` }} />
                      </div>
                      <span className="mono w-16 text-right text-xs">{fmtDuration(t.durationUs)}</span>
                    </div>
                  </td>
                  <td className="td mono text-right text-xs">{t.spanCount}</td>
                  <td className="td"><Badge tone={reasonTone(t.samplingReason)}>{t.samplingReason}</Badge></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
