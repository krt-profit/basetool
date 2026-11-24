/**
 * Escapes text for HTML.
 * @param {unknown} s
 * @returns {string}
 */
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

/**
 * Returns an icon of the sprite.
 * @param {string} name
 * @returns {string}
 */
function icon(name) {
  return `<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-${name}"></use></svg>`;
}

/**
 * Returns the full type label, manufacturer first.
 * @param {string} key
 * @returns {string}
 */
function typeLabel(key) {
  const t = TYPES[key];
  return `${t.maker} ${t.name}`;
}

/**
 * Returns the type chip a ship type carries: RSI's status for a concept ship, „Konzeptschiff"
 * without one, „Nicht im Spiel" for a type without a GUID that is no concept ship.
 * @param {string} key
 * @returns {string}
 */
function typeChip(key) {
  const t = TYPES[key];
  if (t.kind === 'concept') {
    if (t.status === 'IN_PRODUCTION') return '<span class="chip chip--info" title="Nicht flugbereit · laut RSI in Produktion">In Produktion</span>';
    if (t.status === 'IN_CONCEPT') return '<span class="chip chip--warning" title="Nicht flugbereit · laut RSI im Konzept">Im Konzept</span>';
    return '<span class="chip chip--warning" title="Nicht flugbereit · markiert durch die Loaner-Matrix">Konzeptschiff</span>';
  }
  if (t.kind === 'notInGame') return '<span class="chip chip--warning">Nicht im Spiel</span>';
  return '';
}

/**
 * Returns the loaner types a type grants now: the effective pairs without those switched off.
 * @param {string} key
 * @returns {string[]}
 */
function grantsOf(key) {
  if (TYPES[key].kind !== 'concept') return [];
  return (PAIRS[key] || []).filter((p) => p.source !== 'off' && TYPES[p.loaner].kind === 'flight').map((p) => p.loaner);
}

/**
 * Derives the held loaners from a member's ships: loaner type → distinct granting types in order.
 * @param {Array<{type: string}>} ships
 * @returns {Map<string, string[]>}
 */
function heldLoaners(ships) {
  const held = new Map();
  for (const s of ships) {
    for (const l of grantsOf(s.type)) {
      const g = held.get(l) || [];
      if (!g.includes(s.type)) g.push(s.type);
      held.set(l, g);
    }
  }
  return held;
}

/**
 * Returns the tag text of a loaner: up to two granting types, then „+n".
 * @param {string[]} granting
 * @returns {{text: string, title: string}}
 */
function loanerFor(granting) {
  const names = granting.map((g) => TYPES[g].name);
  const shown = names.length > 2 ? `${names.slice(0, 2).join(', ')} +${names.length - 2}` : names.join(', ');
  return { text: `Loaner für ${shown}`, title: `Loaner für ${names.join(', ')}` };
}

/**
 * Returns a squadron badge.
 * @param {string|null} unit
 * @returns {string}
 */
function unitBadge(unit) {
  return unit ? `<span class="squadron-badge" title="${esc(UNITS[unit])}">${esc(unit)}</span>` : '<span class="squadron-badge squadron-badge-muted">—</span>';
}

/**
 * Returns a status cell for the fitted mark.
 * @param {boolean} ready
 * @returns {string}
 */
function fitBadge(ready) {
  return `<span class="hangar-fit${ready ? ' hangar-fit--ready' : ''}"><span class="status-dot${ready ? ' status-dot--on' : ''}" aria-hidden="true"></span><span class="hangar-fit__label">${ready ? 'Einsatzbereit' : 'Nicht bereit'}</span></span>`;
}

/**
 * Returns a segmented control.
 * @param {string} name
 * @param {Array<[string, string, (number|undefined)?]>} options value, label, optional count
 * @param {string} selected
 * @param {string} [label]
 * @returns {string}
 */
