/** PROTOTYPE wiring of the surfaces beside the planner: pages, actions, dialogs and the prototype bar. */

const PAGES = {
  planner: 'Routenplaner',
  routes: 'Routen (Listen)',
  route: 'Geöffnete Route',
  mission: 'Einsatz · Routen',
  ratings: 'Stoppbewertungen',
  place: 'Ort mit Bewertungen',
  admin: 'Admin · Schalter',
  off: 'Schalter aus',
};

/**
 * Switches to a page and resets the working states that belong to the page left.
 * @param {string} page a key of PAGES
 * @returns {void}
 */
function goPage(page) {
  if (page !== 'planner') state.missionSearch = null;
  state.page = page;
  state.edited = false;
  state.modal = null;
  state.flight = null;
  window.scrollTo(0, 0);
}

/**
 * Asks for confirmation before a destructive action.
 * @param {string} title the question
 * @param {string} text the consequence
 * @param {string} ok the label of the confirming button
 * @param {string} act the action run on confirmation
 * @param {string} val the value handed to that action
 * @returns {void}
 */
function confirmFirst(title, text, ok, act, val) {
  state.modal = { kind: 'confirm', title, text, ok, act, val };
}

/**
 * Reads the share code typed into a field, without separators and in upper case.
 * @param {string} id the input id
 * @returns {string} the normalised code
 */
function typedCode(id) {
  const el = document.getElementById(id);
  return (el ? el.value : '').replace(/[^0-9a-z]/gi, '').toUpperCase();
}

