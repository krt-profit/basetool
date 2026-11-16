/** PROTOTYPE edit flows in the planner of variant A: working state, diff, kept plan, insert, marks, pins and write-back. */

const DIFF_TEXT = { UNCHANGED: 'unverändert', CHANGED: 'geändert', DROPPED: 'weggefallen', NOT_SELLABLE: 'nicht wie geplant verkaufbar', NEW: 'neu' };
const DIFF_CLS = { UNCHANGED: 'muted', CHANGED: 'warn', DROPPED: 'danger', NOT_SELLABLE: 'danger', NEW: 'ok' };
const SRC_LABEL = { result: 'Berechnete Route', saved: 'Gespeicherte Route', shared: 'Geteilte Route', unit: 'Einheitsroute', mission: 'Einsatzroute' };
const tname = (t) => TERMINALS[t].name;
const pname = (t) => PLACES[TERMINALS[t].place].name;

/**
 * Resolves what an edit renders and acts on: the planner tab's working state or the open flight.
 * @param {string} ctx plan or flight
 * @returns {object} the working route, its last step, the structural lock index and the context
 */
function edWork(ctx) {
  if (ctx === 'flight') {
    const f = openFlight();
    return { ctx, w: f.w, entry: f.last, lock: f.pos.idx, flight: f, busy: Boolean(f.readOnly) };
  }
  const tb = state.ed.tabs[state.ed.tab];
  const searching = Boolean(state.long.search && state.long.search.phase !== 'refused');
  return { ctx, w: tb.hist[tb.at].w, entry: tb.at > 0 ? tb.hist[tb.at] : null, lock: -1, tab: tb, busy: Boolean(state.ed.optimising) || searching };
}

/**
 * Renders one buy-sell path as text.
 * @param {{c: string, from: string, to: string}} l the path
 * @returns {string} commodity, buy and sell place
 */
function pathText(l) {
  return `${esc(l.c)} · ${esc(pname(l.from))} → ${esc(pname(l.to))}`;
}

/**
 * Renders the diff of the last step beside the figures, as a change list (A) or a table (B).
 * @param {object} k the edit context
 * @param {object} e the computed working route
 * @returns {string} markup
 */
function diffBlock(k, e) {
  const entry = k.entry;
  if (!entry || !entry.diff) return '';
  const old = evaluate(entry.before, entry.before.ships);
  const changed = entry.diff.filter((l) => l.status !== 'UNCHANGED');
  const same = entry.diff.length - changed.length;
  const ships = e.convoy ? `<div class="tr-hint">Pro Schiff: ${e.ships.map((s, i) => `${esc(s.short)} ${fmt(old.ships[i]?.profit || 0)} → <strong class="${s.profit >= (old.ships[i]?.profit || 0) ? 'pos' : 'neg'}">${fmt(s.profit)}</strong>`).join(' · ')} aUEC</div>` : '';
  if (state.ed.diffView === 'B') {
    const sh = e.convoy ? e.ships.map((s) => `<th class="num">${esc(s.short)}</th>`).join('') : '';
    const rows = entry.diff.map((l) => `<tr class="${l.status === 'UNCHANGED' ? 'is-same' : ''}"><td><span class="tr-mark tr-mark--${DIFF_CLS[l.status]}">${DIFF_TEXT[l.status]}</span></td><td>${pathText(l)}</td><td class="num">${l.before ? fmt(l.before) : '—'}</td><td class="num"><strong>${l.after ? fmt(l.after) : '—'}</strong></td>${e.convoy ? l.sharesAfter.map((q, i) => `<td class="num">${fmt(l.sharesBefore[i])} → ${fmt(q)}</td>`).join('') : ''}</tr>`).join('');
    const fig = (k2, o, n, show, up) => `<span class="tr-difffig"><span class="tr-cap-k">${k2}</span> ${show(o)} → <strong class="${(up ? n > o : n < o) ? 'pos' : n === o ? '' : 'neg'}">${show(n)}</strong></span>`;
    return `<section class="card card--inset tr-difftable" aria-label="Änderungen">
      <div class="tr-row tr-row--between"><div class="tr-cap">Änderungen · ${esc(entry.label)}</div><div class="tr-row">${fig('Gewinn', old.profit, e.profit, fmt, true)}${fig('Dauer', old.duration, e.duration, fmtDur, false)}${fig('aUEC/h', old.perHour, e.perHour, fmt, true)}</div></div>
      <div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Status</th><th>Ware · Kauf → Verkauf</th><th class="num">SCU vorher</th><th class="num">SCU jetzt</th>${sh}</tr></thead><tbody>${rows}</tbody></table></div>
      ${ships}</section>`;
  }
  const group = (st, title, cls, icon) => {
    const ls = entry.diff.filter((l) => l.status === st);
    return ls.length ? `<div class="tr-devgroup"><div class="tr-cap">${title} (${ls.length})</div>${ls.map((l) => `<div class="tr-warnline ${cls}">${ic(icon)}<span>${pathText(l)}: ${l.before ? `${fmt(l.before)} SCU` : ''}${l.before && l.after ? ' → ' : ''}${l.after ? `<strong>${fmt(l.after)} SCU</strong>` : ''}</span></div>`).join('')}</div>` : '';
  };
  return `<section class="tr-diff" aria-label="Änderungen">
    ${compareStrip(old, e, `Vorher und jetzt · ${entry.label}`)}
    <div class="tr-devbox">${group('NOT_SELLABLE', 'Nicht wie geplant verkaufbar', 'tr-warnline--danger', 'warning')}${group('DROPPED', 'Weggefallen', 'tr-warnline--danger', 'minus')}${group('CHANGED', 'Geändert', '', 'edit')}${group('NEW', 'Neu im Plan', 'tr-warnline--ok', 'plus')}
    ${same ? `<div class="tr-hint">${same} Frachtzeile${same > 1 ? 'n' : ''} unverändert. Die Zeilen tragen die Änderung auch in der Zeitleiste.</div>` : ''}${changed.length ? '' : `<div class="tr-warnline tr-warnline--ok">${ic('check')}<span>Frachtplan unverändert.</span></div>`}</div>${ships}</section>`;
}

