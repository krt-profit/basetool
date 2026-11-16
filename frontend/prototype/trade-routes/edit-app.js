/** PROTOTYPE wiring of the edit flows and flights: state, edit steps, actions and the prototype bar's controls. */

const REQUEST_ACTS = ['set', 'toggle', 'pick', 'pick-place', 'list-remove', 'chip-remove', 'reset', 'ship-remove', 'ship-add'];
let optTimers = [];

/**
 * Builds a tab's working state from its computed stops.
 * @param {{label: string, stops: Array<string>, forced: Array<string>}} def the tab
 * @param {Array<object>} ships the ship list
 * @returns {object} the tab with its history
 */
function edTab(def, ships) {
  const stops = def.stops.map((t) => ({ t, pinned: false, forced: def.forced.includes(t) }));
  const p = planCargo(stops, { ships, marks: [] });
  return { label: def.label, hist: [{ label: 'Berechnet', w: { stops, paths: p.paths, lost: [], marks: [], ships, lastFull: p.paths, kept: false } }], at: 0 };
}

/**
 * Opens a fresh working state for a source, as the planner does when a route opens in it.
 * @param {string} src result, saved, shared, unit or mission
 * @returns {void}
 */
function resetEdit(src) {
  const ships = src === 'mission' ? ['ALPHA', 'BRAVO'].map((k) => ({ ...FLEET_SHIPS[k] })) : state.request.ships.map((s) => ({ ...s }));
  Object.assign(state.ed, { tabs: editTabsFor(state.request.maxStops).map((d) => edTab(d, ships)), tab: 0, src, requestChanged: false, panel: null, optimising: null });
}

/**
 * Adds the edit and flight state to the prototype's state, read from the URL where given.
 * @returns {void}
 */
function initEditState() {
  const pick = (k, ok, def) => (ok.includes(params.get(k)) ? params.get(k) : def);
  initLongState();
  if (state.page === 'flights') { state.page = 'routes'; state.routesTab = 'flights'; }
  if (params.get('tab') === 'flights') state.routesTab = 'flights';
  state.ed = { diffView: pick('diff', ['A', 'B'], 'A'), pick: pick('pick', ['dialog', 'inline'], 'dialog'), tick: pick('tick', ['A', 'B'], 'A'), bar: pick('bar', ['A', 'B'], 'A'), conflict: false, conflictNext: false };
  state.request.armistice = true;
  resetEdit(pick('src', ['result', 'saved', 'shared', 'unit', 'mission'], 'result'));
  initBuildState();
  Object.assign(state, { fl: null, flyOpen: null, flyAdjust: false, flyDone: null, flyEditKey: null, history: FLIGHT_HISTORY_SEED.map((h) => ({ ...h })), histOpen: null, mflights: {}, mflightEnded: {}, mEndedOpen: null });
  const mr = state.missionRoutes[0];
  const ships = mr.fleet.map((k) => ({ ...FLEET_SHIPS[k] }));
  const mf = newFlight('mission', fromComputed(computeRoute(ROUTES.find((x) => x.id === mr.base), ships), ships), { name: mr.name, src: 'Einsatzroute', mission: mr.id });
  const e = evaluate(mf.w, ships);
  e.stops[0].jobs.forEach((j) => ships.forEach((_, i) => { if (j.shares[i] > 0) mf.ticks[`${j.key}#${i}`] = { qty: j.shares[i], price: j.p, by: i ? 'Rost' : 'Vagabund' }; }));
  mf.pos = { idx: 1, phase: 'stop' };
  mf.lastActivity = 'Rost hat an Seraphim Station alles für BRAVO abgehakt; der Konvoi steht an Area18';
  state.mflights[mr.id] = mf;
  state.mflightEnded.M2 = { outcome: 'abgeschlossen', ended: '12.11., 23:10', earned: 1018400, planned: 984000, lines: [['Port Tressler', 'Kauf', 'Distilled Spirits', 380, 380, 1105, 1105], ['Levski', 'Verkauf', 'Distilled Spirits', 380, 380, 1690, 1702], ['Levski', 'Kauf', 'Hephaestanite', 696, 640, 2210, 2230], ['Ruin Station', 'Verkauf', 'Hephaestanite', 696, 640, 2960, 2990]] };
  const fly = params.get('flight');
  if (fly === 'personal') { const tb0 = state.ed.tabs[0]; const w = tb0.hist[tb0.at].w; startFlight(w, evaluate(w, w.ships).name, 'Berechnete Route'); state.flyOpen = 'personal'; }
  if (fly === 'M1') state.flyOpen = 'M1';
  applyLongFlightParams();
}

