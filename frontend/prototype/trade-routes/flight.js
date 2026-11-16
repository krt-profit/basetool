/** PROTOTYPE flights: a server-held copy of a route with position, loading-job ticks, edits, undo, history and the mission flight. */

/**
 * Returns the flight the overlay shows: the member's personal flight or a mission route's flight.
 * @returns {object|null} the flight
 */
function openFlight() {
  if (!state.flyOpen) return null;
  return state.flyOpen === 'personal' ? state.fl : state.mflights[state.flyOpen];
}

/**
 * Starts a flight from a working route, as the server would from a job result.
 * @param {string} kind personal or mission
 * @param {object} w the working route
 * @param {{name: string, src: string, mission?: string, ship?: number}} meta name, source kind and mission route
 * @returns {object} the flight
 */
function newFlight(kind, w, meta) {
  const e = evaluate(w, w.ships);
  return { kind, ...meta, ships: w.ships, w: { ...w, marks: [], lost: [], kept: false }, pos: { idx: 0, phase: 'stop' }, ticks: {}, tickBefore: {}, undo: [], redo: [], last: null, conflict: null, planned: e.profit, ship: meta.ship ?? -1, started: 'heute, 19:40' };
}

/**
 * Converts a computed route of the other surfaces into a working route.
 * @param {object} r the computed route
 * @param {Array<object>} ships the ship list
 * @returns {object} the working route
 */
function fromComputed(r, ships) {
  const paths = [];
  r.stops.forEach((s, i) => s.jobs.filter((j) => j.side === 'buy').forEach((j) => {
    const sell = r.stops.slice(i + 1).find((x) => x.jobs.some((y) => y.side === 'sell' && y.c === j.c));
    if (!sell) return;
    const sj = sell.jobs.find((y) => y.side === 'sell' && y.c === j.c);
    paths.push({ c: j.c, from: s.t, to: sell.t, qty: j.qty, pb: j.p, ps: sj.p });
  }));
  return { stops: r.stops.map((s) => ({ t: s.t, forced: s.forced })), paths, lost: [], marks: [], ships, lastFull: paths };
}

/**
 * Reads a loading job's tick, combined over the ships that carry it.
 * @param {object} f the flight
 * @param {object} j the job
 * @returns {{qty: number, price: number}|null} the actual values, or null while a ship's part is open
 */
function flightTickOf(f, j) {
  const n = expandShips(f.ships).length;
  const keys = Array.from({ length: n }, (_, i) => i).filter((i) => (n > 1 ? j.shares[i] : j.qty) > 0).map((i) => `${j.key}#${i}`);
  if (!keys.length || !keys.every((k) => f.ticks[k])) return null;
  return { qty: keys.reduce((a, k) => a + f.ticks[k].qty, 0), price: f.ticks[keys[0]].price };
}

/**
 * Lists the purchases already ticked, which a re-plan must not buy again.
 * @param {object} f the flight
 * @returns {Array<string>} "terminal|commodity" keys
 */
function tickedBuys(f) {
  return Object.keys(f.ticks).filter((k) => k.includes('|buy#')).map((k) => { const [c, from] = k.split('|'); return `${from}|${c}`; });
}

/**
 * Collects the flight's fixed prefix: cargo bought before the current stop or ticked, re-routed when its sale left the route.
 * @param {object} f the flight
 * @param {Array<object>} stops the stop sequence the re-plan uses
 * @returns {{fixed: Array<object>, lost: Array<object>}} the fixed paths and cargo aboard without a buyer
 */
function flightPrefix(f, stops) {
  const old = f.w.stops.map((s) => s.t);
  const ts = stops.map((s) => s.t);
  const bought = tickedBuys(f);
  const fixed = [];
  const lost = [];
  f.w.paths.filter((p) => p.fixed || old.indexOf(p.from) < f.pos.idx || bought.includes(`${p.from}|${p.c}`)).forEach((p) => {
    const from = ts.indexOf(p.from);
    const soldBefore = old.indexOf(p.to) >= 0 && old.indexOf(p.to) < f.pos.idx;
    if (soldBefore || ts.indexOf(p.to) > Math.max(from, f.pos.idx - 1)) { fixed.push({ ...p, fixed: true }); return; }
    const best = ts.slice(Math.max(from, f.pos.idx) + 1).filter((t) => PRICES[t]?.sell?.[p.c]).sort((a, b) => PRICES[b].sell[p.c][0] - PRICES[a].sell[p.c][0])[0];
    if (best) fixed.push({ ...p, to: best, ps: PRICES[best].sell[p.c][0], fixed: true });
    else lost.push({ ...p, why: 'UNSOLD' });
  });
  return { fixed, lost };
}

/**
 * Applies a tick with its actual values; a deviation re-plans the rest at once.
 * @param {object} f the flight
 * @param {string} tk the job key with the ship index
 * @param {number} qty the actual quantity
 * @param {number} price the actual price per SCU
 * @returns {void}
 */