/**
 * Renders the kept-plan banner after a reorder.
 * @param {object} k the edit context
 * @param {object} e the computed working route
 * @returns {string} markup
 */
function keptBanner(k, e) {
  if (!k.w.kept) return '';
  const lost = (k.w.lost || []).length;
  const cut = k.w.paths.filter((p) => p.reduced).length;
  return `<div class="tr-kept" role="status"><div>${ic('swap')}<span><strong>Restplan</strong> nach dem Umstellen: der letzte volle Frachtplan in der neuen Reihenfolge, gekürzt auf das, was noch geht. ${lost ? `<strong class="neg">${lost} nicht wie geplant verkaufbar</strong>` : 'Alles bleibt verkaufbar'}${cut ? ` · ${cut} gekürzt` : ''}. Die Kennzahlen sind die des Restplans; du kannst ihn so speichern, teilen oder fliegen.</span></div>
    <button class="btn btn-outline btn-xs" data-act="ed-recompute" data-ctx="${k.ctx}" ${k.busy ? 'disabled' : ''}>${ic('reset')} Waren neu berechnen</button></div>`;
}

/**
 * Renders the cargo lines of one stop with diff markers, marks and their actions.
 * @param {object} k the edit context
 * @param {object} s the computed stop
 * @param {object} e the computed working route
 * @param {Map<string, object>} dm the diff lines by path key, empty for diff B
 * @returns {string} markup
 */
function editJobs(k, s, e, dm) {
  const f = k.flight;
  const done = s.si < k.lock;
  const rows = [...s.jobs].sort((a, b) => (a.side === b.side ? 0 : a.side === 'sell' ? -1 : 1)).map((j) => {
    const l = dm.get(pathKey(j.path));
    const mark = k.w.marks.find((m) => m.t === s.t && m.c === j.c && m.side === j.side);
    const ticked = f ? flightTickOf(f, j) : null;
    const marks = [
      l && l.status === 'NEW' ? '<span class="tr-mark tr-mark--ok">neu</span>' : '',
      l && l.status === 'CHANGED' ? `<span class="tr-mark tr-mark--warn">geändert</span><div class="tr-was">vorher ${fmt(l.before)} SCU</div>` : '',
      j.path.reduced ? `<span class="tr-mark tr-mark--warn">gekürzt</span><div class="tr-was">vorher ${fmt(j.path.reduced)} SCU</div>` : '',
      mark ? `<span class="tr-mark tr-mark--danger">${j.side === 'buy' ? 'ausverkauft' : 'keine Nachfrage'} · noch ${fmt(mark.rest)} SCU</span>` : '',
      j.side === 'sell' && j.demand === null ? '<span class="tr-mark tr-mark--info tr-mark--est">Nachfrage unbekannt</span>' : '',
      ticked ? `<span class="tr-mark tr-mark--ok">${ic('check')} Ist ${fmt(ticked.qty)} à ${fmt(ticked.price)}</span>` : '',
      j.path.fixed && !ticked && f ? '<span class="tr-mark tr-mark--muted">an Bord, fest</span>' : '',
    ].join(' ');
    const split = e.convoy ? `<div class="tr-hint">${j.shares.map((q, i) => `${esc(e.ships[i].short)} ${fmt(q)}`).join(' · ')}</div>` : '';
    let act = '';
    if (!done && !ticked && !k.busy) {
      const markBtn = `<button class="btn btn-ghost btn-xs" data-act="ed-mark" data-ctx="${k.ctx}" data-si="${s.si}" data-c="${esc(j.c)}" data-side="${j.side}">${mark ? 'Alternativen' : j.side === 'buy' ? 'Ausverkauft' : 'Keine Nachfrage'}</button>`;
      act = markBtn + (mark ? `<button class="btn btn-ghost btn-xs" data-act="ed-unmark" data-ctx="${k.ctx}" data-si="${s.si}" data-c="${esc(j.c)}" data-side="${j.side}">Aufheben</button>` : '');
    }
    const panel = state.ed.pick === 'inline' && state.ed.panel && state.ed.panel.kind === 'mark' && state.ed.panel.ctx === k.ctx && state.ed.panel.si === s.si && state.ed.panel.c === j.c && state.ed.panel.side === j.side
      ? `<tr class="tr-inline-row"><td colspan="8">${markPanel(k)}</td></tr>` : '';
    return `<tr class="${ticked ? 'is-ticked' : ''}">
      <td class="c-side"><span class="tr-side tr-side--${j.side}">${j.side === 'buy' ? 'Kauf' : 'Verkauf'}</span></td>
      <td class="c-ware"><span class="tr-cname">${esc(j.c)}</span> ${marks}${split}</td>
      <td class="num c-qty">${fmt(j.qty)}<span class="c-unit"> SCU</span></td>
      <td class="num c-price"><span class="c-unit">à </span>${fmt(j.p)}</td>
      <td class="num c-sum ${j.side === 'buy' ? 'neg' : 'pos'}">${j.side === 'buy' ? '−' : '+'}${fmt(j.sum)}</td>
      <td class="nowrap c-age"><span class="tr-hint">vor 3 h</span></td>
      <td class="c-avail">${availChip(j)}</td>
      <td class="num c-act"><span class="tr-iconbtns">${act}</span></td></tr>${panel}`;
  }).join('');
  const goneDiff = state.ed.diffView === 'A' ? [...dm.values()].filter((l) => (l.status === 'DROPPED' || l.status === 'NOT_SELLABLE') && (l.from === s.t || l.to === s.t) && !s.gone.some((g) => g.c === l.c)).map((l) => ({ side: l.from === s.t ? 'buy' : 'sell', c: l.c, qty: l.before, gone: DIFF_TEXT[l.status] })) : [];
  const gone = [...s.gone, ...goneDiff].map((g) => `<tr class="is-gone"><td class="c-side"><span class="tr-side tr-side--${g.side}">${g.side === 'buy' ? 'Kauf' : 'Verkauf'}</span></td><td class="c-ware"><span class="tr-cname">${esc(g.c)}</span> <span class="tr-mark tr-mark--danger">${esc(g.gone)}</span></td><td class="num c-qty">${fmt(g.qty)}<span class="c-unit"> SCU</span></td><td class="num c-price"></td><td class="num c-sum">—</td><td class="c-age"></td><td class="c-avail"></td><td class="c-act"></td></tr>`).join('');
  const body = rows + gone || '<tr><td colspan="8" class="tr-hint">Kein Ladeauftrag an diesem Stopp.</td></tr>';
  return `<div class="hud-scroll scroll-x"><table class="tr-jobs tr-jobs--stop"><thead><tr><th>Seite</th><th>Ware</th><th class="num">SCU</th><th class="num">aUEC/SCU</th><th class="num">Summe</th><th>Preis</th><th>Verfügbarkeit</th><th></th></tr></thead><tbody>${body}</tbody></table></div>`;
}

