/** Row-look alternatives of the hangar pages. */
const ROW_DIM = { key: 'row', label: 'Loaner-Zeile', opts: [['A', 'Tag in der Unterzeile'], ['B', 'Streifen + Tag-Chip']] };

/** Type-picker alternatives of the add and edit dialogs. */
const ADD_DIM = { key: 'add', label: 'Typwahl im Dialog', opts: [['A', 'eine Liste mit Status'], ['B', 'Gruppe „Nicht flugbereit"']] };

/**
 * The surfaces of the prototype with their roles and alternatives.
 * @type {Array<{key: string, label: string, admin?: boolean, roles?: string[][], dims: Array<{key: string, label: string, opts: string[][]}>}>}
 */
const PAGES = [
  { key: 'mine', label: 'Meine Schiffe', dims: [ROW_DIM, ADD_DIM] },
  { key: 'admin-hangar', label: 'Admin: Mitglieds-Hangar', admin: true, dims: [ROW_DIM, ADD_DIM] },
  { key: 'unit', label: 'Org-Einheit (Übersicht)', roles: [['member', 'Mitglied'], ['officer', 'Offizier']], dims: [
    { key: 'count', label: 'Loaner in der Typzeile', opts: [['A', 'nicht genannt'], ['B', '„davon n Loaner"'], ['C', 'Chip an der Anzahl']] },
    { key: 'section', label: 'Nicht flugbereit', opts: [['A', 'in derselben Tabelle'], ['B', 'eigene Box']] },
  ] },
  { key: 'mission', label: 'Einsatz: Einheiten', dims: [{ key: 'picker', label: 'Schiffswahl', opts: [['A', 'Zusatz „· Loaner"'], ['B', 'Gruppen Schiffe / Loaner']] }] },
  { key: 'matrix', label: 'Admin: Loaner-Matrix', admin: true, dims: [{ key: 'mlayout', label: 'Aufbau', opts: [['A', 'eine Seite'], ['B', 'Tabs']] }] },
  { key: 'shipdata', label: 'Schiffsdaten', roles: [['member', 'Mitglied'], ['admin', 'Admin']], dims: [{ key: 'sdlayout', label: 'Loaner', opts: [['A', 'als Spalte'], ['B', 'aufklappbar']] }] },
];

/**
 * Returns the current page definition.
 * @returns {typeof PAGES[number]}
 */
function currentPage() {
  const key = param('page', 'mine');
  return PAGES.find((p) => p.key === key) || PAGES[0];
}

/**
 * Returns the chosen alternative of a dimension.
 * @param {string} key
 * @returns {string}
 */
function dim(key) {
  return param(key, 'A');
}

/** Renders the current surface and the prototype bar, keeping the focus of a search field. */
function render() {
  const page = currentPage();
  const active = document.activeElement;
  const focusKey = active instanceof HTMLInputElement ? active.dataset.input : undefined;
  const caret = active instanceof HTMLInputElement ? active.selectionStart : null;
  const role = param('role', page.roles ? page.roles[0][0] : '');
  document.body.classList.toggle('admin-mode', Boolean(page.admin));
  const app = /** @type {HTMLElement} */ (document.getElementById('app'));
  let html = '';
  switch (page.key) {
    case 'mine': html = renderHangar({ admin: false, row: dim('row') }); break;
    case 'admin-hangar': html = renderHangar({ admin: true, row: dim('row') }); break;
    case 'unit': html = renderUnit({ officer: role === 'officer', count: dim('count'), section: dim('section') }); break;
    case 'mission': html = renderMission({ picker: dim('picker') }); break;
    case 'matrix': html = renderMatrix({ layout: dim('mlayout') }); break;
    case 'shipdata': html = renderShipData({ admin: role === 'admin', layout: dim('sdlayout') }); break;
    default: html = '';
  }
  app.innerHTML = html;
  renderBar(page, role);
  if (focusKey) {
    const el = /** @type {HTMLInputElement|null} */ (app.querySelector(`[data-input="${focusKey}"]`));
    if (el) { el.focus(); if (caret !== null) el.setSelectionRange(caret, caret); }
  }
}