function tickFlight(f, tk, qty, price) {
  const [jk, si] = tk.split('#');
  const e = evaluate(f.w, f.ships);
  const stop = e.stops.find((s) => s.jobs.some((x) => x.key === jk));
  const j = stop.jobs.find((x) => x.key === jk);
  const ship = Number(si);
  const planned = e.convoy ? j.shares[ship] : j.qty;
  f.ticks[tk] = { qty, price, by: f.kind === 'mission' ? ME : 'dir' };
  f.undo = [];
  f.redo = [];
  f.conflict = null;
  if (qty === planned && price === j.p) { f.last = null; return; }
  const before = f.w;
  const paths = before.paths.map((p) => ({ ...p }));
  const p = paths.find((x) => pathKey(x) === pathKey(j.path));
  const delta = planned - qty;
  p.qty = Math.max(0, p.qty - delta);
  p.fixed = true;
  if (j.side === 'buy') p.pb = price; else p.ps = price;
  const work = { ...f, w: { ...before, paths } };
  const { fixed, lost } = flightPrefix(work, before.stops);
  if (j.side === 'sell' && delta > 0) {
    const best = before.stops.slice(stop.si + 1).map((s) => s.t).filter((t) => PRICES[t]?.sell?.[j.c]).sort((a, b) => PRICES[b].sell[j.c][0] - PRICES[a].sell[j.c][0])[0];
    if (best) fixed.push({ c: j.c, from: p.from, to: best, qty: delta, pb: p.pb, ps: PRICES[best].sell[j.c][0], fixed: true });
    else lost.push({ c: j.c, from: p.from, to: stop.t, qty: delta, pb: p.pb, ps: p.ps, why: 'UNSOLD' });
  }
  const plan = planCargo(before.stops, { ships: f.ships, marks: before.marks, fixed, fromIdx: f.pos.idx, skip: tickedBuys(f) });
  f.w = { ...before, paths: plan.paths, lost };
  const what = qty !== planned ? `${fmt(qty)} statt ${fmt(planned)} SCU` : `à ${fmt(price)} statt ${fmt(j.p)}`;
  f.last = { label: `Ist-Wert ${j.c} (${j.side === 'buy' ? 'Kauf' : 'Verkauf'}): ${what}`, before, diff: diffPlans(before, f.w, f.ships), tick: tk, belowPlan: j.side === 'buy' && qty < planned ? { si: stop.si, c: j.c } : null };
  f.tickBefore[tk] = before;
}

/**
 * Takes a tick back, with the re-plan it caused.
 * @param {object} f the flight
 * @param {string} tk the job key with the ship index
 * @returns {void}
 */
function untickFlight(f, tk) {
  if (f.tickBefore[tk]) { f.w = f.tickBefore[tk]; delete f.tickBefore[tk]; }
  delete f.ticks[tk];
  if (f.last && f.last.tick === tk) f.last = null;
}

/**
 * Sums the margin the ticked sales realised so far; cargo still aboard counts nothing yet.
 * @param {object} f the flight
 * @returns {number} aUEC, per sold SCU its actual price minus its purchase price
 */
function earnedOf(f) {
  return Object.entries(f.ticks).filter(([k]) => k.includes('|sell#')).reduce((a, [k, v]) => {
    const pk = k.split('|sell#')[0];
    const p = [...f.w.paths, ...(f.w.lost || [])].find((x) => pathKey(x) === pk);
    return a + v.qty * (v.price - (p ? p.pb : v.price));
  }, 0);
}

/**
 * Sums the purchase value of the cargo aboard: ticked purchases not yet ticked as sold.
 * @param {object} f the flight
 * @returns {number} aUEC
 */
function aboardValueOf(f) {
  const bought = Object.entries(f.ticks).filter(([k]) => k.includes('|buy#')).reduce((a, [, v]) => a + v.qty * v.price, 0);
  const sold = Object.entries(f.ticks).filter(([k]) => k.includes('|sell#')).reduce((a, [k, v]) => {
    const p = [...f.w.paths, ...(f.w.lost || [])].find((x) => pathKey(x) === k.split('|sell#')[0]);
    return a + v.qty * (p ? p.pb : 0);
  }, 0);
  return Math.max(0, bought - sold);
}

/**
 * Ends a flight and leaves its history entry.
 * @param {object} f the flight
 * @param {string} outcome finished, abandoned or expired, with the stop
 * @returns {object} the history entry
 */
