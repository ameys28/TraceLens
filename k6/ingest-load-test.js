// Ingestion load test: posts synthetic OTLP/JSON batches straight to the TraceLens
// ingest API and reports spans/sec. Run:
//   docker compose --profile load run --rm k6-load
// Tune with -e PEAK_RPS=80 -e SPANS_PER_TRACE=6 -e TRACES_PER_BATCH=20
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import crypto from 'k6/crypto';

const PLATFORM = __ENV.PLATFORM_URL || 'http://localhost:8080';
const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '40', 10);
const SPANS_PER_TRACE = parseInt(__ENV.SPANS_PER_TRACE || '6', 10);
const TRACES_PER_BATCH = parseInt(__ENV.TRACES_PER_BATCH || '20', 10);

const spansSent = new Counter('spans_sent');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 200,
      stages: [
        { target: PEAK_RPS, duration: '30s' },
        { target: PEAK_RPS, duration: '60s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

function hex(bytes) {
  return Array.from(new Uint8Array(crypto.randomBytes(bytes)))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

const SERVICES = ['load-gateway', 'load-orders', 'load-inventory', 'load-payments', 'load-notify', 'load-search'];

function attr(key, value) {
  return typeof value === 'number'
    ? { key, value: { intValue: String(value) } }
    : { key, value: { stringValue: String(value) } };
}

function makeTrace() {
  const traceId = hex(16);
  const nowUs = Date.now() * 1000; // micros fit safely in a JS number
  const spansByService = {};
  let parent = '';
  let start = nowUs;
  const isError = Math.random() < 0.02;
  for (let i = 0; i < SPANS_PER_TRACE; i++) {
    const svc = SERVICES[i % SERVICES.length];
    const spanId = hex(8);
    const durMs = Math.max(1, Math.round((SPANS_PER_TRACE - i) * (5 + Math.random() * 20)));
    const end = start + durMs * 1000;
    const span = {
      traceId,
      spanId,
      parentSpanId: parent,
      name: i === 0 ? 'POST /api/checkout' : `op-${i}`,
      kind: i === 0 ? 2 : 3,
      startTimeUnixNano: `${start}000`,
      endTimeUnixNano: `${end}000`,
      attributes: [attr('http.request.method', 'POST'), attr('load.test', 'true')],
      status: isError && i === SPANS_PER_TRACE - 1 ? { code: 2, message: 'synthetic failure' } : {},
    };
    (spansByService[svc] = spansByService[svc] || []).push(span);
    parent = spanId;
    start = start + 1000; // children start 1ms after parent
  }
  return Object.entries(spansByService).map(([svc, spans]) => ({
    resource: { attributes: [attr('service.name', svc)] },
    scopeSpans: [{ scope: { name: 'k6' }, spans }],
  }));
}

export default function () {
  let resourceSpans = [];
  for (let t = 0; t < TRACES_PER_BATCH; t++) resourceSpans = resourceSpans.concat(makeTrace());
  const res = http.post(`${PLATFORM}/v1/traces`, JSON.stringify({ resourceSpans }), {
    headers: { 'Content-Type': 'application/json' },
  });
  const ok = check(res, { 'status is 200': (r) => r.status === 200 });
  if (ok) spansSent.add(TRACES_PER_BATCH * SPANS_PER_TRACE);
}
