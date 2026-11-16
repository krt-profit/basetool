/** PROTOTYPE stop ratings: the list of places, a place's detail with distributions and reset, and "My reports". */

/**
 * Renders a distribution over the steps of a scale as bars.
 * @param {Array<number>} shares the weighted share per step in percent
 * @param {Array<string>} labels the step labels
 * @returns {string} markup
 */
function distBars(shares, labels) {
  return `<div class="tr-dist">${shares.map((p, i) => `<div class="tr-dist__row"><span class="tr-dist__k">${i + 1} · ${labels[i]}</span><span class="tr-dist__bar"><i style="width:${p}%"></i></span><span class="tr-dist__v num">${p} %</span></div>`).join('')}</div>`;
}

/**
 * Renders the three availability shares as one stacked bar.
 * @param {Array<number>} shares as shown, less, nothing, in percent
 * @returns {string} markup
 */
function availBar(shares) {
  return `<span class="tr-availbar" title="${AVAIL_STEPS.map((l, i) => `${l}: ${shares[i]} %`).join(' · ')}">${shares.map((p, i) => `<i class="s${i}" style="width:${p}%"></i>`).join('')}</span>`;
}

/**
 * Lists the terminals of a place.
 * @param {string} place the place id
 * @returns {Array<[string, object]>} terminal ids and terminals
 */
function terminalsOf(place) {
  return Object.entries(TERMINALS).filter(([, t]) => t.place === place);
}

/**
 * Renders the admin reset button for one object and scale.
 * @param {string} what the object and scale in words
 * @param {number} count the number of reports it removes
 * @returns {string} markup
 */
function resetBtn(what, count) {
  return state.role === 'admin' ? `<button class="btn btn-quiet-danger btn-xs" data-act="confirm-reset" data-what="${esc(what)}" data-val="${count}">${ic('reset')} Zurücksetzen</button>` : '';
}

/**
 * Renders the list of places with danger and their terminals with bugs.
 * @returns {string} markup
 */