function endFlight(f, outcome) {
  const e = evaluate(f.w, f.ships);
  const lines = e.stops.flatMap((s) => s.jobs.map((j) => {
    const t = flightTickOf(f, j);
    return [s.place.name, j.side === 'buy' ? 'Kauf' : 'Verkauf', j.c, j.qty, t ? t.qty : 0, j.p, t ? t.price : null];
  }));
  const entry = { id: `h${Date.now()}`, name: f.name, path: e.stops.map((s) => s.place.name).join(' › '), ships: e.convoy ? `Konvoi · ${e.ships.length} Schiffe` : f.ships[0].name, src: f.src, outcome, earned: earnedOf(f), planned: f.planned, ended: 'gerade eben', lines, w: f.w };
  if (f.kind === 'mission') { state.mflightEnded[f.mission] = entry; state.mflights[f.mission] = null; } else { state.history = [entry, ...state.history].slice(0, 100); state.fl = null; }
  return entry;
}

/**
 * Renders one loading job of one ship in the flight view, in the tick layout the prototype bar chose.
 * @param {object} f the flight
 * @param {object} e the computed flight route
 * @param {object} s the computed stop
 * @param {object} j the job
 * @param {number} i the ship index
 * @param {boolean} ro whether the viewer only watches
 * @returns {string} markup
 */
function flightJob(f, e, s, j, i, ro) {
  const tk = `${j.key}#${i}`;
  const planned = e.convoy ? j.shares[i] : j.qty;
  const t = f.ticks[tk];
  const buy = j.side === 'buy';
  const who = e.convoy ? `<span class="tr-hint">${esc(e.ships[i].label)}</span> ` : '';
  const verb = `<span class="tr-flyside tr-flyside--${j.side}">${buy ? 'Kaufen' : 'Verkaufen'}</span>`;
  const mark = f.w.marks.find((m) => m.t === s.t && m.c === j.c && m.side === j.side);
  const markBtn = ro || t ? '' : `<button class="btn ${mark ? 'btn-outline' : 'btn-ghost'} btn-xs" data-act="ed-mark" data-ctx="flight" data-si="${s.si}" data-c="${esc(j.c)}" data-side="${j.side}">${mark ? 'Alternativen' : buy ? 'Ausverkauft' : 'Keine Nachfrage'}</button>`;
  const done = t ? `<div class="tr-flydone">${ic('check')} Ist ${fmt(t.qty)} SCU à ${fmt(t.price)}${t.qty !== planned ? ` <span class="warn-text">statt ${fmt(planned)}</span>` : ''}${t.price !== j.p ? ` <span class="warn-text">statt à ${fmt(j.p)}</span>` : ''} · abgehakt von ${esc(t.by)}</div>` : '';
  if (state.ed.tick === 'B' && !ro) {
    if (t) return `<div class="tr-fly__job is-done tr-fly__job--b"><div class="tr-fly__jobmain">${who}${verb}<strong>${esc(j.c)}</strong>${done}</div><button class="btn btn-ghost btn-xs" data-act="fl-untick" data-key="${tk}">Zurücknehmen</button></div>`;
    return `<div class="tr-fly__job tr-fly__job--b ${mark ? 'is-soldout' : ''}"><div class="tr-fly__jobmain">${who}${verb}<strong>${esc(j.c)}</strong> <span class="tr-hint">geplant ${fmt(planned)} SCU à ${fmt(j.p)}${!buy && j.demand === null ? ' · Nachfrage unbekannt' : ''}</span></div>
      <div class="tr-fly__fields"><div class="tr-field"><label for="fq-${tk}">Menge (SCU)</label><input id="fq-${tk}" type="number" min="0" value="${planned}" inputmode="numeric"></div><div class="tr-field"><label for="fp-${tk}">Preis (aUEC/SCU)</label><input id="fp-${tk}" type="number" min="0" value="${j.p}" inputmode="numeric"></div>
      <button class="btn btn--cta" data-act="fl-tick-fields" data-key="${tk}">${ic('check')} ${buy ? 'Gekauft' : 'Verkauft'}</button></div>${markBtn ? `<div>${markBtn}</div>` : ''}</div>`;
  }
  const open = state.flyEditKey === tk && !t && !ro;
  const fields = open ? `<div class="tr-fly__fields"><div class="tr-field"><label for="fq-${tk}">Ist-Menge (SCU)</label><input id="fq-${tk}" type="number" min="0" value="${planned}" inputmode="numeric"></div><div class="tr-field"><label for="fp-${tk}">Ist-Preis (aUEC/SCU)</label><input id="fp-${tk}" type="number" min="0" value="${j.p}" inputmode="numeric"></div><button class="btn btn--cta" data-act="fl-tick-fields" data-key="${tk}">${ic('check')} Abhaken</button></div>` : '';
  return `<div class="tr-fly__job ${t ? 'is-done' : ''} ${mark ? 'is-soldout' : ''}"><div class="tr-fly__jobrow"><label><input type="checkbox" data-act="${t ? 'fl-untick' : 'fl-tick'}" data-key="${tk}" ${t ? 'checked' : ''} ${ro ? 'disabled' : ''}><span>${who}${verb}<br><strong class="tr-flyqty">${fmt(planned)} SCU ${esc(j.c)}</strong> <span class="tr-hint">à ${fmt(j.p)} aUEC${!buy && j.demand === null ? ' · Nachfrage unbekannt' : ''}</span></span></label>
    <span class="tr-iconbtns">${t || ro ? '' : `<button class="btn btn-ghost btn-xs" data-act="fl-edit-key" data-key="${tk}" aria-expanded="${open}">Menge/Preis</button>`}${markBtn}</span></div>${done}${fields}</div>`;
}