function segmented(name, options, selected, label) {
  const items = options.map(([v, l, n]) => `<label class="krt-segmented__item"><input type="radio" name="${name}" value="${v}"${v === selected ? ' checked' : ''}><span>${esc(l)}</span>${n !== undefined ? `<span class="krt-segmented__count">${n}</span>` : ''}</label>`).join('');
  return `<div class="krt-segmented" role="radiogroup" aria-label="${esc(label || name)}">${items}</div>`;
}

/**
 * Returns the page head.
 * @param {{eyebrow: string, title: string, count?: number, actions?: string, back?: boolean}} o
 * @returns {string}
 */
function pageHead(o) {
  const eyebrow = o.back ? `<a class="page-eyebrow" href="#">${icon('arrow-left')}<span>${esc(o.eyebrow)}</span></a>` : `<span class="page-eyebrow">${esc(o.eyebrow)}</span>`;
  const count = o.count !== undefined ? `<span class="count-badge count-badge--sm">${o.count}</span>` : '';
  return `<div class="page-head"><div class="page-head__main">${eyebrow}<div class="page-title"><h1>${esc(o.title)}</h1>${count}</div></div>${o.actions ? `<div class="page-actions">${o.actions}</div>` : ''}</div>`;
}

/**
 * Opens a dialog in the app's modal shape.
 * @param {{title: string, body: string, foot: string, wide?: boolean, danger?: boolean, id?: string}} o
 * @returns {HTMLDialogElement}
 */
function openDialog(o) {
  const root = /** @type {HTMLElement} */ (document.getElementById('dialog-root'));
  root.innerHTML = `<dialog class="krt-modal-overlay is-open" id="${o.id || 'proto-dialog'}" aria-label="${esc(o.title)}"><div class="krt-modal${o.wide ? ' krt-modal--wide' : ''}${o.danger ? ' krt-modal--danger' : ''}"><div class="krt-modal-head"><h2>${esc(o.title)}</h2><button type="button" class="krt-modal-close" data-act="close-dialog" aria-label="Schließen">&#10005;</button></div><div class="krt-modal-body">${o.body}</div><div class="krt-modal-foot">${o.foot}</div></div></dialog>`;
  const d = /** @type {HTMLDialogElement} */ (root.firstElementChild);
  d.showModal();
  d.addEventListener('close', () => { root.innerHTML = ''; });
  return d;
}

/** Closes the open dialog. */
function closeDialog() {
  const d = document.querySelector('#dialog-root dialog');
  if (d instanceof HTMLDialogElement) d.close();
}

/**
 * Shows a toast that fades after a few seconds.
 * @param {string} msg
 * @param {string} [kind]
 */
function toast(msg, kind) {
  const root = /** @type {HTMLElement} */ (document.getElementById('toast-root'));
  const el = document.createElement('div');
  el.className = `ln-toast${kind === 'info' ? ' ln-toast--info' : ''}`;
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => el.remove(), 4200);
}

/**
 * Reads a URL search parameter.
 * @param {string} key
 * @param {string} fallback
 * @returns {string}
 */
function param(key, fallback) {
  return new URLSearchParams(location.search).get(key) || fallback;
}

/**
 * Writes URL search parameters without reloading.
 * @param {Record<string, string|null>} values
 */
function setParams(values) {
  const p = new URLSearchParams(location.search);
  for (const [k, v] of Object.entries(values)) {
    if (v === null) p.delete(k); else p.set(k, v);
  }
  history.replaceState(null, '', `${location.pathname}?${p}`);
}

/**
 * Returns the type sort key of REQ-HANGAR-002: manufacturer, then type name.
 * @param {string} key
 * @returns {string}
 */
function typeSortKey(key) {
  return `${TYPES[key].maker}\u0000${TYPES[key].name}`;
}

/**
 * Returns a prototype note line, set apart from the design under review.
 * @param {string} html
 * @returns {string}
 */
function protoNote(html) {
  return `<p class="ln-proto-note">${html}</p>`;
}