function placesTab() {
  const f = state.ratingFilter;
  const worst = (id) => Math.max(...terminalsOf(id).map(([, t]) => t.bugs.m));
  let places = Object.entries(PLACES).filter(([, p]) => (!f.systems.length || f.systems.includes(p.system)) && (!f.kinds.length || f.kinds.includes(p.kind)));
  const key = { name: ([, p]) => p.name, danger: ([, p]) => -p.danger.m, bugs: ([id]) => -worst(id) }[f.sort];
  places = places.sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
  const sortTh = (k, label, cls) => `<th class="${cls || ''}" aria-sort="${f.sort === k ? 'descending' : 'none'}"><button type="button" class="sort-header" data-act="rating-sort" data-val="${k}">${label} <span class="sort-header__mark" aria-hidden="true"></span></button></th>`;
  const rows = places.map(([id, p]) => `<tr>
      <td><a class="row-link" href="#" data-act="go-place" data-val="${id}"><span class="cell-title">${esc(p.name)}</span><span class="cell-sub">${p.kind} · ${p.body} · ${p.system}</span></a></td>
      <td data-label="Gefahr">${rateChip('Gefahr', p.danger, MIN_RATERS.danger)}</td>
      <td class="cell-block" data-label="Terminals"><div class="tr-termlist">${terminalsOf(id).map(([, t]) => `<div><span>${esc(t.name)}</span> ${rateChip('Bugs', t.bugs, MIN_RATERS.bugs)}</div>`).join('')}</div></td>
      <td class="cell-actions"><svg class="krt-icon row-chevron" aria-hidden="true"><use href="#krt-icon-chevron-right"></use></svg></td>
    </tr>`).join('');
  const tg = (key2, values) => `<div class="tr-toggles" role="group">${values.map((v) => `<button type="button" class="tr-toggle" data-act="rating-filter" data-key="${key2}" data-val="${esc(v)}" aria-pressed="${f[key2].includes(v)}">${esc(v)}</button>`).join('')}</div>`;
  return `<div class="tr-ratingfilter"><div class="tr-field"><span class="tr-cap">System</span>${tg('systems', SYSTEMS)}</div><div class="tr-field"><span class="tr-cap">Ortsart</span>${tg('kinds', KINDS.slice(0, 3))}</div></div>
    <span class="tr-hint">Anonym und organisationsweit. Ältere Meldungen zählen weniger (Halbwertszeit Gefahr 14, Bugs 30, Verfügbarkeit 7 Tage); jedes Mitglied zählt einmal. Unter 3 / 3 / 2 Meldern steht „wenige Meldungen“ und keine Schwelle greift.</span>
    <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr>${sortTh('name', 'Ort')}${sortTh('danger', 'Gefahr')}${sortTh('bugs', 'Terminals · Bugs')}<th class="cell-actions"><span class="visually-hidden">Öffnen</span></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the member's own counting reports.
 * @returns {string} markup
 */
function myReportsTab() {
  const list = state.myReportList;
  if (!list.length) return '<div class="empty-state"><div class="empty-title">Keine Meldungen</div><div class="empty-text">Melde Gefahr, Bugs oder Verfügbarkeit an einem Routenstopp oder auf der Seite eines Orts.</div></div>';
  const rows = list.map((r) => `<tr>
      <td><span class="cell-title">${esc(r.obj)}</span><span class="cell-sub">${esc(r.when)}</span></td>
      <td class="cell-status"><span class="chip chip--muted">${esc(r.scale)}</span></td>
      <td data-label="Wert">${esc(r.val)}</td>
      <td data-label="Zählt">${esc(r.counts)}</td>
      <td class="cell-actions">${rowBtn('confirm-report-delete', 'trash', 'Meldung löschen', 'btn-quiet-danger', `data-val="${r.id}"`)}</td>
    </tr>`).join('');
  return `<span class="tr-hint">Nur du siehst diese Liste. Gezeigt sind deine Meldungen, die noch zählen. Bearbeiten geht nicht: Eine neue Meldung am selben Tag ersetzt die alte. Löschen nimmt eine Meldung beim nächsten Stundenlauf aus dem Durchschnitt.</span>
    <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr><th>Objekt</th><th class="cell-status">Skala</th><th>Wert</th><th>Zählt</th><th class="cell-actions"><span class="visually-hidden">Löschen</span></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the stop ratings page with its two tabs.
 * @returns {string} markup
 */
function ratingsPage() {
  const tabs = [['places', 'Orte', String(Object.keys(PLACES).length)], ['mine', 'Meine Meldungen', String(state.myReportList.length)]];
  return `<div class="page-head"><div class="page-head__main"><span class="page-eyebrow">Handel</span><div class="page-title"><h1>Stoppbewertungen</h1></div></div></div>
    <p class="proto-note"><strong>PROTOTYP</strong> · Navigationsgruppe „Handel“. Jeder Routenstopp und der Terminal-Picker verlinken hierher. Ansicht als ${state.role === 'admin' ? 'Admin' : 'Mitglied'}.</p>
    <div class="tab-nav" role="tablist">${tabs.map(([k, l, c]) => `<button class="tab ${state.ratingsTab === k ? 'active' : ''}" role="tab" aria-selected="${state.ratingsTab === k}" data-act="ratings-tab" data-val="${k}">${l} <span class="tab-count">${c}</span></button>`).join('')}</div>
    <div class="tr-stack tr-tabbody">${state.ratingsTab === 'mine' ? myReportsTab() : placesTab()}</div>`;
}

/**
 * Renders a place's detail: danger with its distribution, each terminal's bugs and availability.
 * @returns {string} markup
 */
function placePage() {
  const id = state.place;
  const p = PLACES[id];
  const dist = DISTRIBUTION.danger[id] || [20, 40, 30, 10, 0];
  const terms = terminalsOf(id).map(([tid, t]) => {
    const avail = AVAILABILITY[tid] || [];
    const rows = avail.map((a) => {
      const few = a.n < MIN_RATERS.avail;
      return `<tr><td><span class="tr-cname">${esc(a.c)}</span></td><td><span class="tr-side tr-side--${a.side}">${a.side === 'buy' ? 'Kauf · Bestand' : 'Verkauf · Nachfrage'}</span></td>
        <td class="num"><b>${a.pct} %</b>${few ? ' <span class="tr-mark tr-mark--muted">wenige Meldungen</span>' : ''}</td><td>${availBar(a.shares)}</td><td class="tr-hint nowrap">${a.n} Melder · vor ${a.d} T.</td>
        <td class="num"><span class="tr-iconbtns"><button class="btn btn-ghost btn-xs" data-act="place-rate" data-key="avail" data-what="${esc(`${a.c} · ${a.side === 'buy' ? 'Kauf' : 'Verkauf'} · ${t.name}`)}">Melden</button>${resetBtn(`Verfügbarkeit ${a.c} (${a.side === 'buy' ? 'Kauf' : 'Verkauf'}) an ${t.name}`, a.n + 2)}</span></td></tr>`;
    }).join('');
    const table = rows ? `<div class="hud-scroll scroll-x"><table class="tr-jobs tr-jobs--avail"><thead><tr><th>Ware</th><th>Seite</th><th class="num">Zuverlässigkeit</th><th>wie angezeigt · weniger · nichts</th><th>Melder</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>` : '<span class="tr-hint">Noch keine Verfügbarkeitsmeldungen an diesem Terminal.</span>';
    return `<section class="card"><div class="card-head"><h3 class="card-title">${esc(t.name)}</h3></div>
      <div class="tr-place__grid"><div class="tr-stack" style="gap:8px"><div class="tr-row tr-row--between">${rateChip('Bugs', t.bugs, MIN_RATERS.bugs)}<span class="tr-row"><button class="btn btn-ghost btn-xs" data-act="place-rate" data-key="bugs" data-what="${esc(t.name)}">Bugs melden</button>${resetBtn(`Bugs an ${t.name}`, t.bugs.n + 3)}</span></div>${distBars(DISTRIBUTION.bugs, BUG_STEPS)}</div>
      <div class="tr-stack" style="gap:8px"><div class="tr-row tr-row--between"><span class="tr-cap">Verfügbarkeit je Ware und Seite</span><button class="btn btn-ghost btn-xs" data-act="place-rate" data-key="avail" data-what="${esc(t.name)}">Verfügbarkeit melden</button></div>${table}</div></div></section>`;
  }).join('');
  return `<div class="page-head"><div class="page-head__main"><a class="page-eyebrow" href="#" data-act="go" data-page="ratings">${ic('arrow-left')}<span>Stoppbewertungen</span></a><div class="page-title"><h1>${esc(p.name)}</h1></div></div></div>
    <div class="facts-bar"><span class="fact"><span class="fact-k">Ortsart</span> <span class="fact-v">${p.kind}</span></span><span class="fact"><span class="fact-k">Ort</span> <span class="fact-v">${p.body} · ${p.system}</span></span><span class="fact"><span class="fact-k">Waffenstillstand</span> <span class="fact-v">${p.armistice ? 'ja' : 'nein'}</span></span><span class="fact"><span class="fact-k">Überwacht</span> <span class="fact-v">${p.monitored ? 'ja' : 'nein'}</span></span></div>
    <p class="proto-note"><strong>PROTOTYP</strong> · Ansicht als ${state.role === 'admin' ? 'Admin: „Zurücksetzen“ je Objekt und Skala' : 'Mitglied'}. Verteilungen sind gewichtete Anteile der Melder, nicht Köpfe.</p>
    <div class="tr-stack tr-tabbody">
      <section class="card"><div class="card-head"><h3 class="card-title">Gefahr am Ort</h3></div>
        <div class="tr-place__grid"><div class="tr-stack" style="gap:8px"><div class="tr-row tr-row--between">${rateChip('Gefahr', p.danger, MIN_RATERS.danger)}<span class="tr-row"><button class="btn btn-ghost btn-xs" data-act="place-rate" data-key="danger" data-what="${esc(p.name)}">Gefahr melden</button>${resetBtn(`Gefahr an ${p.name}`, p.danger.n + 4)}</span></div>
        <span class="tr-hint">Gilt für alle Terminals dieses Orts. Deine letzte Meldung: 2 · gering, vor 3 Tagen.</span></div>${distBars(dist, DANGER_STEPS)}</div></section>
      ${terms}
    </div>`;
}
