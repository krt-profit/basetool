/** PROTOTYPE wiring for long routes: state, tabs by n, the simulated long search, the stop index's jumps and the prototype bar. */

let longTimers = [];

/**
 * Adds the long-route state to the prototype's state, read from the URL where given.
 * @returns {void}
 */
function initLongState() {
  const pick = (k, ok, def) => (ok.includes(params.get(k)) ? params.get(k) : def);
  const limit = Number(pick('limit', ['10', '8'], String(SEARCH_LIMIT_DEFAULT)));
  state.long = { nField: pick('nfield', ['A', 'B'], 'A'), index: pick('index', ['A', 'B'], 'A'), after: pick('after', ['A', 'B'], 'B'), afterOpen: false, searchLimit: limit, slot: pick('slot', ['free', 'queue', 'full'], 'free'), search: null, last: null };
  const n = Number(params.get('n'));
  if (n >= 2 && n <= limit) state.request.maxStops = n;
  state.long.last = { elapsed: state.request.maxStops > LONG_JOB_ABOVE ? 44 : 6, long: state.request.maxStops > LONG_JOB_ABOVE, stopped: false };
}

/**
 * Lists the tabs a search for up to n stops returns, best first by profit per hour.
 * @param {number} n the request's stop count
 * @returns {Array<{label: string, stops: Array<string>, forced: Array<string>}>} the tab definitions
 */
function editTabsFor(n) {
  const forced = (stops) => stops.filter((t) => state.request.forced.includes(t));
  const defs = n <= 3
    ? EDIT_TABS.map((d) => ({ stops: d.stops.slice(0, n) }))
    : [LONG_CHAINS[0].slice(0, n), LONG_CHAINS[1].slice(0, n), LONG_CHAINS[1].slice(1, n + 1)].map((stops) => ({ stops }));
  const ships = state.request.ships;
  const ranked = defs.map((d) => {
    const stops = d.stops.map((t) => ({ t, pinned: false }));
    const e = evaluate({ stops, paths: planCargo(stops, { ships, marks: [] }).paths }, ships);
    return { ...d, perHour: e.perHour };
  }).sort((a, b) => b.perHour - a.perHour);
  return ranked.map((d, i) => ({ label: i ? `Alternative ${i}` : 'Beste Route', stops: d.stops, forced: forced(d.stops) }));
}

/**
 * Clears the timers of the simulated planner search.
 * @returns {void}
 */
function clearLongTimers() {
  longTimers.forEach(clearTimeout);
  longTimers = [];
}

/**
 * Ends the simulated planner search and shows its tabs.
 * @param {boolean} stopped whether the member stopped it with an intermediate result
 * @returns {void}
 */
function finishEditSearch(stopped) {
  const s = state.long.search;
  const n = state.request.maxStops;
  clearLongTimers();
  state.long.search = null;
  state.long.last = { elapsed: s ? s.elapsed : 6, long: n > LONG_JOB_ABOVE, stopped };
  resetEdit('result');
}

/**
 * Starts the simulated search of the planner, a long one above five stops, honouring the long slot's state.
 * @returns {void}
 */
function startEditSearch() {
  clearLongTimers();
  closePanel();
  state.ed.requestChanged = false;
  const n = state.request.maxStops;
  const long = n > LONG_JOB_ABOVE;
  if (long && state.long.slot === 'full') { state.long.search = { phase: 'refused', retry: 30 }; return; }
  const run = () => {
    state.long.search = { phase: 'running', step: 0, elapsed: 0 };
    const steps = long ? [[900, 1, 9], [1900, 2, 22], [2900, 3, 37]] : [[600, 1, 2], [1200, 2, 4]];
    steps.forEach(([ms, step, elapsed]) => longTimers.push(setTimeout(() => { Object.assign(state.long.search, { step, elapsed }); render(); }, ms)));
    longTimers.push(setTimeout(() => { state.long.search.elapsed = long ? 44 : 6; finishEditSearch(false); render(); }, long ? 3800 : 1800));
  };
  if (long && state.long.slot === 'queue') {
    state.long.search = { phase: 'queued' };
    longTimers.push(setTimeout(() => { run(); render(); }, 1800));
    return;
  }
  run();
}

