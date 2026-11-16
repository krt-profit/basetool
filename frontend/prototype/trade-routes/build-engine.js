/** PROTOTYPE engine for building a route stop by stop: append candidates under two keys, the onward estimate, holding earlier cargo and the reasons. */

const buildMemo = new WeakMap();

/**
 * Returns the index an appended stop takes: before a fixed end, else after the last stop.
 * @param {{stops: Array<object>}} w the working route
 * @returns {number} the insert index
 */
function appendPos(w) {
  const last = w.stops[w.stops.length - 1];
  return w.stops.length - (w.stops.length > 1 && last.end ? 1 : 0);
}

/**
 * Returns the age of a terminal's prices.
 * @param {string} t terminal id
 * @returns {number} hours
 */
function priceAge(t) {
  return PRICE_AGE_H[t] ?? PRICE_AGE_DEFAULT_H;
}

/**
 * Reads the availability rating of one offer, if members reported it.
 * @param {string} t terminal id
 * @param {string} c commodity
 * @param {string} side buy or sell
 * @returns {{pct: number, n: number}|null} the rating
 */
function availOf(t, c, side) {
  return AVAIL_RATING[`${t}|${c}|${side}`] || null;
}

/**
 * Scores a route value under the request's objective; fastest ranks by profit per hour while building.
 * @param {number} profit aUEC
 * @param {number} dur seconds
 * @returns {number} the value candidates are ranked by
 */
function objectiveValue(profit, dur) {
  return state.request.objective === 'maxProfit' ? profit : (profit * 3600) / Math.max(1, dur);
}

/**
 * Returns the request's time limit while it applies.
 * @returns {number} seconds, 0 without a limit
 */
function timeLimitSec() {
  const r = state.request;
  return r.objective === 'maxProfit' && Number(r.timeLimit) > 0 ? Number(r.timeLimit) * 60 : 0;
}

/**
 * Splits one path at a new stop that buys its commodity dearer, as far as its demand takes it.
 * @param {object} p the path sold at the former last stop
 * @param {string} to the new stop
 * @returns {{parts: Array<object>, moved: object|null}} the path, moved in whole or in part, and the moved sale
 */
function moveSale(p, to) {
  const here = PRICES[to]?.sell?.[p.c];
  if (!here || here[0] <= p.ps) return { parts: [p], moved: null };
  const q = Math.min(p.qty, here[1] === null ? Infinity : here[1]);
  const parts = [{ ...p, to, ps: here[0], qty: q }];
  if (p.qty > q) parts.push({ ...p, qty: p.qty - q });
  return { parts, moved: { c: p.c, qty: q, from: p.to, to, gain: q * (here[0] - p.ps) } };
}

/**
 * Plans the cargo of a stop sequence with a new stop at pos: held earlier cargo, a flight's frozen part or a full re-plan.
 * @param {object} k the edit context
 * @param {Array<object>} stops the stop sequence with the new stop
 * @param {number} pos the index of the new stop
 * @param {string} mode hold or replan
 * @returns {{paths: Array<object>, lost: Array<object>, moved: Array<object>}} the plan and the sales that moved to the new stop
 */
function appendPlan(k, stops, pos, mode) {
  const newT = stops[pos].t;
  const prevT = stops[pos - 1].t;
  const moved = [];
  const shift = (list) => list.flatMap((p) => {
    if (p.to !== prevT) return [p];
    const r = moveSale(p, newT);
    if (r.moved) moved.push(r.moved);
    return r.parts;
  });
  if (k.ctx === 'flight') {
    const o = planOpts(k, stops, k.w.marks);
    const f = k.flight;
    const sold = (p) => Object.keys(f.ticks).some((tk) => tk.startsWith(`${pathKey(p)}|sell#`));
    o.fixed = o.fixed.flatMap((p) => (sold(p) ? [p] : shift([p])));
    const plan = planCargo(stops, o);
    return { paths: plan.paths, lost: [...(o.lostPrefix || []), ...plan.lost], moved };
  }
  const base = { ships: k.w.ships, marks: k.w.marks };
  if (mode !== 'hold' || k.w.stops.length < 2) {
    const plan = planCargo(stops, base);
    return { paths: plan.paths, lost: plan.lost, moved };
  }
  const ts = stops.map((s) => s.t);
  const held = k.w.paths.filter((p) => ts.indexOf(p.from) >= 0 && ts.indexOf(p.from) <= pos - 2).map((p) => ({ ...p, fixed: true }));
  const plan = planCargo(stops, { ...base, fixed: shift(held), fromIdx: pos - 1 });
  return { paths: plan.paths.map(({ fixed: _f, ...p }) => p), lost: plan.lost, moved };
}

