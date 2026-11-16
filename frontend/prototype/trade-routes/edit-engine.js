/** PROTOTYPE engine for the edit flows: a greedy cargo planner, the kept plan, the diff, candidates and alternatives. */

const MODE_LOAD = { 'Auto-Load': [120, 0.07], Ladedock: [120, 0.4], Frachtaufzug: [180, 0.5] };

/**
 * Builds the key of a buy-sell path.
 * @param {{c: string, from: string, to: string}} p the path
 * @returns {string} commodity, buy terminal and sell terminal
 */
const pathKey = (p) => `${p.c}|${p.from}|${p.to}`;

/**
 * Sums the usable hold of a ship list.
 * @param {Array<object>} ships the ship list
 * @returns {number} SCU
 */
const holdOf = (ships) => expandShips(ships).reduce((a, s) => a + Math.max(0, s.scu - s.reserve), 0);

/**
 * Sums the starting capital of a ship list.
 * @param {Array<object>} ships the ship list
 * @returns {number} aUEC
 */
const capitalOf = (ships) => expandShips(ships).reduce((a, s) => a + s.capital, 0);

/**
 * Returns the straight-line distance between two terminals in one system.
 * @param {string} a terminal id
 * @param {string} b terminal id
 * @returns {number|null} Gm, or null across systems
 */
function gmBetween(a, b) {
  const pa = POS[TERMINALS[a].place];
  const pb = POS[TERMINALS[b].place];
  if (!pa || !pb) return null;
  return Math.max(0.3, Math.hypot(pa[0] - pb[0], pa[1] - pb[1]));
}

/**
 * Names the request filter a terminal fails, if any.
 * @param {string} t terminal id
 * @returns {string} the failed filter, or an empty string
 */
function failsFilter(t) {
  const place = PLACES[TERMINALS[t].place];
  const r = state.request;
  if (r.excluded.includes(t)) return 'ausgeschlossen';
  if (r.armistice && !place.armistice) return 'nur Waffenstillstand';
  if (!r.kinds.includes(place.kind)) return `ohne ${place.kind}`;
  if (r.systems.length && !r.systems.includes(place.system)) return 'anderes System';
  return '';
}

/**
 * Reads the stock or demand cap of one side of a commodity at a terminal, a mark taking precedence.
 * @param {string} side buy or sell
 * @param {string} t terminal id
 * @param {string} c commodity
 * @param {Array<object>} marks the route-local marks
 * @returns {number} SCU, Infinity for unknown demand
 */
function capOf(side, t, c, marks) {
  const m = marks.find((x) => x.t === t && x.c === c && x.side === side);
  if (m) return m.rest;
  const e = PRICES[t]?.[side]?.[c];
  if (!e) return 0;
  return e[1] === null ? Infinity : e[1];
}

/**
 * Plans the cargo on a fixed stop order: fixed paths first, then kept paths as upper bounds, then greedy by margin.
 * @param {Array<object>} stops the stop sequence ({t, pinned, inserted, ...})
 * @param {{ships: Array<object>, marks?: Array<object>, fixed?: Array<object>, keep?: Array<object>, fromIdx?: number, onlyFrom?: string, extra?: Array<object>, skip?: Array<string>}} opts planning options; skip lists "terminal|commodity" purchases already ticked
 * @returns {{paths: Array<object>, lost: Array<object>}} the planned paths and the kept paths no longer sellable as planned
 */