const SURFACE_ACTIONS = {
  go: (el) => { goPage(el.dataset.page); if (el.dataset.tab) state.routesTab = el.dataset.tab; },
  'routes-tab': (el) => { state.routesTab = el.dataset.val; },
  'open-route': (el) => { goPage('route'); state.routeSrc = el.dataset.src; },
  'go-place': (el) => { goPage('place'); state.place = el.dataset.val; },
  'share-saved': () => { state.modal = { kind: 'share' }; },
  rename: (el) => { state.modal = { kind: 'rename', what: el.dataset.what }; },
  'confirm-delete': (el) => { const s = state.savedList.find((x) => x.id === el.dataset.val); confirmFirst('Gespeicherte Route löschen?', `„${s.name}“ wird endgültig gelöscht. Was du daraus geteilt hast, bleibt geteilt.`, 'Löschen', 'do-delete', s.id); },
  'do-delete': (v) => { state.savedList = state.savedList.filter((x) => x.id !== v); say('Route gelöscht', `${state.savedList.length} von ${SAVED_LIMIT} Plätzen belegt.`); },
  'confirm-delete-unit': (el) => { const u = state.unitList.find((x) => x.id === el.dataset.val); confirmFirst('Einheitsroute löschen?', `„${u.name}“ verschwindet für alle in ${u.unit}. Eigene Kopien der Mitglieder bleiben.`, 'Löschen', 'do-delete-unit', u.id); },
  'do-delete-unit': (v) => { state.unitList = state.unitList.filter((x) => x.id !== v); say('Einheitsroute gelöscht', 'Für alle Mitglieder der Einheit.'); },
  'confirm-revoke': (el) => confirmFirst('Teilen widerrufen?', `Code ${el.dataset.val} und sein Link funktionieren sofort nicht mehr. Wer die Route schon als eigene gespeichert hat, behält sie.`, 'Widerrufen', 'do-revoke', el.dataset.val),
  'do-revoke': (v) => { state.shares = state.shares.filter((x) => x.code !== v); state.revoked.push(v.replace(/-/g, '')); say('Teilen widerrufen', `Probier es: „Code öffnen“ mit ${v} zeigt jetzt „widerrufen oder unbekannt“.`); },
  'save-copy': () => { say('Als deine Route gespeichert', `${state.savedList.length + 1} von ${SAVED_LIMIT} Plätzen. Hangarschiffe anderer sind Typen.`); },
  'to-unit': () => { state.modal = { kind: 'to-unit' }; },
  'to-unit-confirm': () => { state.modal = null; say('In die Einheit gespeichert', 'Unabhängige Kopie in ALPHA (24 von 100). Hangarschiffe sind Typen.'); },
  'my-ship': () => { state.modal = { kind: 'my-ship' }; },
  'my-ship-confirm': () => { state.modal = null; say('Mit deinem Schiff geplant', 'Fracht auf denselben Stopps neu geplant; nichts wird zurückgeschrieben.'); },
  'saved-update': () => { state.edited = false; say('Gespeicherte Route aktualisiert', 'Inhalt ersetzt (Anfrage, Stopps, Momentaufnahme); Name und Notiz unberührt.'); },
  'save-new': () => { state.modal = { kind: 'save', route: 'o1' }; },
  'unit-update': () => { state.edited = false; say('Einheitsroute aktualisiert', 'Neue Momentaufnahme für alle in ALPHA.'); },
  research: () => { goPage('planner'); startSearch(); say('Neue Suche', 'Mit den gespeicherten Einstellungen auf aktuellen Daten.'); },
  'pin-optimise': () => { goPage('planner'); startSearch(); say('Suche mit festen Stopps', 'Die gespeicherten Stopps sind erzwungen; die Suche darf weitere ergänzen.'); },
  'edit-in-planner': () => { goPage('planner'); state.search = { ...state.search, phase: 'done' }; state.results = ['r2']; say('Im Planer', 'Als dein Arbeitsstand übernommen; speichern legt eine eigene Route an.'); },
  'open-code-go': (el) => {
    const code = typedCode(el.dataset.input || 'm-code');
    goPage('route');
    state.routeSrc = state.revoked.includes(code) ? 'dead' : 'shared';
  },
  'm-open': (el) => { const id = el.dataset.val; state.missionOpen = state.missionOpen.includes(id) ? state.missionOpen.filter((x) => x !== id) : [...state.missionOpen, id]; },
  'm-up': (el) => { const i = Number(el.dataset.val); const l = state.missionRoutes; [l[i - 1], l[i]] = [l[i], l[i - 1]]; say('Reihenfolge gespeichert', 'Eigener Abschnitt: kollidiert nicht mit der Bearbeitung einer Route.'); },
  'm-down': (el) => { const i = Number(el.dataset.val); const l = state.missionRoutes; [l[i + 1], l[i]] = [l[i], l[i + 1]]; say('Reihenfolge gespeichert', 'Eigener Abschnitt: kollidiert nicht mit der Bearbeitung einer Route.'); },
  'm-adopt': (el) => { const mr = state.missionRoutes.find((x) => x.id === el.dataset.val); mr.marks = []; say('Route aktualisiert', `Neuer Plan gespeichert; Hinweis „Routenplan geändert“ an die Crews von ${mr.fleet.join(', ') || 'niemandem'}.`); },
  'fleet-toggle': (el) => { const mr = state.missionRoutes.find((x) => x.id === el.dataset.route); const k = el.dataset.val; mr.fleet = mr.fleet.includes(k) ? mr.fleet.filter((x) => x !== k) : [...mr.fleet, k].sort(); },
  'fleet-apply': (el) => { const mr = state.missionRoutes.find((x) => x.id === el.dataset.route); mr.marks = mr.fleet.length ? [] : [{ kind: 'muted', text: 'keine Flotte gewählt' }]; delete mr.fleetNote; say('Flotte übernommen', 'Fracht auf den Stopps neu geplant und gespeichert; die Crews sind benachrichtigt.'); },
  'm-optimise': (el) => {
    const mr = state.missionRoutes.find((x) => x.id === el.dataset.val);
    goPage('planner');
    state.variant = 'A';
    state.missionSearch = { id: mr.id, name: mr.name };
    setScenario('convoy');
    startSearch();
  },
  'mission-adopt': () => { goPage('mission'); say('In den Einsatz übernommen', 'Der Plan ist ersetzt; Hinweis „Routenplan geändert“ an die Crews der Flotte.'); },
  'confirm-m-remove': (el) => { const mr = state.missionRoutes.find((x) => x.id === el.dataset.val); confirmFirst('Route aus dem Einsatz entfernen?', `„${mr.name}“ und ihr Plan verschwinden aus diesem Einsatz. Die Quelle bleibt.`, 'Entfernen', 'do-m-remove', mr.id); },
  'do-m-remove': (v) => { state.missionRoutes = state.missionRoutes.filter((x) => x.id !== v); say('Route entfernt', ''); },
  'add-route': () => { state.modal = { kind: 'add-route', from: 'saved' }; },
  'add-route-from': (el) => { state.modal.from = el.dataset.val; },
  'add-route-confirm': () => {
    if (state.modal.from === 'search') { const first = state.missionRoutes[0]; state.modal = null; SURFACE_ACTIONS['m-optimise']({ dataset: { val: first.id } }); return; }
    state.modal = null;
    say('Route hinzugefügt', 'Unabhängige Kopie im Einsatz; Stoppzeiten aus der Quelle. Wähle die Routenflotte.');
  },
  'fly-part': (el) => { state.flight = { route: el.dataset.route, idx: 0, phase: 'stop', done: {}, ship: Number(el.dataset.ship) }; },
  'm-view': (el) => { state.missionView = el.dataset.val; },
  'ratings-tab': (el) => { state.ratingsTab = el.dataset.val; },
  'rating-sort': (el) => { state.ratingFilter.sort = el.dataset.val; },
  'rating-filter': (el) => { const f = state.ratingFilter[el.dataset.key]; const v = el.dataset.val; state.ratingFilter[el.dataset.key] = f.includes(v) ? f.filter((x) => x !== v) : [...f, v]; },
  'place-rate': (el) => { state.modal = { kind: 'place-rate', key: el.dataset.key, what: el.dataset.what, vals: {} }; },
  'place-rate-send': () => { state.modal = null; state.myReportList = [{ id: Date.now(), when: 'gerade eben', obj: 'Area18', scale: 'Gefahr', val: '2 · gering', counts: 'voll · bis 11.01.' }, ...state.myReportList]; say('Gemeldet', 'Anonym. Erscheint sofort unter „Meine Meldungen“, im Durchschnitt mit dem nächsten Stundenlauf.'); },
  'confirm-reset': (el) => confirmFirst('Meldungen zurücksetzen?', `Alle ${el.dataset.val} Meldungen zu „${el.dataset.what}“ werden gelöscht, auch die zählenden. Niemand sieht, wer was gemeldet hat. Nicht umkehrbar; steht im Audit-Log.`, 'Zurücksetzen', 'do-reset', el.dataset.val),
  'do-reset': (v) => { say('Zurückgesetzt', `${v} Meldungen gelöscht. Der Wert gilt ab dem nächsten Stundenlauf als „wenige Meldungen“.`); },
  'confirm-report-delete': (el) => confirmFirst('Meldung löschen?', 'Sie zählt ab dem nächsten Stundenlauf nicht mehr.', 'Löschen', 'do-report-delete', el.dataset.val),
  'do-report-delete': (v) => { state.myReportList = state.myReportList.filter((x) => String(x.id) !== String(v)); say('Meldung gelöscht', ''); },
  'switch-draft': () => { state.switchDraft = !state.switchDraft; },
  'switch-save': () => {
    if (!state.switchDraft) { confirmFirst('Handelsrouten ausschalten?', 'Mitglieder verlieren sofort den Zugriff auf Planer, Routen und Stoppbewertungen; der Routen-Tab im Einsatz verschwindet. Alle Daten bleiben, die Datenjobs laufen weiter.', 'Ausschalten', 'do-switch', 'off'); return; }
    SURFACE_ACTIONS['do-switch']('on');
  },
  'do-switch': (v) => { state.switchOn = v === 'on'; state.switchDraft = state.switchOn; say('Gespeichert', `Handelsrouten ${state.switchOn ? 'eingeschaltet' : 'ausgeschaltet'}. Steht im Audit-Log.`); },
  'confirm-ok': () => { const m = state.modal; state.modal = null; SURFACE_ACTIONS[m.act](m.val); },
  'proto-role': (el) => { state.role = el.dataset.val; },
  'proto-mrole': (el) => { state.missionRole = el.dataset.val; },
  'proto-src': (el) => { state.routeSrc = el.dataset.val; state.edited = false; },
  'proto-alt': () => {
    if (state.page === 'route') state.openMode = state.openMode === 'planner' ? 'page' : 'planner';
    if (state.page === 'mission') state.missionAlt = state.missionAlt === 'top' ? 'switch' : 'top';
  },
  'proto-edited': () => { state.edited = !state.edited; },
  'proto-hangar': () => { state.hangarGone = !state.hangarGone; },
};

