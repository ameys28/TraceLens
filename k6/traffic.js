// Steady, realistic background traffic against the demo gateway.
// Mix: checkouts (most), product lookups, sold-out items (409), huge orders (payment declined).
import http from 'k6/http';
import { sleep } from 'k6';

const GATEWAY = __ENV.GATEWAY_URL || 'http://localhost:8081';
const RATE = parseInt(__ENV.RATE || '5', 10);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '24h',
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
  thresholds: {},
};

const SKUS = ['SKU-100', 'SKU-200', 'SKU-300', 'SKU-400', 'SKU-500'];
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

export function setup() {
  // Wait for the gateway to come up.
  for (let i = 0; i < 60; i++) {
    const r = http.get(`${GATEWAY}/actuator/health`, { tags: { name: 'health' } });
    if (r.status === 200) return;
    sleep(2);
  }
}

export default function () {
  const roll = Math.random();
  const headers = { 'Content-Type': 'application/json' };
  if (roll < 0.15) {
    http.get(`${GATEWAY}/api/products/${pick(SKUS)}`, { tags: { name: 'product' } });
    return;
  }
  let sku = pick(SKUS);
  let amount = Math.round(100 + Math.random() * 4000);
  if (roll > 0.97) sku = 'SOLD-OUT';
  else if (roll > 0.95) amount = 25000; // payment declined
  const body = JSON.stringify({
    customerId: `cust-${Math.floor(Math.random() * 500)}`,
    items: [{ sku, qty: 1 + Math.floor(Math.random() * 3) }],
    amount,
  });
  http.post(`${GATEWAY}/api/checkout`, body, { headers, tags: { name: 'checkout' } });
}
