/** PROTOTYPE wiring for building a route stop by stop: state, the build entry and its validation, appending, the flight actions and the prototype bar. */

/**
 * Builds the tab of a route built stop by stop: the start screen, or start and end at once with an end terminal.
 * @param {Array<object>} ships the ship list
 * @returns {object} the tab with its history
 */
function builtTab(ships) {
  const r = state.request;
  const s0 = { t: r.start, pinned: true, start: true, forced: r.forced.includes(r.start) };
  if (!r.end || r.end === r.start) {
    return { label: 'Eigene Route', built: true, hist: [{ label: 'Start', w: { stops: [s0], paths: [], lost: [], marks: [], ships, lastFull: [], kept: false } }], at: 0 };
  }
  const stops = [s0, { t: r.end, pinned: true, end: true, forced: r.forced.includes(r.end) }];
  const p = planCargo(stops, { ships, marks: [] });
  return { label: 'Eigene Route', built: true, hist: [{ label: 'Start und Ziel', w: { stops, paths: p.paths, lost: p.lost, marks: [], ships, lastFull: p.paths, kept: false } }], at: 0 };
}

/**
 * Replaces the shown result with a route built from the request's start terminal.
 * @returns {void}
 */
function doBuildStart() {
  clearLongTimers();
  state.long.search = null;
  closePanel();
  const ships = state.request.ships.map((s) => ({ ...s }));
  Object.assign(state.ed, { tabs: [builtTab(ships)], tab: 0, src: 'result', requestChanged: false, optimising: null });
  Object.assign(state.bd, { open: [], inlineOpen: true, err: false });
}

/**
 * Appends a terminal at the append position of the planner tab or the flight, as one edit step.
 * @param {string} ctx plan or flight
 * @param {string} t terminal id
 * @returns {void}
 */
function takeCandidate(ctx, t) {
  doStep(ctx, (k) => {
    if (k.w.stops.length >= ROUTE_LIMIT) { say(`Höchstens ${ROUTE_LIMIT} Stopps`, 'Routengrenze erreicht.'); return null; }
    if (k.w.stops.some((s) => s.t === t)) return null;
    const start = k.w.stops.length === 1;
    const pos = appendPos(k.w);
    const mode = ctx === 'plan' && pos >= 2 ? state.bd.hold : 'replan';
    if (state.request.excluded.includes(t)) state.request.excluded = state.request.excluded.filter((x) => x !== t);
    const stops = [...k.w.stops.slice(0, pos), { t, pinned: true, appended: true, failsFilter: failsFilter(t), forced: state.request.forced.includes(t) }, ...k.w.stops.slice(pos)];
    const plan = appendPlan(k, stops, pos, mode);
    const w = { ...k.w, stops, paths: plan.paths, lost: plan.lost, kept: false, lastFull: plan.paths };
    let label = `${tname(t)} angehängt`;
    if (start) label = `Zweiter Stopp: ${tname(t)}`;
    else if (ctx === 'plan') label += mode === 'hold' ? ' · bisherige Fracht gehalten' : ' · Fracht neu geplant';
    return { label, w, diff: start ? false : undefined };
  });
  closePanel();
  state.bd.open = [];
}

/**
 * Appends the top candidate until the built route has a number of stops, for links to longer built routes.
 * @param {number} n the stop count to reach
 * @returns {void}
 */
function autoBuild(n) {
  for (let i = 0; i < ROUTE_LIMIT; i += 1) {
    const k = edWork('plan');
    if (k.w.stops.length >= Math.min(n, ROUTE_LIMIT)) return;
    const res = buildCandidates(k, appendPos(k.w) >= 2 ? state.bd.hold : 'replan');
    const top = (res.oneKey ? res.byHere : res.byOnward)[0];
    if (!top) return;
    takeCandidate('plan', top.t);
  }
}

/**
 * Adds the building state to the prototype's state, read from the URL where given.
 * @returns {void}
 */
function initBuildState() {
  const pick = (k, ok, def) => (ok.includes(params.get(k)) ? params.get(k) : def);
  state.bd = { append: pick('append', ['A', 'B'], 'A'), line: pick('line', ['A', 'B'], 'A'), key: pick('bkey', ['onward', 'here'], 'onward'), hold: pick('hold', ['hold', 'replan'], 'hold'), open: [], inlineOpen: true, err: false, focusStart: false };
  if (params.get('goal') === 'end') state.request.end = BUILD_DEFAULT_END;
  const built = Number(params.get('built'));
  if (params.get('build') === '1' || built >= 2) {
    state.request.start = state.request.start || BUILD_DEFAULT_START;
    doBuildStart();
    if (built >= 2) autoBuild(built);
  }
  if (params.get('bdopen') === '1' && state.page === 'edit') { state.ed.panel = { kind: 'build', ctx: 'plan' }; state.modal = { kind: 'ed-panel' }; }
}

