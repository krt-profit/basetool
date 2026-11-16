/** PROTOTYPE views for long routes: the stop-count field, the stop index, the shortened stop sequence and the "Danach" list. */

/**
 * Shortens a stop sequence by whole names: the full sequence while it fits, else first → second → … (+n) → last.
 * @param {Array<string>} names the stop names in route order
 * @param {number} [max] the character budget of the surface
 * @returns {string} the sequence as text
 */
function seqText(names, max = SEQ_TEXT_MAX) {
  const list = (typeof names === 'string' ? names.split(' › ') : names).filter((x, i, a) => i === 0 || x !== a[i - 1]);
  const sep = ' → ';
  const full = list.join(sep);
  if (full.length <= max || list.length < 3) return full;
  const last = list[list.length - 1];
  const two = `${list[0]}${sep}${list[1]}${sep}… (+${list.length - 3})${sep}${last}`;
  if (list.length > 3 && two.length <= max) return two;
  return `${list[0]}${sep}… (+${list.length - 2})${sep}${last}`;
}

/**
 * Names the long-route choices of the prototype bar for the page's prototype note.
 * @returns {string} one sentence
 */
function longNote() {
  const L = state.long;
  return `Lange Routen: Stoppfeld ${L.nField === 'A' ? 'A (Segmente)' : 'B (Stepper)'}, Stoppindex ${L.index === 'A' ? 'A (Chips)' : 'B (Strecke)'} bis 768 px ab 6 Stopps, Suchgrenze ${L.searchLimit}, Routengrenze ${ROUTE_LIMIT}, Platz für lange Suchen ${{ free: 'frei', queue: 'mit Warteschlange', full: 'voll' }[L.slot]}.`;
}

/**
 * Returns the highest n the request form offers: the search limit.
 * @returns {number} the search limit
 */
function searchLimit() {
  return state.long.searchLimit;
}

/**
 * Renders the request's stop-count field in the layout the prototype bar chose.
 * @returns {string} markup
 */
function stopsField() {
  const r = state.request;
  const max = searchLimit();
  const longHint = r.maxStops > LONG_JOB_ABOVE ? ` Über ${LONG_JOB_ABOVE} Stopps ist es eine lange Suche: sie läuft auf einem eigenen Platz und braucht länger, bis 60 s.` : '';
  const hint = `<span class="tr-hint">2 = A → B. Die Suche darf weniger Stopps nehmen. Einfügen geht später bis ${ROUTE_LIMIT}.${longHint}</span>`;
  if (state.long.nField === 'B') {
    return `<div class="tr-field"><span class="tr-cap" id="f-n-cap">Stopps, höchstens</span><div class="tr-stepper" role="group" aria-labelledby="f-n-cap">
      <button type="button" class="btn btn-ghost btn-icon" data-act="n-step" data-d="-1" aria-label="Ein Stopp weniger" ${r.maxStops <= 2 ? 'disabled' : ''}>${ic('minus')}</button>
      <output class="tr-stepper__v" aria-live="polite">${r.maxStops}</output>
      <button type="button" class="btn btn-ghost btn-icon" data-act="n-step" data-d="1" aria-label="Ein Stopp mehr" ${r.maxStops >= max ? 'disabled' : ''}>${ic('plus')}</button>
      <span class="tr-stepper__range">von 2 bis ${max}</span></div>${hint}</div>`;
  }
  const items = Array.from({ length: max - 1 }, (_, i) => i + 2);
  return `<div class="tr-field"><span class="tr-cap">Stopps, höchstens</span><div class="krt-segmented tr-nseg" role="group" aria-label="Stopps">${items.map((n) => `<button type="button" class="krt-segmented__item" data-act="set" data-key="maxStops" data-val="${n}" aria-pressed="${r.maxStops === n}">${n}</button>`).join('')}</div>${hint}</div>`;
}

/**
 * Collects what a stop's index chip signals: a mark, a warning or a change from the last step.
 * @param {object} k the edit context
 * @param {object} s the computed stop
 * @returns {{kind: string, text: string}|null} the strongest signal, or null
 */
function stopSignal(k, s) {
  const marks = k.w.marks.filter((m) => m.t === s.t);
  if (marks.length) return { kind: 'danger', text: `${marks.length} Markierung${marks.length > 1 ? 'en' : ''}` };
  if (s.gone.length || s.failsFilter) return { kind: 'warn', text: s.failsFilter ? `verletzt Filter: ${s.failsFilter}` : 'nicht wie geplant verkaufbar' };
  const diff = k.entry && k.entry.diff ? k.entry.diff.filter((l) => l.status !== 'UNCHANGED' && (l.from === s.t || l.to === s.t)) : [];
  if (diff.length) return { kind: 'info', text: `${diff.length} Frachtzeile${diff.length > 1 ? 'n' : ''} geändert` };
  return null;
}

/**
 * Renders the sticky stop index of a route from six stops; CSS shows it only up to 768 px.
 * @param {object} k the edit context
 * @param {object} e the computed working route
 * @returns {string} markup, empty below six stops
 */