/**
 * Renders the controls of one stop tile: pin, move, remove and rate.
 * @param {object} k the edit context
 * @param {object} s the computed stop
 * @param {number} n the number of stops
 * @returns {string} markup
 */
function editStopTools(k, s, n) {
  if (s.si < k.lock) return '<span class="tr-mark tr-mark--muted">erledigt · eingefroren</span>';
  const rate = `<button class="btn btn-ghost btn-xs" data-act="rate" data-route="${k.ctx === 'flight' ? 'flight' : 'edit'}" data-stop="${s.si}">Bewerten</button>`;
  if (s.si === k.lock) return `<span class="tr-iconbtns"><span class="tr-mark tr-mark--forced">aktueller Stopp</span>${rate}</span>`;
  const dis = k.busy ? 'disabled' : '';
  const pin = `<button class="tr-pin" data-act="ed-pin" data-ctx="${k.ctx}" data-si="${s.si}" aria-pressed="${Boolean(s.pinned)}" title="${s.pinned ? 'Fest: „Rest optimieren“ lässt diesen Stopp an seiner Stelle' : 'Frei: „Rest optimieren“ darf diesen Stopp ersetzen'}" ${dis}>${ic('map-pin')} ${s.pinned ? 'Fest' : 'Frei'}</button>`;
  const up = `<button class="btn btn-ghost btn-xs btn-icon" data-act="ed-move" data-ctx="${k.ctx}" data-si="${s.si}" data-d="-1" aria-label="Stopp nach oben" title="Stopp nach oben" ${s.si === 0 || s.si - 1 <= k.lock || k.busy ? 'disabled' : ''}>${ic('chevron-up')}</button>`;
  const down = `<button class="btn btn-ghost btn-xs btn-icon" data-act="ed-move" data-ctx="${k.ctx}" data-si="${s.si}" data-d="1" aria-label="Stopp nach unten" title="Stopp nach unten" ${s.si === n - 1 || k.busy ? 'disabled' : ''}>${ic('chevron-down')}</button>`;
  const rm = `<button class="btn btn-quiet-danger btn-xs btn-icon" data-act="ed-remove" data-ctx="${k.ctx}" data-si="${s.si}" aria-label="Stopp entfernen" title="${n <= 2 ? 'Eine Route behält mindestens zwei Terminals' : 'Stopp entfernen'}" ${n <= 2 || k.busy ? 'disabled' : ''}>${ic('trash')}</button>`;
  return `<span class="tr-iconbtns">${pin}${rate}${up}${down}${rm}</span>`;
}

/**
 * Renders the insert entry point or its inline panel at a position.
 * @param {object} k the edit context
 * @param {number} pos the index a new stop takes
 * @param {boolean} append whether it is the position after the last stop
 * @returns {string} markup
 */