/**
 * Ticks every loading job before a stop and moves the personal flight there, as if flown so far.
 * @param {number} at the stop number the flight stands at
 * @returns {void}
 */
function flyTo(at) {
  const f = state.fl;
  if (!f) return;
  const e = evaluate(f.w, f.ships);
  const idx = Math.max(0, Math.min(e.stops.length - 1, at - 1));
  e.stops.slice(0, idx).forEach((s) => s.jobs.forEach((j) => { f.ticks[`${j.key}#0`] = { qty: j.qty, price: j.p, by: 'dir' }; }));
  f.pos = { idx, phase: 'stop' };
  f.last = null;
}

/**
 * Applies the flight parameters of the URL after the edit state exists: the stop a flight stands at and the adjust view.
 * @returns {void}
 */
function applyLongFlightParams() {
  const at = Number(params.get('at'));
  if (state.fl && at > 1) flyTo(at);
  if (state.fl && params.get('adjust') === '1') state.flyAdjust = true;
}

const LONG_ACTIONS = {
  'n-step': (el) => { state.request.maxStops = Math.max(2, Math.min(searchLimit(), state.request.maxStops + Number(el.dataset.d))); },
  'ed-research': () => {
    const shown = edWork('plan').w.stops.length;
    const n = state.request.maxStops;
    const edited = state.ed.tabs.some((tb) => tb.at);
    if (!edited && shown <= n) { startEditSearch(); return; }
    const parts = [];
    if (shown > n) parts.push(`Die gezeigte Route hat ${shown} Stopps; die neue Suche plant höchstens ${n}, wie in deiner Anfrage.`);
    parts.push(edited ? 'Die volle Suche verwirft alle Bearbeitungen in allen Tabs. Rückgängig geht danach nicht mehr.' : 'Das gezeigte Ergebnis wird ersetzt.');
    confirmFirst('Neu suchen?', parts.join(' '), 'Neu suchen', 'do-ed-research', '');
  },
  'do-ed-research': () => startEditSearch(),
  compute: () => {
    if (state.page !== 'edit') { startSearch(); return; }
    LONG_ACTIONS['ed-research']();
  },
  'ed-search-stop': () => {
    const s = state.long.search;
    if (s && s.phase === 'running' && s.step > 0) { finishEditSearch(true); say('Suche gestoppt', 'Bestes bis dahin gefundenes Ergebnis übernommen.'); return; }
    clearLongTimers();
    state.long.search = null;
    say('Suche abgebrochen', 'Noch kein Ergebnis; die gezeigte Route bleibt.');
  },
  'ed-search-retry': () => startEditSearch(),
  'after-toggle': () => { state.long.afterOpen = !state.long.afterOpen; },
  'proto-n': (el) => { clearLongTimers(); state.long.search = null; state.request.maxStops = Math.min(searchLimit(), Number(el.dataset.val)); finishEditSearch(false); state.long.last.elapsed = state.request.maxStops > LONG_JOB_ABOVE ? 44 : 6; },
  'proto-long': (el) => {
    const k = el.dataset.key;
    state.long[k] = k === 'searchLimit' ? Number(el.dataset.val) : el.dataset.val;
    if (k === 'searchLimit' && state.request.maxStops > state.long.searchLimit) state.request.maxStops = state.long.searchLimit;
  },
  'proto-flyat': (el) => { flyTo(Number(el.dataset.val)); },
};
REQUEST_ACTS.push('n-step');

/**
 * Renders the prototype bar's controls for long routes.
 * @returns {string} markup
 */
