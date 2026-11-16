/** PROTOTYPE app: in-memory state, rendering, events and the simulated route search. */
const params = new URLSearchParams(location.search);
const state = {
  variant: VARIANTS[params.get('variant')] ? params.get('variant') : 'A',
  scenario: params.get('scenario') === 'convoy' ? 'convoy' : 'single',
  request: null,
  resultShips: null,
  results: ['r1', 'r2', 'r3'],
  search: { phase: 'done', step: 0, elapsed: 6, deadline: false, stopped: false },
  sel: 0,
  aOpen: ['ships', 'shape', 'objective'],
  aCollapsed: window.matchMedia('(width <= 1024px)').matches,
  bStep: 0,
  bView: 'form',
  bStop: 0,
  drawer: '',
  flight: null,
  modal: null,
  toast: null,
  saved: [],
  soldOut: {},
  myReports: [],
  showState: false,
  newPrices: params.get('open') === 'new-prices',
  page: PAGES[params.get('page')] ? params.get('page') : 'planner',
  routesTab: ['saved', 'unit', 'shared'].includes(params.get('tab')) ? params.get('tab') : 'saved',
  routeSrc: ['saved', 'unit', 'shared', 'dead'].includes(params.get('src')) ? params.get('src') : 'saved',
  openMode: params.get('alt') === 'B' ? 'page' : 'planner',
  missionAlt: params.get('alt') === 'B' ? 'switch' : 'top',
  missionRole: ['manager', 'crew', 'reader'].includes(params.get('role')) ? params.get('role') : 'manager',
  role: ['member', 'officer', 'admin'].includes(params.get('role')) ? params.get('role') : 'member',
  edited: false,
  hangarGone: false,
  missionSearch: null,
  missionOpen: ['M1'],
  missionView: 'all',
  ratingsTab: 'places',
  ratingFilter: { systems: [], kinds: [], sort: 'danger' },
  place: PLACES[params.get('place')] ? params.get('place') : 'area18',
  savedList: SAVED_ROUTES.map((s) => ({ ...s })),
  unitList: UNIT_ROUTES.map((u) => ({ ...u })),
  shares: MY_SHARES.map((s) => ({ ...s })),
  revoked: [],
  missionRoutes: MISSION_ROUTES.map((m) => ({ ...m, fleet: [...m.fleet], marks: [...m.marks] })),
  myReportList: MY_REPORTS_SEED.map((r) => ({ ...r })),
  switchOn: false,
  switchDraft: false,
};
state.request = freshRequest(state.scenario);
state.resultShips = state.request.ships.map((s) => ({ ...s }));

/**
 * Opens the state a URL parameter names, so a view can be linked directly.
 * @param {string} open fly, fly-leg, rate, share, save, soldout, overheads, drawer, result or expand
 * @returns {void}
 */
function openFromUrl(open) {
  const first = 'r1';
  if (open === 'fly' || open === 'fly-leg') state.flight = { route: first, idx: 0, phase: open === 'fly' ? 'stop' : 'leg', done: {}, ship: -1 };
  if (open === 'rate') state.modal = { kind: 'rate', route: first, stop: 1, vals: { danger: 2 } };
  if (open === 'share' || open === 'save') state.modal = { kind: open, route: first };
  if (open === 'overheads') state.modal = { kind: 'overheads' };
  if (open === 'soldout') { state.soldOut['r1-0-Medical Supplies-buy'] = true; state.modal = { kind: 'soldout', c: 'Medical Supplies', term: TERMINALS.seraphim.name, t: 'seraphim' }; }
  if (open === 'drawer') state.drawer = 'all';
  if (open === 'result') state.bView = 'result';
  if (open === 'expand') { state.aCollapsed = false; state.aOpen = GROUPS.map(([k]) => k); }
}
openFromUrl(params.get('open') || '');

let timers = [];
let toastTimer = 0;

/**
 * Re-renders the page, keeping the scroll position of inner scroll areas.
 * @returns {void}
 */