function insertRow(k, pos, append) {
  if (pos <= k.lock) return '';
  const p = state.ed.panel;
  if (state.ed.pick === 'inline' && p && p.kind === 'insert' && p.ctx === k.ctx && p.pos === pos) return `<li class="tr-insert tr-insert--open">${insertPanel(k)}</li>`;
  const full = k.w.stops.length >= ROUTE_LIMIT;
  return `<li class="tr-insert"><button class="btn btn-ghost btn-xs" data-act="ed-insert-open" data-ctx="${k.ctx}" data-pos="${pos}" ${full || k.busy ? 'disabled' : ''} title="${full ? `Routengrenze: höchstens ${ROUTE_LIMIT} Stopps` : ''}">${ic('plus')} ${append ? 'Stopp anhängen' : 'Stopp einfügen'}</button></li>${append ? routeLimitHint(k) : ''}`;
}

/**
 * Renders the editable stop timeline of a working route.
 * @param {object} k the edit context
 * @param {object} e the computed working route
 * @returns {string} markup
 */
function editTimeline(k, e) {
  const dm = state.ed.diffView === 'A' && k.entry && k.entry.diff ? new Map(k.entry.diff.map((l) => [`${l.c}|${l.from}|${l.to}`, l])) : new Map();
  const n = e.stops.length;
  const items = e.stops.map((s, i) => {
    const leg = e.legs[i];
    const legHtml = leg ? `<li class="tr-timeline__leg">${legFacts(leg, false)}</li>${insertRow(k, i + 1, false)}` : insertRow(k, n, true);
    const marks = [s.inserted ? '<span class="tr-mark tr-mark--est">eingefügt</span>' : '', s.failsFilter ? `<span class="tr-mark tr-mark--warn" title="Von dir genannt; gilt trotz Filter">verletzt Filter: ${esc(s.failsFilter)}</span>` : ''].join(' ');
    const cls = [s.si < k.lock ? 'tr-stoptile--frozen' : '', s.si === k.lock ? 'tr-stoptile--now' : '', s.pinned ? 'tr-stoptile--pinned' : ''].join(' ');
    return `<li class="tr-timeline__stop ${s.si < k.lock ? 'is-done' : ''}" data-n="${i + 1}"><div class="tr-stoptile ${cls}">
        <div class="tr-stoptile__head"><div><div class="tr-stoptile__title">${esc(s.term.name)} ${marks}</div><div class="tr-stoptile__sub">${s.place.kind} · ${s.place.body} · ${s.place.system}</div></div>${editStopTools(k, s, n)}</div>
        ${s.si < k.lock ? '' : stopFacts(s)}
        ${editJobs(k, s, e, dm)}
        ${shipLists(s, e, k.flight ? k.flight.ship : -1)}
        ${stopTime(s)}
      </div></li>${legHtml}`;
  }).join('');
  return `<ol class="tr-timeline">${items}</ol>`;
}

/**
 * Renders the insert panel: the ten candidates for the position and the terminal search.
 * @param {object} k the edit context
 * @returns {string} markup
 */
function insertPanel(k) {
  const p = state.ed.panel;
  const ships = k.w.ships;
  const { list, onRoute } = candidatesFor(k.w, p.pos, ships, p.commodity, (stops) => planOpts(k, stops, k.w.marks));
  const at = p.pos >= k.w.stops.length ? `nach ${esc(tname(k.w.stops[k.w.stops.length - 1].t))}` : `zwischen ${p.pos ? esc(tname(k.w.stops[p.pos - 1].t)) : 'Start'} und ${esc(tname(k.w.stops[p.pos].t))}`;
  const rows = list.map((c) => {
    const t = TERMINALS[c.t];
    const pl = PLACES[t.place];
    return `<tr><td><span class="tr-cname">${esc(t.name)}</span><div class="tr-hint">${pl.kind} · ${pl.body} · ${pl.system}</div></td>
      ${p.commodity ? `<td class="num">${fmt(c.sells)}</td>` : ''}
      <td class="num ${c.dProfit >= 0 ? 'pos' : 'neg'}">${c.dProfit >= 0 ? '+' : '−'}${fmt(Math.abs(c.dProfit))}</td>
      <td class="num">+${fmtDur(Math.max(0, c.dDur))}</td><td class="num">${fmt(c.perHour)}</td>
      <td>${rateChip('', pl.danger, MIN_RATERS.danger)}</td><td>${rateChip('', t.bugs, MIN_RATERS.bugs)}</td>
      <td class="num"><button class="btn btn-outline btn-xs" data-act="ed-insert" data-ctx="${k.ctx}" data-pos="${p.pos}" data-t="${c.t}">Einfügen</button></td></tr>`;
  }).join('');
  const greyed = onRoute.map((t) => `<tr class="is-off"><td><span class="tr-cname">${esc(tname(t))}</span></td><td colspan="${p.commodity ? 7 : 6}" class="tr-hint">schon auf der Route</td></tr>`).join('');
  const head = p.commodity ? `Abnehmer für ${esc(p.commodity)} einfügen ${at}` : `${p.pos >= k.w.stops.length ? 'Stopp anhängen' : 'Stopp einfügen'} ${at}`;
  return `<div class="tr-panel" role="group" aria-label="${head}">
    <div class="tr-row tr-row--between"><div class="tr-cap">${head}</div>${state.ed.pick === 'inline' ? `<button class="btn btn-ghost btn-xs" data-act="ed-panel-close">${ic('close')} Schließen</button>` : ''}</div>
    <div class="tr-hint">Die zehn besten Terminals für diese Stelle, jeweils mit der ganzen Route neu geplant (schnelle Spur). Sie folgen den Filtern deiner Anfrage. Ein eingefügter Stopp ist fest. ${k.w.stops.length + 1 > state.request.maxStops ? `Über deine ${state.request.maxStops} Stopps hinaus erlaubt, bis ${ROUTE_LIMIT}.` : ''}</div>
    <div class="hud-scroll scroll-x"><table class="tr-jobs tr-cands"><thead><tr><th>Terminal</th>${p.commodity ? '<th class="num">kauft à</th>' : ''}<th class="num">Δ Gewinn</th><th class="num">Δ Dauer</th><th class="num">aUEC/h</th><th>Gefahr</th><th>Bugs</th><th></th></tr></thead><tbody>${rows || `<tr><td colspan="8" class="tr-hint">Kein Terminal passt zu den Filtern.</td></tr>`}${greyed}</tbody></table></div>
    <div class="tr-field tr-picker"><label for="ed-search">Oder ein Terminal nennen</label><input id="ed-search" type="search" autocomplete="off" placeholder="Terminal oder Ort suchen …" data-edsearch="${k.ctx}" data-pos="${p.pos}"><div class="tr-picker__list" hidden></div><span class="tr-hint">Ein genanntes Terminal gilt auch gegen einen Filter der Anfrage und wird markiert; ein ausgeschlossenes verlässt die Ausschlussliste.</span></div>
  </div>`;
}