function stopIndex(k, e) {
  if (e.stops.length < 6) return '';
  const variant = state.long.index;
  const items = e.stops.map((s, i) => {
    const sig = stopSignal(k, s);
    const done = s.si < k.lock;
    const now = s.si === k.lock;
    const cls = [s.pinned ? 'is-pinned' : '', done ? 'is-done' : '', now ? 'is-now' : ''].join(' ');
    const label = [`Stopp ${i + 1}: ${s.term.name}`, done ? 'erledigt' : '', now ? 'aktueller Stopp' : '', s.pinned ? 'fest' : '', sig ? sig.text : ''].filter(Boolean).join(', ');
    const dot = sig ? `<i class="tr-sidx__dot tr-sidx__dot--${sig.kind}" aria-hidden="true"></i>` : '';
    return `<button type="button" class="tr-sidx__chip ${cls}" data-sidx="${i + 1}" aria-label="${esc(label)}" title="${esc(label)}">${variant === 'B' ? `<span class="tr-sidx__node">${i + 1}</span>` : i + 1}${dot}</button>`;
  }).join(variant === 'B' ? '<span class="tr-sidx__rail" aria-hidden="true"></span>' : '');
  return `<nav class="tr-sidx tr-sidx--${variant}" aria-label="Stoppindex">${items}</nav>`;
}

/**
 * Renders the flight view's "Danach" list: the next stops up to a count, then the rest as one line or expandable.
 * @param {object} e the computed flight route
 * @param {number} from the index of the first stop after the current one
 * @returns {string} markup
 */
function afterList(e, from) {
  const rest = e.stops.slice(from);
  if (!rest.length) return '<div class="card"><h4 class="card-title">Danach</h4><span class="tr-hint">Letzter Stopp.</span></div>';
  const open = state.long.after === 'B' && state.long.afterOpen;
  const shown = open ? rest : rest.slice(0, AFTER_SHOWN);
  const more = rest.length - shown.length;
  const row = (s) => `<div class="tr-after__row"><span class="tr-after__n">${s.si + 1}</span><span class="tr-after__name">${esc(s.term.name)}</span><span class="tr-hint">${s.jobs.map((j) => `${j.side === 'buy' ? 'Kauf' : 'Verkauf'} ${fmt(j.qty)} ${esc(j.c)}`).join(', ') || 'kein Ladeauftrag'}</span></div>`;
  let tail = '';
  if (state.long.after === 'B' && rest.length > AFTER_SHOWN) {
    tail = `<button type="button" class="btn btn-ghost btn-xs tr-after__more" data-act="after-toggle" aria-expanded="${open}">${open ? `${ic('chevron-up')} Weniger anzeigen` : `${ic('chevron-down')} +${more} weitere anzeigen`}</button>`;
  } else if (more > 0) {
    tail = `<div class="tr-after__rest">+${more} weitere bis ${esc(e.stops[e.stops.length - 1].term.name)}</div>`;
  }
  return `<div class="card tr-after"><h4 class="card-title">Danach</h4>${shown.map(row).join('')}${tail}</div>`;
}

/**
 * Renders the hint at the end of a full route: no more stops through inserting or appending.
 * @param {object} k the edit context
 * @returns {string} markup
 */
function routeLimitHint(k) {
  if (k.w.stops.length < ROUTE_LIMIT) return '';
  return `<li class="tr-insert tr-insert--limit"><span class="tr-hint">${ic('info')} Routengrenze: höchstens ${ROUTE_LIMIT} Stopps${k.ctx === 'flight' ? ', erledigte mitgezählt' : ''}. Entferne einen Stopp, um einen anderen einzufügen.</span></li>`;
}

/**
 * Renders the long-search state in the planner's status line: waiting for the long slot, running or refused.
 * @returns {string} markup, empty without a planner search
 */
function editSearchStatus() {
  const s = state.long.search;
  if (!s) return '';
  const n = state.request.maxStops;
  const kind = n > LONG_JOB_ABOVE ? `lange Suche · bis ${n} Stopps` : `bis ${n} Stopps`;
  if (s.phase === 'refused') return `<div class="tr-status tr-status--refused" role="alert">${ic('warning')}<span><strong>Lange Suchen sind gerade ausgelastet.</strong> Versuch es in ${s.retry} s noch einmal. Suchen bis ${LONG_JOB_ABOVE} Stopps laufen davon unabhängig.</span><span class="tr-actions"><button class="btn btn-ghost btn-xs" data-act="ed-search-retry">${ic('reset')} Erneut versuchen</button></span></div>`;
  if (s.phase === 'queued') return `<div class="tr-status tr-status--running" role="status"><span class="krt-spinner" aria-hidden="true"></span><strong>Wartet auf den Platz für lange Suchen</strong><span class="muted">vor dir: 1 Suche · ${kind}</span><button class="btn btn-ghost btn-xs" data-act="ed-search-stop">Abbrechen</button></div>`;
  return `<div class="tr-status tr-status--running" role="status"><span class="krt-spinner" aria-hidden="true"></span><strong>Suche läuft · ${kind}</strong><span class="tr-status__bar"><i style="width:${[10, 35, 60, 85][s.step]}%"></i></span><span class="muted num">${s.elapsed} s / 60 s</span><span class="muted">${s.step ? `Zwischenstand: ${s.step} Route${s.step > 1 ? 'n' : ''}` : 'noch kein Zwischenstand'}</span><button class="btn btn-ghost btn-xs" data-act="ed-search-stop">Suche stoppen</button></div>`;
}