function render() {
  const keep = ['.tr-a__groups', '.tr-drawer__body', '.tr-fly'].map((sel) => [sel, document.querySelector(sel)?.scrollTop || 0]);
  Object.keys(pageRoutes).forEach((k) => { delete pageRoutes[k]; });
  const view = state.page === 'planner' ? { A: variantA, B: variantB, C: variantC }[state.variant] : surfacePage;
  document.getElementById('app').innerHTML = view();
  document.getElementById('overlay-root').innerHTML = (state.flight ? flightView() : '') + modal();
  document.getElementById('toast-root').innerHTML = toast();
  document.getElementById('proto-root').innerHTML = protoBar();
  keep.forEach(([sel, top]) => { const el = document.querySelector(sel); if (el) el.scrollTop = top; });
  document.body.style.overflow = state.flight || state.modal || state.drawer ? 'hidden' : '';
  document.body.classList.toggle('has-fly', Boolean(state.flight));
  const url = new URL(location.href);
  url.searchParams.set('page', state.page);
  url.searchParams.set('variant', state.variant);
  url.searchParams.set('scenario', state.scenario);
  if (state.page === 'route') url.searchParams.set('src', state.routeSrc); else url.searchParams.delete('src');
  if (state.page === 'routes') url.searchParams.set('tab', state.routesTab); else url.searchParams.delete('tab');
  if (state.page === 'place') url.searchParams.set('place', state.place); else url.searchParams.delete('place');
  history.replaceState(null, '', url);
}

/**
 * Shows a toast for a few seconds.
 * @param {string} title the headline
 * @param {string} text the detail
 * @returns {void}
 */
function say(title, text) {
  state.toast = { title, text };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { state.toast = null; document.getElementById('toast-root').innerHTML = ''; }, 4200);
}

/**
 * Starts the simulated route search with intermediate results.
 * @returns {void}
 */
function startSearch() {
  timers.forEach(clearTimeout);
  timers = [];
  state.resultShips = state.request.ships.map((s) => ({ ...s }));
  state.search = { phase: 'running', step: 0, elapsed: 0, deadline: state.search.deadline, stopped: false };
  state.sel = 0;
  state.flight = null;
  if (state.variant === 'B') state.bView = 'result';
  state.drawer = '';
  const steps = [[1200, 1, 1], [3000, 2, 3], [4600, 2, 5]];
  steps.forEach(([ms, step, elapsed]) => timers.push(setTimeout(() => { state.search.step = step; state.search.elapsed = elapsed; render(); }, ms)));
  timers.push(setTimeout(() => {
    state.search = { ...state.search, phase: 'done', elapsed: state.search.deadline ? 60 : 6 };
    state.results = ['r1', 'r2', 'r3'];
    render();
  }, 6000));
  render();
}

/**
 * Stops the simulated search and keeps the best result found so far.
 * @returns {void}
 */
function stopSearch() {
  timers.forEach(clearTimeout);
  timers = [];
  state.results = [[], ['r3'], ['r1', 'r3']][state.search.step] || [];
  state.search = { ...state.search, phase: 'done', stopped: true };
  render();
}

/**
 * Toggles a value in a request list.
 * @param {string} key the list
 * @param {string} val the value
 * @returns {void}
 */
function toggleIn(key, val) {
  const list = state.request[key];
  state.request[key] = list.includes(val) ? list.filter((v) => v !== val) : [...list, val];
}

/**
 * Switches the scenario and rebuilds the request and result for it.
 * @param {string} scenario single or convoy
 * @returns {void}
 */
function setScenario(scenario) {
  state.scenario = scenario;
  state.request.ships = scenario === 'convoy' ? [{ ...SHIP_C2 }, { ...SHIP_CAT }] : [{ ...SHIP_C2 }];
  state.resultShips = state.request.ships.map((s) => ({ ...s }));
  state.flight = null;
}