function planCargo(stops, opts) {
  const ts = stops.map((s) => s.t);
  const n = ts.length;
  const marks = opts.marks || [];
  const free = Array(Math.max(0, n - 1)).fill(holdOf(opts.ships));
  const cash = Array(n).fill(capitalOf(opts.ships));
  const used = {};
  const paths = [];
  const lost = [];
  const room = (i, j, c, pb) => {
    let q = Math.min(capOf('buy', ts[i], c, marks) - (used[`b|${ts[i]}|${c}`] || 0), capOf('sell', ts[j], c, marks) - (used[`s|${ts[j]}|${c}`] || 0));
    for (let k = i; k < j; k += 1) q = Math.min(q, free[k], Math.floor(cash[k] / pb));
    return Math.max(0, Math.floor(q));
  };
  const place = (p, i, j) => {
    for (let k = i; k < j; k += 1) { free[k] -= p.qty; cash[k] -= p.qty * p.pb; }
    for (let k = j; k < n; k += 1) cash[k] += p.qty * (p.ps - p.pb);
    used[`b|${p.from}|${p.c}`] = (used[`b|${p.from}|${p.c}`] || 0) + p.qty;
    used[`s|${p.to}|${p.c}`] = (used[`s|${p.to}|${p.c}`] || 0) + p.qty;
    const same = paths.find((x) => pathKey(x) === pathKey(p) && x.pb === p.pb && x.ps === p.ps && !x.fixed && !p.fixed);
    if (same) same.qty += p.qty; else paths.push(p);
  };
  (opts.fixed || []).forEach((p) => { const i = ts.indexOf(p.from); const j = ts.indexOf(p.to); if (i >= 0 && j > i) place({ ...p }, i, j); });
  if (opts.keep) {
    [...opts.keep].sort((a, b) => (b.ps - b.pb) - (a.ps - a.pb)).forEach((p) => {
      if ((opts.fixed || []).some((f) => pathKey(f) === pathKey(p))) return;
      const i = ts.indexOf(p.from);
      const j = ts.indexOf(p.to);
      if (i < 0 || j < 0 || j < i) { lost.push({ ...p, why: i < 0 ? 'DROPPED' : 'NOT_SELLABLE' }); return; }
      const q = Math.min(p.qty, room(i, j, p.c, p.pb));
      if (q > 0) place({ ...p, qty: q, reduced: q < p.qty ? p.qty : 0 }, i, j);
      else lost.push({ ...p, why: 'NOT_SELLABLE' });
    });
    (opts.extra || []).forEach((p) => { const i = ts.indexOf(p.from); const j = ts.indexOf(p.to); const q = Math.min(p.qty, room(i, j, p.c, p.pb)); if (q > 0) place({ ...p, qty: q }, i, j); });
    return { paths, lost };
  }
  const cands = [];
  for (let i = opts.fromIdx || 0; i < n; i += 1) {
    if (opts.onlyFrom && ts[i] !== opts.onlyFrom) continue;
    Object.entries(PRICES[ts[i]]?.buy || {}).forEach(([c, [pb]]) => {
      if (state.request.exclCommodities.includes(c) || (opts.skip || []).includes(`${ts[i]}|${c}`)) return;
      for (let j = i + 1; j < n; j += 1) {
        const e = PRICES[ts[j]]?.sell?.[c];
        if (e && e[0] > pb) cands.push({ c, from: ts[i], to: ts[j], pb, ps: e[0], i, j });
      }
    });
  }
  cands.sort((a, b) => (b.ps - b.pb) - (a.ps - a.pb)).forEach((x) => {
    const q = room(x.i, x.j, x.c, x.pb);
    if (q > 0) place({ c: x.c, from: x.from, to: x.to, pb: x.pb, ps: x.ps, qty: q }, x.i, x.j);
  });
  return { paths, lost };
}

/**
 * Computes a working route's figures in the shape the shared fragments render.
 * @param {{stops: Array<object>, paths: Array<object>, lost?: Array<object>, marks?: Array<object>}} w the working route
 * @param {Array<object>} shipList the ship list
 * @returns {object} the computed route
 */