/**
 * Renders the dialogs of the surfaces; the planner's own dialogs stay in modal().
 * @param {object} m the open dialog
 * @param {function(string, string, string, boolean): string} shell the dialog frame
 * @returns {string} markup, or an empty string for a dialog of the planner
 */
function surfaceModal(m, shell) {
  const cancel = '<button class="btn btn-ghost" data-act="modal-close">Abbrechen</button>';
  if (m.kind === 'confirm') {
    return `<div class="krt-confirm-overlay" data-act="modal-scrim"><div class="krt-confirm-dialog" role="alertdialog" aria-modal="true"><h3 class="krt-confirm-title">${esc(m.title)}</h3><p class="krt-confirm-message">${esc(m.text)}</p><div class="krt-confirm-actions"><button class="btn btn-secondary" type="button" data-act="modal-close">Abbrechen</button><button class="btn btn-danger" type="button" data-act="confirm-ok">${esc(m.ok)}</button></div></div></div>`;
  }
  if (m.kind === 'rename') {
    return shell(`${m.what} umbenennen`, `<div class="tr-field"><label for="m-rn">Name</label><input id="m-rn" type="text" maxlength="80" value="Abendrunde Crusader – ArcCorp"></div><div class="tr-field"><label for="m-rnote">Notiz</label><textarea id="m-rnote" rows="3" maxlength="500">Stims nur, wenn Seraphim mehr als 300 SCU hat.</textarea></div><span class="tr-hint">Ändert nur Name und Notiz; kollidiert nicht mit einer gleichzeitigen Aktualisierung des Inhalts.</span>`, `${cancel}<button class="btn btn--cta" data-act="modal-ok" data-val="Name und Notiz gespeichert">${ic('save')} Speichern</button>`);
  }
  if (m.kind === 'to-unit') {
    return shell('In eine Einheit speichern', `<div class="tr-field"><label for="m-unit">Einheit</label><select id="m-unit">${MY_UNITS.map((u) => `<option>${u.label} · ${u.used} von ${UNIT_LIMIT}</option>`).join('')}</select><span class="tr-hint">Einheiten, in denen du Mitglied bist oder die du als Offizier erreichst. Später nicht änderbar.</span></div><div class="tr-field"><label for="m-uname">Name</label><input id="m-uname" type="text" maxlength="80" value="Seraphim › Area18 › Baijini Point"></div><div class="tr-field"><label for="m-unote">Notiz <span class="tr-hint">sehen alle in der Einheit</span></label><textarea id="m-unote" rows="2" maxlength="500"></textarea></div><span class="tr-hint">Die Einheit bekommt eine unabhängige Kopie; Hangarschiffe werden zu Typen. Du bleibst als „eingetragen von“ sichtbar und kannst sie ändern, solange du Mitglied bist.</span>`, `${cancel}<button class="btn btn--cta" data-act="to-unit-confirm">${ic('users')} In Einheit speichern</button>`);
  }
  if (m.kind === 'my-ship') {
    const opts = [['C2 Hercules „Lastesel“', 'Hangarschiff · 696 SCU'], ['Freelancer MAX „Kiste“', 'Hangarschiff · 120 SCU'], ['Katalogtyp wählen …', 'beliebiger Frachttyp']];
    return shell('Mit meinem Schiff berechnen', `<span class="tr-hint">Plant die Fracht auf denselben Stopps für dein Schiff neu. Die Route selbst bleibt unverändert.</span><div class="tr-stack" style="gap:6px">${opts.map(([n, s], i) => `<label class="tr-radio"><input type="radio" name="m-ship" ${i === 0 ? 'checked' : ''}> <span><strong>${n}</strong> <span class="tr-hint">${s}</span></span></label>`).join('')}</div>`, `${cancel}<button class="btn btn--cta" data-act="my-ship-confirm">${ic('search')} Neu planen</button>`);
  }
  if (m.kind === 'add-route') {
    const src = [['saved', 'Aus meinen gespeicherten Routen'], ['unit', 'Aus den Einheitsrouten'], ['code', 'Geteilte Route per Code'], ['search', 'Neue Suche für die Flotte']];
    const pick = { saved: `<select>${SAVED_ROUTES.map((s) => `<option>${esc(s.name)}</option>`).join('')}</select>`, unit: `<select>${UNIT_ROUTES.map((s) => `<option>${esc(s.name)} · ${s.unit}</option>`).join('')}</select>`, code: '<input type="text" placeholder="z. B. K7Q2-M9XA-P3">', search: '<span class="tr-hint">Öffnet den Routenplaner mit den Einheiten des Einsatzes als Schiffsliste; „In den Einsatz übernehmen“ legt die Route an.</span>' }[m.from];
    return shell('Route hinzufügen', `<div class="tr-scale" role="group">${src.map(([k, l]) => `<button type="button" class="tr-toggle" data-act="add-route-from" data-val="${k}" aria-pressed="${m.from === k}">${l}</button>`).join('')}</div><div class="tr-field">${pick}</div><span class="tr-hint">Der Einsatz bekommt eine unabhängige Kopie mit den Stoppzeiten der Quelle. Danach wählst du die Routenflotte. 3 von ${MISSION_ROUTE_LIMIT}.</span>`, `${cancel}<button class="btn btn--cta" data-act="add-route-confirm">${ic('plus')} Hinzufügen</button>`, true);
  }
  if (m.kind === 'place-rate') {
    const scale = (key, labels) => `<div class="tr-scale" role="group">${labels.map((l, i) => `<button type="button" class="tr-toggle" data-act="rate-val" data-key="${key}" data-val="${i + 1}" aria-pressed="${m.vals[key] === i + 1}">${key === 'avail' ? l : `${i + 1} · ${l}`}</button>`).join('')}</div>`;
    let body = '';
    if (m.key === 'danger') body = `<div class="tr-field"><span class="tr-cap">Gefahr am Ort ${esc(m.what)}</span>${scale('danger', DANGER_STEPS)}</div>`;
    if (m.key === 'bugs') body = `<div class="tr-field"><span class="tr-cap">Bugs am Terminal ${esc(m.what)}</span>${scale('bugs', BUG_STEPS)}</div>`;
    if (m.key === 'avail') body = `<div class="tr-grid2"><div class="tr-field"><label for="m-ac">Ware</label><select id="m-ac">${COMMODITIES.slice(0, 8).map((c) => `<option>${esc(c.name)}</option>`).join('')}</select></div><div class="tr-field"><span class="tr-cap">Seite</span><div class="krt-segmented" role="group"><button type="button" class="krt-segmented__item" data-act="rate-val" data-key="side" data-val="1" aria-pressed="${m.vals.side !== 2}">Kauf · Bestand</button><button type="button" class="krt-segmented__item" data-act="rate-val" data-key="side" data-val="2" aria-pressed="${m.vals.side === 2}">Verkauf · Nachfrage</button></div></div></div><div class="tr-field"><span class="tr-cap">Wie hast du es vorgefunden?</span>${scale('avail', AVAIL_STEPS)}</div>`;
    return shell('Melden', `<div class="tr-hint">Anonym und organisationsweit; sichtbar sind nur Durchschnitte. Eine zweite Meldung am selben Tag ersetzt die erste.</div>${body}`, `${cancel}<button class="btn btn--cta" data-act="place-rate-send">Melden</button>`, true);
  }
  return '';
}