/**
 * Resolves the planning options of an edit context; a flight adds its fixed prefix.
 * @param {object} k the edit context
 * @param {Array<object>} stops the stop sequence the re-plan uses
 * @param {Array<object>} marks the marks the re-plan honours
 * @returns {object} options for planCargo
 */
function planOpts(k, stops, marks) {
  if (k.ctx !== 'flight') return { ships: k.w.ships, marks };
  const { fixed, lost } = flightPrefix(k.flight, stops);
  return { ships: k.flight.ships, marks, fixed, fromIdx: k.flight.pos.idx, skip: tickedBuys(k.flight), lostPrefix: lost };
}

/**
 * Re-plans the cargo on a stop sequence, fully or in keep mode.
 * @param {object} k the edit context
 * @param {Array<object>} stops the stop sequence
 * @param {{keep?: Array<object>, extra?: Array<object>, marks?: Array<object>}} [more] keep mode, extra paths and replaced marks
 * @returns {object} the new working route
 */
function replan(k, stops, more = {}) {
  const marks = more.marks || k.w.marks;
  const o = { ...planOpts(k, stops, marks), keep: more.keep, extra: more.extra };
  const p = planCargo(stops, o);
  return { ...k.w, stops, marks, paths: p.paths, lost: [...(o.lostPrefix || []), ...p.lost], kept: Boolean(more.keep) && !more.extra && !more.marks, lastFull: more.keep ? k.w.lastFull : p.paths };
}

/**
 * Ticks the next open loading job at the flight's stop, as another device or crew would.
 * @param {object} f the flight
 * @returns {void}
 */
function otherDeviceTick(f) {
  const e = evaluate(f.w, f.ships);
  const s = e.stops[f.pos.idx];
  const n = expandShips(f.ships).length;
  const hit = s.jobs.flatMap((j) => Array.from({ length: n }, (_, i) => ({ j, i }))).find(({ j, i }) => (n > 1 ? j.shares[i] : j.qty) > 0 && !f.ticks[`${j.key}#${i}`]);
  if (!hit) return;
  const who = f.kind === 'mission' ? 'Rost' : 'Dein Handy';
  f.ticks[`${hit.j.key}#${hit.i}`] = { qty: n > 1 ? hit.j.shares[hit.i] : hit.j.qty, price: hit.j.p, by: who };
  f.undo = [];
  f.redo = [];
  f.lastActivity = `${who} hat ${hit.j.c} (${hit.j.side === 'buy' ? 'Kauf' : 'Verkauf'}) abgehakt`;
  say('Live aktualisiert', `${f.lastActivity}. Die Ansicht folgt ohne Neuladen.`);
}

/**
 * Runs one edit step in the planner tab or the flight; in a flight a conflicting change ends it unapplied.
 * @param {string} ctx plan or flight
 * @param {function(object): ({label: string, w: object, diff?: boolean}|null)} make computes the step from the edit context
 * @returns {void}
 */
function doStep(ctx, make) {
  const k = edWork(ctx);
  const res = make(k);
  if (!res) return;
  if (ctx === 'flight') {
    const f = k.flight;
    if (state.ed.conflictNext) {
      state.ed.conflictNext = false;
      otherDeviceTick(f);
      f.conflict = { label: res.label, why: `${f.lastActivity}, während deine Änderung lief.`, retry: () => doStep(ctx, make) };
      return;
    }
    f.undo.push({ label: res.label, w: f.w });
    if (f.undo.length > FLIGHT_UNDO_MAX) f.undo.shift();
    f.redo = [];
    f.last = res.diff === false ? null : { label: res.label, before: f.w, diff: diffPlans(f.w, res.w, f.ships) };
    f.w = res.w;
    f.conflict = null;
    return;
  }
  const tb = k.tab;
  tb.hist = tb.hist.slice(0, tb.at + 1);
  tb.hist.push({ label: res.label, w: res.w, before: k.w, diff: res.diff === false ? null : diffPlans(k.w, res.w, res.w.ships) });
  tb.at += 1;
}