function evaluate(w, shipList) {
  const ships = expandShips(shipList);
  const convoy = ships.length > 1;
  const hold = holdOf(shipList);
  const ts = w.stops.map((s) => s.t);
  const perShip = ships.map((s) => ({ label: s.label, short: s.short, capital: s.capital, revenue: 0, cost: 0, cash: 0, low: 0 }));
  let revenue = 0;
  let cost = 0;
  let cash = 0;
  let low = 0;
  const lost = w.lost || [];
  const stops = w.stops.map((ws, si) => {
    const term = TERMINALS[ws.t];
    const pl = PLACES[term.place];
    const jobs = [];
    w.paths.forEach((p) => {
      ['buy', 'sell'].forEach((side) => {
        if ((side === 'buy' ? p.from : p.to) !== ws.t) return;
        const price = side === 'buy' ? p.pb : p.ps;
        const shares = split(p.qty, ships);
        const sum = p.qty * price;
        const e = PRICES[ws.t]?.[side]?.[p.c];
        if (side === 'buy') cost += sum; else revenue += sum;
        shares.forEach((q, i) => { if (side === 'buy') perShip[i].cost += q * price; else perShip[i].revenue += q * price; });
        jobs.push({ side, c: p.c, qty: p.qty, p: price, sum, shares, path: p, key: `${pathKey(p)}|${side}`, age: 3, demand: side === 'sell' && e && e[1] === null ? null : 1, stock: e ? e[1] : 0, avail: { pct: 85, n: 3 } });
      });
    });
    jobs.filter((j) => j.side === 'sell').forEach((j) => { cash += j.sum; });
    jobs.filter((j) => j.side === 'buy').forEach((j) => { cash -= j.sum; });
    low = Math.min(low, cash);
    perShip.forEach((p, i) => { jobs.forEach((j) => { p.cash += (j.side === 'sell' ? 1 : -1) * j.shares[i] * j.p; }); p.low = Math.min(p.low, p.cash); });
    const mode = MODES.find((m) => term.modes.includes(m) && state.request.modes.includes(m)) || term.modes[0];
    const moved = jobs.reduce((a, j) => a + j.qty, 0);
    const [base, per] = MODE_LOAD[mode];
    const load = moved ? Math.round(base + per * moved) : 0;
    const over = OVERHEADS[pl.kind] * 60;
    const loaders = convoy ? ships.filter((_, i) => jobs.some((j) => j.shares[i] > 0)).length : 1;
    const surcharge = convoy ? SURCHARGE[pl.kind] * Math.max(0, loaders - 1) : 0;
    const fee = mode === 'Auto-Load' ? Math.round(moved * 1.6) : 0;
    const why = (p) => {
      const m = (w.marks || []).find((x) => x.c === p.c && ((x.side === 'buy' && x.t === p.from) || (x.side === 'sell' && x.t === p.to)));
      if (m) return m.side === 'buy' ? 'ausverkauft' : 'keine Nachfrage';
      return { DROPPED: 'weggefallen', UNSOLD: 'kein Abnehmer auf dem Rest der Route' }[p.why] || 'nicht wie geplant verkaufbar';
    };
    const gone = lost.filter((p) => p.from === ws.t || p.to === ws.t).map((p) => ({ side: p.from === ws.t ? 'buy' : 'sell', c: p.c, qty: p.qty, p: p.from === ws.t ? p.pb : p.ps, gone: why(p) }));
    return { ...ws, si, t: ws.t, term, place: pl, jobs, gone, mode, over, load, fee, surcharge, loaders, dur: over + load + surcharge, forced: ws.forced };
  });
  const legs = stops.slice(1).map((s, i) => {
    const gm = gmBetween(ts[i], ts[i + 1]);
    const est = gm === null ? ['Systemwechsel: Entfernung aus UEX'] : [];
    const g = gm === null ? 60 : gm;
    const qt = Math.round((110 + 19 * g) * (convoy ? 1.12 : 1));
    const crossings = gm === null ? 1 : 0;
    const aboard = w.paths.filter((p) => ts.indexOf(p.from) <= i && ts.indexOf(p.to) > i).reduce((a, p) => a + p.qty, 0);
    return { gm: g, est, qtDur: qt, crossings, dur: qt + crossings * CROSSING, aboard, hold };
  });
  const duration = stops.reduce((a, s) => a + s.dur, 0) + legs.reduce((a, l) => a + l.dur, 0);
  const profit = revenue - cost;
  const warnings = [];
  const unknown = stops.flatMap((s) => s.jobs).filter((j) => j.side === 'sell' && j.demand === null);
  if (unknown.length) warnings.push({ kind: 'info', icon: 'info', text: `Nachfrage unbekannt bei ${unknown.length} Verkauf${unknown.length > 1 ? 'en' : ''}: nur der Laderaum begrenzt die Menge` });
  stops.filter((s) => s.failsFilter).forEach((s) => warnings.push({ kind: 'warn', icon: 'warning', text: `${s.term.name} verletzt einen Filter der Anfrage (${s.failsFilter}); von dir eingefügt, darum bleibt es` }));
  (w.marks || []).forEach((m) => warnings.push({ kind: 'warn', icon: 'warning', text: `${m.side === 'buy' ? 'Ausverkauft' : 'Keine Nachfrage'}: ${m.c} bei ${TERMINALS[m.t].name}, noch ${fmt(m.rest)} SCU (nur deine Route)` }));
  const ships2 = perShip.map((p) => ({ ...p, profit: p.revenue - p.cost, need: -p.low, perHour: ((p.revenue - p.cost) * 3600) / Math.max(1, duration) }));
  const name = stops.map((s) => s.place.name).filter((x, i, a) => i === 0 || x !== a[i - 1]).join(' → ');
  const buys = stops.flatMap((s) => s.jobs).filter((j) => j.side === 'buy');
  const runs = buys.length ? Math.max(1, Math.min(...buys.map((j) => Math.floor(j.stock / Math.max(1, j.qty))))) : 1;
  return {
    id: w.id || 'edit', round: false, stops, legs, revenue, cost, profit, duration,
    perHour: (profit * 3600) / Math.max(1, duration), capital: -low, runs, oldest: 9, stale: false,
    estimated: legs.some((l) => l.est.length), warnings, ships: ships2, convoy, name, paths: w.paths,
  };
}

/**
 * Compares two cargo plans per buy-sell path.
 * @param {object} before the working route before the step
 * @param {object} after the working route after the step
 * @param {Array<object>} shipList the ship list, for the per-ship split
 * @returns {Array<object>} one line per path with status, quantities before and after, and per-ship shares
 */