/**
 * Renders the compact diff of the flight's last re-plan.
 * @param {object} f the flight
 * @param {object} e the computed flight route
 * @param {boolean} ro whether the viewer only watches
 * @returns {string} markup
 */
function flightReplanCard(f, e, ro) {
  if (!f.last || !f.last.diff) return '';
  const old = evaluate(f.last.before, f.ships);
  const lines = f.last.diff.filter((l) => l.status !== 'UNCHANGED').map((l) => `<div class="tr-flydiff__line"><span class="tr-mark tr-mark--${DIFF_CLS[l.status]}">${DIFF_TEXT[l.status]}</span> ${pathText(l)} ${l.before ? fmt(l.before) : ''}${l.before && l.after ? ' → ' : ''}${l.after ? `<strong>${fmt(l.after)}</strong>` : ''} SCU</div>`).join('');
  const undo = f.last.tick ? '<span class="tr-hint">Zurücknehmen des Hakens nimmt die Neuplanung mit zurück.</span>' : ro ? '' : `<button class="btn btn-ghost btn-xs" data-act="fl-undo" ${f.undo.length ? '' : 'disabled'}>${ic('arrow-left')} Rückgängig</button>`;
  const sold = f.last.belowPlan && !ro ? `<button class="btn btn-outline btn-xs" data-act="ed-mark" data-ctx="flight" data-si="${f.last.belowPlan.si}" data-c="${esc(f.last.belowPlan.c)}" data-side="buy">Ausverkauft markieren</button>` : '';
  return `<div class="tr-flydiff" role="status"><div class="tr-row tr-row--between"><strong>${ic('reset')} Rest neu geplant</strong><span class="tr-hint">Gewinn ${fmt(old.profit)} → <strong class="${e.profit >= old.profit ? 'pos' : 'neg'}">${fmt(e.profit)}</strong></span></div><div class="tr-hint">${esc(f.last.label)}</div>${lines || '<div class="tr-hint">Frachtplan unverändert.</div>'}<div class="tr-row">${sold}${undo}</div></div>`;
}

/**
 * Renders the flight view: progress, money, the current step and the remaining stops, or the adjust view.
 * @returns {string} markup
 */