/**
 * Finds the best single hop from a candidate to an allowed terminal off the route, with the full hold and the cash at hand.
 * @param {string} t the candidate terminal
 * @param {Array<string>} onRoute terminals already on the route, the candidate included
 * @param {Array<object>} ships the ship list; a convoy counts as one combined hold and purse
 * @param {{profit: number, duration: number}} so the route up to the candidate
 * @returns {object|null} the hop with commodity, target, quantity, profit and duration, or null
 */
function onwardHop(t, onRoute, ships, so) {
  const hold = holdOf(ships);
  const cash = capitalOf(ships) + so.profit;
  const limit = timeLimitSec();
  let best = null;
  Object.entries(PRICES[t]?.buy || {}).forEach(([c, [pb, stock]]) => {
    if (state.request.exclCommodities.includes(c)) return;
    Object.keys(TERMINALS).forEach((u) => {
      if (onRoute.includes(u) || !POS[TERMINALS[u].place] || failsFilter(u)) return;
      const e = PRICES[u]?.sell?.[c];
      if (!e || e[0] <= pb) return;
      const qty = Math.floor(Math.min(hold, stock, e[1] === null ? Infinity : e[1], cash / pb));
      if (qty <= 0) return;
      const ev = evaluate({ stops: [{ t }, { t: u }], paths: [{ c, from: t, to: u, pb, ps: e[0], qty }] }, ships);
      const dur = ev.duration - ev.stops[0].over;
      if (limit && so.duration + dur > limit) return;
      const profit = qty * (e[0] - pb);
      const score = objectiveValue(so.profit + profit, so.duration + dur);
      if (!best || score > best.score) best = { c, to: u, qty, pb, ps: e[0], profit, dur, score, unknown: e[1] === null };
    });
  });
  return best;
}

/**
 * Counts the loading jobs at stops before an index that a re-plan changes.
 * @param {Array<object>} diff the diff lines of the re-plan
 * @param {Array<string>} ts the new stop order
 * @param {number} pos the index of the new stop
 * @returns {number} changed loading jobs
 */
function earlierChanges(diff, ts, pos) {
  return diff.filter((l) => l.status !== 'UNCHANGED').reduce((a, l) => a + (ts.indexOf(l.from) >= 0 && ts.indexOf(l.from) < pos - 1 ? 1 : 0) + (ts.indexOf(l.to) >= 0 && ts.indexOf(l.to) < pos - 1 ? 1 : 0), 0);
}

/**
 * Computes one candidate for the append position: the exact value up to it, the onward estimate and the reasons.
 * @param {object} k the edit context
 * @param {string} t the candidate terminal
 * @param {number} pos the index it takes
 * @param {string} mode hold or replan
 * @param {object} base the computed current route
 * @param {boolean} fills whether this append fills the route limit, so no onward hop follows
 * @returns {object} the candidate
 */
