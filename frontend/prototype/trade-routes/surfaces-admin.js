/** PROTOTYPE switch: the admin page of traderoutes.enabled and what members see while it is off. */

/**
 * Renders the admin page of the trade-route switch with the data jobs that run regardless.
 * @returns {string} markup
 */
function adminPage() {
  const on = state.switchDraft;
  const jobs = DATA_JOBS.map((j) => `<tr><td><span class="cell-title">${esc(j.name)}</span><span class="cell-sub">${esc(j.every)}</span></td><td class="cell-status"><span class="status-pill ${j.ok ? 'status-active' : 'status-cancelled'}">${j.ok ? 'OK' : 'Fehler'}</span></td><td data-label="Letzter Lauf">${esc(j.last)}</td><td data-label="Ergebnis">${esc(j.result)}</td></tr>`).join('');
  const dirty = on !== state.switchOn;
  return `<div class="page-head"><div class="page-head__main"><span class="page-eyebrow">Betrieb</span><div class="page-title"><h1>Handelsrouten</h1></div></div></div>
    <p class="proto-note"><strong>PROTOTYP</strong> · Administration, Gruppe „Betrieb“, nach dem Muster von „Bedarf &amp; Spenden“ und „Ankauf“. Der Schalter ist ab Werk aus.</p>
    <div class="card tr-admin">
      <fieldset class="tr-formsec"><legend>Allgemein</legend>
        <label class="krt-switch"><input type="checkbox" role="switch" data-act="switch-draft" ${on ? 'checked' : ''}> Handelsrouten einschalten</label>
        <span class="tr-hint">Aus: Routenplaner, gespeicherte, geteilte und Einheitsrouten, die Routen im Einsatz, die Stoppbewertungen und die Exchange-Routen antworten „nicht gefunden“; Menüeintrag und Routen-Tab im Einsatz sind ausgeblendet. Gespeicherte Daten bleiben. Jede Änderung steht im Audit-Log.</span>
        <div class="tr-row"><span class="tr-cap">Zurzeit:</span><span class="status-pill ${state.switchOn ? 'status-active' : 'status-completed'}">${state.switchOn ? 'Eingeschaltet' : 'Ausgeschaltet'}</span>${dirty ? '<span class="tr-mark tr-mark--warn">ungespeichert</span>' : ''}</div>
      </fieldset>
      <div class="tr-formactions"><button type="button" class="btn btn--cta" data-act="switch-save" ${dirty ? '' : 'disabled'}>${ic('save')} Speichern</button></div>
    </div>
    <section class="tr-stack" style="gap:8px;margin-top:var(--space-4)"><div class="card-head"><h2 class="card-title">Datenjobs</h2></div>
      <span class="tr-hint">Laufen unabhängig vom Schalter, damit die Daten beim Einschalten frisch sind; die Preise dienen auch den Materialseiten. Der Routen-Datenstand (Snapshot je Instanz) wird nur gebaut, solange eingeschaltet: ${state.switchOn ? 'zuletzt heute, 14:02' : 'zurzeit keiner'}.</span>
      <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr><th>Job</th><th class="cell-status">Status</th><th>Letzter Lauf</th><th>Ergebnis</th></tr></thead><tbody>${jobs}</tbody></table></div>
    </section>`;
}

/**
 * Renders the trade group of the navigation with or without the route tool.
 * @param {boolean} on whether the switch is on
 * @returns {string} markup
 */
function navMock(on) {
  const links = ['Material', 'Materialbörse', 'Beschaffung', ...(on ? ['Routenplaner', 'Stoppbewertungen'] : [])];
  return `<section class="nav-group is-active tr-navmock"><div class="nav-group-head">${ic('swap')}<span class="nav-group-label">Handel</span></div><div class="nav-group-links">${links.map((l) => `<div class="nav-row"><span class="nav-link ${['Routenplaner', 'Stoppbewertungen'].includes(l) ? 'tr-navmock__new' : ''}">${l}</span></div>`).join('')}</div></section>`;
}

/**
 * Renders what members see while the switch is off, beside the state when it is on.
 * @returns {string} markup
 */
function offPage() {
  const tabs = (on) => `<div class="tab-nav"><span class="tab">Übersicht</span><span class="tab">Teilnehmer &amp; Einheiten</span><span class="tab">Finanzen &amp; Auszahlung</span>${on ? '<span class="tab active">Routen <span class="tab-count">3</span></span>' : ''}</div>`;
  return `<div class="page-head"><div class="page-head__main"><span class="page-eyebrow">Zustand</span><div class="page-title"><h1>Schalter aus</h1></div></div></div>
    <p class="proto-note"><strong>PROTOTYP</strong> · Was Mitglieder sehen, solange <code>traderoutes.enabled</code> aus ist, jeweils neben dem eingeschalteten Zustand. Admins sehen dasselbe; nur die Schalterseite bleibt erreichbar.</p>
    <div class="tr-offgrid">
      <section class="card"><div class="card-head"><h3 class="card-title">Menü</h3></div><div class="tr-offpair"><div><div class="tr-cap">Aus</div>${navMock(false)}</div><div><div class="tr-cap">Ein</div>${navMock(true)}</div></div><span class="tr-hint">Auch die Schnellsuche (Strg K) findet die Seiten nicht.</span></section>
      <section class="card"><div class="card-head"><h3 class="card-title">Lesezeichen, geteilter Link, alter Tab</h3></div>
        <div class="tr-dead hud-box"><div class="tr-dead__title">Signal verloren</div><p>Der Routenplaner ist derzeit nicht eingeschaltet.</p><div class="tr-row" style="justify-content:center"><button class="btn btn--cta">Zurück zur Basis</button></div></div>
        <span class="tr-hint">Dieselbe Seite für jede Routen-Adresse; ein offener Planer meldet beim nächsten Klick dieselbe Meldung als Fehler-Toast.</span></section>
      <section class="card tr-offwide"><div class="card-head"><h3 class="card-title">Einsatzseite</h3></div><div class="tr-cap">Aus</div>${tabs(false)}<div class="tr-cap" style="margin-top:10px">Ein</div>${tabs(true)}<span class="tr-hint">Die Einsatzrouten bleiben gespeichert und erscheinen beim Einschalten wieder.</span></section>
    </div>`;
}