/**
 * Starts a personal flight, which replaces nothing; a running flight is asked about first by the caller.
 * @param {object} w the working route
 * @param {string} name the flight's name
 * @param {string} src the source kind
 * @returns {void}
 */
function startFlight(w, name, src) {
  state.fl = newFlight('personal', w, { name, src });
  state.flyOpen = 'personal';
  state.flyAdjust = false;
}

/**
 * Opens the mark or insert panel, as a dialog or inline as the prototype bar chose.
 * @param {object} panel the panel state
 * @returns {void}
 */
function openPanel(panel) {
  state.ed.panel = panel;
  state.modal = state.ed.pick === 'dialog' || (panel.ctx === 'flight' && !state.flyAdjust && panel.kind === 'insert') ? { kind: 'ed-panel' } : null;
}

/**
 * Closes the open panel and its dialog.
 * @returns {void}
 */
function closePanel() {
  state.ed.panel = null;
  if (state.modal && state.modal.kind === 'ed-panel') state.modal = null;
}

/**
 * Reads the planned quantity and price of a loading job for one ship.
 * @param {object} f the flight
 * @param {string} tk the job key with the ship index
 * @returns {{qty: number, price: number}} the plan
 */
function plannedOf(f, tk) {
  const [jk, si] = tk.split('#');
  const e = evaluate(f.w, f.ships);
  const j = e.stops.flatMap((s) => s.jobs).find((x) => x.key === jk);
  return { qty: e.convoy ? j.shares[Number(si)] : j.qty, price: j.p };
}

/**
 * Re-plans a history entry's stops on current data and opens them in the planner.
 * @param {object} h the history entry
 * @returns {void}
 */
function flyAgain(h) {
  const ships = state.request.ships.map((s) => ({ ...s }));
  const stops = h.stopIds || (h.w ? h.w.stops.map((s) => s.t) : ['seraphim', 'area18', 'baijini']);
  state.flyDone = null;
  goPage('edit');
  resetEdit('result');
  state.ed.tabs[0] = edTab({ label: 'Aus „Meine Flüge“', stops, forced: [] }, ships);
  say('Stopps neu geplant', `„${h.name}“ auf aktuellen Daten. Prüfe die Route und flieg los.`);
}