/**
 * Lists terminals matching a query for the insert panel's search.
 * @param {string} q the query
 * @param {string} ctx plan or flight
 * @param {number} pos the insert position
 * @returns {string} option markup
 */
function edSearchOptions(q, ctx, pos) {
  const k = edWork(ctx);
  const on = k.w.stops.map((s) => s.t);
  const needle = q.trim().toLowerCase();
  return Object.entries(TERMINALS).filter(([, t]) => !needle || `${t.name} ${PLACES[t.place].name}`.toLowerCase().includes(needle)).slice(0, 12).map(([id, t]) => {
    const why = failsFilter(id);
    if (on.includes(id)) return `<button type="button" class="tr-picker__opt" disabled><span>${esc(t.name)}</span><small>schon auf der Route</small></button>`;
    return `<button type="button" class="tr-picker__opt" data-act="ed-insert" data-ctx="${ctx}" data-pos="${pos}" data-t="${id}"><span>${esc(t.name)}</span><small>${PLACES[t.place].kind} · ${PLACES[t.place].system}${why ? ` · <span class="warn-text">verletzt Filter: ${esc(why)}</span>` : ''}</small></button>`;
  }).join('') || '<div class="tr-hint" style="padding:8px">Nichts gefunden.</div>';
}

/**
 * Renders the mark panel: the remaining quantity, then the report offer and the alternatives.
 * @param {object} k the edit context
 * @returns {string} markup
 */
