// Typed client for the TraceLens platform API.

export interface LatencyStats {
  requests: number; errors: number; errorRate: number; rps: number;
  avgUs: number; p50Us: number; p95Us: number; p99Us: number; maxUs: number;
}
export interface ServiceStats { service: string; stats: LatencyStats }
export interface OperationStats { service: string; operation: string; stats: LatencyStats }
export interface TimePoint { minuteEpochMs: number; requests: number; errors: number; p50Us: number; p95Us: number; p99Us: number }
export interface TraceSummary {
  traceId: string; startEpochMs: number; durationUs: number; rootService: string; rootOperation: string;
  spanCount: number; errorCount: number; services: string[]; samplingReason: string;
}
export interface SpanEvent { name: string; timeNanos: number; attributes: Record<string, unknown> }
export interface AssembledSpan {
  spanId: string; parentSpanId: string; serviceName: string; name: string; kind: string;
  startOffsetMicros: number; durationMicros: number; selfTimeMicros: number; depth: number;
  statusCode: string; statusMessage: string; attributes: Record<string, unknown>; events: SpanEvent[];
  onCriticalPath: boolean; criticalPathMicros: number; skewAdjustmentMicros: number; orphan: boolean;
}
export interface AssembledTrace {
  traceId: string; startEpochMicros: number; durationMicros: number; spanCount: number; errorCount: number;
  rootService: string; rootOperation: string; services: string[]; spans: AssembledSpan[];
  rootCause: { spanId: string; serviceName: string; operation: string; exceptionType: string | null; message: string } | null;
  bottleneck: { spanId: string; serviceName: string; operation: string; criticalPathMicros: number; shareOfTrace: number } | null;
}
export interface Edge { caller: string; callee: string; calls: number; errors: number; errorRate: number; avgUs: number }
export interface DependencyGraph { nodes: { id: string; stats: LatencyStats | null }[]; edges: Edge[] }
export interface Alert {
  id: number; alertKey: string; type: string; severity: string; service: string; operation: string; message: string;
  observed: number; baseline: number; exampleTraceId: string | null; topError: string | null; status: string;
  openedAtMs: number; lastSeenAtMs: number; resolvedAtMs: number | null;
}
export interface SamplingInfo {
  totals: { reason: string; traces: number; spans: number }[];
  series: { minuteEpochMs: number; reason: string; traces: number }[];
  tracesSeen: number; tracesKept: number; spansSeen: number; spansKept: number; storageReduction: number;
  pendingTraces: number; learnedThresholds: number;
  partitions: { name: string; bytes: number; estimatedRows: number }[];
}
export interface ChaosRow { service: string; reachable: boolean; latencyMs?: number; errorRate?: number }

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init });
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try { const body = await res.json(); if (body?.error) msg = body.error; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

const qs = (params: Record<string, string | number | undefined | null>) => {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== '') p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : '';
};

export const api = {
  services: (windowMinutes = 15) => req<ServiceStats[]>(`/api/services${qs({ windowMinutes })}`),
  operations: (service: string, windowMinutes = 15) =>
    req<OperationStats[]>(`/api/services/${encodeURIComponent(service)}/operations${qs({ windowMinutes })}`),
  allOperations: (windowMinutes = 15) => req<OperationStats[]>(`/api/operations${qs({ windowMinutes })}`),
  timeseries: (service: string, operation?: string, windowMinutes = 60) =>
    req<TimePoint[]>(`/api/services/${encodeURIComponent(service)}/timeseries${qs({ operation, windowMinutes })}`),
  traces: (f: { service?: string; operation?: string; status?: string; minDurationMs?: number | ''; lookbackMinutes?: number; limit?: number }) =>
    req<TraceSummary[]>(`/api/traces${qs(f as Record<string, string | number>)}`),
  trace: (id: string) => req<{ trace: AssembledTrace; samplingReason: string }>(`/api/traces/${id}`),
  dependencies: (windowMinutes = 15) => req<DependencyGraph>(`/api/dependencies${qs({ windowMinutes })}`),
  alerts: (status = 'ALL') => req<Alert[]>(`/api/alerts${qs({ status })}`),
  evaluateAlerts: () => req<Alert[]>(`/api/alerts/evaluate`, { method: 'POST' }),
  sampling: (windowMinutes = 60) => req<SamplingInfo>(`/api/sampling${qs({ windowMinutes })}`),
  chaos: () => req<ChaosRow[]>(`/api/chaos`),
  setChaos: (service: string, latencyMs: number, errorRate: number) =>
    req<unknown>(`/api/chaos/${encodeURIComponent(service)}`, { method: 'POST', body: JSON.stringify({ latencyMs, errorRate }) }),
  resetChaos: () => req<ChaosRow[]>(`/api/chaos`, { method: 'DELETE' }),
};