const EDIT_ACTIONS = {
  'ed-tab': (el) => { state.ed.tab = Number(el.dataset.val); closePanel(); },
  'ed-remove': (el) => {
    const si = Number(el.dataset.si);
    doStep(el.dataset.ctx, (k) => {
      const s = k.w.stops[si];
      const stops = k.w.stops.filter((_, i) => i !== si);
      if (stops.length < 2) return null;
      if (k.ctx === 'plan' && s.forced) state.request.forced = state.request.forced.filter((t) => t !== s.t);
      return { label: `${tname(s.t)} entfernt${s.forced && k.ctx === 'plan' ? ' (verlässt die erzwungenen Terminals)' : ''}`, w: replan(k, stops) };
    });
  },
  'ed-move': (el) => {
    const si = Number(el.dataset.si);
    const d = Number(el.dataset.d);
    doStep(el.dataset.ctx, (k) => {
      const stops = k.w.stops.map((s) => ({ ...s }));
      [stops[si], stops[si + d]] = [stops[si + d], stops[si]];
      return { label: `${tname(stops[si + d].t)} nach ${d < 0 ? 'oben' : 'unten'}`, w: replan(k, stops, { keep: k.w.lastFull }) };
    });
  },
  'ed-recompute': (el) => doStep(el.dataset.ctx, (k) => ({ label: 'Waren neu berechnet', w: replan(k, k.w.stops) })),
  'ed-pin': (el) => {
    const si = Number(el.dataset.si);
    doStep(el.dataset.ctx, (k) => {
      const stops = k.w.stops.map((s, i) => (i === si ? { ...s, pinned: !s.pinned } : s));
      return { label: `${tname(stops[si].t)} ${stops[si].pinned ? 'festgesetzt' : 'freigegeben'}`, w: { ...k.w, stops }, diff: false };
    });
  },
  'ed-insert-open': (el) => openPanel({ kind: 'insert', ctx: el.dataset.ctx, pos: Number(el.dataset.pos) }),
  'ed-insert': (el) => {
    const pos = Number(el.dataset.pos);
    const t = el.dataset.t;
    doStep(el.dataset.ctx, (k) => {
      if (k.w.stops.length >= ROUTE_LIMIT) { say(`Höchstens ${ROUTE_LIMIT} Stopps`, 'Entferne zuerst einen Stopp.'); return null; }
      const why = failsFilter(t);
      if (state.request.excluded.includes(t)) state.request.excluded = state.request.excluded.filter((x) => x !== t);
      const stops = [...k.w.stops.slice(0, pos), { t, pinned: true, inserted: true, failsFilter: why }, ...k.w.stops.slice(pos)];
      return { label: `${tname(t)} eingefügt an Position ${pos + 1}`, w: replan(k, stops) };
    });
    closePanel();
  },
  'ed-panel-close': () => closePanel(),
  'ed-mark': (el) => {
    const k = edWork(el.dataset.ctx);
    const si = Number(el.dataset.si);
    const m = k.w.marks.find((x) => x.t === k.w.stops[si].t && x.c === el.dataset.c && x.side === el.dataset.side);
    openPanel({ kind: 'mark', ctx: el.dataset.ctx, si, c: el.dataset.c, side: el.dataset.side, phase: m ? 'done' : 'ask', rest: m ? m.rest : 0, sel: [] });
  },
  'ed-mark-apply': (el) => {
    const p = state.ed.panel;
    const rest = Math.max(0, Number(document.getElementById('ed-rest')?.value) || 0);
    doStep(el.dataset.ctx, (k) => {
      const t = k.w.stops[p.si].t;
      const marks = [...k.w.marks.filter((x) => !(x.t === t && x.c === p.c && x.side === p.side)), { t, c: p.c, side: p.side, rest }];
      return { label: `${p.side === 'buy' ? 'Ausverkauft' : 'Keine Nachfrage'}: ${p.c} bei ${tname(t)}, noch ${fmt(rest)} SCU`, w: replan(k, k.w.stops, { keep: k.w.paths, marks }) };
    });
    Object.assign(p, { phase: 'done', rest });
  },
  'ed-unmark': (el) => {
    const si = Number(el.dataset.si);
    doStep(el.dataset.ctx, (k) => {
      const t = k.w.stops[si].t;
      const marks = k.w.marks.filter((x) => !(x.t === t && x.c === el.dataset.c && x.side === el.dataset.side));
      return { label: `Markierung aufgehoben: ${el.dataset.c}`, w: replan(k, k.w.stops, { keep: k.w.lastFull, marks }) };
    });
    closePanel();
  },
  'ed-alt-sel': (el) => { const i = Number(el.dataset.i); const s = state.ed.panel.sel; state.ed.panel.sel = s.includes(i) ? s.filter((x) => x !== i) : [...s, i]; },
  'ed-alt-take': (el) => {
    const p = state.ed.panel;
    doStep(el.dataset.ctx, (k) => {
      const alts = alternativesAt(k.w, p.si, k.w.ships).filter((_, i) => p.sel.includes(i));
      return { label: `${alts.length} alternative Ware${alts.length > 1 ? 'n' : ''} bei ${tname(k.w.stops[p.si].t)} übernommen`, w: replan(k, k.w.stops, { keep: k.w.paths, extra: alts }) };
    });
    closePanel();
  },
  'ed-replan-here': (el) => { doStep(el.dataset.ctx, (k) => ({ label: 'Fracht ab hier neu geplant', w: replan(k, k.w.stops) })); closePanel(); },
  'ed-sell-later': (el) => {
    const p = state.ed.panel;
    const to = el.dataset.t;
    doStep(el.dataset.ctx, (k) => {
      const t = k.w.stops[p.si].t;
      const src = k.w.lastFull.find((x) => x.c === p.c && x.to === t) || k.w.paths.find((x) => x.c === p.c && x.to === t);
      if (!src) return null;
      return { label: `${p.c} an ${tname(to)} verkaufen`, w: replan(k, k.w.stops, { keep: k.w.paths, extra: [{ c: p.c, from: src.from, to, pb: src.pb, ps: PRICES[to].sell[p.c][0], qty: 99999 }] }) };
    });
    closePanel();
  },
  'ed-report': () => { closePanel(); say('Gemeldet', 'Verfügbarkeit anonym gemeldet; der Durchschnitt folgt mit dem nächsten Stundenlauf.'); },
  'ed-undo': () => { const tb = state.ed.tabs[state.ed.tab]; tb.at = Math.max(0, tb.at - 1); closePanel(); },
  'ed-redo': () => { const tb = state.ed.tabs[state.ed.tab]; tb.at = Math.min(tb.hist.length - 1, tb.at + 1); closePanel(); },
  'ed-restore': () => { const tb = state.ed.tabs[state.ed.tab]; doStep('plan', () => ({ label: 'Berechnete Route wiederhergestellt', w: tb.hist[0].w })); },
  'ed-optimise': () => {
    closePanel();
    const upTo = Math.min(searchLimit(), Math.max(state.request.maxStops, edWork('plan').w.stops.length));
    const long = upTo > LONG_JOB_ABOVE;
    state.ed.optimising = { step: 0, elapsed: 0, upTo, long };
    optTimers = [[900, 1, long ? 11 : 2], [1900, 2, long ? 26 : 4]].map(([ms, step, s]) => setTimeout(() => { Object.assign(state.ed.optimising, { step, elapsed: s }); render(); }, ms));
    optTimers.push(setTimeout(() => { EDIT_ACTIONS['ed-opt-finish'](); render(); }, 3000));
  },
  'ed-opt-finish': () => {
    optTimers.forEach(clearTimeout);
    state.ed.optimising = null;
    doStep('plan', (k) => {
      const stops = optimiseRest(k.w, k.w.ships);
      const same = stops.every((s, i) => s.t === k.w.stops[i].t);
      return { label: same ? 'Rest optimiert: die freien Stopps sind schon die besten' : 'Rest optimiert, feste Stopps blieben', w: replan(k, stops) };
    });
  },
  'ed-opt-stop': () => { const got = state.ed.optimising.step > 0; optTimers.forEach(clearTimeout); state.ed.optimising = null; if (got) EDIT_ACTIONS['ed-opt-finish'](); say('Suche gestoppt', got ? 'Bestes bis dahin gefundenes Ergebnis übernommen.' : 'Noch kein Ergebnis; die Route bleibt.'); },
  'ed-replan-request': () => {
    state.ed.requestChanged = false;
    doStep('plan', (k) => {
      const ships = state.request.ships.map((s) => ({ ...s }));
      const stops = k.w.stops.map((s) => ({ ...s, failsFilter: failsFilter(s.t) }));
      return { label: 'Nur Fracht neu geplant (Anfrage geändert)', w: replan({ ...k, w: { ...k.w, ships } }, stops) };
    });
  },
  'ed-update': (el) => {
    if (state.ed.conflict) { state.modal = { kind: 'ed-conflict', what: el.dataset.what }; return; }
    const tb = state.ed.tabs[state.ed.tab];
    say(`${el.dataset.what} aktualisiert`, el.dataset.what === 'Einsatzroute' ? 'Plan gespeichert; „Routenplan geändert“ geht an die Crews der Flotte.' : 'Ganzer Inhalt ersetzt: Anfrage, Stopps, Momentaufnahme. Auf dem Server gibt es dafür kein Rückgängig.');
    tb.hist = [{ label: 'Gespeichert', w: tb.hist[tb.at].w }];
    tb.at = 0;
  },
  'ed-reload': () => { state.modal = null; resetEdit(state.ed.src); say('Neu geladen', 'Der gespeicherte Stand steht im Planer; deine Schritte sind verworfen.'); },
  'ed-save-mine': () => say('Als deine Route gespeichert', `${state.savedList.length + 1} von ${SAVED_LIMIT} Plätzen.`),
  edit: () => { state.modal = null; goPage('edit'); },
  'edit-in-planner': () => { const src = state.routeSrc; goPage('edit'); resetEdit(src); },
  'm-edit': () => { goPage('edit'); resetEdit('mission'); },
  fly: (el) => {
    const id = el.dataset.route;
    const go = () => {
      if (id === 'edit') { const k = edWork('plan'); startFlight(k.w, evaluate(k.w, k.w.ships).name, SRC_LABEL[state.ed.src]); return; }
      const r = routeById(id);
      const ships = state.page === 'planner' ? state.resultShips.map((s) => ({ ...s })) : [{ ...SHIP_C2 }];
      startFlight(fromComputed(r, ships), r.name, state.page === 'route' ? OPEN_SOURCES[state.routeSrc].label : 'Berechnete Route');
    };
    if (state.fl) { state.pendingFly = go; confirmFirst('Laufenden Flug abbrechen?', `„${state.fl.name}“ läuft noch. Losfliegen bricht ihn ab; er landet als „abgebrochen“ in „Meine Flüge“.`, 'Abbrechen und losfliegen', 'do-fly-replace', ''); return; }
    go();
  },
  'ed-fly': () => EDIT_ACTIONS.fly({ dataset: { route: 'edit' } }),
  'do-fly-replace': () => { endFlight(state.fl, `abgebrochen an Stopp ${state.fl.pos.idx + 1}`); state.pendingFly(); },
  'fly-part': (el) => {
    const mr = state.missionRoutes.find((x) => x.base === el.dataset.route) || state.missionRoutes[0];
    if (!state.mflights[mr.id]) {
      const ships = mr.fleet.map((k) => ({ ...FLEET_SHIPS[k] }));
      state.mflights[mr.id] = newFlight('mission', fromComputed(computeRoute(ROUTES.find((x) => x.id === mr.base), ships), ships), { name: mr.name, src: 'Einsatzroute', mission: mr.id });
      say('Einsatzflug gestartet', 'Für die ganze Routenflotte; alle Leser des Einsatzes sehen live zu.');
    }
    state.mflights[mr.id].ship = Number(el.dataset.ship);
    state.flyOpen = mr.id;
  },
  'mf-open': (el) => { const f = state.mflights[el.dataset.val]; f.ship = state.missionRole === 'crew' ? Math.max(0, state.missionRoutes.find((x) => x.id === el.dataset.val).fleet.indexOf(MY_UNIT)) : -1; state.flyOpen = el.dataset.val; },
  'mf-ended': (el) => { state.mEndedOpen = state.mEndedOpen === el.dataset.val ? null : el.dataset.val; },
  'fl-open': () => { state.flyOpen = 'personal'; },
  'fl-close': () => { state.flyOpen = null; state.flyAdjust = false; closePanel(); },
  'fl-ship': (el) => { openFlight().ship = Number(el.dataset.val); },
  'fl-tick': (el) => { const f = openFlight(); const p = plannedOf(f, el.dataset.key); tickFlight(f, el.dataset.key, p.qty, p.price); state.flyEditKey = null; },
  'fl-tick-fields': (el) => {
    const f = openFlight();
    const tk = el.dataset.key;
    const p = plannedOf(f, tk);
    const q = Number(document.getElementById(`fq-${tk}`)?.value);
    const pr = Number(document.getElementById(`fp-${tk}`)?.value);
    tickFlight(f, tk, Number.isFinite(q) ? Math.max(0, q) : p.qty, Number.isFinite(pr) && pr > 0 ? pr : p.price);
    state.flyEditKey = null;
  },
  'fl-untick': (el) => untickFlight(openFlight(), el.dataset.key),
  'fl-edit-key': (el) => { state.flyEditKey = state.flyEditKey === el.dataset.key ? null : el.dataset.key; },
  'fl-next': () => { openFlight().pos.phase = 'leg'; document.querySelector('.tr-fly')?.scrollTo(0, 0); },
  'fl-arrive': () => { const f = openFlight(); f.pos = { idx: f.pos.idx + 1, phase: 'stop' }; f.last = null; document.querySelector('.tr-fly')?.scrollTo(0, 0); },
  'fl-back': () => { openFlight().pos.phase = 'stop'; },
  'fl-finish': () => { const f = openFlight(); state.flyDone = { ...endFlight(f, 'abgeschlossen'), mission: f.kind === 'mission' }; state.flyOpen = null; },
  'fl-abandon': () => confirmFirst('Flug abbrechen?', 'Der Flug endet als „abgebrochen“ an diesem Stopp. Abgehaktes bleibt im Verlauf; die Quelle der Route bleibt unberührt.', 'Flug abbrechen', 'do-fl-abandon', ''),
  'do-fl-abandon': () => { const f = openFlight(); state.flyDone = { ...endFlight(f, `abgebrochen an Stopp ${f.pos.idx + 1}`), mission: f.kind === 'mission' }; state.flyOpen = null; },
  'fl-done-close': () => { state.flyDone = null; },
  'fl-adjust': () => { state.flyAdjust = true; closePanel(); document.querySelector('.tr-fly')?.scrollTo(0, 0); },
  'fl-adjust-done': () => { state.flyAdjust = false; closePanel(); },
  'fl-undo': () => { const f = openFlight(); const u = f.undo.pop(); if (!u) return; f.redo.push({ label: u.label, w: f.w }); f.w = u.w; f.last = null; say('Rückgängig', `„${u.label}“ zurückgenommen, für alle Geräte.`); },
  'fl-redo': () => { const f = openFlight(); const r = f.redo.pop(); if (!r) return; f.undo.push({ label: r.label, w: f.w }); f.w = r.w; f.last = null; },
  'fl-retry': () => { const f = openFlight(); const c = f.conflict; f.conflict = null; c.retry(); },
  'fl-dismiss': () => { openFlight().conflict = null; },
  'hist-open': (el) => { state.histOpen = state.histOpen === el.dataset.val ? null : el.dataset.val; },
  'hist-again': (el) => { const h = state.history.find((x) => x.id === el.dataset.val) || state.flyDone; flyAgain(h); },
  'hist-save': () => { state.flyDone = null; say('Als Route gespeichert', `Der letzte Plan des Flugs; ${state.savedList.length + 1} von ${SAVED_LIMIT} Plätzen.`); },
  'hist-delete': (el) => confirmFirst('Flug aus dem Verlauf löschen?', 'Der Eintrag verschwindet endgültig; er zählt nirgends mehr.', 'Löschen', 'do-hist-delete', el.dataset.val),
  'do-hist-delete': (v) => { state.history = state.history.filter((x) => x.id !== v); say('Flug gelöscht', ''); },
  'proto-edsrc': (el) => { if (el.dataset.val === 'mission') state.missionRole = 'manager'; resetEdit(el.dataset.val); },
  'proto-ed': (el) => { state.ed[el.dataset.key] = el.dataset.val; closePanel(); },
  'proto-conflict': () => { state.ed.conflict = !state.ed.conflict; },
  'proto-fconflict': () => { state.ed.conflictNext = !state.ed.conflictNext; },
  'proto-other': () => { const f = openFlight() || state.fl; if (f) otherDeviceTick(f); },
  'proto-startfly': () => { const k = edWork('plan'); EDIT_ACTIONS.fly({ dataset: { route: 'edit' } }); if (!state.fl) startFlight(k.w, evaluate(k.w, k.w.ships).name, 'Berechnete Route'); },
};

