import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type ChaosRow } from '../lib/api';
import { serviceColor } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Badge, ErrorBox, PageHeader, ServiceDot } from '../components/ui';

const SCENARIOS: { name: string; description: string; settings: Record<string, [number, number]> }[] = [
  { name: 'Payment outage', description: '40% of payment calls fail → error-rate alert, failing traces kept', settings: { 'payment-service': [0, 0.4] } },
  { name: 'Slow inventory DB', description: '+400 ms in inventory → latency alert, bottleneck moves to inventory', settings: { 'inventory-service': [400, 0] } },
  { name: 'Flaky notifications', description: 'Async consumer fails 50% → errors after the response, not on the critical path', settings: { 'notification-service': [0, 0.5] } },
  { name: 'Brownout', description: 'Gateway +150 ms and order 10% errors at the same time', settings: { gateway: [150, 0], 'order-service': [0, 0.1] } },
];

export default function Chaos() {
  const { data, error, reload } = usePoll(() => api.chaos(), 5000);
  const [draft, setDraft] = useState<Record<string, { latencyMs: number; errorRate: number }>>({});
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!data) return;
    setDraft((prev) => {
      const next = { ...prev };
      data.forEach((r) => { if (!next[r.service]) next[r.service] = { latencyMs: r.latencyMs ?? 0, errorRate: r.errorRate ?? 0 }; });
      return next;
    });
  }, [data]);

  const apply = async (service: string, latencyMs: number, errorRate: number) => {
    await api.setChaos(service, latencyMs, errorRate);
    setDraft((d) => ({ ...d, [service]: { latencyMs, errorRate } }));
  };
  const runScenario = async (s: (typeof SCENARIOS)[number]) => {
    await api.resetChaos();
    for (const [svc, [lat, err]] of Object.entries(s.settings)) await apply(svc, lat, err);
    setMsg(`"${s.name}" is running. Alerts fire after ~1–2 minutes of bad data.`);
    reload();
  };
  const resetAll = async () => {
    await api.resetChaos();
    setDraft({});
    setMsg('All faults cleared. Open alerts resolve on the next evaluations.');
    reload();
  };

  return (
    <>
      <PageHeader
        title="Fault injection"
        subtitle="Inject latency and errors into the demo services, then watch traces, the service map and alerts react."
        right={<button className="btn" onClick={resetAll}>Reset all</button>}
      />
      <ErrorBox error={error} />
      {msg && <div className="mb-4 rounded-md border border-signal/40 bg-signal-soft px-3 py-2 text-sm text-signal">{msg} <Link to="/alerts" className="underline">Alerts →</Link></div>}

      <div className="mb-6 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        {SCENARIOS.map((s) => (
          <button key={s.name} onClick={() => runScenario(s)} className="panel px-4 py-3 text-left hover:border-signal">
            <div className="text-sm font-medium text-ink-100">{s.name}</div>
            <div className="mt-1 text-xs text-ink-400">{s.description}</div>
          </button>
        ))}
      </div>

      <div className="panel">
        <table className="w-full">
          <thead><tr>
            <th className="th">Service</th><th className="th">Added latency</th><th className="th">Error rate</th><th className="th">Live</th><th className="th" />
          </tr></thead>
          <tbody>
            {(data ?? []).map((r: ChaosRow) => {
              const d = draft[r.service] ?? { latencyMs: 0, errorRate: 0 };
              const active = (r.latencyMs ?? 0) > 0 || (r.errorRate ?? 0) > 0;
              return (
                <tr key={r.service}>
                  <td className="td"><span className="flex items-center gap-2 text-ink-100"><ServiceDot color={serviceColor(r.service)} />{r.service}</span></td>
                  <td className="td">
                    <div className="flex items-center gap-3">
                      <input type="range" min={0} max={2000} step={50} value={d.latencyMs} disabled={!r.reachable}
                             onChange={(e) => setDraft({ ...draft, [r.service]: { ...d, latencyMs: Number(e.target.value) } })} />
                      <span className="mono w-16 text-xs">{d.latencyMs} ms</span>
                    </div>
                  </td>
                  <td className="td">
                    <div className="flex items-center gap-3">
                      <input type="range" min={0} max={1} step={0.05} value={d.errorRate} disabled={!r.reachable}
                             onChange={(e) => setDraft({ ...draft, [r.service]: { ...d, errorRate: Number(e.target.value) } })} />
                      <span className="mono w-12 text-xs">{Math.round(d.errorRate * 100)}%</span>
                    </div>
                  </td>
                  <td className="td">
                    {!r.reachable ? <Badge>unreachable</Badge> : active
                      ? <Badge tone="bad">{r.latencyMs ? `+${r.latencyMs}ms ` : ''}{r.errorRate ? `${Math.round((r.errorRate ?? 0) * 100)}% err` : ''}</Badge>
                      : <Badge tone="good">healthy</Badge>}
                  </td>
                  <td className="td text-right">
                    <button className="btn" disabled={!r.reachable} onClick={async () => { await apply(r.service, d.latencyMs, d.errorRate); reload(); }}>Apply</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
