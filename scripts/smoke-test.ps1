# End-to-end smoke test for Windows PowerShell. Run from the repo root after `docker compose up -d --build`.
#   powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
$ErrorActionPreference = "Stop"
$gateway  = "http://localhost:8081"
$platform = "http://localhost:8080"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "    PASS $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "    FAIL $msg" -ForegroundColor Red; exit 1 }

Step "Platform health"
$h = Invoke-RestMethod "$platform/actuator/health"
if ($h.status -eq "UP") { Ok "platform is UP" } else { Fail "platform status: $($h.status)" }

Step "Send a checkout that will fail (amount over the payment limit -> 402) with our own correlation ID"
$corr = "smoke-" + [guid]::NewGuid().ToString().Substring(0, 8)
$body = '{"customerId":"smoke","items":[{"sku":"SKU-100","qty":1}],"amount":25000}'
try {
  Invoke-WebRequest "$gateway/api/checkout" -Method Post -Body $body -ContentType "application/json" -Headers @{ "X-Correlation-Id" = $corr } -UseBasicParsing | Out-Null
} catch { }
Ok "request sent (correlation id $corr)"

Step "Force a server error so the trace is kept by tail sampling (payment-service 100% errors for 5 s)"
Invoke-RestMethod "$platform/api/chaos/payment-service" -Method Post -Body '{"latencyMs":0,"errorRate":1.0}' -ContentType "application/json" | Out-Null
try {
  Invoke-WebRequest "$gateway/api/checkout" -Method Post -Body '{"customerId":"smoke","items":[{"sku":"SKU-200","qty":1}]}' -ContentType "application/json" -Headers @{ "X-Correlation-Id" = "$corr-err" } -UseBasicParsing | Out-Null
  Fail "expected a 502 from the gateway"
} catch { Ok "gateway returned an error as expected" }
Invoke-RestMethod "$platform/api/chaos/payment-service" -Method Delete | Out-Null

Step "Wait for the tail-sampling decision window (~15 s)"
Start-Sleep -Seconds 18

Step "Find the failed trace via the search API"
$traces = Invoke-RestMethod "$platform/api/traces?status=error&lookbackMinutes=5&limit=20"
if ($traces.Count -lt 1) { Fail "no error traces found" }
$t = $traces[0]
Ok "found trace $($t.traceId): $($t.rootService) $($t.rootOperation), $($t.spanCount) spans, kept because $($t.samplingReason)"

Step "Fetch the reassembled trace"
$detail = Invoke-RestMethod "$platform/api/traces/$($t.traceId)"
$tr = $detail.trace
Ok "services: $($tr.services -join ' -> ')"
if ($tr.rootCause) { Ok "root cause: $($tr.rootCause.serviceName) / $($tr.rootCause.operation): $($tr.rootCause.message)" } else { Fail "no root cause found" }
$critical = ($tr.spans | Where-Object { $_.onCriticalPath }).Count
Ok "$critical of $($tr.spanCount) spans on the critical path"

Step "Metrics and dependency graph"
$deps = Invoke-RestMethod "$platform/api/dependencies?windowMinutes=15"
Ok "$($deps.nodes.Count) services, $($deps.edges.Count) dependency edges"
foreach ($e in $deps.edges) { Write-Host ("      {0,-14} -> {1,-22} {2,6} calls" -f $e.caller, $e.callee, $e.calls) }
$s = Invoke-RestMethod "$platform/api/sampling?windowMinutes=15"
Ok ("sampling kept {0} of {1} traces (span storage saved: {2:P0})" -f $s.tracesKept, $s.tracesSeen, $s.storageReduction)

Write-Host "`nAll checks passed. Open http://localhost:3000/traces/$($t.traceId)" -ForegroundColor Green