/**
 * Renders the prototype bar's controls for the edit page and for flights.
 * @returns {string} markup
 */
function editBarControls() {
  const btn = (act, key, val, label, on) => `<button data-act="${act}" data-key="${key}" data-val="${val}" aria-pressed="${on}">${label}</button>`;
  const sep = '<span class="proto-bar__sep"></span>';
  let out = '';
  if (state.page === 'edit') {
    out += `${sep}<select class="proto-bar__page" data-proto-edsrc aria-label="Quelle">${Object.entries(SRC_LABEL).map(([k, l]) => `<option value="${k}" ${state.ed.src === k ? 'selected' : ''}>${l}</option>`).join('')}</select>`;
    if (state.ed.src === 'unit') out += btn('proto-role', '', state.role === 'officer' ? 'member' : 'officer', state.role === 'officer' ? 'Offizier' : 'Mitglied', true);
    if (state.ed.src === 'mission') out += btn('proto-mrole', '', state.missionRole === 'manager' ? 'crew' : 'manager', state.missionRole === 'manager' ? 'Leiter' : 'Crew', true);
    if (state.ed.src !== 'result') out += `<button data-act="proto-conflict" aria-pressed="${state.ed.conflict}" title="Aktualisieren antwortet 409">409</button>`;
    out += `${sep}${btn('proto-ed', 'diffView', 'A', 'Diff A', state.ed.diffView === 'A')}${btn('proto-ed', 'diffView', 'B', 'Diff B', state.ed.diffView === 'B')}${sep}${btn('proto-ed', 'pick', 'dialog', 'Dialog', state.ed.pick === 'dialog')}${btn('proto-ed', 'pick', 'inline', 'Inline', state.ed.pick === 'inline')}`;
    if (!state.fl) out += `${sep}<button data-act="proto-startfly">Flug starten</button>`;
  }
  const f = openFlight() || state.fl;
  if (f || state.page === 'mission') {
    out += `${sep}${btn('proto-ed', 'tick', 'A', 'Haken A', state.ed.tick === 'A')}${btn('proto-ed', 'tick', 'B', 'Haken B', state.ed.tick === 'B')}`;
    if (state.fl) out += `${btn('proto-ed', 'bar', 'A', 'Leiste A', state.ed.bar === 'A')}${btn('proto-ed', 'bar', 'B', 'Leiste B', state.ed.bar === 'B')}`;
    if (f) out += `<button data-act="proto-other" title="Ein anderes Gerät oder eine andere Crew hakt ab">Anderes Gerät</button><button data-act="proto-fconflict" aria-pressed="${state.ed.conflictNext}" title="Die nächste Änderung im Flug trifft auf einen Konflikt">Konflikt</button>`;
  }
  return out + longBarControls() + buildBarControls();
}