function markPanel(k) {
  const p = state.ed.panel;
  const s = k.w.stops[p.si];
  const term = tname(s.t);
  const buy = p.side === 'buy';
  const close = `<button class="btn btn-ghost btn-xs" data-act="ed-panel-close">${ic('close')} Schließen</button>`;
  if (p.phase === 'ask') {
    return `<div class="tr-panel"><div class="tr-row tr-row--between"><div class="tr-cap">${buy ? 'Ausverkauft' : 'Keine Nachfrage'} · ${esc(p.c)} bei ${esc(term)}</div>${state.ed.pick === 'inline' ? close : ''}</div>
      <div class="tr-row"><div class="tr-field" style="width:12rem"><label for="ed-rest">${buy ? 'Noch verfügbar' : 'Nur so viel abgenommen'} (SCU)</label><input id="ed-rest" type="number" min="0" value="0"></div>
      <button class="btn btn--cta" data-act="ed-mark-apply" data-ctx="${k.ctx}" style="align-self:flex-end">Markieren</button></div>
      <span class="tr-hint">Nur für ${k.ctx === 'flight' ? 'diesen Flug' : 'deine Route'}; nichts wird geteilt. Die fehlende Menge und was an ihr hängt, verlassen den Plan sofort; die Kennzahlen folgen.</span></div>`;
  }
  const e = evaluate(k.w, k.w.ships);
  const report = `<div class="card card--inset tr-report"><div><div class="tr-cap">Verfügbarkeit melden?</div><span class="tr-hint">Vorbelegt mit „${p.rest ? 'weniger' : 'nichts da'}“ für ${esc(p.c)} · ${buy ? 'Kauf' : 'Verkauf'}. Anonym; gesendet wird nur auf deinen Klick.</span></div><button class="btn btn-ghost btn-xs" data-act="ed-report">Melden: ${p.rest ? 'weniger' : 'nichts da'}</button></div>`;
  let body = '';
  if (buy) {
    const alts = alternativesAt(k.w, p.si, k.w.ships);
    const sel = p.sel || [];
    const rows = alts.map((a, i) => `<tr><td><input type="checkbox" data-act="ed-alt-sel" data-i="${i}" ${sel.includes(i) ? 'checked' : ''} aria-label="${esc(a.c)} übernehmen"></td><td><span class="tr-cname">${esc(a.c)}</span></td><td class="num">${fmt(a.qty)}</td><td class="num">${fmt(a.pb)}</td><td>${esc(pname(a.to))} <span class="tr-hint">à ${fmt(a.ps)}</span></td><td class="num pos">+${fmt(a.gain)}</td></tr>`).join('');
    body = `<div class="tr-cap">Alternative Waren an diesem Stopp</div><span class="tr-hint">Kaufbar hier, passen in den frei gewordenen Laderaum (${fmt(e.legs[p.si]?.hold - e.legs[p.si]?.aboard || 0)} SCU) und das Bargeld und werden an einem späteren Stopp der Route verkauft. Der Rest des Plans bleibt. Mehrere gehen, solange Laderaum und Bargeld reichen.</span>
      <div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th></th><th>Ware</th><th class="num">SCU</th><th class="num">Kauf à</th><th>Verkauf an</th><th class="num">Δ Gewinn</th></tr></thead><tbody>${rows || '<tr><td colspan="6" class="tr-hint">Keine Ware passt zu einem späteren Stopp.</td></tr>'}</tbody></table></div>
      <div class="tr-actions"><button class="btn btn--cta" data-act="ed-alt-take" data-ctx="${k.ctx}" ${sel.length ? '' : 'disabled'}>${ic('check')} Übernehmen${sel.length ? ` (${sel.length})` : ''}</button><button class="btn btn-ghost" data-act="ed-replan-here" data-ctx="${k.ctx}">${ic('reset')} Fracht ab hier neu planen</button><span class="tr-hint">„Fracht ab hier neu planen“ darf auch andere Stopps ändern.</span></div>`;
  } else {
    const later = k.w.stops.slice(p.si + 1).filter((x) => PRICES[x.t]?.sell?.[p.c]).map((x) => `<tr><td>${esc(tname(x.t))}</td><td class="num">${fmt(PRICES[x.t].sell[p.c][0])}</td><td class="num"><button class="btn btn-outline btn-xs" data-act="ed-sell-later" data-ctx="${k.ctx}" data-t="${x.t}">Dort verkaufen</button></td></tr>`).join('');
    const ins = candidatesFor(k.w, p.si + 1, k.w.ships, p.c, (stops) => planOpts(k, stops, k.w.marks)).list.slice(0, 3).map((c) => `<tr><td>${esc(tname(c.t))} <span class="tr-hint">einfügen nach Stopp ${p.si + 1}</span></td><td class="num">${fmt(c.sells)}</td><td class="num"><button class="btn btn-ghost btn-xs" data-act="ed-insert" data-ctx="${k.ctx}" data-pos="${p.si + 1}" data-t="${c.t}">Einfügen</button></td></tr>`).join('');
    body = `<div class="tr-cap">Wohin mit der Ware?</div><span class="tr-hint">Was hier nicht abgenommen wird, bleibt an Bord. Spätere Stopps der Route, die ${esc(p.c)} kaufen, und die besten Terminals, die sich direkt danach einfügen lassen.</span>
      <div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Terminal</th><th class="num">kauft à</th><th></th></tr></thead><tbody>${later}${ins || ''}${later || ins ? '' : '<tr><td colspan="3" class="tr-hint">Niemand auf oder nahe der Route kauft diese Ware.</td></tr>'}</tbody></table></div>`;
  }
  return `<div class="tr-panel"><div class="tr-row tr-row--between"><div class="tr-cap">${buy ? 'Ausverkauft' : 'Keine Nachfrage'} markiert · ${esc(p.c)} bei ${esc(term)} · noch ${fmt(p.rest)} SCU</div>${state.ed.pick === 'inline' ? close : ''}</div>${report}${body}</div>`;
}

/**
 * Renders the save, share and fly actions that follow the working state's source and the member's right.
 * @param {object} k the edit context
 * @returns {string} markup
 */