const ACTIONS = {
  variant: (el) => {
    const keys = Object.keys(VARIANTS);
    state.variant = keys[(keys.indexOf(state.variant) + Number(el.dataset.val) + keys.length) % keys.length];
    state.drawer = '';
  },
  scenario: (el) => setScenario(el.dataset.val),
  deadline: () => { state.search.deadline = !state.search.deadline; if (state.search.phase === 'done' && !state.search.stopped) state.search.elapsed = state.search.deadline ? 60 : 6; },
  state: () => { state.showState = !state.showState; },
  'new-prices': () => { state.newPrices = !state.newPrices; },
  recheck: () => { state.newPrices = false; say('Route geprüft', 'Fracht auf denselben Stopps mit den neuen Preisen geplant; Abweichungen sind markiert.'); },
  compute: () => startSearch(),
  'stop-search': () => stopSearch(),
  sel: (el) => { state.sel = Number(el.dataset.val); state.bStop = 0; },
  set: (el) => { const k = el.dataset.key; state.request[k] = k === 'maxStops' ? Number(el.dataset.val) : el.dataset.val; },
  toggle: (el) => toggleIn(el.dataset.key, el.dataset.val),
  pick: (el) => {
    const k = el.dataset.key;
    if (['start', 'end'].includes(k)) state.request[k] = el.dataset.val;
    else if (!state.request[k].includes(el.dataset.val)) state.request[k] = [...state.request[k], el.dataset.val];
  },
  'pick-place': (el) => {
    const ids = Object.entries(TERMINALS).filter(([, t]) => t.place === el.dataset.val).map(([id]) => id);
    state.request[el.dataset.key] = [...new Set([...state.request[el.dataset.key], ...ids])];
  },
  'list-remove': (el) => {
    const k = el.dataset.key;
    if (['start', 'end'].includes(k)) state.request[k] = '';
    else state.request[k] = state.request[k].filter((v) => v !== el.dataset.val);
  },
  'chip-remove': (el) => {
    const k = el.dataset.key;
    const def = freshRequest(state.scenario);
    if (Array.isArray(state.request[k])) {
      state.request[k] = ['kinds', 'modes'].includes(k) ? [...state.request[k], el.dataset.val] : state.request[k].filter((v) => v !== el.dataset.val);
    } else {
      state.request[k] = k === 'warning' ? false : def[k];
    }
  },
  reset: () => { const ships = state.request.ships; state.request = freshRequest(state.scenario); state.request.ships = ships; state.request.forced = []; state.request.avoidConn = []; state.request.warning = false; },
  'ship-remove': (el) => { state.request.ships.splice(Number(el.dataset.i), 1); },
  'ship-add': (el) => { state.request.ships.push(el.dataset.val === 'type' ? { ...SHIP_CAT, count: 1 } : { ...SHIP_MAX }); },
  'a-collapse': () => { state.aCollapsed = !state.aCollapsed; },
  'b-step': (el) => { const n = Number(el.dataset.val); if (n >= 0 && n < B_STEPS.length) { state.bStep = n; state.bView = 'form'; window.scrollTo(0, 0); } },
  'b-view': (el) => { state.bView = el.dataset.val; if (state.bView === 'form') state.bStep = B_STEPS.length - 1; },
  'b-stop': (el) => { state.bStop = Number(el.dataset.val); },
  drawer: (el) => { state.drawer = el.dataset.val; },
  'drawer-close': () => { state.drawer = ''; },
  save: (el) => { state.modal = { kind: 'save', route: el.dataset.route }; },
  'save-confirm': (el) => { state.saved.push(el.dataset.route); state.modal = null; say('Route gespeichert', 'Zu finden unter „Gespeicherte Routen“.'); },
  share: (el) => { state.modal = { kind: 'share', route: el.dataset.route }; },
  'share-revoke': () => { state.modal = null; say('Teilen widerrufen', 'Code und Link funktionieren nicht mehr.'); },
  copy: (el) => { navigator.clipboard?.writeText(el.dataset.val === 'Code' ? 'K7Q2M9XAP3' : '…/handel/routen/geteilt/K7Q2M9XAP3').catch(() => undefined); say(`${el.dataset.val} kopiert`, 'In die Zwischenablage gelegt.'); },
  rate: (el) => { state.modal = { kind: 'rate', route: el.dataset.route, stop: Number(el.dataset.stop), vals: {} }; },
  'rate-val': (el) => { state.modal.vals[el.dataset.key] = Number(el.dataset.val); },
  'rate-send': () => {
    const r = routeById(state.modal.route);
    const s = r.stops[state.modal.stop];
    const n = Object.keys(state.modal.vals).length;
    if (!n) { say('Nichts ausgewählt', 'Wähle mindestens einen Wert.'); return; }
    state.myReports.push({ t: s.t, values: { ...state.modal.vals } });
    state.modal = null;
    say(`${n} Meldung${n > 1 ? 'en' : ''} gesendet`, 'Anonym. Sie erscheinen sofort als deine Meldung, im Durchschnitt mit dem nächsten Stundenlauf.');
  },
  soldout: (el) => {
    const k = el.dataset.key;
    if (state.soldOut[k]) {
      if (state.flight) { state.modal = soldOutModal(el); return; }
      delete state.soldOut[k];
      return;
    }
    state.soldOut[k] = true;
    state.modal = soldOutModal(el);
  },
  'soldout-report': () => { state.myReports.push({ t: state.modal.t, values: { availability: 'nichts da' } }); state.modal = null; say('Gemeldet', 'Verfügbarkeit „nichts da“, anonym.'); },
  overheads: () => { state.modal = { kind: 'overheads' }; },
  'open-code': () => { state.modal = { kind: 'code' }; },
  'modal-ok': (el) => { state.modal = null; say(el.dataset.val, ''); },
  'modal-close': () => { state.modal = null; },
  'modal-scrim': (el, ev) => { if (ev.target === el) state.modal = null; },
  edit: (el) => {
    state.modal = null;
    if (state.page === 'route') state.edited = true;
    say(`Einstieg: ${el.dataset.what}`, 'Das Verhalten entscheidet das Ticket „Editing a calculated route“.');
  },
  followup: (el) => { state.modal = null; say(`Nicht im Prototyp: ${el.dataset.what}`, 'Diese Fläche gibt es schon oder sie gehört nicht zu den Routen.'); },
  fly: (el) => { state.flight = { route: el.dataset.route, idx: 0, phase: 'stop', done: {}, ship: -1 }; },
  'fly-close': () => { state.flight = null; },
  'fly-ship': (el) => { state.flight.ship = Number(el.dataset.val); },
  'fly-job': (el) => { const k = el.dataset.key; state.flight.done[k] = !state.flight.done[k]; },
  'fly-next': () => {
    const r = routeById(state.flight.route);
    const f = state.flight;
    if (f.phase === 'stop') f.phase = f.idx === r.stops.length - 1 ? 'done' : 'leg';
    else if (f.phase === 'leg') { f.idx += 1; f.phase = 'stop'; }
    document.querySelector('.tr-fly')?.scrollTo(0, 0);
  },
  'fly-back': () => { state.flight.phase = 'stop'; },
  'fly-restart': () => { state.flight = { ...state.flight, idx: 0, phase: 'stop', done: {} }; },
  ...SURFACE_ACTIONS,
};