document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-act]');
  if (el && state.page === 'edit' && !state.flyOpen && REQUEST_ACTS.includes(el.dataset.act) && el.closest('.tr-a__aside')) state.ed.requestChanged = true;
}, true);
document.addEventListener('change', (ev) => {
  const el = ev.target;
  if (state.page === 'edit' && (el.dataset.bind || el.dataset.ship) && el.closest('.tr-a__aside')) state.ed.requestChanged = true;
  if (el.dataset.protoEdsrc !== undefined) { EDIT_ACTIONS['proto-edsrc']({ dataset: { val: el.value } }); render(); }
}, true);

/**
 * Fills the insert panel's terminal search list for its current query.
 * @param {HTMLInputElement} el the search input
 * @returns {void}
 */
function fillEdSearch(el) {
  const list = el.nextElementSibling;
  list.innerHTML = edSearchOptions(el.value, el.dataset.edsearch, Number(el.dataset.pos));
  list.hidden = false;
}
document.addEventListener('input', (ev) => { if (ev.target.dataset.edsearch) fillEdSearch(ev.target); });
document.addEventListener('focusin', (ev) => { if (ev.target.dataset.edsearch) fillEdSearch(ev.target); });
document.addEventListener('focusout', (ev) => { if (ev.target.dataset.edsearch) { const list = ev.target.nextElementSibling; setTimeout(() => { list.hidden = true; }, 180); } });
