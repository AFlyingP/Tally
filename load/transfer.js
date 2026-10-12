import http from 'k6/http';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL;
const token = __ENV.TOKEN;
const workload = __ENV.WORKLOAD;
const runId = __ENV.RUN_ID;
const accounts = JSON.parse(open('/data/accounts.json'));

if (workload !== 'uniform' && workload !== 'hot') {
  throw new Error(`unknown workload: ${workload}`);
}

export const options = {
  vus: Number(__ENV.VUS),
  duration: __ENV.DURATION + 's',
  summaryTrendStats: ['p(50)', 'p(99)'],
  discardResponseBodies: false,
};

const posted = new Counter('posted');
const insufficient = new Counter('insufficient');
const exhausted = new Counter('exhausted');
const unexpected = new Counter('unexpected');
const retries = new Counter('retries');

export default function () {
  const source =
    workload === 'hot' && Math.random() < 0.8
      ? accounts[Math.floor(Math.random() * 10)]
      : accounts[Math.floor(Math.random() * accounts.length)];
  let destination;
  do {
    destination = accounts[Math.floor(Math.random() * accounts.length)];
  } while (destination === source);

  const response = http.post(
    `${baseUrl}/v1/transfers`,
    JSON.stringify({
      source_account_id: source,
      destination_account_id: destination,
      amount: Math.floor(Math.random() * 2000) + 1,
      currency: 'EUR',
    }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `${runId}-${__VU}-${__ITER}`,
      },
    },
  );

  const attempts = response.headers['X-Tally-Attempts'];
  if (attempts !== undefined) {
    retries.add(Number.parseInt(attempts, 10) - 1);
  }

  if (response.status === 201) {
    posted.add(1);
  } else if (response.status === 422) {
    insufficient.add(1);
  } else if (response.status === 503) {
    exhausted.add(1);
  } else {
    unexpected.add(1);
  }
}

export function handleSummary(data) {
  const count = (name) => data.metrics[name]?.values.count ?? 0;
  const duration = data.metrics.http_req_duration.values;
  return {
    '/data/summary.json': JSON.stringify({
      posted: count('posted'),
      insufficient: count('insufficient'),
      exhausted: count('exhausted'),
      unexpected: count('unexpected'),
      retries: count('retries'),
      p50_ms: duration['p(50)'],
      p99_ms: duration['p(99)'],
      duration_s: data.state.testRunDurationMs / 1000,
    }),
  };
}
