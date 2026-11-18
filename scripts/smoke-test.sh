#!/usr/bin/env bash
# End-to-end smoke test (macOS/Linux/Git Bash). Needs curl and jq. Run after `docker compose up -d --build`.
set -euo pipefail
GW=http://localhost:8081; PL=http://localhost:8080
echo "==> health";   curl -fsS $PL/actuator/health | jq -r .status
echo "==> causing a payment failure"
curl -fsS -X POST $PL/api/chaos/payment-service -H 'Content-Type: application/json' -d '{"latencyMs":0,"errorRate":1.0}' >/dev/null
curl -s -o /dev/null -w "gateway answered %{http_code}\n" -X POST $GW/api/checkout -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: smoke-$RANDOM" -d '{"customerId":"smoke","items":[{"sku":"SKU-100","qty":1}]}'
curl -fsS -X DELETE $PL/api/chaos/payment-service >/dev/null
echo "==> waiting for the tail-sampling decision (18 s)"; sleep 18
TRACE=$(curl -fsS "$PL/api/traces?status=error&lookbackMinutes=5&limit=1" | jq -r '.[0].traceId')
[ "$TRACE" != "null" ] || { echo "FAIL: no error trace"; exit 1; }
echo "==> trace $TRACE"
curl -fsS $PL/api/traces/$TRACE | jq '{services: .trace.services, rootCause: .trace.rootCause, bottleneck: .trace.bottleneck, kept: .samplingReason}'
echo "==> dependencies"; curl -fsS "$PL/api/dependencies?windowMinutes=15" | jq -r '.edges[] | "\(.caller) -> \(.callee): \(.calls) calls"'
echo "PASS — open http://localhost:3000/traces/$TRACE"