function scoreCandidate(k, t, pos, mode, base, fills) {
  const ships = k.w.ships;
  const why = failsFilter(t);
  const stops = [...k.w.stops.slice(0, pos), { t, pinned: true, appended: true, failsFilter: why, forced: state.request.forced.includes(t) }, ...k.w.stops.slice(pos)];
  const plan = appendPlan(k, stops, pos, mode);
  const w2 = { ...k.w, stops, paths: plan.paths, lost: plan.lost };
  const e = evaluate(w2, ships);
  const ts = stops.map((s) => s.t);
  const atEnd = pos === stops.length - 1;
  const hop = atEnd && !fills ? onwardHop(t, ts, ships, e) : null;
  const limit = timeLimitSec();
  const here = objectiveValue(e.profit, e.duration);
  const onward = hop ? hop.score : here;
  const stop = e.stops[pos];
  const sells = stop.jobs.filter((j) => j.side === 'sell').map((j) => ({ c: j.c, qty: j.qty, p: j.p, sum: j.sum, unknown: j.demand === null, avail: availOf(t, j.c, 'sell') }));
  const nextBuys = atEnd ? [] : stop.jobs.filter((j) => j.side === 'buy').map((j) => ({ c: j.c, qty: j.qty, to: j.path.to, gain: j.qty * (j.path.ps - j.p) }));
  const prevT = stops[pos - 1].t;
  const added = mode === 'hold' && k.ctx === 'plan' && k.w.stops.length >= 2
    ? plan.paths.filter((p) => p.from === prevT && !k.w.paths.some((q) => pathKey(q) === pathKey(p))).map((p) => ({ c: p.c, qty: p.qty, to: p.to }))
    : [];
  const diff = k.w.stops.length >= 2 ? diffPlans(k.w, w2, ships) : [];
  const markers = [];
  const age = Math.max(priceAge(t), hop ? priceAge(hop.to) : 0);
  if (age > PRICE_EXPIRED_H) markers.push({ kind: 'danger', text: 'Preis abgelaufen' });
  else if (age > PRICE_STALE_H) markers.push({ kind: 'warn', text: `Preis veraltet · ${ageShort(age)}` });
  const lowAvail = [...sells.map((s) => ({ c: s.c, a: s.avail })), ...(hop ? [{ c: hop.c, a: availOf(t, hop.c, 'buy') }] : [])].filter((x) => x.a && x.a.pct < AVAIL_WARN_BELOW);
  lowAvail.forEach((x) => markers.push({ kind: x.a.pct < 50 ? 'danger' : 'warn', text: `Verfügbarkeit ${x.a.pct} % · ${x.c}` }));
  return {
    t, why, forced: state.request.forced.includes(t), e, w: w2, hop, here, onward,
    over: Boolean(limit) && e.duration > limit,
    reachesMin: state.request.objective === 'fastest' && base.profit < Number(state.request.minProfit) && e.profit >= Number(state.request.minProfit),
    dProfit: e.profit - base.profit, dDur: e.duration - base.duration,
    sells, nextBuys, moved: plan.moved, added, changes: mode === 'replan' ? earlierChanges(diff, ts, pos) : 0, lost: plan.lost.length, markers,
  };
}

/**
 * Lists the append candidates of a working route under both keys, with the forced terminals still missing in a block of their own.
 * @param {object} k the edit context
 * @param {string} mode hold or replan
 * @returns {{pos: number, base: object, byOnward: Array<object>, byHere: Array<object>, forced: Array<object>, oneKey: boolean, fills: boolean}} the candidate lists; one key with a fixed end or when the append fills the route limit
 */
function buildCandidates(k, mode) {
  const r = state.request;
  const sig = JSON.stringify([k.ctx, mode, r.objective, r.timeLimit, r.minProfit, r.forced, r.excluded, r.kinds, r.systems, r.armistice, r.exclCommodities, k.ctx === 'flight' ? Object.keys(k.flight.ticks).length : 0, k.ctx === 'flight' ? k.flight.pos.idx : 0]);
  let memo = buildMemo.get(k.w);
  if (memo && memo[sig]) return memo[sig];
  const pos = appendPos(k.w);
  const base = evaluate(k.w, k.w.ships);
  const onRoute = k.w.stops.map((s) => s.t);
  const pool = Object.keys(TERMINALS).filter((t) => POS[TERMINALS[t].place] && PRICES[t] && !onRoute.includes(t));
  const fills = k.w.stops.length + 1 >= ROUTE_LIMIT;
  const all = pool.filter((t) => !failsFilter(t)).map((t) => scoreCandidate(k, t, pos, mode, base, fills));
  const rank = (key) => [...all].sort((a, b) => (a.over === b.over ? b[key] - a[key] : a.over ? 1 : -1)).slice(0, BUILD_LIST_SIZE);
  const missing = r.forced.filter((t) => !onRoute.includes(t) && !r.excluded.includes(t));
  const forced = missing.map((t) => all.find((c) => c.t === t) || scoreCandidate(k, t, pos, mode, base, fills));
  const res = { pos, base, byOnward: rank('onward'), byHere: rank('here'), forced, oneKey: pos < k.w.stops.length || fills, fills };
  memo = memo || {};
  memo[sig] = res;
  buildMemo.set(k.w, memo);
  return res;
}