const BUILD_ACTIONS = {
  'bd-start': () => {
    if (!state.request.start) {
      state.bd.err = true;
      state.bd.focusStart = true;
      state.aCollapsed = false;
      state.aOpen = [...new Set([...state.aOpen, 'shape'])];
      if (state.page !== 'edit') goPage('edit');
      return;
    }
    if (state.page !== 'edit') goPage('edit');
    if (state.ed.tabs.some((tb) => tb.at)) { confirmFirst('Stopp für Stopp bauen?', 'Das gezeigte Ergebnis wird ersetzt. Die Bearbeitungen in allen Tabs gehen verloren; Rückgängig geht danach nicht mehr.', 'Bauen', 'do-bd-start', ''); return; }
    doBuildStart();
  },
  'do-bd-start': () => doBuildStart(),
  'bd-open': (el) => {
    const ctx = el.dataset.ctx;
    const k = edWork(ctx);
    if (ctx === 'plan' && k.tab.built && state.bd.append === 'B') { state.bd.inlineOpen = true; return; }
    state.ed.panel = { kind: 'build', ctx };
    state.modal = { kind: 'ed-panel' };
  },
  'bd-inline': (el) => { state.bd.inlineOpen = el.dataset.val === '1'; },
  'bd-key': (el) => { state.bd.key = el.dataset.val; },
  'bd-hold': (el) => { state.bd.hold = el.dataset.val; },
  'bd-exp': (el) => { const id = el.dataset.val; state.bd.open = state.bd.open.includes(id) ? state.bd.open.filter((x) => x !== id) : [...state.bd.open, id]; },
  'bd-take': (el) => takeCandidate(el.dataset.ctx, el.dataset.t),
  'bd-replan-all': () => doStep('plan', (k) => ({ label: 'Fracht ganz neu geplant', w: replan(k, k.w.stops) })),
  'bd-from-flight': (el) => {
    const h = state.history.find((x) => x.id === el.dataset.val) || state.flyDone;
    const last = h.w.stops[h.w.stops.length - 1].t;
    state.request.start = last;
    state.request.end = '';
    state.request.ships = h.w.ships.map((s) => ({ ...s }));
    state.flyDone = null;
    goPage('edit');
    doBuildStart();
    say(`Weiterbauen ab ${pname(last)}`, 'Neue Route mit den Schiffen und der Anfrage des Flugs. Der Laderaum ist leer; nichts wird übernommen. Fliegen ist ein eigener Flug.');
  },
  'proto-bd': (el) => { state.bd[el.dataset.key] = el.dataset.val; closePanel(); },
  'proto-goal': () => { state.request.end = state.request.end ? '' : BUILD_DEFAULT_END; if (state.request.start) doBuildStart(); },
  'proto-nostart': () => { state.request.start = ''; },
  'proto-autobuild': () => { if (!state.request.start) state.request.start = BUILD_DEFAULT_START; if (!edWork('plan').tab.built) doBuildStart(); autoBuild(9); },
  'proto-flylast': () => { const f = openFlight(); if (f) flyTo(f.w.stops.length); },
};

/**
 * Renders the prototype bar's controls for building.
 * @returns {string} markup
 */
function buildBarControls() {
  const btn = (act, key, val, label, on) => `<button data-act="${act}" data-key="${key}" data-val="${val}" aria-pressed="${on}">${label}</button>`;
  const sep = '<span class="proto-bar__sep"></span>';
  const B = state.bd;
  let out = '';
  if (state.page === 'edit' && !state.flyOpen) {
    out += `${sep}${btn('proto-bd', 'append', 'A', 'Anhängen A', B.append === 'A')}${btn('proto-bd', 'append', 'B', 'Anhängen B', B.append === 'B')}`;
    out += `${sep}${btn('proto-bd', 'line', 'A', 'Zeile A', B.line === 'A')}${btn('proto-bd', 'line', 'B', 'Zeile B', B.line === 'B')}`;
    out += `${sep}<button data-act="proto-goal" aria-pressed="${Boolean(state.request.end)}" title="Zielterminal ${esc(tname(BUILD_DEFAULT_END))} in der Anfrage">Ziel fest</button><button data-act="proto-nostart" title="Startterminal leeren, um die Prüfung zu sehen">Start leeren</button><button data-act="proto-autobuild" title="Die jeweils besten Kandidaten bis 9 Stopps anhängen">Bis 9 bauen</button>`;
  }
  const f = openFlight();
  if (f && f.kind === 'personal' && !state.flyAdjust) out += `${sep}<button data-act="proto-flylast" title="Bis zum letzten geplanten Stopp abhaken">Letzter Stopp</button>`;
  return out;
}

/**
 * Runs after every render: focuses the start terminal after a failed build start and keeps the build choices in the URL.
 * @returns {void}
 */
function afterBuildRender() {
  if (state.bd.focusStart) {
    state.bd.focusStart = false;
    const field = document.getElementById('f-start');
    const groups = field && field.closest('.tr-a__groups');
    if (groups && groups.scrollHeight > groups.clientHeight) groups.scrollTop += field.getBoundingClientRect().top - groups.getBoundingClientRect().top - 16;
    if (field) window.scrollBy({ top: field.getBoundingClientRect().top - window.innerHeight / 3, behavior: 'instant' });
  }
  if (state.page !== 'edit') return;
  const url = new URL(location.href);
  url.searchParams.set('append', state.bd.append);
  url.searchParams.set('line', state.bd.line);
  ['build', 'built', 'goal', 'bdopen'].forEach((k) => url.searchParams.delete(k));
  history.replaceState(null, '', url);
}

/**
 * Fills the append panel's terminal search list for its current query.
 * @param {HTMLInputElement} el the search input
 * @returns {void}
 */
function fillBdSearch(el) {
  const list = el.nextElementSibling;
  list.innerHTML = bdSearchOptions(el.value, el.dataset.bdsearch);
  list.hidden = false;
}

document.addEventListener('input', (ev) => { if (ev.target.dataset.bdsearch) fillBdSearch(ev.target); });
document.addEventListener('focusin', (ev) => { if (ev.target.dataset.bdsearch) fillBdSearch(ev.target); });
document.addEventListener('focusout', (ev) => { if (ev.target.dataset.bdsearch) { const list = ev.target.nextElementSibling; setTimeout(() => { list.hidden = true; }, 180); } });
document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-act]');
  if (el && el.dataset.act === 'pick' && el.dataset.key === 'start' && state.bd) state.bd.err = false;
}, true);