function diffPlans(before, after, shipList) {
  const ships = expandShips(shipList);
  const order = after.stops.map((s) => s.t);
  const now = new Map(after.paths.map((p) => [pathKey(p), p]));
  const was = new Set(before.paths.map(pathKey));
  const lines = before.paths.map((p) => {
    const q = now.get(pathKey(p));
    const fi = order.indexOf(p.from);
    const ti = order.indexOf(p.to);
    let status = 'DROPPED';
    if (q) status = q.qty === p.qty ? 'UNCHANGED' : 'CHANGED';
    else if (fi >= 0 && (ti < 0 || ti < fi)) status = 'NOT_SELLABLE';
    return { c: p.c, from: p.from, to: p.to, before: p.qty, after: q ? q.qty : 0, status };
  });
  after.paths.filter((q) => !was.has(pathKey(q))).forEach((q) => lines.push({ c: q.c, from: q.from, to: q.to, before: 0, after: q.qty, status: 'NEW' }));
  return lines.map((l) => ({ ...l, sharesBefore: split(l.before, ships), sharesAfter: split(l.after, ships) }));
}

/**
 * Lists the ten best terminals for a position, each with the whole route's cargo re-planned.
 * @param {object} w the working route
 * @param {number} pos the index the new stop takes
 * @param {Array<object>} ships the ship list
 * @param {string} [commodity] only terminals that buy this commodity
 * @param {function(Array<object>): object} [optsFor] planning options for a stop sequence, a flight's fixed prefix included
 * @returns {{list: Array<object>, onRoute: Array<string>}} the candidates and the terminals already on the route
 */
function candidatesFor(w, pos, ships, commodity, optsFor) {
  const base = evaluate(w, ships);
  const onRoute = w.stops.map((s) => s.t);
  const list = Object.keys(TERMINALS).filter((t) => POS[TERMINALS[t].place] && PRICES[t] && !onRoute.includes(t) && !failsFilter(t))
    .filter((t) => !commodity || PRICES[t].sell[commodity])
    .map((t) => {
      const stops = [...w.stops.slice(0, pos), { t, pinned: true, inserted: true }, ...w.stops.slice(pos)];
      const plan = planCargo(stops, optsFor ? optsFor(stops) : { ships, marks: w.marks });
      const e = evaluate({ stops, paths: plan.paths }, ships);
      return { t, dProfit: e.profit - base.profit, dDur: e.duration - base.duration, perHour: e.perHour, sells: commodity ? PRICES[t].sell[commodity][0] : 0 };
    });
  list.sort((a, b) => (commodity ? b.sells - a.sells : b.perHour - a.perHour));
  return { list: list.slice(0, 10), onRoute };
}

/**
 * Lists commodities that fill the hold and cash a mark freed at a stop and sell at later planned stops.
 * @param {object} w the working route, its paths fixed
 * @param {number} si the stop index
 * @param {Array<object>} ships the ship list
 * @returns {Array<object>} paths with their profit, best first
 */
function alternativesAt(w, si, ships) {
  const t = w.stops[si].t;
  const alts = [];
  Object.entries(PRICES[t]?.buy || {}).forEach(([c, [pb]]) => {
    if (w.marks.some((m) => m.t === t && m.c === c && m.side === 'buy')) return;
    w.stops.slice(si + 1).forEach((s) => {
      const e = PRICES[s.t]?.sell?.[c];
      if (!e || e[0] <= pb) return;
      const k = `${c}|${t}|${s.t}`;
      const sum = (ps) => ps.filter((p) => pathKey(p) === k).reduce((a, p) => a + p.qty, 0);
      const trial = planCargo(w.stops, { ships, marks: w.marks, fixed: w.paths, keep: [], extra: [{ c, from: t, to: s.t, pb, ps: e[0], qty: 99999 }] });
      const qty = sum(trial.paths) - sum(w.paths);
      if (qty > 0) alts.push({ c, from: t, to: s.t, pb, ps: e[0], qty, gain: qty * (e[0] - pb) });
    });
  });
  return alts.sort((a, b) => b.gain - a.gain).slice(0, 6);
}

/**
 * Searches the unpinned positions again, the pinned stops keeping theirs.
 * @param {object} w the working route
 * @param {Array<object>} ships the ship list
 * @returns {Array<object>} the new stop sequence
 */
function optimiseRest(w, ships) {
  const stops = w.stops.map((s) => ({ ...s }));
  const pool = Object.keys(TERMINALS).filter((t) => POS[TERMINALS[t].place] && PRICES[t] && !failsFilter(t));
  stops.forEach((s, i) => {
    if (s.pinned) return;
    let best = { t: s.t, v: -Infinity };
    pool.filter((t) => t === s.t || !stops.some((x) => x.t === t)).forEach((t) => {
      const trial = stops.map((x, k) => (k === i ? { t, pinned: false } : x));
      const e = evaluate({ stops: trial, paths: planCargo(trial, { ships, marks: w.marks }).paths }, ships);
      if (e.perHour > best.v) best = { t, v: e.perHour };
    });
    stops[i] = { t: best.t, pinned: false, forced: false };
  });
  return stops;
}