function flightView2() {
  const f = openFlight();
  if (state.flyDone) return flightDoneView();
  if (!f) return '';
  const e = evaluate(f.w, f.ships);
  pageRoutes.flight = e;
  const ro = f.kind === 'mission' && state.missionRole === 'reader';
  const earned = earnedOf(f);
  const head = `<div class="tr-fly__head"><div><div class="tr-fly__eyebrow">${f.kind === 'mission' ? `Einsatzflug · ${esc(MISSION.name)}` : `Flug · ${esc(f.src)} · seit ${esc(f.started)}`}</div><h1>${esc(f.name)}</h1></div><div class="tr-iconbtns">${ro || state.flyAdjust ? '' : `<button class="btn btn-ghost" data-act="fl-adjust">${ic('edit')} Route anpassen</button>`}<button class="btn btn-ghost" data-act="fl-close">${ic('close')} Schließen</button></div></div>`;
  const live = f.kind === 'mission' ? `<div class="tr-warnline tr-warnline--info">${ic('users')}<span>${ro ? '<strong>Live · nur ansehen.</strong> Ticks, Markierungen und Änderungen der Flotte erscheinen hier sofort.' : 'Ein Flug für die ganze Routenflotte: jede Crew und jeder Einsatzleiter hakt ab, markiert und ändert; alle Leser sehen live zu.'}</span></div>` : '';
  const conflict = f.conflict ? `<div class="tr-warnline tr-warnline--danger" role="alert">${ic('warning')}<span><strong>Flug hat sich inzwischen geändert.</strong> ${esc(f.conflict.why)} Deine Änderung „${esc(f.conflict.label)}“ wurde nicht übernommen.</span><span class="tr-actions"><button class="btn btn-outline btn-xs" data-act="fl-retry">Erneut ausführen</button><button class="btn btn-ghost btn-xs" data-act="fl-dismiss">Verwerfen</button></span></div>` : '';
  const money = `<div class="tr-flymoney"><span>Verdient <strong class="${earned >= 0 ? 'pos' : 'neg'}">${earned < 0 ? '−' : ''}${fmt(Math.abs(earned))}</strong></span><span>+ noch geplant <strong>${fmt(e.profit - earned)}</strong></span><span>= <strong>${fmt(e.profit)}</strong> aUEC</span><span class="tr-hint">Ladung an Bord: ${fmt(aboardValueOf(f))} aUEC Einkaufswert</span></div>`;
  const progress = `<div class="tr-fly__progress" aria-hidden="true">${e.stops.map((_, i) => `<i class="${i < f.pos.idx || (i === f.pos.idx && f.pos.phase === 'leg') ? 'is-done' : i === f.pos.idx ? 'is-now' : ''}"></i>`).join('')}</div>`;
  if (state.flyAdjust) {
    const k = edWork('flight');
    return `<div class="tr-fly" role="dialog" aria-modal="true" aria-label="Route anpassen"><div class="tr-fly__inner tr-fly__inner--wide">${head}${progress}${stopIndex(k, e)}${conflict}
      <div class="tr-warnline tr-warnline--info">${ic('edit')}<span><strong>Route anpassen.</strong> Was vor dem aktuellen Stopp liegt, ist eingefroren; jede Änderung wirkt auf den Rest. Ladung an Bord und Bargeld gehen fest in jede Neuplanung ein. Das Ergebnis übernimmt der Server in den Flug.</span></div>
      <div class="tr-workbar"><span class="tr-iconbtns"><button class="btn btn-ghost btn-xs" data-act="fl-undo" ${f.undo.length ? '' : 'disabled'}>${ic('arrow-left')} Rückgängig${f.undo.length ? ` (${f.undo.length})` : ''}</button><button class="btn btn-ghost btn-xs" data-act="fl-redo" ${f.redo.length ? '' : 'disabled'}>Wiederholen ${ic('arrow-right')}</button></span><span class="tr-hint">Am Server, von jedem Gerät${f.kind === 'mission' ? ' und jeder Crew der Flotte' : ''}; Abhaken leert den Verlauf.</span></div>
      ${money}${keptBanner(k, e)}${diffBlock(k, e)}${warnList(e)}${editTimeline(k, e)}</div>
      <div class="tr-fly__foot"><button class="btn btn--cta" data-act="fl-adjust-done">${ic('check')} Zurück zum Flug</button></div></div>`;
  }
  const n = expandShips(f.ships).length;
  const shipSel = e.convoy ? `<div class="tr-field"><span class="tr-cap">Ansicht für</span><div class="tr-fly__shipsel">${[-1, ...e.ships.map((_, i) => i)].map((i) => `<button class="tr-toggle" data-act="fl-ship" data-val="${i}" aria-pressed="${f.ship === i}">${i < 0 ? 'Ganzer Konvoi' : esc(e.ships[i].label)}</button>`).join('')}</div></div>` : '';
  const stop = e.stops[f.pos.idx];
  let main = '';
  let foot = '';
  if (f.pos.phase === 'stop') {
    const ships = e.convoy ? (f.ship >= 0 ? [f.ship] : e.ships.map((_, i) => i)) : [0];
    const jobs = [...stop.jobs].sort((a, b) => (a.side === b.side ? 0 : a.side === 'sell' ? -1 : 1));
    const rows = jobs.flatMap((j) => ships.filter((i) => (n > 1 ? j.shares[i] : j.qty) > 0).map((i) => flightJob(f, e, stop, j, i, ro))).join('');
    const open = jobs.flatMap((j) => ships.filter((i) => (n > 1 ? j.shares[i] : j.qty) > 0).map((i) => `${j.key}#${i}`)).filter((k) => !f.ticks[k]).length;
    const markPanelHere = state.ed.panel && state.ed.panel.ctx === 'flight' && state.ed.pick === 'inline' ? markPanel(edWork('flight')) : '';
    const gone = stop.gone.map((g) => `<div class="tr-warnline tr-warnline--danger">${ic('warning')}<span>${esc(g.c)}: ${fmt(g.qty)} SCU ${esc(g.gone)}</span></div>`).join('');
    main = `<div class="tr-fly__next"><div class="tr-fly__eyebrow">Stopp ${f.pos.idx + 1} von ${e.stops.length} · ${stop.place.kind} · ${stop.place.system}</div>
      <div class="tr-fly__big">${esc(stop.term.name)}</div>${stopFacts(stop)}
      <div class="tr-stack" style="gap:8px">${rows || '<span class="tr-hint">Kein Ladeauftrag für diese Ansicht.</span>'}</div>${gone}${markPanelHere}
      ${stopTime(stop)}
      <div class="tr-row"><button class="btn btn-ghost btn-xs" data-act="rate" data-route="flight" data-stop="${stop.si}">Stopp bewerten</button>${ro ? '' : '<button class="btn btn-quiet-danger btn-xs" data-act="fl-abandon">Flug abbrechen</button>'}</div></div>`;
    const last = f.pos.idx === e.stops.length - 1;
    const full = e.stops.length >= ROUTE_LIMIT;
    if (last && !ro) main += `<div class="tr-fly__append ${full ? 'is-full' : ''}">${ic(full ? 'info' : 'plus')}<span>${full ? `<strong>Routengrenze: höchstens ${ROUTE_LIMIT} Stopps, erledigte mitgezählt.</strong> Anhängen geht nicht mehr. Nach „Route abschließen“ kannst du ab hier weiterbauen.` : '<strong>Letzter geplanter Stopp.</strong> Willst du weiter, häng den nächsten Stopp an: Ladung an Bord und Bargeld gehen fest ein.'}</span></div>`;
    const append = last && !ro ? `<button class="btn btn-outline" data-act="bd-open" data-ctx="flight" ${full ? 'disabled' : ''} title="${full ? `Routengrenze: höchstens ${ROUTE_LIMIT} Stopps` : 'Kandidaten mit „Mit Weiterflug“ und „Bis hier“'}">${ic('plus')} Nächsten Stopp anhängen</button>` : '';
    foot = ro ? '<span class="tr-hint">Live mitlesen · Schritte setzen die Crews</span>' : `${append}<button class="btn btn--cta" data-act="${last ? 'fl-finish' : 'fl-next'}">${ic('check')} ${last ? 'Route abschließen' : 'Stopp erledigt'}${open ? ` (${open} offen)` : ''}</button>`;
  } else {
    const leg = e.legs[f.pos.idx];
    const next = e.stops[f.pos.idx + 1];
    main = `<div class="tr-fly__next"><div class="tr-fly__eyebrow">Etappe ${f.pos.idx + 1} von ${e.legs.length}</div><div class="tr-fly__big">Fliege zu ${esc(next.term.name)}</div>
      <div><strong>${leg.est.length ? '≈ ' : ''}${fmtShort(leg.dur)}</strong> · ${dec(leg.gm)} Gm · an Bord ${fmt(leg.aboard)} SCU</div></div>`;
    foot = ro ? '<span class="tr-hint">Live mitlesen · Schritte setzen die Crews</span>' : `<button class="btn btn-ghost" data-act="fl-back">${ic('arrow-left')} Zurück</button><button class="btn btn--cta" data-act="fl-arrive">${ic('map-pin')} Angekommen</button>`;
  }
  const later = afterList(e, f.pos.idx + 1);
  return `<div class="tr-fly" role="dialog" aria-modal="true" aria-label="Flugansicht"><div class="tr-fly__inner">${head}${progress}${live}${conflict}${money}${flightReplanCard(f, e, ro)}${shipSel}${main}${later}</div><div class="tr-fly__foot">${foot}</div></div>`;
}