function longBarControls() {
  const btn = (act, key, val, label, on) => `<button data-act="${act}" data-key="${key}" data-val="${val}" aria-pressed="${on}">${label}</button>`;
  const sep = '<span class="proto-bar__sep"></span>';
  const L = state.long;
  let out = '';
  if (state.page === 'edit') {
    out += `${sep}${[3, 6, 10].filter((n) => n <= searchLimit()).map((n) => btn('proto-n', '', n, `n=${n}`, state.request.maxStops === n)).join('')}`;
    out += `${sep}${btn('proto-long', 'nField', 'A', 'Feld A', L.nField === 'A')}${btn('proto-long', 'nField', 'B', 'Feld B', L.nField === 'B')}`;
    out += `${sep}${btn('proto-long', 'index', 'A', 'Index A', L.index === 'A')}${btn('proto-long', 'index', 'B', 'Index B', L.index === 'B')}`;
    out += `${sep}${btn('proto-long', 'searchLimit', '10', 'Grenze 10', L.searchLimit === 10)}${btn('proto-long', 'searchLimit', '8', 'Grenze 8', L.searchLimit === 8)}`;
    out += `${sep}${btn('proto-long', 'slot', 'free', 'Platz frei', L.slot === 'free')}${btn('proto-long', 'slot', 'queue', 'wartet', L.slot === 'queue')}${btn('proto-long', 'slot', 'full', 'voll', L.slot === 'full')}`;
  }
  const f = openFlight();
  if (f && f.kind === 'personal') {
    out += `${sep}${btn('proto-long', 'after', 'A', 'Danach A', L.after === 'A')}${btn('proto-long', 'after', 'B', 'Danach B', L.after === 'B')}`;
    out += `${sep}${btn('proto-long', 'index', 'A', 'Index A', L.index === 'A')}${btn('proto-long', 'index', 'B', 'Index B', L.index === 'B')}`;
    out += `<button data-act="proto-flyat" data-val="${Math.min(f.w.stops.length, f.pos.idx + 3)}" title="Abhaken bis zwei Stopps weiter">+2 Stopps</button>`;
  }
  return out;
}

/**
 * Scrolls a stop tile below the stuck stop index, in the page or in the flight overlay.
 * @param {HTMLElement} chip the index chip tapped
 * @returns {void}
 */
function jumpToStop(chip) {
  const bar = chip.closest('.tr-sidx');
  const scroller = bar.closest('.tr-fly');
  const root = scroller || document.getElementById('app');
  const tile = root.querySelector(`.tr-timeline__stop[data-n="${chip.dataset.sidx}"]`);
  if (!tile) return;
  const stuckBottom = (parseFloat(getComputedStyle(bar).top) || 0) + bar.offsetHeight;
  const dy = tile.getBoundingClientRect().top - (scroller ? scroller.getBoundingClientRect().top : 0) - stuckBottom - 8;
  (scroller || window).scrollBy({ top: dy, behavior: 'instant' });
}

/**
 * Marks the index chip of the stop at the top of the visible timeline.
 * @returns {void}
 */
function spyStopIndex() {
  document.querySelectorAll('.tr-sidx').forEach((bar) => {
    if (!bar.offsetParent) return;
    const root = bar.closest('.tr-fly') || document.getElementById('app');
    const line = bar.getBoundingClientRect().bottom + 16;
    let cur = 0;
    root.querySelectorAll('.tr-timeline__stop').forEach((t, i) => { if (t.getBoundingClientRect().top <= line) cur = i; });
    bar.querySelectorAll('.tr-sidx__chip').forEach((c, i) => c.classList.toggle('is-here', i === cur));
  });
}

/**
 * Runs after every render: the sticky offset under header and flight bar, the scroll spy and the URL.
 * @returns {void}
 */
function afterRender() {
  const head = document.querySelector('body > header');
  const bar = document.querySelector('.tr-flybar');
  document.documentElement.style.setProperty('--tr-stick-top', `${(head ? head.offsetHeight : 0) + (bar ? bar.offsetHeight : 0)}px`);
  spyStopIndex();
  const url = new URL(location.href);
  if (state.page === 'edit') {
    url.searchParams.set('n', String(state.request.maxStops));
    ['nField', 'index', 'after'].forEach((k) => url.searchParams.set(k === 'nField' ? 'nfield' : k, state.long[k]));
    url.searchParams.set('limit', String(state.long.searchLimit));
    url.searchParams.set('slot', state.long.slot);
  }
  history.replaceState(null, '', url);
}

document.addEventListener('click', (ev) => {
  const chip = ev.target.closest('[data-sidx]');
  if (!chip) return;
  ev.preventDefault();
  ev.stopPropagation();
  jumpToStop(chip);
}, true);
document.addEventListener('scroll', () => requestAnimationFrame(spyStopIndex), true);
window.addEventListener('resize', () => afterRender());