/**
 * Renders the prototype bar's controls for the current page.
 * @returns {string} markup
 */
function surfaceBarControls() {
  const btn = (act, val, label, on) => `<button data-act="${act}" data-val="${val}" aria-pressed="${on}">${label}</button>`;
  const sep = '<span class="proto-bar__sep"></span>';
  const select = `<select class="proto-bar__page" data-proto-page aria-label="Fläche">${Object.entries(PAGES).map(([k, l]) => `<option value="${k}" ${state.page === k ? 'selected' : ''}>${l}</option>`).join('')}</select>`;
  let extra = '';
  if (state.page === 'route') {
    extra = `${sep}${['saved', 'unit', 'shared', 'dead'].map((k) => btn('proto-src', k, { saved: 'Gespeichert', unit: 'Einheit', shared: 'Geteilt', dead: 'Widerrufen' }[k], state.routeSrc === k)).join('')}${sep}${state.routeSrc === 'saved' ? `${btn('proto-alt', '', state.openMode === 'planner' ? 'A · im Planer' : 'B · Routenseite', true)}${btn('proto-hangar', '', 'Schiff weg', state.hangarGone)}` : ''}${state.routeSrc !== 'dead' ? btn('proto-edited', '', 'Bearbeitet', state.edited) : ''}${state.routeSrc === 'unit' ? btn('proto-role', state.role === 'officer' ? 'member' : 'officer', state.role === 'officer' ? 'Offizier' : 'Mitglied', true) : ''}`;
  }
  if (state.page === 'routes' && state.routesTab === 'unit') extra = `${sep}${btn('proto-role', 'member', 'Mitglied', state.role !== 'officer')}${btn('proto-role', 'officer', 'Offizier', state.role === 'officer')}`;
  if (state.page === 'mission') extra = `${sep}${btn('proto-mrole', 'manager', 'Leiter', state.missionRole === 'manager')}${btn('proto-mrole', 'crew', 'Crew', state.missionRole === 'crew')}${btn('proto-mrole', 'reader', 'Mitglied', state.missionRole === 'reader')}${state.missionRole === 'crew' ? `${sep}${btn('proto-alt', '', state.missionAlt === 'top' ? 'A · Teil oben' : 'B · Umschalter', true)}` : ''}`;
  if (state.page === 'place' || state.page === 'ratings') extra = `${sep}${btn('proto-role', 'member', 'Mitglied', state.role !== 'admin')}${btn('proto-role', 'admin', 'Admin', state.role === 'admin')}`;
  return `${select}${extra}`;
}

/**
 * Renders the current page of the surfaces.
 * @returns {string} markup
 */
function surfacePage() {
  return { routes: routesPage, route: routePage, mission: missionPage, ratings: ratingsPage, place: placePage, admin: adminPage, off: offPage }[state.page]();
}
