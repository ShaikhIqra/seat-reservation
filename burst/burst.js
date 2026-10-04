import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_KEY = __ENV.ADMIN_KEY || 'local-admin-key';
const HOT_USERS = Number(__ENV.HOT_USERS || 500);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const confirmed = new Counter('confirmed_201');
const replay    = new Counter('replay_200');
const seatTaken = new Counter('declined_seat_taken');
const overLimit = new Counter('declined_per_user_limit');
const other4xx  = new Counter('other_4xx');
const errors5xx = new Counter('errors_5xx');

export const options = {
    setupTimeout: '600s',
    scenarios: {
        hot_seat: { executor: 'shared-iterations', vus: HOT_USERS, iterations: HOT_USERS, maxDuration: '120s', exec: 'hotSeat' },
        one_user: { executor: 'shared-iterations', vus: 10, iterations: 10, maxDuration: '120s', exec: 'oneUser' },
        retries:  { executor: 'shared-iterations', vus: 20, iterations: 20, maxDuration: '120s', exec: 'retry' },
    },
};

function userTokens(prefix, n) {
    const tokens = [];
    for (let start = 0; start < n; start += 50) {
        let pending = [];
        for (let i = start; i < Math.min(n, start + 50); i++) pending.push(`${prefix}-${i}`);

        for (let attempt = 1; attempt <= 3 && pending.length > 0; attempt++) {
            const responses = http.batch(pending.map((id) =>
                ['POST', `${BASE}/auth/token`, JSON.stringify({ user_id: id }), { headers: JSON_HEADERS }]));
            const failed = [];
            responses.forEach((r, k) => {
                if (r.status === 200) {
                    tokens.push(r.json('token'));
                } else {
                    console.log(`TOKEN FAIL attempt=${attempt} status=${r.status} error=${r.error}`);
                    failed.push(pending[k]);
                }
            });
            pending = failed;
        }
        if (pending.length > 0) throw new Error(`Could not get tokens for ${pending.length} users`);
    }
    return tokens;
}

export function setup() {
    const run = Date.now();

    const admin = http.post(`${BASE}/auth/token`,
        JSON.stringify({ user_id: 'admin', role: 'admin' }),
        { headers: { ...JSON_HEADERS, 'X-Admin-Key': ADMIN_KEY } });
    if (admin.status !== 200) throw new Error(`Admin token failed: ${admin.status} ${admin.body}`);

    const seats = [];
    for (let i = 1; i <= 50; i++) seats.push('A' + i);
    const show = http.post(`${BASE}/shows`,
        JSON.stringify({ name: `burst-${run}`, seats, price_paise: 25000, per_user_limit: 4 }),
        { headers: { ...JSON_HEADERS, Authorization: `Bearer ${admin.json('token')}` } });
    if (show.status !== 201) throw new Error(`Create show failed: ${show.status} ${show.body}`);

    return {
        showId: show.json('id'),
        run,
        hotTokens: userTokens(`hot-${run}`, HOT_USERS),
        riyaToken: userTokens(`riya-${run}`, 1)[0],
        retryToken: userTokens(`retry-${run}`, 1)[0],
    };
}

function record(res) {
    if (res.status === 201) return confirmed.add(1);
    if (res.status === 200) return replay.add(1);
    if (res.status === 0 || res.status >= 500) return errors5xx.add(1);
    let err;
    try { err = res.json('error'); } catch (e) { err = null; }
    if (err === 'seat_taken') return seatTaken.add(1);
    if (err === 'per_user_limit') return overLimit.add(1);
    other4xx.add(1);
    console.log(`OTHER status=${res.status} body=${res.body}`);
}

function reserve(showId, token, seats, key) {
    return http.post(`${BASE}/shows/${showId}/reserve`,
        JSON.stringify({ seats, idempotency_key: key }),
        { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });
}

export function hotSeat(data) {
    const i = exec.scenario.iterationInTest;
    record(reserve(data.showId, data.hotTokens[i], ['A12'], `hot-key-${data.run}-${i}`));
}

export function oneUser(data) {
    const i = exec.scenario.iterationInTest;
    record(reserve(data.showId, data.riyaToken, ['A' + (20 + i)], `riya-key-${data.run}-${i}`));
}

export function retry(data) {
    record(reserve(data.showId, data.retryToken, ['A40'], `retry-key-${data.run}`));
}

export function teardown(data) {
    const c = http.get(`${BASE}/shows/${data.showId}`).json('counts');
    console.log(`FINAL available=${c.available} held=${c.held} confirmed=${c.confirmed} total=${c.total}`);
    console.log(`INVARIANT available+held+confirmed==total: ${c.available + c.held + c.confirmed === c.total}`);
    console.log(`EXPECTED confirmed=6 (1 hot seat + 4 riya + 1 retry user)`);
}