/**
 * Builds the sold-out dialog for a cargo line.
 * @param {HTMLElement} el the button that marked it
 * @returns {object} the dialog state
 */
function soldOutModal(el) {
  const r = routeById(el.dataset.route);
  const s = r.stops[Number(el.dataset.stop)];
  const j = s.jobs.find((x) => x.key === el.dataset.key);
  return { kind: 'soldout', c: j.c, term: s.term.name, t: s.t };
}

document.addEventListener('click', (ev) => {
  const el = ev.target.closest('[data-act]');
  if (!el || el.disabled) return;
  const fn = ACTIONS[el.dataset.act];
  if (!fn) return;
  if (el.dataset.act === 'fly-job') return;
  if (el.dataset.act === 'modal-scrim' && ev.target !== el) return;
  if (el.tagName === 'A') ev.preventDefault();
  fn(el, ev);
  render();
});

document.addEventListener('change', (ev) => {
  const el = ev.target;
  if (el.dataset.protoPage !== undefined) { goPage(el.value); render(); return; }
  if (el.dataset.act === 'fly-job') { ACTIONS['fly-job'](el); render(); return; }
  if (el.dataset.bind) {
    const k = el.dataset.bind;
    state.request[k] = el.type === 'checkbox' ? el.checked : el.type === 'number' && k === 'runs' ? Math.max(1, Number(el.value)) : el.value;
    render();
    return;
  }
  if (el.dataset.ship) {
    const s = state.request.ships[Number(el.dataset.ship)];
    const k = el.dataset.sk;
    s[k] = ['count', 'reserve', 'capital'].includes(k) ? Number(el.value) || (k === 'count' ? 1 : 0) : el.value;
    if (k === 'drive' && s.est && el.value !== 'Standard des Typs') s.est = '';
    render();
  }
});

/**
 * Fills a picker's option list for its current query.
 * @param {HTMLInputElement} el the picker input
 * @returns {void}
 */
function fillPicker(el) {
  const list = el.nextElementSibling;
  list.innerHTML = pickerOptions(el.dataset.picker, el.dataset.kind, el.value);
  list.hidden = false;
}

document.addEventListener('input', (ev) => { if (ev.target.dataset.picker) fillPicker(ev.target); });
document.addEventListener('focusin', (ev) => { if (ev.target.dataset.picker) fillPicker(ev.target); });
document.addEventListener('focusout', (ev) => {
  if (!ev.target.dataset.picker) return;
  const list = ev.target.nextElementSibling;
  setTimeout(() => { list.hidden = true; }, 180);
});
document.addEventListener('mousedown', (ev) => { if (ev.target.closest('.tr-picker__opt')) ev.preventDefault(); });
document.addEventListener('toggle', (ev) => {
  const g = ev.target.dataset && ev.target.dataset.group;
  if (!g) return;
  state.aOpen = ev.target.open ? [...new Set([...state.aOpen, g])] : state.aOpen.filter((x) => x !== g);
}, true);
document.addEventListener('keydown', (ev) => {
  if (ev.target.closest('input, textarea, select, [contenteditable]')) return;
  if (ev.key === 'Escape') { state.modal = null; state.drawer = ''; render(); return; }
  if (state.flight || state.modal || state.page !== 'planner') return;
  if (ev.key === 'ArrowLeft' || ev.key === 'ArrowRight') {
    ACTIONS.variant({ dataset: { val: ev.key === 'ArrowLeft' ? '-1' : '1' } });
    render();
  }
});

render();
