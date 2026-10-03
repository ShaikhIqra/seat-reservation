import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const confirmed = new Counter('confirmed_201');
const replay    = new Counter('replay_200');
const seatTaken = new Counter('declined_seat_taken');
const overLimit = new Counter('declined_per_user_limit');
const other4xx  = new Counter('other_4xx');
const errors5xx = new Counter('errors_5xx');

export const options = {
    scenarios: {
        // 500 different users all grab A12 at once → exactly 1 should win
        hot_seat: {
            executor: 'shared-iterations', vus: 500, iterations: 500,
            maxDuration: '60s', exec: 'hotSeat',
        },
        // 1 user fires 10 parallel requests for 10 different seats, limit 4 → at most 4 win
        one_user: {
            executor: 'shared-iterations', vus: 10, iterations: 10,
            maxDuration: '60s', exec: 'oneUser',
        },
    },
};

export function setup() {
    const seats = [];
    for (let i = 1; i <= 50; i++) seats.push('A' + i);
    const res = http.post(`${BASE}/shows`,
        JSON.stringify({ name: 'burst-test', seats, price_paise: 25000, per_user_limit: 4 }),
        { headers: JSON_HEADERS });
    return { showId: res.json('id'), run: Date.now() };
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

function reserve(showId, userId, seats, key) {
    return http.post(`${BASE}/shows/${showId}/reserve`,
        JSON.stringify({ seats, idempotency_key: key }),
        { headers: { ...JSON_HEADERS, 'X-User-Id': userId } });   // TEMPORARY until token auth
}

export function hotSeat(data) {
    const i = exec.scenario.iterationInTest;
    record(reserve(data.showId, `hot-user-${data.run}-${i}`, ['A12'], `hot-key-${data.run}-${i}`));
}

export function oneUser(data) {
    const i = exec.scenario.iterationInTest;
    record(reserve(data.showId, `riya-${data.run}`, ['A' + (20 + i)], `riya-key-${data.run}-${i}`));
}

export function teardown(data) {
    const c = http.get(`${BASE}/shows/${data.showId}`).json('counts');
    console.log(`FINAL available=${c.available} held=${c.held} confirmed=${c.confirmed} total=${c.total}`);
    console.log(`INVARIANT available+held+confirmed==total: ${c.available + c.held + c.confirmed === c.total}`);
    console.log(`EXPECTED confirmed=5 (1 hot seat + 4 for riya)`);
}