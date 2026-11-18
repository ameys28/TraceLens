import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../lib/api';
import { fmtAgo, fmtDuration, fmtPct } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Badge, Empty, ErrorBox, PageHeader } from '../components/ui';

export default function Alerts() {
  const [status, setStatus] = useState('ALL');
  const [busy, setBusy] = useState(false);
  const { data, error, reload } = usePoll(() => api.alerts(status), 10000, [status]);

  const evaluate = async () => {
    setBusy(true);
    try { await api.evaluateAlerts(); await reload(); } finally { setBusy(false); }
  };

  return (
    <>
      <PageHeader
        title="Alerts"
        subtitle="Every 30 s each operation's last 5 minutes are compared with the hour before: error-rate spikes and p95 regressions."
        right={<>
          <select className="input" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ALL">All</option><option value="OPEN">Open</option><option value="RESOLVED">Resolved</option>
          </select>
          <button className="btn" disabled={busy} onClick={evaluate}>{busy ? 'Evaluating…' : 'Evaluate now'}</button>
        </>}
      />
      <ErrorBox error={error} />
      <div className="panel">
        {(data ?? []).length === 0 ? <Empty>No alerts. Try the Fault injection page to cause one.</Empty> : (
          <ul>
            {data!.map((a) => (
              <li key={a.id} className="flex items-start gap-4 border-t border-ink-800 px-4 py-3 first:border-t-0">
                <div className="flex w-28 shrink-0 flex-col gap-1">
                  <Badge tone={a.status === 'OPEN' ? (a.severity === 'CRITICAL' ? 'bad' : 'signal') : 'good'}>
                    {a.status === 'OPEN' ? a.severity : 'RESOLVED'}
                  </Badge>
                  <span className="text-[11px] text-ink-500">{a.type === 'LATENCY' ? 'Latency regression' : 'Error spike'}</span>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-sm text-ink-100">{a.message}</div>
                  <div className="mono mt-1 text-xs text-ink-400">
                    observed {a.type === 'LATENCY' ? fmtDuration(a.observed) : fmtPct(a.observed)} · baseline{' '}
                    {a.type === 'LATENCY' ? fmtDuration(a.baseline) : fmtPct(a.baseline)}
                  </div>
                  {a.topError && <div className="mono mt-1 text-xs text-bad">top error: {a.topError}</div>}
                </div>
                <div className="flex shrink-0 flex-col items-end gap-1 text-xs text-ink-500">
                  <span>opened {fmtAgo(a.openedAtMs)}</span>
                  {a.resolvedAtMs ? <span>resolved {fmtAgo(a.resolvedAtMs)}</span> : <span>seen {fmtAgo(a.lastSeenAtMs)}</span>}
                  {a.exampleTraceId && <Link to={`/traces/${a.exampleTraceId}`} className="text-signal hover:underline">example trace →</Link>}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
}