/**
 * Renders the end of a flight with its outcome and the follow-up actions.
 * @returns {string} markup
 */
function flightDoneView() {
  const h = state.flyDone;
  return `<div class="tr-fly" role="dialog" aria-modal="true" aria-label="Flug beendet"><div class="tr-fly__inner">
    <div class="tr-fly__next"><div class="tr-fly__eyebrow">${esc(h.outcome)}</div><div class="tr-fly__big">${esc(h.name)}</div>
    <div class="tr-flymoney"><span>Verdient <strong class="${h.earned >= 0 ? 'pos' : 'neg'}">${h.earned < 0 ? '−' : ''}${fmt(Math.abs(h.earned))}</strong></span><span>geplant beim Start <strong>${fmt(h.planned)}</strong> aUEC</span></div>
    <span class="tr-hint">${h.mission ? 'Der Eintrag bleibt bei der Einsatzroute.' : 'Steht jetzt unter „Meine Flüge“, 90 Tage lang.'} Stimmte etwas nicht? Bewerte die Stopps, damit die nächste Crew es weiß.</span></div>
    ${!h.mission && h.outcome === 'abgeschlossen' && h.w ? `<div class="card tr-fly__onward"><div><div class="tr-cap">Weiterfliegen?</div><span class="tr-hint">Der Planer baut eine neue Route ab ${esc(tname(h.w.stops[h.w.stops.length - 1].t))}, mit den Schiffen und der Anfrage dieses Flugs. Der Laderaum ist leer; nichts wird übernommen. Die neue Route fliegst du als eigenen Flug.</span></div><button class="btn btn-outline" data-act="bd-from-flight" data-val="${h.id}">${ic('plus')} Ab hier weiterbauen</button></div>` : ''}</div>
    <div class="tr-fly__foot">${h.mission ? '' : `<button class="btn btn-ghost" data-act="hist-again" data-val="${h.id}">Nochmal fliegen</button><button class="btn btn-ghost" data-act="hist-save" data-val="${h.id}">Als Route speichern</button>`}<button class="btn btn--cta" data-act="fl-done-close">Schließen</button></div></div>`;
}