function editSaveActions(k) {
  const edited = k.tab.at > 0;
  const fly = (cls) => `<button class="btn ${cls}" data-act="ed-fly">${ic('arrow-right')} Losfliegen</button>`;
  const share = '<button class="btn btn-ghost" data-act="share" data-route="edit">Teilen</button>';
  const mine = (cls) => `<button class="btn ${cls}" data-act="ed-save-mine">${ic('save')} Als meine Route speichern</button>`;
  const src = state.ed.src;
  if (src === 'result') return `<button class="btn btn--cta" data-act="save" data-route="edit">${ic('save')} Speichern</button>${fly('btn-outline')}${share}<button class="btn btn-ghost" data-act="to-unit">${ic('users')} Für Einheit</button>`;
  if (!edited) return `${fly('btn--cta')}${share}`;
  if (src === 'saved') return `<button class="btn btn--cta" data-act="ed-update" data-what="Gespeicherte Route">${ic('save')} Gespeicherte Route aktualisieren</button><button class="btn btn-ghost" data-act="save" data-route="edit">Als neue speichern</button>${fly('btn-outline')}`;
  if (src === 'shared') return `${mine('btn--cta')}${fly('btn-outline')}<span class="tr-hint">Eine geteilte Route bleibt eingefroren; eine geänderte teilst du neu.</span>`;
  if (src === 'unit') {
    return state.role === 'officer'
      ? `<button class="btn btn--cta" data-act="ed-update" data-what="Einheitsroute">${ic('save')} Einheitsroute aktualisieren</button><button class="btn btn-ghost" data-act="to-unit">Als neue Einheitsroute speichern</button>${mine('btn-ghost')}`
      : `<button class="btn btn--cta" data-act="to-unit">${ic('users')} Als neue Einheitsroute speichern</button>${mine('btn-ghost')}<span class="tr-hint">Zurückschreiben dürfen der Eintragende, Offiziere und Einsatzleiter von ALPHA.</span>`;
  }
  return state.missionRole === 'manager'
    ? `<button class="btn btn--cta" data-act="ed-update" data-what="Einsatzroute">${ic('save')} Einsatzroute aktualisieren</button><span class="tr-hint">Speichert den Plan und schickt „Routenplan geändert“ an die Crews der Flotte.</span>`
    : `${mine('btn--cta')}<span class="tr-hint">Du bearbeitest eine Kopie; zurückschreiben darf nur ein Einsatzleiter.</span>`;
}

/**
 * Renders the status line of the planner's working state.
 * @param {object} k the edit context
 * @returns {string} markup
 */
function editStatus(k) {
  const searching = editSearchStatus();
  if (searching) return searching;
  const o = state.ed.optimising;
  if (o) return `<div class="tr-status tr-status--running" role="status"><span class="krt-spinner" aria-hidden="true"></span><strong>Rest optimieren · feste Stopps bleiben · ${o.long ? 'lange Suche · ' : ''}bis ${o.upTo} Stopps</strong><span class="tr-status__bar"><i style="width:${[20, 55, 85][o.step]}%"></i></span><span class="muted num">${o.elapsed} s / 60 s</span><button class="btn btn-ghost btn-xs" data-act="ed-opt-stop">Suche stoppen</button></div>`;
  const h = k.tab.hist[k.tab.at];
  const last = state.long.last;
  const done = `Suche abgeschlossen in ${last.elapsed} s · ${state.ed.tabs.length} Routen${last.long ? ' · lange Suche' : ''}${last.stopped ? ' · gestoppt, bestes Zwischenergebnis' : ''}`;
  if (!k.tab.at) return `<div class="tr-status" role="status">${ic('check')}<span>${state.ed.src === 'result' ? done :`${SRC_LABEL[state.ed.src]} geöffnet · Fracht neu geplant`} · Datenstand 14:00 Uhr</span><span class="muted">Bearbeite direkt in der Zeitleiste: ↑ ↓, entfernen, einfügen, festsetzen, markieren.</span></div>`;
  return `<div class="tr-status" role="status">${ic('edit')}<span><strong>Bearbeitet</strong> · Schritt ${k.tab.at} von ${k.tab.hist.length - 1}: ${esc(h.label)} · ${h.w.kept ? 'Restplan in 0,2 s' : h.diff ? 'Fracht neu geplant in 0,4 s' : 'ohne Neuberechnung'}</span><span class="muted">Arbeitsstand in diesem Browser-Tab; der Server hält nichts, bis du speicherst, teilst oder losfliegst.</span></div>`;
}

/**
 * Renders the planner page with the working state of the shown tab.
 * @returns {string} markup
 */
