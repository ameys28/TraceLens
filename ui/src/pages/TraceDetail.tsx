import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, type AssembledSpan } from '../lib/api';
import { fmtDuration, fmtPct, serviceColor } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Badge, ErrorBox, PageHeader, ServiceDot, reasonTone } from '../components/ui';

const KIND_LABEL: Record<string, string> = { SERVER: 'server', CLIENT: 'client', PRODUCER: 'producer', CONSUMER: 'consumer', INTERNAL: 'internal' };

export default function TraceDetail() {
  const { traceId = '' } = useParams();
  const { data, error, loading } = usePoll(() => api.trace(traceId), 0, [traceId]);
  const [selected, setSelected] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [criticalOnly, setCriticalOnly] = useState(false);

  const trace = data?.trace;
  const childrenOf = useMemo(() => {
    const m = new Map<string, string[]>();
    trace?.spans.forEach((s) => {
      if (!s.parentSpanId) return;
      m.set(s.parentSpanId, [...(m.get(s.parentSpanId) ?? []), s.spanId]);
    });
    return m;
  }, [trace]);

  const visible = useMemo(() => {
    if (!trace) return [];
    const hidden = new Set<string>();
    const hide = (id: string) => (childrenOf.get(id) ?? []).forEach((c) => { hidden.add(c); hide(c); });
    collapsed.forEach(hide);
    return trace.spans.filter((s) => !hidden.has(s.spanId) && (!criticalOnly || s.onCriticalPath));
  }, [trace, collapsed, criticalOnly, childrenOf]);

  if (loading && !data) return <div className="text-sm text-ink-500">Loading trace…</div>;
  if (error || !trace) {
    return (
      <>
        <PageHeader title="Trace" subtitle={<span className="mono">{traceId}</span>} />
        <ErrorBox error={error ?? 'not found'} />
        <Link to="/traces" className="text-sm text-signal">← Back to traces</Link>
      </>
    );
  }

  const total = Math.max(1, trace.durationMicros);
  const sel = trace.spans.find((s) => s.spanId === selected) ?? null;
  const skewed = trace.spans.filter((s) => s.skewAdjustmentMicros !== 0).length;
  const orphans = trace.spans.filter((s) => s.orphan).length;
  const toggle = (id: string) => setCollapsed((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <>
      <PageHeader
        title={`${trace.rootService} · ${trace.rootOperation}`}
        subtitle={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="mono text-ink-500">{trace.traceId}</span>
            <span>{new Date(trace.startEpochMicros / 1000).toLocaleString()}</span>
            <Badge tone={reasonTone(data!.samplingReason)}>kept: {data!.samplingReason}</Badge>
          </span>
        }
        right={<Link to="/traces" className="btn">← Traces</Link>}
      />

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Fact label="Duration" value={fmtDuration(trace.durationMicros)} />
        <Fact label="Spans" value={trace.spanCount} />
        <Fact label="Services" value={trace.services.length} />
        <Fact label="Errors" value={trace.errorCount} tone={trace.errorCount ? 'bad' : undefined} />
        <Fact label="Clock-skew fixes" value={skewed} hint={orphans ? `${orphans} orphan span${orphans > 1 ? 's' : ''}` : undefined} />
      </div>

      <div className="mb-4 grid gap-3 lg:grid-cols-2">
        {trace.rootCause && (
          <button onClick={() => setSelected(trace.rootCause!.spanId)} className="panel border-bad/40 px-4 py-3 text-left hover:border-bad">
            <div className="label text-bad">Probable root cause</div>
            <div className="mt-1 text-sm text-ink-100">
              <span className="font-medium">{trace.rootCause.serviceName}</span> · {trace.rootCause.operation}
            </div>
            <div className="mono mt-1 text-xs text-ink-300">
              {trace.rootCause.exceptionType && <span className="text-bad">{trace.rootCause.exceptionType.split('.').pop()}: </span>}
              {trace.rootCause.message || 'error status without message'}
            </div>
          </button>
        )}
        {trace.bottleneck && (
          <button onClick={() => setSelected(trace.bottleneck!.spanId)} className="panel border-signal/40 px-4 py-3 text-left hover:border-signal">
            <div className="label text-signal">Bottleneck on the critical path</div>
            <div className="mt-1 text-sm text-ink-100">
              <span className="font-medium">{trace.bottleneck.serviceName}</span> · {trace.bottleneck.operation}
            </div>
            <div className="mono mt-1 text-xs text-ink-300">
              {fmtDuration(trace.bottleneck.criticalPathMicros)} of its own time on the critical path ({fmtPct(trace.bottleneck.shareOfTrace, 0)} of the request)
            </div>
          </button>
        )}
      </div>

      <div className="flex gap-4">
        <section className="panel min-w-0 flex-1 overflow-hidden">
          <div className="flex items-center justify-between border-b border-ink-800 px-4 py-2.5">
            <div className="flex items-center gap-4 text-xs text-ink-400">
              <span className="flex items-center gap-1.5"><span className="h-2.5 w-4 rounded-sm border-2 border-signal" /> critical path</span>
              <span className="flex items-center gap-1.5"><span className="h-2.5 w-4 rounded-sm bg-bad" /> error</span>
            </div>
            <label className="flex cursor-pointer items-center gap-2 text-xs text-ink-300">
              <input type="checkbox" checked={criticalOnly} onChange={(e) => setCriticalOnly(e.target.checked)} />
              Critical path only
            </label>
          </div>
          <div className="relative flex border-b border-ink-800 text-[10px] text-ink-500">
            <div className="w-[38%] shrink-0 px-4 py-1">Service · operation</div>
            <div className="relative flex-1 py-1">
              {[0, 0.25, 0.5, 0.75, 1].map((f) => (
                <span key={f} className={`mono absolute ${f === 1 ? '-translate-x-full pr-2' : '-translate-x-1/2'}`} style={{ left: `${f * 100}%` }}>
                  {f === 0 ? '' : fmtDuration(total * f)}
                </span>
              ))}
            </div>
          </div>
          <div>
            {visible.map((s) => (
              <Row
                key={s.spanId}
                span={s}
                total={total}
                selected={s.spanId === selected}
                hasChildren={(childrenOf.get(s.spanId) ?? []).length > 0}
                collapsed={collapsed.has(s.spanId)}
                onToggle={() => toggle(s.spanId)}
                onSelect={() => setSelected(s.spanId === selected ? null : s.spanId)}
              />
            ))}
          </div>
        </section>
        {sel && <SpanPanel span={sel} traceId={trace.traceId} onClose={() => setSelected(null)} />}
      </div>
    </>
  );
}

function Fact({ label, value, tone, hint }: { label: string; value: React.ReactNode; tone?: 'bad'; hint?: string }) {
  return (
    <div className="panel px-4 py-2.5">
      <div className="label">{label}</div>
      <div className={`mono mt-0.5 text-lg ${tone === 'bad' ? 'text-bad' : 'text-ink-100'}`}>{value}</div>
      {hint && <div className="text-[11px] text-ink-500">{hint}</div>}
    </div>
  );
}

function Row({ span, total, selected, hasChildren, collapsed, onToggle, onSelect }: {
  span: AssembledSpan; total: number; selected: boolean; hasChildren: boolean; collapsed: boolean;
  onToggle: () => void; onSelect: () => void;
}) {
  const left = (span.startOffsetMicros / total) * 100;
  const width = Math.max(0.3, (span.durationMicros / total) * 100);
  const color = serviceColor(span.serviceName);
  const error = span.statusCode === 'ERROR';
  return (
    <div
      onClick={onSelect}
      className={`group flex cursor-pointer items-stretch border-b border-ink-850 text-sm ${selected ? 'bg-ink-800' : 'hover:bg-ink-850'}`}
    >
      <div className="flex w-[38%] shrink-0 items-center gap-1.5 overflow-hidden py-1 pr-2" style={{ paddingLeft: 12 + span.depth * 14 }}>
        <button
          onClick={(e) => { e.stopPropagation(); if (hasChildren) onToggle(); }}
          className={`w-3 text-[10px] text-ink-500 ${hasChildren ? 'hover:text-ink-200' : 'invisible'}`}
          aria-label={collapsed ? 'expand' : 'collapse'}
        >
          {collapsed ? '▸' : '▾'}
        </button>
        <span className="h-3.5 w-1 shrink-0 rounded-sm" style={{ background: color }} />
        <span className="shrink-0 text-xs text-ink-400">{span.serviceName}</span>
        <span className={`truncate ${error ? 'text-bad' : 'text-ink-100'}`}>{span.name}</span>
        {span.orphan && <Badge>orphan</Badge>}
      </div>
      <div className="relative flex-1 py-1.5">
        <div className="absolute inset-y-0 left-1/4 w-px bg-ink-850" />
        <div className="absolute inset-y-0 left-1/2 w-px bg-ink-850" />
        <div className="absolute inset-y-0 left-3/4 w-px bg-ink-850" />
        <div
          className={`absolute top-1.5 h-4 rounded-sm ${span.onCriticalPath ? 'ring-2 ring-signal' : ''}`}
          style={{ left: `${left}%`, width: `${width}%`, background: error ? '#f0565a' : color, opacity: span.onCriticalPath || error ? 1 : 0.55 }}
          title={`${fmtDuration(span.durationMicros)} (self ${fmtDuration(span.selfTimeMicros)})`}
        />
        <span
          className="mono absolute top-1.5 whitespace-nowrap px-1.5 text-[11px] leading-4 text-ink-300"
          style={left + width > 82 ? { right: `${100 - left}%` } : { left: `${left + width}%` }}
        >
          {fmtDuration(span.durationMicros)}
        </span>
      </div>
    </div>
  );
}

function SpanPanel({ span, traceId, onClose }: { span: AssembledSpan; traceId: string; onClose: () => void }) {
  const attrs = Object.entries(span.attributes ?? {}).sort(([a], [b]) => a.localeCompare(b));
  return (
    <aside className="panel sticky top-6 h-fit max-h-[calc(100vh-3rem)] w-[380px] shrink-0 overflow-y-auto">
      <div className="flex items-start justify-between border-b border-ink-800 px-4 py-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs text-ink-400">
            <ServiceDot color={serviceColor(span.serviceName)} /> {span.serviceName} · {KIND_LABEL[span.kind] ?? span.kind}
          </div>
          <div className="mt-1 break-words text-sm font-medium text-ink-100">{span.name}</div>
        </div>
        <button onClick={onClose} className="ml-2 text-ink-500 hover:text-ink-200" aria-label="close">✕</button>
      </div>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 px-4 py-3 text-xs">
        <Kv k="Duration" v={fmtDuration(span.durationMicros)} />
        <Kv k="Self time" v={fmtDuration(span.selfTimeMicros)} />
        <Kv k="Starts at" v={`+${fmtDuration(span.startOffsetMicros)}`} />
        <Kv k="Critical path" v={span.onCriticalPath ? fmtDuration(span.criticalPathMicros) : 'no'} />
        <Kv k="Status" v={span.statusCode} tone={span.statusCode === 'ERROR' ? 'bad' : undefined} />
        <Kv k="Skew fix" v={span.skewAdjustmentMicros ? `${span.skewAdjustmentMicros > 0 ? '+' : ''}${fmtDuration(Math.abs(span.skewAdjustmentMicros))}` : '–'} />
      </dl>
      {span.statusMessage && (
        <div className="mx-4 mb-3 rounded border border-bad/30 bg-bad-soft px-2.5 py-2 text-xs text-bad">{span.statusMessage}</div>
      )}
      {span.events.length > 0 && (
        <div className="border-t border-ink-800 px-4 py-3">
          <div className="label mb-2">Events</div>
          {span.events.map((e, i) => (
            <div key={i} className="mb-2 rounded border border-ink-800 bg-ink-850 p-2">
              <div className="text-xs font-medium text-ink-200">{e.name}</div>
              {Object.entries(e.attributes ?? {}).map(([k, v]) => (
                <div key={k} className="mono mt-1 text-[11px]">
                  <span className="text-ink-500">{k}</span>{' '}
                  <span className={`text-ink-300 ${k === 'exception.stacktrace' ? 'block max-h-40 overflow-auto whitespace-pre text-[10px]' : 'break-all'}`}>{String(v)}</span>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
      <div className="border-t border-ink-800 px-4 py-3">
        <div className="label mb-2">Attributes</div>
        {attrs.length === 0 ? <div className="text-xs text-ink-500">none</div> : attrs.map(([k, v]) => (
          <div key={k} className="mono flex gap-2 py-0.5 text-[11px]">
            <span className="w-2/5 shrink-0 break-all text-ink-500">{k}</span>
            <span className="break-all text-ink-200">{typeof v === 'object' ? JSON.stringify(v) : String(v)}</span>
          </div>
        ))}
      </div>
      <div className="border-t border-ink-800 px-4 py-3">
        <div className="label mb-1">Correlated logs</div>
        <p className="mb-1.5 text-[11px] text-ink-400">Every log line carries trace and span IDs:</p>
        <code className="mono block break-all rounded bg-ink-850 p-2 text-[11px] text-ink-300">
          docker compose logs {span.serviceName} | findstr {traceId}
        </code>
      </div>
    </aside>
  );
}

function Kv({ k, v, tone }: { k: string; v: React.ReactNode; tone?: 'bad' }) {
  return (
    <div>
      <dt className="text-ink-500">{k}</dt>
      <dd className={`mono ${tone === 'bad' ? 'text-bad' : 'text-ink-200'}`}>{v}</dd>
    </div>
  );
}