/**
 * Renders the running-flight bar every page carries while a personal flight runs.
 * @returns {string} markup
 */
function flightBar() {
  const f = state.fl;
  if (!f || state.flyOpen || state.flyDone) return '';
  const e = evaluate(f.w, f.ships);
  const where = f.pos.phase === 'stop' ? `Stopp ${f.pos.idx + 1} von ${e.stops.length} · ${esc(e.stops[f.pos.idx].term.name)}` : `unterwegs nach ${esc(e.stops[f.pos.idx + 1].term.name)}`;
  if (state.ed.bar === 'B') return `<div class="tr-flymini" role="status"><div class="tr-flymini__k">${ic('arrow-right')} Flug läuft</div><div class="tr-flymini__name">${esc(f.name)}</div><div class="tr-hint">${where}</div><div class="tr-fly__progress" aria-hidden="true">${e.stops.map((_, i) => `<i class="${i < f.pos.idx ? 'is-done' : i === f.pos.idx ? 'is-now' : ''}"></i>`).join('')}</div><button class="btn btn--cta btn-xs" data-act="fl-open">Fortsetzen</button></div>`;
  return `<div class="tr-flybar" role="status"><span class="tr-flybar__k">${ic('arrow-right')} Flug läuft</span><strong>${esc(f.name)}</strong><span class="tr-hint">${where}</span><span class="tr-flybar__sp"></span><button class="btn btn--cta btn-xs" data-act="fl-open">Fortsetzen</button></div>`;
}

/**
 * Renders the member's flight history as the routes page's fourth tab.
 * @returns {string} markup
 */