/**
 * Renders the floating prototype bar: surface, role and the alternatives of the surface.
 * @param {typeof PAGES[number]} page
 * @param {string} role
 */
function renderBar(page, role) {
  const root = /** @type {HTMLElement} */ (document.getElementById('proto-root'));
  const pages = PAGES.map((p) => `<option value="${p.key}"${p.key === page.key ? ' selected' : ''}>${esc(p.label)}</option>`).join('');
  const roles = page.roles ? `<label class="ln-bar__field"><span>Rolle</span><select data-bar="role">${page.roles.map(([v, l]) => `<option value="${v}"${v === role ? ' selected' : ''}>${esc(l)}</option>`).join('')}</select></label>` : '';
  const dims = page.dims.map((d) => `<label class="ln-bar__field"><span>${esc(d.label)}</span><select data-bar="${d.key}">${d.opts.map(([v, l]) => `<option value="${v}"${v === dim(d.key) ? ' selected' : ''}>${v} · ${esc(l)}</option>`).join('')}</select></label>`).join('');
  root.innerHTML = `<div class="ln-bar" role="region" aria-label="Prototyp-Steuerung"><span class="ln-bar__tag">Prototyp</span>
    <button type="button" class="ln-bar__arrow" data-bar-step="-1" aria-label="Vorherige Oberfläche">‹</button>
    <label class="ln-bar__field"><span>Oberfläche</span><select data-bar="page">${pages}</select></label>
    <button type="button" class="ln-bar__arrow" data-bar-step="1" aria-label="Nächste Oberfläche">›</button>${roles}${dims}
    <span class="ln-bar__note">Daten erfunden · nichts wird gespeichert</span></div>`;
}

/**
 * Switches to a surface.
 * @param {string} key
 */
function go(key) {
  setParams({ page: key, role: null });
  closeDialog();
  window.scrollTo(0, 0);
  render();
}

/**
 * Routes an event to the current surface and re-renders when it asks for it.
 * @param {Event} ev
 */
function route(ev) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'change' && t instanceof HTMLSelectElement && t.dataset.bar) {
    if (t.dataset.bar === 'page') go(t.value); else { setParams({ [t.dataset.bar]: t.value }); render(); }
    return;
  }
  if (ev.type === 'click') {
    const step = /** @type {HTMLElement|null} */ (t.closest('[data-bar-step]'));
    if (step) {
      const i = PAGES.indexOf(currentPage());
      go(PAGES[(i + Number(step.dataset.barStep) + PAGES.length) % PAGES.length].key);
      return;
    }
    const link = /** @type {HTMLElement|null} */ (t.closest('[data-go]'));
    if (link) { ev.preventDefault(); go(String(link.dataset.go)); return; }
    if (t.closest('a[href="#"]')) ev.preventDefault();
    if (t.closest('[data-act="close-dialog"]')) { closeDialog(); return; }
  }
  const page = currentPage().key;
  let dirty = false;
  if (page === 'mine' || page === 'admin-hangar') dirty = importEvent(ev) || hangarEvent(ev, { admin: page === 'admin-hangar', add: dim('add') });
  else if (page === 'unit') dirty = unitEvent(ev);
  else if (page === 'mission') dirty = missionEvent(ev, { picker: dim('picker') });
  else if (page === 'matrix') dirty = matrixEvent(ev);
  else if (page === 'shipdata') dirty = shipDataEvent(ev);
  if (dirty) render();
}

document.addEventListener('click', route);
document.addEventListener('input', (ev) => { if (ev.target instanceof HTMLInputElement && ev.target.type === 'search') route(ev); });
document.addEventListener('change', (ev) => { if (!(ev.target instanceof HTMLInputElement && ev.target.type === 'search')) route(ev); });
document.addEventListener('keydown', (ev) => {
  const t = ev.target;
  if (t instanceof HTMLInputElement || t instanceof HTMLSelectElement || t instanceof HTMLTextAreaElement || document.querySelector('#dialog-root dialog')) return;
  if (ev.key !== 'ArrowLeft' && ev.key !== 'ArrowRight') return;
  const i = PAGES.indexOf(currentPage());
  go(PAGES[(i + (ev.key === 'ArrowRight' ? 1 : -1) + PAGES.length) % PAGES.length].key);
});
render();
