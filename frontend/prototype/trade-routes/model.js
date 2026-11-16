/** PROTOTYPE model: formatting, request state and the route computation behind every variant. */
const NF = new Intl.NumberFormat('de-DE');
const fmt = (n) => NF.format(Math.round(n));
const dec = (n) => n.toFixed(1).replace('.', ',');
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const ic = (n) => `<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-${n}"></use></svg>`;

/**
 * Formats a duration in seconds as minutes, or hours and minutes.
 * @param {number} s seconds
 * @returns {string} the formatted duration
 */
function fmtDur(s) {
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`;
}

/**
 * Formats a short duration with seconds below ten minutes.
 * @param {number} s seconds
 * @returns {string} the formatted duration
 */
function fmtShort(s) {
  if (s >= 600) return fmtDur(s);
  const m = Math.floor(s / 60);
  const r = Math.round(s % 60);
  return r ? `${m} min ${r} s` : `${m} min`;
}

/**
 * Formats a price age in hours as text.
 * @param {number} h hours
 * @returns {string} the age text
 */
function ageText(h) {
  return `vor ${ageShort(h).replace('Tage', 'Tagen')}`;
}

/**
 * Formats a price age in hours as a bare amount.
 * @param {number} h hours
 * @returns {string} the age
 */
function ageShort(h) {
  if (h < 48) return `${Math.round(h)} h`;
  return `${Math.round(h / 24)} Tage`;
}

/**
 * Builds the initial request of a scenario.
 * @param {string} scenario single or convoy
 * @returns {object} the request
 */
function freshRequest(scenario) {
  return {
    ships: scenario === 'convoy' ? [{ ...SHIP_C2 }, { ...SHIP_CAT }] : [{ ...SHIP_C2 }],
    maxStops: 4, roundTrip: false, start: '', end: '', runs: 1,
    objective: 'perHour', timeLimit: '', minProfit: '100000',
    systems: [], kinds: [...KINDS], modes: [...MODES],
    refuel: false, armistice: false, monitoring: 'any',
    forced: ['baijini'], excluded: [],
    avoidConn: ['Nyx – Stanton'], avoidSys: [],
    categories: [], only: [], exclCommodities: [], illegal: false, warning: true,
    probable: false, dangerMax: '', bugsMax: '', availMin: '',
  };
}

/**
 * Lists the request's settings that differ from a fresh form, for chips and counts.
 * @param {object} r the request
 * @returns {Array<{key: string, val: string, label: string}>} the active filters
 */
function activeFilters(r) {
  const out = [];
  r.systems.forEach((v) => out.push({ key: 'systems', val: v, label: `System: ${v}` }));
  KINDS.filter((k) => !r.kinds.includes(k)).forEach((v) => out.push({ key: 'kinds', val: v, label: `Ohne ${v}` }));
  MODES.filter((k) => !r.modes.includes(k)).forEach((v) => out.push({ key: 'modes', val: v, label: `Ohne ${v}` }));
  if (r.refuel) out.push({ key: 'refuel', val: '', label: 'Nur mit Tanken' });
  if (r.armistice) out.push({ key: 'armistice', val: '', label: 'Nur Waffenstillstand' });
  if (r.monitoring !== 'any') out.push({ key: 'monitoring', val: '', label: r.monitoring === 'yes' ? 'Nur überwacht' : 'Nur unüberwacht' });
  r.forced.forEach((v) => out.push({ key: 'forced', val: v, label: `Erzwungen: ${TERMINALS[v].name}` }));
  r.excluded.forEach((v) => out.push({ key: 'excluded', val: v, label: `Ausgeschlossen: ${TERMINALS[v].name}` }));
  r.avoidConn.forEach((v) => out.push({ key: 'avoidConn', val: v, label: `Gemieden: ${v}` }));
  r.avoidSys.forEach((v) => out.push({ key: 'avoidSys', val: v, label: `System gemieden: ${v}` }));
  r.categories.forEach((v) => out.push({ key: 'categories', val: v, label: `Kategorie: ${v}` }));
  r.only.forEach((v) => out.push({ key: 'only', val: v, label: `Nur: ${v}` }));
  r.exclCommodities.forEach((v) => out.push({ key: 'exclCommodities', val: v, label: `Ohne Ware: ${v}` }));
  if (r.illegal) out.push({ key: 'illegal', val: '', label: 'Illegale Waren erlaubt' });
  if (r.warning) out.push({ key: 'warning', val: '', label: 'Warnwaren erlaubt' });
  if (r.probable) out.push({ key: 'probable', val: '', label: 'Nur wahrscheinlich verfügbar' });
  if (r.dangerMax) out.push({ key: 'dangerMax', val: '', label: `Gefahr ≤ ${r.dangerMax.replace('.', ',')}` });
  if (r.bugsMax) out.push({ key: 'bugsMax', val: '', label: `Bugs ≤ ${r.bugsMax.replace('.', ',')}` });
  if (r.availMin) out.push({ key: 'availMin', val: '', label: `Verfügbarkeit ≥ ${r.availMin} %` });
  return out;
}

/**
 * Expands a ship list into single ships, one per counted unit.
 * @param {Array<object>} ships the ship list
 * @returns {Array<object>} one entry per ship
 */
function expandShips(ships) {
  const out = [];
  ships.forEach((s) => {
    for (let i = 0; i < s.count; i += 1) {
      out.push({ ...s, label: s.count > 1 ? `${s.name} ${i + 1}` : s.name, short: s.count > 1 ? `${s.short} ${i + 1}` : s.short });
    }
  });
  return out;
}

/**
 * Splits a quantity over the ships by their usable hold.
 * @param {number} qty whole SCU
 * @param {Array<object>} ships the expanded ships
 * @returns {Array<number>} the share per ship
 */
function split(qty, ships) {
  const holds = ships.map((s) => Math.max(0, s.scu - s.reserve));
  const total = holds.reduce((a, b) => a + b, 0);
  const shares = holds.map((h) => Math.floor((qty * h) / total));
  shares[0] += qty - shares.reduce((a, b) => a + b, 0);
  return shares;
}

/**
 * Computes a route for a ship list: quantities, ship shares, times, money and markers.
 * @param {object} base a route of ROUTES, planned for one hold of BASE_HOLD
 * @param {Array<object>} shipList the ship list the result was computed for
 * @returns {object} the computed route
 */
function computeRoute(base, shipList) {
  const ships = expandShips(shipList);
  const hold = ships.reduce((a, s) => a + Math.max(0, s.scu - s.reserve), 0);
  const factor = hold / BASE_HOLD;
  const convoy = ships.length > 1;
  const shipEst = shipList.filter((s) => s.est).map((s) => s.est);
  const perShip = ships.map((s) => ({ label: s.label, short: s.short, capital: s.capital, revenue: 0, cost: 0, cash: 0, low: 0 }));
  let revenue = 0;
  let cost = 0;
  let cash = 0;
  let low = 0;
  let oldest = 0;
  const stops = base.stops.map((st, si) => {
    const term = TERMINALS[st.t];
    const place = PLACES[term.place];
    const jobs = st.jobs.map((j) => {
      const qty = Math.floor(j.qty * factor);
      const shares = split(qty, ships);
      const sum = qty * j.p;
      if (j.side === 'buy') cost += sum; else revenue += sum;
      shares.forEach((q, i) => {
        if (j.side === 'buy') perShip[i].cost += q * j.p; else perShip[i].revenue += q * j.p;
      });
      oldest = Math.max(oldest, j.age);
      return { ...j, qty, shares, sum, key: `${base.id}-${si}-${j.c}-${j.side}` };
    });
    jobs.filter((j) => j.side === 'sell').forEach((j) => { cash += j.sum; });
    jobs.filter((j) => j.side === 'buy').forEach((j) => { cash -= j.sum; });
    low = Math.min(low, cash);
    perShip.forEach((p, i) => {
      jobs.forEach((j) => { p.cash += (j.side === 'sell' ? 1 : -1) * j.shares[i] * j.p; });
      p.low = Math.min(p.low, p.cash);
    });
    const loaders = convoy ? ships.filter((_, i) => jobs.some((j) => j.shares[i] > 0)).length : 1;
    const surcharge = convoy ? SURCHARGE[place.kind] * Math.max(0, loaders - 1) : 0;
    const dur = st.over + st.load + surcharge;
    return { ...st, si, term, place, jobs, dur, surcharge, loaders };
  });
  const legs = base.legs.map((l) => {
    const est = [l.est, ...shipEst].filter(Boolean);
    const qt = Math.round(l.qt * (convoy ? 1.12 : 1));
    const aboard = stops[0] ? Math.min(hold, stops.slice(0, base.legs.indexOf(l) + 1).reduce((a, s) => a + s.jobs.reduce((b, j) => b + (j.side === 'buy' ? j.qty : -j.qty), 0), 0)) : 0;
    return { ...l, est, dur: qt + l.crossings * CROSSING, qtDur: qt, aboard, hold };
  });
  const duration = stops.reduce((a, s) => a + s.dur, 0) + legs.reduce((a, l) => a + l.dur, 0);
  const profit = revenue - cost;
  const estimated = legs.some((l) => l.est.length);
  const warnings = [];
  const detours = legs.filter((l) => l.detour);
  if (detours.length) warnings.push({ kind: 'warn', icon: 'warning', text: `${detours.length} Etappe umgeleitet: ${detours.map((d) => d.detour.text).join('; ')} · +${fmtDur(detours.reduce((a, d) => a + d.detour.extra, 0))} gegenüber dem kürzesten Weg` });
  const highDanger = stops.filter((s) => s.place.danger.n >= MIN_RATERS.danger && s.place.danger.m >= 3.5);
  if (highDanger.length) warnings.push({ kind: 'warn', icon: 'warning', text: `Hohe Gefahr gemeldet: ${[...new Set(highDanger.map((s) => s.place.name))].join(', ')}` });
  const unknownDemand = stops.flatMap((s) => s.jobs).filter((j) => j.side === 'sell' && j.demand === null);
  if (unknownDemand.length) warnings.push({ kind: 'info', icon: 'info', text: `Nachfrage unbekannt bei ${unknownDemand.length} Verkauf${unknownDemand.length > 1 ? 'en' : ''}: nur der Laderaum begrenzt die Menge` });
  const stale = stops.flatMap((s) => s.jobs).filter((j) => j.age > 168);
  if (stale.length) warnings.push({ kind: 'info', icon: 'clock', text: `${stale.length} Preis${stale.length > 1 ? 'e' : ''} veraltet (älter als 7 Tage)` });
  const warn = stops.flatMap((s) => s.jobs).filter((j) => j.warn);
  if (warn.length) warnings.push({ kind: 'warn', icon: 'warning', text: `Warnware an Bord: ${[...new Set(warn.map((j) => `${j.c} (${j.warn})`))].join(', ')}` });
  if (estimated) warnings.push({ kind: 'info', icon: 'info', text: `Dauer geschätzt: ${[...new Set(legs.flatMap((l) => l.est))].join('; ')}` });
  const ships2 = perShip.map((p) => ({ ...p, profit: p.revenue - p.cost, need: -p.low, perHour: ((p.revenue - p.cost) * 3600) / duration }));
  const name = stops.map((s) => s.place.name).filter((n, i, a) => i === 0 || n !== a[i - 1]).join(' → ');
  return {
    id: base.id, round: base.round, stops, legs, revenue, cost, profit, duration,
    perHour: (profit * 3600) / duration, capital: -low, runs: base.runs, oldest,
    stale: oldest > 168, estimated, warnings, ships: ships2, convoy, name,
    fees: stops.reduce((a, s) => a + (s.fee || 0), 0),
  };
}

/**
 * Orders computed routes by the request's objective.
 * @param {Array<object>} routes computed routes
 * @param {object} r the request
 * @returns {Array<object>} the ranked routes
 */
function rank(routes, r) {
  const list = [...routes];
  if (r.objective === 'maxProfit') return list.sort((a, b) => b.profit - a.profit);
  if (r.objective === 'fastest') {
    const min = Number(r.minProfit || 0);
    return list.filter((x) => x.profit >= min).sort((a, b) => a.duration - b.duration);
  }
  return list.sort((a, b) => b.perHour - a.perHour);
}