function flightsTab() {
  const list = state.history;
  if (!list.length) return '<div class="empty-state"><div class="empty-title">Noch keine Flüge</div><div class="empty-text">Ein beendeter Flug bleibt hier 90 Tage, ohne Zeiten.</div></div>';
  const rows = list.map((h) => {
    const open = state.histOpen === h.id;
    const detail = open ? `<tr class="tr-histdetail"><td colspan="6"><div class="tr-stack" style="gap:8px">
      ${h.lines.length ? `<div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Stopp</th><th>Seite</th><th>Ware</th><th class="num">SCU geplant</th><th class="num">SCU Ist</th><th class="num">à geplant</th><th class="num">à Ist</th></tr></thead><tbody>${h.lines.map((l) => `<tr><td>${esc(l[0])}</td><td><span class="tr-side tr-side--${l[1] === 'Kauf' ? 'buy' : 'sell'}">${l[1]}</span></td><td class="tr-cname">${esc(l[2])}</td><td class="num">${fmt(l[3])}</td><td class="num ${l[4] !== l[3] ? 'warn-text' : ''}">${l[4] ? fmt(l[4]) : '—'}</td><td class="num">${fmt(l[5])}</td><td class="num ${l[6] !== null && l[6] !== l[5] ? 'warn-text' : ''}">${l[6] === null ? '—' : fmt(l[6])}</td></tr>`).join('')}</tbody></table></div>` : '<span class="tr-hint">Keine Ladeaufträge abgehakt.</span>'}
      <div class="tr-actions"><button class="btn btn-outline btn-xs" data-act="hist-again" data-val="${h.id}">${ic('arrow-right')} Nochmal fliegen</button><button class="btn btn-ghost btn-xs" data-act="hist-save" data-val="${h.id}">${ic('save')} Als Route speichern</button><button class="btn btn-quiet-danger btn-xs" data-act="hist-delete" data-val="${h.id}">${ic('trash')} Löschen</button><span class="tr-hint">„Nochmal fliegen“ plant die Stopps auf aktuellen Daten neu und öffnet sie im Planer.</span></div></div></td></tr>` : '';
    return `<tr><td><a class="row-link" href="#" data-act="hist-open" data-val="${h.id}" aria-expanded="${open}"><span class="cell-title">${esc(h.name)}</span><span class="cell-sub">${esc(seqText(h.path))} · ${esc(h.src)}</span></a></td>
      <td class="cell-status"><span class="tr-mark tr-mark--${h.outcome === 'abgeschlossen' ? 'ok' : 'warn'}">${esc(h.outcome)}</span></td>
      <td data-label="Schiffe">${esc(h.ships)}</td>
      <td class="num" data-label="Verdient"><strong class="${h.earned >= 0 ? 'pos' : 'neg'}">${h.earned < 0 ? '−' : ''}${fmt(Math.abs(h.earned))}</strong><div class="tr-hint">geplant ${fmt(h.planned)}</div></td>
      <td data-label="Beendet">${esc(h.ended)}</td>
      <td class="cell-actions"><svg class="krt-icon row-chevron" aria-hidden="true"><use href="#krt-icon-chevron-${open ? 'up' : 'down'}"></use></svg></td></tr>${detail}`;
  }).join('');
  return `<div class="tr-row tr-row--between"><span class="tr-hint">Deine beendeten Flüge: Stopps, Schiffe, der letzte Plan mit Ist-Mengen und Ist-Preisen, verdient neben geplant. Ohne Zeiten. 90 Tage, höchstens 100; nur du siehst sie.</span><span class="tr-hint">${list.length} von 100</span></div>
    <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr><th>Flug</th><th class="cell-status">Ausgang</th><th>Schiffe</th><th class="num">aUEC</th><th>Beendet</th><th class="cell-actions"><span class="visually-hidden">Details</span></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the mission flight of a mission route on the mission's routes tab.
 * @param {object} mr the mission route
 * @param {object} plan the stored plan
 * @returns {string} markup
 */
function missionFlightPanel(mr, plan) {
  const f = state.mflights[mr.id];
  const ended = state.mflightEnded[mr.id];
  const role = state.missionRole;
  const crew = role === 'crew' && mr.fleet.includes(MY_UNIT);
  const may = role === 'manager' || crew;
  if (!f) {
    const last = ended ? `<div class="tr-hint">Letzter Einsatzflug: <strong>${esc(ended.outcome)}</strong> ${esc(ended.ended)} · verdient <strong class="${ended.earned >= 0 ? 'pos' : 'neg'}">${fmt(ended.earned)}</strong> von geplant ${fmt(ended.planned)} aUEC <button class="btn btn-ghost btn-xs" data-act="mf-ended" data-val="${mr.id}">${state.mEndedOpen === mr.id ? 'Details ausblenden' : 'Details'}</button></div>${state.mEndedOpen === mr.id ? `<div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Stopp</th><th>Seite</th><th>Ware</th><th class="num">SCU geplant</th><th class="num">SCU Ist</th><th class="num">à Ist</th></tr></thead><tbody>${ended.lines.map((l) => `<tr><td>${esc(l[0])}</td><td>${l[1]}</td><td class="tr-cname">${esc(l[2])}</td><td class="num">${fmt(l[3])}</td><td class="num">${l[4] ? fmt(l[4]) : '—'}</td><td class="num">${l[6] === null ? '—' : fmt(l[6])}</td></tr>`).join('')}</tbody></table></div>` : ''}` : '<span class="tr-hint">Noch kein Einsatzflug. Das erste „Losfliegen (mein Teil)“ einer Crew startet ihn für die ganze Routenflotte.</span>';
    return `<div class="card card--inset tr-mflight"><div class="tr-cap">Einsatzflug</div>${last}</div>`;
  }
  const e = evaluate(f.w, f.ships);
  const jobs = e.stops.flatMap((s) => s.jobs);
  const perShip = e.ships.map((s, i) => {
    const mine = jobs.filter((j) => (e.convoy ? j.shares[i] : j.qty) > 0);
    const done = mine.filter((j) => f.ticks[`${j.key}#${i}`]).length;
    return `<span class="tr-fleetchip ${s.short === MY_UNIT && crew ? 'is-mine' : ''}"><span class="squadron-badge">${esc(s.short)}</span>${done} von ${mine.length} Ladeaufträgen</span>`;
  }).join('');
  const where = f.pos.phase === 'stop' ? `an Stopp ${f.pos.idx + 1} von ${e.stops.length} · ${esc(e.stops[f.pos.idx].term.name)}` : `unterwegs nach ${esc(e.stops[f.pos.idx + 1].term.name)}`;
  const btn = may ? `<button class="btn btn--cta btn-xs" data-act="mf-open" data-val="${mr.id}">${ic('arrow-right')} Mitfliegen${crew ? ' (mein Teil)' : ''}</button>` : `<button class="btn btn-outline btn-xs" data-act="mf-open" data-val="${mr.id}">${ic('eye')} Live ansehen</button>`;
  return `<div class="card card--inset tr-mflight tr-mflight--live"><div class="tr-row tr-row--between"><div class="tr-cap">${ic('arrow-right')} Einsatzflug läuft · Konvoi ${where}</div>${btn}</div>
    <div class="tr-fly__progress" aria-hidden="true">${e.stops.map((_, i) => `<i class="${i < f.pos.idx || (i === f.pos.idx && f.pos.phase === 'leg') ? 'is-done' : i === f.pos.idx ? 'is-now' : ''}"></i>`).join('')}</div>
    <div class="tr-row">${perShip}</div>
    <div class="tr-hint">Zuletzt: ${esc(f.lastActivity || 'Rost hat Medical Supplies (Kauf, BRAVO) abgehakt')} · verdient ${fmt(earnedOf(f))} aUEC. ${role === 'manager' ? '„Route aktualisieren“ ändert den gespeicherten Plan, nicht den laufenden Flug.' : ''}</div></div>`;
}