function editPage() {
  const k = edWork('plan');
  const e = evaluate(k.w, k.w.ships);
  pageRoutes.edit = e;
  const tabs = state.ed.tabs.map((tb, i) => {
    const r = evaluate(tb.hist[tb.at].w, tb.hist[tb.at].w.ships);
    return `<button class="tab ${i === state.ed.tab ? 'active' : ''}" role="tab" aria-selected="${i === state.ed.tab}" data-act="ed-tab" data-val="${i}">${tb.label}${tb.at ? ' <span class="tr-mark tr-mark--forced">bearbeitet</span>' : ''}<small><span class="tr-tab__more">${fmt(r.perHour)} aUEC/h · ${fmtDur(r.duration)} · </span>${r.stops.length} Stopps</small></button>`;
  }).join('');
  const src = state.ed.src === 'result' ? '' : `<div class="tr-ctxbar"><span class="chip chip--primary">${SRC_LABEL[state.ed.src]}</span><strong>${esc({ saved: 'Abendrunde Crusader – ArcCorp', shared: 'Drei-System-Runde', unit: 'Eis-Pendel Hurston', mission: 'Stanton-Runde · Frachtkonvoi Pyro' }[state.ed.src])}</strong><span class="tr-hint">${state.ed.src === 'mission' ? 'Schiffsliste ist die Routenflotte; Stoppzeiten aus der Einsatzroute' : 'Im Planer bearbeitet; die Quelle bleibt, bis du sie aktualisierst'}</span></div>`;
  const reqBar = state.ed.requestChanged ? `<div class="tr-warnline tr-warnline--info tr-reqbar" role="status">${ic('info')}<span><strong>Anfrage geändert.</strong> Das Ergebnis rechnet noch mit der alten Anfrage.</span><span class="tr-actions"><button class="btn btn-ghost btn-xs" data-act="ed-research">${ic('search')} Neu suchen</button><button class="btn btn-outline btn-xs" data-act="ed-replan-request">${ic('reset')} Nur Fracht neu planen</button></span></div>` : '';
  const tb = k.tab;
  const work = `<div class="tr-workbar"><span class="tr-iconbtns"><button class="btn btn-ghost btn-xs" data-act="ed-undo" ${tb.at && !k.busy ? '' : 'disabled'}>${ic('arrow-left')} Rückgängig</button><button class="btn btn-ghost btn-xs" data-act="ed-redo" ${tb.at < tb.hist.length - 1 && !k.busy ? '' : 'disabled'}>Wiederholen ${ic('arrow-right')}</button></span><button class="btn btn-ghost btn-xs" data-act="ed-restore" ${tb.at && !k.busy ? '' : 'disabled'}>${ic('reset')} Berechnete Route wiederherstellen</button><span class="tr-hint">${tb.hist.length - 1} Schritt${tb.hist.length === 2 ? '' : 'e'} in diesem Tab</span></div>`;
  const pins = k.w.stops.filter((s) => s.pinned).length;
  const count = k.w.stops.length;
  const over = count > searchLimit();
  const upTo = Math.min(searchLimit(), Math.max(state.request.maxStops, count));
  const optHint = over
    ? `<span class="tr-hint tr-hint--warn">${ic('info')} Rest optimieren sucht höchstens ${searchLimit()} Stopps (Suchgrenze); diese Route hat ${count}. Entferne Stopps, um wieder zu optimieren. Bearbeiten und Einfügen gehen weiter bis ${ROUTE_LIMIT}.</span>`
    : `<span class="tr-hint">Feste Stopps bleiben an ihrer Stelle; die freien sucht die Suche neu, bis zu ${upTo} Stopps${upTo > LONG_JOB_ABOVE ? ' als lange Suche' : ''} (bis 60 s).</span>`;
  const pinbar = `<div class="tr-pinbar"><span>${ic('map-pin')} <strong>${pins} von ${count}</strong> Stopps fest</span><button class="btn btn-outline btn-xs" data-act="ed-optimise" ${k.busy || pins === count || over ? 'disabled' : ''}>${ic('search')} Rest optimieren</button>${optHint}</div>`;
  const note = `<p class="proto-note"><strong>PROTOTYP</strong> · Bearbeiten einer berechneten Route · Quelle: ${SRC_LABEL[state.ed.src]} · Änderungsanzeige ${state.ed.diffView === 'A' ? 'A: in der Zeitleiste' : 'B: als Tabelle'} · Einfügen und Alternativen ${state.ed.pick === 'dialog' ? 'im Dialog' : 'in der Zeitleiste'}. ${longNote()} Alles rechnet eine kleine Engine im Browser auf erfundenen Preisen.</p>`;
  return `${pageHead()}${note}<div class="tr-a">${requestAside()}<section class="tr-a__result" aria-label="Ergebnis">
    ${src}${editStatus(k)}${reqBar}
    <div><div class="tab-nav tr-a__tabs" role="tablist">${tabs}</div>
    <div class="tr-a__routecard">
      ${stopIndex(k, e)}
      <div class="tr-row tr-row--between"><div><div class="tr-stoptile__title">${esc(e.name)}</div><div class="tr-hint">${e.convoy ? `Konvoi · ${e.ships.length} Schiffe` : esc(k.w.ships[0].name)}</div></div><div class="tr-actions">${editSaveActions(k)}</div></div>
      ${work}${keptBanner(k, e)}${diffBlock(k, e)}
      ${kpis(e)}${warnList(e)}${shipShares(e)}${pinbar}
      ${editTimeline(k, e)}
    </div></div></section></div>`;
}

/**
 * Renders the dialogs of the edit flows; empty for a dialog of another surface.
 * @param {object} m the open dialog
 * @param {function(string, string, string, boolean): string} shell the dialog frame
 * @returns {string} markup
 */
function editModal(m, shell) {
  if (m.kind === 'ed-panel') {
    const k = edWork(state.ed.panel.ctx);
    const body = state.ed.panel.kind === 'insert' ? insertPanel(k) : markPanel(k);
    const title = state.ed.panel.kind === 'insert' ? 'Stopp einfügen' : state.ed.panel.side === 'buy' ? 'Ausverkauft' : 'Keine Nachfrage';
    return shell(title, body, '<button class="btn btn-ghost" data-act="ed-panel-close">Schließen</button>', true);
  }
  if (m.kind === 'ed-conflict') {
    return shell('Inzwischen geändert', `<div class="tr-warnline tr-warnline--danger">${ic('warning')}<span>${esc(m.what)}: Jemand hat gespeichert, während du bearbeitet hast (409). Dein Arbeitsstand ist nicht verloren.</span></div><span class="tr-hint">„Neu laden“ holt den gespeicherten Stand in den Planer und verwirft deine Schritte; „Als neue speichern“ behält beide.</span>`, `<button class="btn btn-ghost" data-act="ed-reload">${ic('reset')} Neu laden</button><button class="btn btn--cta" data-act="${m.what === 'Einheitsroute' ? 'to-unit' : 'save'}" data-route="edit">${ic('save')} Als neue speichern</button>`);
  }
  return '';
}
