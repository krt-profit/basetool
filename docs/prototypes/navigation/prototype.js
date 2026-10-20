const ICONS = {
  star: '<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
  wedge: '<path d="M12 2l8 19-8-4-8 4z"/><circle cx="12" cy="12.5" r="2.2"/>',
  box: '<path d="M3 7.5L12 3l9 4.5v9L12 21l-9-4.5z"/><path d="M3 7.5l9 4.5 9-4.5M12 12v9"/>',
  trade: '<path d="M7 4L3 8l4 4M3 8h14M17 12l4 4-4 4M21 16H7"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.8-3.6 3.4-5.5 6.5-5.5s5.7 1.9 6.5 5.5"/><path d="M16 4.6a3.5 3.5 0 010 6.8M18.5 14.8c1.6.8 2.6 2.5 3 5.2"/>',
  bank: '<path d="M3 9.5L12 4l9 5.5M5 10v8M9.7 10v8M14.3 10v8M19 10v8M3 20.5h18"/>',
  more: '<circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/>',
  shield: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/><path d="M9 12l2 2 4-4"/>',
  database: '<ellipse cx="12" cy="5.5" rx="8" ry="2.5"/><path d="M4 5.5v13c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5v-13M4 12c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5"/>',
  gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
  orgchart: '<rect x="9" y="3" width="6" height="5"/><rect x="3" y="16" width="6" height="5"/><rect x="15" y="16" width="6" height="5"/><path d="M12 8v4M6 16v-4h12v4"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4.2 4.2-6.5 8-6.5s7 2.3 8 6.5"/>',
  grid: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/>',
  bell: '<path d="M6 16v-5a6 6 0 0112 0v5l2 2H4z"/><path d="M10 20a2 2 0 004 0"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  up: '<path d="M6 15l6-6 6 6"/>',
  down: '<path d="M6 9l6 6 6-6"/>',
  left: '<path d="M15 6l-6 6 6 6"/>',
  right: '<path d="M9 6l6 6-6 6"/>',
  eyeoff: '<path d="M3 3l18 18"/><path d="M10.6 6.1A9.8 9.8 0 0112 6c5 0 8.5 4 9.5 6-.4.9-1.3 2.2-2.6 3.4M6.5 7.6C4.6 8.9 3.2 10.7 2.5 12c1 2 4.5 6 9.5 6 1.6 0 3-.4 4.3-1"/>',
  eye: '<path d="M2.5 12C3.5 10 7 6 12 6s8.5 4 9.5 6c-1 2-4.5 6-9.5 6s-8.5-4-9.5-6z"/><circle cx="12" cy="12" r="3"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M14 6l4 4"/>',
  clipboard: '<rect x="5" y="4" width="14" height="17" rx="1"/><path d="M9 4V2.5h6V4M9 13l2 2 4-4"/>',
  flask: '<path d="M9 3h6M10 3v6l-5 9a2 2 0 001.8 3h10.4a2 2 0 001.8-3l-5-9V3"/><path d="M7.5 15h9"/>',
  hangar: '<path d="M3 20V10l9-6 9 6v10"/><path d="M7 20v-6h10v6"/>',
  diamond: '<path d="M12 3l8 9-8 9-8-9z"/><path d="M4 12h16"/>',
  cart: '<path d="M3 4h2l2.5 11h11L21 8H7"/><circle cx="9" cy="19" r="1.5"/><circle cx="17" cy="19" r="1.5"/>',
  rank: '<path d="M6 9l6-5 6 5M6 14l6-5 6 5M6 19l6-5 6 5"/>',
  pin: '<path d="M12 21s-6-5.5-6-11a6 6 0 0112 0c0 5.5-6 11-6 11z"/><circle cx="12" cy="10" r="2.2"/>',
  file: '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4M9 12h6M9 16h6"/>',
  upload: '<path d="M12 16V4M7 9l5-5 5 5M4 20h16"/>',
  link: '<path d="M10 14a4 4 0 005.7 0l3-3a4 4 0 00-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 00-5.7 0l-3 3a4 4 0 005.7 5.7l1-1"/>',
  drawer: '<rect x="3" y="4" width="18" height="16"/><path d="M9 4v16"/>',
  rail: '<rect x="3" y="4" width="18" height="16"/><path d="M7 4v16"/><path d="M4.5 8h1M4.5 12h1M4.5 16h1"/>',
  pinned: '<rect x="3" y="4" width="18" height="16"/><path d="M10 4v16M5 8h3M5 12h3M5 16h3"/>',
  key: '<rect x="2.5" y="7" width="19" height="11" rx="1"/><path d="M6 10.5h1M9.5 10.5h1M13 10.5h1M16.5 10.5h1M7 14.5h10"/>',
  logout: '<path d="M10 4H5v16h5M14 8l4 4-4 4M18 12H9"/>',
  swap: '<path d="M8 7h12M16 3l4 4-4 4M16 17H4M8 13l-4 4 4 4"/>',
  dot: '<circle cx="12" cy="12" r="3"/>',
};

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const icon = (n, cls = '') => `<svg class="ico ${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[n] || ICONS.dot}</svg>`;

const UNITS = { alpha: { name: 'Staffel Alpha', short: 'Alpha', promotion: true }, bravo: { name: 'SK Bravo', short: 'Bravo', promotion: false } };
const CONTEXTS = { alpha: 'Staffel Alpha', bravo: 'SK Bravo', all: 'Alle meine Einheiten' };
const CONTEXT_SHORT = { alpha: 'Alpha', bravo: 'Bravo', all: 'Alle' };
const PERSONAS = {
  member: { label: 'Mitglied', handle: 'Nightjar', officerIn: [], bank: false, admin: false },
  officer: { label: 'Offizier (Leitung Staffel Alpha)', handle: 'Vex', officerIn: ['alpha'], bank: false, admin: false },
  bank: { label: 'Bankmitarbeiter', handle: 'Halcyon', officerIn: [], bank: true, admin: false },
  admin: { label: 'Admin', handle: 'Corsair_7', officerIn: ['alpha', 'bravo'], bank: true, admin: true },
};

const NAV = {
  member: [
    { key: 'g.einsatz', label: 'Einsatz & Flotte', icon: 'wedge', pages: [
      { key: 'missions', label: 'Missionen', icon: 'wedge', tabs: [['missions.list', 'Missionen'], ['missions.operations', 'Operationen']] },
      { key: 'hangar', label: 'Hangar', icon: 'hangar', tabs: [['hangar.own', 'Hangar'], ['hangar.catalog', 'Schiffskatalog']] },
    ] },
    { key: 'g.logistik', label: 'Logistik', icon: 'box', pages: [
      { key: 'storage', label: 'Lager', icon: 'box', tabs: [['storage.stock', 'Bestand'], ['storage.minimum', 'Mindestbestände']] },
      { key: 'refinery', label: 'Raffinerie', icon: 'flask', tabs: [] },
      { key: 'orders', label: 'Aufträge', icon: 'clipboard', tabs: [['orders.list', 'Aufträge'], ['orders.demand', 'Materialbedarf'], ['orders.blueprints', 'Blueprint-Verfügbarkeit']] },
    ] },
    { key: 'g.handel', label: 'Handel', icon: 'trade', pages: [
      { key: 'material', label: 'Material', icon: 'diamond', tabs: [['material.list', 'Liste'], ['material.overview', 'Übersicht'], ['material.profit', 'Profitberechnung']] },
      { key: 'exchange', label: 'Materialbörse', icon: 'trade', tabs: [] },
      { key: 'procurement', label: 'Beschaffung', icon: 'cart', tabs: [['procurement.demand', 'Bedarf'], ['procurement.donations', 'Meine Spenden'], ['procurement.purchase', 'Ankauf']] },
    ] },
    { key: 'g.staffel', label: 'Staffel', icon: 'users', pages: [
      { key: 'org', label: 'Organisation', icon: 'orgchart', tabs: [['org.chart', 'Organigramm'], ['org.bank', 'Staffel-Bank'], ['org.lead', 'Leitung', 'officer']] },
      { key: 'promotion', label: 'Beförderung', icon: 'rank', gate: 'promotion', tabs: [['promotion.overview', 'Übersicht'], ['promotion.mine', 'Meine Bewertungen'], ['promotion.admin', 'Verwaltung', 'officer']] },
    ] },
    { key: 'g.bank', label: 'Kartellbank', icon: 'bank', gate: 'bank', pages: [
      { key: 'bank', label: 'Kartellbank', icon: 'bank', gate: 'bank', tabs: [['bank.dashboard', 'Dashboard'], ['bank.manage', 'Verwaltung'], ['bank.permissions', 'Berechtigungen'], ['bank.requests', 'Anträge']] },
    ] },
  ],
  admin: [
    { key: 'ga.members', label: 'Mitglieder & Organisation', icon: 'users', pages: [
      { key: 'admin.members', label: 'Mitgliederverwaltung', icon: 'users', tabs: [['admin.members.list', 'Mitglieder'], ['admin.members.discord', 'Discord-Registrierungen'], ['admin.members.terms', 'Zustimmung Nutzungsbedingungen']] },
      { key: 'admin.orgstructure', label: 'Organisationsstruktur', icon: 'orgchart', tabs: [['admin.orgstructure.tree', 'Struktur'], ['admin.orgstructure.squadrons', 'Staffeln'], ['admin.orgstructure.sk', 'Spezialkommandos']] },
      { key: 'admin.privacy', label: 'Datenschutz-Anfragen', icon: 'shield', tabs: [['admin.privacy.search', 'Personensuche'], ['admin.privacy.erasure', 'Löschanträge']] },
    ] },
    { key: 'ga.master', label: 'Stammdaten', icon: 'database', pages: [
      { key: 'admin.missiondata', label: 'Missionsdaten', icon: 'wedge', tabs: [['admin.missiondata.tasks', 'Aufgaben'], ['admin.missiondata.freq', 'Frequenzen']] },
      { key: 'admin.locations', label: 'Orte', icon: 'pin', tabs: [['admin.locations.list', 'Orte'], ['admin.locations.uex', 'UEX-Standorte & Terminals']] },
      { key: 'admin.shipdata', label: 'Schiffsdaten', icon: 'hangar', tabs: [] },
      { key: 'admin.materials', label: 'Materialien', icon: 'diamond', tabs: [['admin.materials.list', 'Materialien'], ['admin.materials.alias', 'Aliase'], ['admin.materials.tiers', 'Qualitätsstufen']] },
      { key: 'admin.blueprints', label: 'Blueprints', icon: 'file', tabs: [['admin.blueprints.list', 'Blueprints'], ['admin.blueprints.default', 'Standard']] },
      { key: 'admin.imports', label: 'Datenimporte', icon: 'upload', tabs: [['admin.imports.p4k', 'P4K-Import'], ['admin.imports.sync', 'Sync-Berichte']] },
    ] },
    { key: 'ga.ops', label: 'Betrieb', icon: 'gear', pages: [
      { key: 'admin.audit', label: 'Audit-Logs', icon: 'clipboard', tabs: [] },
      { key: 'admin.settings', label: 'Systemeinstellungen', icon: 'gear', tabs: [['admin.settings.general', 'Allgemein'], ['admin.settings.supply', 'Bedarf & Spenden'], ['admin.settings.purchase', 'Ankauf'], ['admin.settings.danger', 'Gefahrenzone']] },
      { key: 'admin.apps', label: 'Verbundene Anwendungen', icon: 'link', tabs: [] },
      { key: 'admin.notifrules', label: 'Benachrichtigungsregeln', icon: 'bell', tabs: [] },
    ] },
  ],
};

const EXTRA = [
  { key: 'home', label: 'Dashboard', icon: 'grid', mode: 'member', tabs: [] },
  { key: 'home.admin', label: 'Admin-Dashboard', icon: 'grid', mode: 'admin', tabs: [] },
  { key: 'notifications', label: 'Benachrichtigungen', icon: 'bell', mode: 'member', tabs: [], personal: true },
  { key: 'inventory', label: 'Mein Inventar', icon: 'box', mode: 'member', tabs: [['inventory.items', 'Inventar'], ['inventory.blueprints', 'Blueprints']], personal: true },
  { key: 'profile', label: 'Profil', icon: 'user', mode: 'member', tabs: [], personal: true },
  { key: 'apps', label: 'Verbundene Anwendungen', icon: 'link', mode: 'member', tabs: [], personal: true },
];

const TAB_DEFAULTS = { member: ['missions', 'storage', 'orders'], admin: ['home.admin', 'admin.members', 'admin.audit'] };
const FAV_CAP = 12;
const WIDGET_CAP = 12;

const REG = new Map();
const GROUPS = { member: NAV.member, admin: NAV.admin };
function addPage(p, mode, group) {
  const page = { key: p.key, label: p.label, icon: p.icon, kind: 'page', mode, group, gate: p.gate || (mode === 'admin' ? 'admin' : null), tabs: [], personal: !!p.personal };
  REG.set(p.key, page);
  for (const [k, l, g] of p.tabs) {
    REG.set(k, { key: k, label: l, kind: 'tab', page: p.key, mode, group, gate: g || null, icon: p.icon });
    page.tabs.push(k);
  }
}
for (const mode of ['member', 'admin']) for (const g of NAV[mode]) for (const p of g.pages) addPage(p, mode, g.key);
for (const p of EXTRA) addPage(p, p.mode, null);

const UNIT_OPT = { k: 'unit', label: 'Einheit', def: 'ctx', values: [['ctx', 'Aktiver Kontext'], ['alpha', 'Staffel Alpha'], ['bravo', 'SK Bravo']] };
const rowsOpt = (d = 5) => ({ k: 'rows', label: 'Zeilen', def: d, values: [[3, '3'], [5, '5'], [10, '10']] });
const rangeOpt = (values, d) => ({ k: 'range', label: 'Zeitraum', def: d, values });
const statusOpt = (values, d) => ({ k: 'status', label: 'Status', def: d, values });
const WIDGETS = {
  next7: { label: 'Nächste Tage', mode: 'member', col: 'wide', link: 'missions.list', desc: 'Einsätze und Operationen im gewählten Zeitraum', opts: [UNIT_OPT, rangeOpt([[3, '3 Tage'], [7, '7 Tage'], [14, '14 Tage']], 7), rowsOpt(5)] },
  nextMission: { label: 'Nächster Einsatz', mode: 'member', col: 'narrow', link: 'missions.list', desc: 'Der nächste Einsatz, für den du angemeldet bist', opts: [UNIT_OPT] },
  hangarReady: { label: 'Hangar-Bereitschaft', mode: 'member', col: 'narrow', link: 'hangar.own', desc: 'Einsatzbereite Schiffe der Einheit', opts: [UNIT_OPT] },
  ordersTodo: { label: 'Aufträge zu bearbeiten', mode: 'member', col: 'wide', link: 'orders.list', desc: 'Aufträge, die deine Einheit bearbeitet', opts: [UNIT_OPT, statusOpt([['both', 'Offen + in Bearbeitung'], ['open', 'Offen'], ['progress', 'In Bearbeitung']], 'both'), rowsOpt(5)] },
  myOrders: { label: 'Meine Aufträge', mode: 'member', col: 'wide', link: 'orders.list', desc: 'Aufträge, die du angelegt hast', opts: [statusOpt([['active', 'Laufend'], ['done', 'Abgeschlossen'], ['all', 'Alle']], 'active'), rowsOpt(5)] },
  assigned: { label: 'Ich bearbeite', mode: 'member', col: 'wide', link: 'orders.list', desc: 'Aufträge, bei denen du Bearbeiter bist', opts: [statusOpt([['both', 'Offen + in Bearbeitung'], ['open', 'Offen'], ['progress', 'In Bearbeitung']], 'both'), rowsOpt(5)], fresh: true },
  demand: { label: 'Materialbedarf', mode: 'member', col: 'wide', link: 'orders.demand', desc: 'Offener Materialbedarf aus Aufträgen', opts: [UNIT_OPT, rowsOpt(5)] },
  refineryDone: { label: 'Raffinerie: fertig', mode: 'member', col: 'narrow', link: 'refinery', desc: 'Deine abholbereiten Raffinerieaufträge', opts: [rowsOpt(5)] },
  lowStock: { label: 'Bestand niedrig', mode: 'member', col: 'wide', link: 'storage.minimum', desc: 'Mindestbestände, die unterschritten sind', opts: [UNIT_OPT, rowsOpt(5)], fresh: true },
  exchange: { label: 'Materialbörse', mode: 'member', col: 'wide', link: 'exchange', desc: 'Neue Angebote und Gesuche', opts: [statusOpt([['both', 'Angebote + Gesuche'], ['offers', 'Angebote'], ['requests', 'Gesuche']], 'both'), rowsOpt(5)] },
  supply: { label: 'Bedarf & Spenden', mode: 'member', col: 'wide', link: 'procurement.demand', desc: 'Offener Bedarf deiner Einheit', opts: [UNIT_OPT, statusOpt([['all', 'Alle offenen'], ['open', 'Ungedeckt'], ['partial', 'Teilweise gedeckt']], 'all'), rowsOpt(5)] },
  bankRequests: { label: 'Meine Bankanträge', mode: 'member', col: 'narrow', link: 'org.bank', desc: 'Stand deiner Anträge an die Bank', opts: [statusOpt([['open', 'Offen'], ['decided', 'Entschieden'], ['all', 'Alle']], 'open'), rowsOpt(3)] },
  bank: { label: 'Bank', mode: 'member', col: 'narrow', link: 'org.bank', desc: 'Kontostände, die du in der Staffel-Bank siehst', opts: [rowsOpt(3)], fresh: true },
  info: { label: 'Information', mode: 'member', col: 'wide', link: null, desc: 'Ankündigung der Administration', opts: [] },
  notifications: { label: 'Benachrichtigungen', mode: 'member', col: 'narrow', link: 'notifications', desc: 'Deine letzten Benachrichtigungen', opts: [statusOpt([['unread', 'Ungelesen'], ['all', 'Alle']], 'unread'), rowsOpt(5)] },
  favourites: { label: 'Favoriten', mode: 'member', col: 'narrow', link: null, desc: 'Deine Favoriten als Kacheln', opts: [] },
  hub: { label: 'Hub', mode: 'admin', col: 'wide', link: null, desc: 'Alle 13 Admin-Seiten in 3 Gruppen', opts: [] },
  erasure: { label: 'Offene Löschanträge', mode: 'admin', col: 'narrow', link: 'admin.privacy.erasure', desc: 'Löschanträge, die auf Bearbeitung warten', opts: [rowsOpt(5)] },
  discord: { label: 'Discord-Registrierungen', mode: 'admin', col: 'narrow', link: 'admin.members.discord', desc: 'Neue Registrierungen zur Freigabe', opts: [rowsOpt(5)] },
  sync: { label: 'Sync- & Importstatus', mode: 'admin', col: 'narrow', link: 'admin.imports.sync', desc: 'Letzte Läufe von UEX, SC Wiki und P4K', opts: [rangeOpt([[1, '24 Stunden'], [7, '7 Tage'], [30, '30 Tage']], 7)] },
  terms: { label: 'Offene Nutzungsbedingungen', mode: 'admin', col: 'narrow', link: 'admin.members.terms', desc: 'Mitglieder ohne Zustimmung zur aktuellen Fassung', opts: [rowsOpt(5)] },
};

const SAMPLE = {
  missions: [
    { name: 'Nordlicht – Quantanium-Bergbau', day: 0, time: 'Heute 20:00', unit: 'alpha', kind: 'Einsatz' },
    { name: 'Konvoi nach Pyro', day: 2, time: 'Do 19:30', unit: 'bravo', kind: 'Einsatz' },
    { name: 'Operation Sturmflut', day: 3, time: 'Fr 20:00', unit: 'alpha', kind: 'Operation' },
    { name: 'Salvage am Yela-Gürtel', day: 4, time: 'Sa 18:00', unit: 'alpha', kind: 'Einsatz' },
    { name: 'Raffinerie-Lauf ArcCorp', day: 6, time: 'Mo 21:00', unit: 'bravo', kind: 'Einsatz' },
    { name: 'Operation Eisbrecher', day: 11, time: '01.11. 19:00', unit: 'bravo', kind: 'Operation' },
  ],
  orders: [
    { id: 'A-1042', title: 'Quantanium 120 SCU', status: 'progress', prio: 'Hoch', unit: 'alpha', mine: false, assigned: true },
    { id: 'A-1043', title: 'Medpens ×200', status: 'open', prio: 'Mittel', unit: 'alpha', mine: true, assigned: false },
    { id: 'A-1107', title: 'Laranite 48 SCU (≥ 700)', status: 'open', prio: 'Hoch', unit: 'bravo', mine: false, assigned: true },
    { id: 'A-1110', title: 'Hadanite 12 SCU', status: 'progress', prio: 'Niedrig', unit: 'bravo', mine: false, assigned: false },
    { id: 'A-1121', title: 'Agricium 64 SCU', status: 'open', prio: 'Mittel', unit: 'alpha', mine: false, assigned: true },
    { id: 'A-0998', title: 'Titan 300 SCU', status: 'done', prio: 'Mittel', unit: 'alpha', mine: true, assigned: false },
  ],
  lowStock: [
    { mat: 'Laranite', tier: '—', free: 0, min: 20, target: 30, unit: 'bravo', req: 48 },
    { mat: 'Quantanium', tier: '≥ 700', free: 12, min: 40, target: 60, unit: 'alpha', req: 120 },
    { mat: 'Medpen (Gegenstand)', tier: '—', free: 8, min: 25, target: 40, unit: 'alpha', req: 0 },
    { mat: 'Agricium', tier: '≥ 500', free: 30, min: 50, target: 80, unit: 'alpha', req: 64 },
  ],
  bank: [
    { acct: 'KRT · Kartellkonto', bal: '48.250.000', target: 0.72, unit: null, series: [30, 32, 31, 35, 38, 37, 41, 44, 43, 48] },
    { acct: 'Staffel Alpha', bal: '3.120.000', target: 0.4, unit: 'alpha', series: [40, 38, 36, 37, 33, 31, 30, 32, 31, 31] },
    { acct: 'SK Bravo', bal: '940.500', target: null, unit: 'bravo', series: [10, 12, 11, 9, 9, 10, 12, 13, 12, 14] },
  ],
  notifications: [
    { t: 'Neuer Bearbeiter in A-1042', at: 'vor 5 Min.', unread: true },
    { t: 'Bestand niedrig: Laranite (SK Bravo)', at: 'vor 40 Min.', unread: true },
    { t: 'Einsatz „Nordlicht" beginnt heute 20:00', at: 'vor 2 Std.', unread: true },
    { t: 'Bankantrag genehmigt', at: 'gestern', unread: false },
    { t: 'Neue Bewertung freigegeben', at: 'vor 3 Tagen', unread: false },
  ],
  entities: {
    mission: ['Nordlicht – Quantanium-Bergbau', 'Konvoi nach Pyro', 'Salvage am Yela-Gürtel', 'Raffinerie-Lauf ArcCorp'],
    operation: ['Operation Sturmflut', 'Operation Eisbrecher'],
    material: ['Quantanium', 'Laranite', 'Agricium', 'Hadanite', 'Titan'],
    blueprint: ['Arclight-Pistole', 'P4-AR Gewehr', 'Pembroke-Rüstung'],
    ship: ['Cutlass Black', 'Prospector', 'MOLE', 'C2 Hercules', 'Carrack'],
    unit: ['Staffel Alpha', 'SK Bravo'],
    order: ['A-1042', 'A-1043', 'A-1107', 'A-1110', 'A-1121'],
    member: ['Nightjar', 'Vex', 'Corsair_7', 'Halcyon', 'Mira_K'],
  },
};

const KINDS = {
  page: { label: 'Seite', pl: 'Seiten', sym: '/', words: ['seite', 'page'] },
  action: { label: 'Aktion', pl: 'Aktionen', sym: '>', words: ['aktion', 'action'] },
  mission: { label: 'Mission', pl: 'Missionen', words: ['mission'] },
  operation: { label: 'Operation', pl: 'Operationen', words: ['operation'] },
  material: { label: 'Material', pl: 'Materialien', words: ['material'] },
  blueprint: { label: 'Blueprint', pl: 'Blueprints', words: ['blueprint'] },
  ship: { label: 'Schiff', pl: 'Schiffe', words: ['schiff', 'ship'] },
  unit: { label: 'Einheit', pl: 'Einheiten', words: ['einheit', 'unit'] },
  order: { label: 'Auftrag', pl: 'Aufträge', sym: '#', words: ['auftrag', 'order'] },
  member: { label: 'Mitglied', pl: 'Mitglieder', sym: '@', words: ['mitglied', 'member'] },
};
const ENTITY_KINDS = ['mission', 'operation', 'material', 'blueprint', 'ship', 'unit', 'order', 'member'];

let instSeq = 1;
function inst(type, col) {
  const opts = {};
  for (const o of WIDGETS[type].opts) opts[o.k] = o.def;
  return { id: 'w' + instSeq++, type, col: col || WIDGETS[type].col, opts, v: 0 };
}
function freshPrefs() {
  return {
    favourites: [], hidden: [], order: { member: null, admin: null }, tabbar: { member: null, admin: null },
    display: { desktopMode: 'drawer', shortcuts: true, startPage: { member: null, admin: null } },
    v: { favourites: 0, hidden: 0, 'order.member': 0, 'order.admin': 0, 'tabbar.member': 0, 'tabbar.admin': 0, display: 0 },
  };
}
function freshDash(mode) {
  if (mode === 'admin') return { v: 0, touched: false, items: [inst('hub', 'wide'), inst('erasure', 'narrow'), inst('discord', 'narrow'), inst('sync', 'narrow'), inst('terms', 'narrow')] };
  return { v: 0, touched: false, items: [inst('next7', 'wide'), inst('info', 'wide'), inst('favourites', 'narrow'), inst('notifications', 'narrow')] };
}
function freshLocal() { return { collapsed: {}, recent: [], frequent: {}, hintDismissed: false }; }
function freshUi() {
  return { route: 'home', drawer: false, editing: false, flyout: null, flyoutPinned: false, menu: false, palette: null, modal: null, dashEdit: false, statePanel: false, barMin: false, filter: null, highlight: null, segment: 'mine' };
}

const params = new URLSearchParams(location.search);
const S = {
  persona: PERSONAS[params.get('persona')] ? params.get('persona') : 'member',
  device: params.get('device') === 'phone' ? 'phone' : 'desktop',
  ctx: 'alpha', mode: 'member',
  prefs: freshPrefs(), dash: { member: freshDash('member'), admin: freshDash('admin') }, local: freshLocal(), ui: freshUi(),
  log: [], toasts: [], lp: false, lpUntil: 0,
};

const device = document.getElementById('device');
const stage = document.getElementById('stage');
const bar = document.getElementById('proto-bar');
const statePanel = document.getElementById('proto-state');
const live = document.getElementById('sr-live');

const persona = () => PERSONAS[S.persona];
const ctxUnits = () => (S.ctx === 'all' ? ['alpha', 'bravo'] : [S.ctx]);
const ctxName = () => CONTEXTS[S.ctx];
const width = () => (S.device === 'phone' ? 390 : window.innerWidth);
const isPhoneW = () => width() < 1025;
function navMode() {
  const w = width();
  const m = S.prefs.display.desktopMode;
  if (w < 1025) return 'drawer';
  if (m === 'pinned' && w < 1280) return 'rail';
  return m;
}
const homeKey = (mode = S.mode) => (mode === 'admin' ? 'home.admin' : 'home');

function gateStatus(g) {
  if (!g) return 'ok';
  const p = persona();
  const units = ctxUnits();
  if (g === 'bank') return p.bank ? 'ok' : 'role';
  if (g === 'admin') return p.admin ? 'ok' : 'role';
  if (g === 'officer') {
    if (p.admin) return 'ok';
    if (!p.officerIn.length) return 'role';
    return p.officerIn.some((u) => units.includes(u)) ? 'ok' : 'context';
  }
  if (g === 'promotion') return units.some((u) => UNITS[u].promotion) ? 'ok' : 'context';
  return 'ok';
}
const worst = (a, b) => (a === 'role' || b === 'role' ? 'role' : a === 'context' || b === 'context' ? 'context' : 'ok');
function status(key) {
  const e = REG.get(key);
  if (!e) return 'role';
  let s = gateStatus(e.gate);
  if (e.mode === 'admin') s = worst(s, gateStatus('admin'));
  if (e.kind === 'tab') s = worst(s, status(e.page));
  if (e.kind === 'page' && e.tabs.length) {
    const ts = e.tabs.map((t) => worst(gateStatus(REG.get(t).gate), 'ok'));
    if (!ts.includes('ok')) s = worst(s, ts.includes('context') ? 'context' : 'role');
  }
  return s;
}
function firstOkTab(pageKey) {
  const p = REG.get(pageKey);
  return p.tabs.find((t) => status(t) === 'ok') || null;
}
function entryLabel(key) {
  const e = REG.get(key);
  if (!e) return key;
  if (e.kind === 'tab') {
    const p = REG.get(e.page);
    return e.label === p.label ? p.label : `${e.label} · ${p.label}`;
  }
  return e.label;
}
const isFav = (k) => S.prefs.favourites.includes(k);
const isHidden = (k) => S.prefs.hidden.includes(k);
const favFull = () => S.prefs.favourites.length >= FAV_CAP;
const favList = (mode) => S.prefs.favourites.filter((k) => REG.get(k) && REG.get(k).mode === mode && status(k) !== 'role');
const hiddenList = (mode) => S.prefs.hidden.filter((k) => REG.get(k) && REG.get(k).mode === mode && status(k) !== 'role');
const shortcutsActive = () => S.mode === 'member' && S.prefs.display.shortcuts && !isPhoneW();
function isRouteOf(key) {
  const r = S.ui.route;
  if (r === key) return true;
  const re = REG.get(r);
  const ke = REG.get(key);
  if (!re || !ke) return false;
  if (ke.kind === 'page' && re.kind === 'tab' && re.page === key) return true;
  if (ke.kind === 'tab' && re.kind === 'page' && ke.page === r && firstOkTab(r) === key) return true;
  return false;
}

function mergeOrder(def, stored) {
  if (!stored) return def.slice();
  const out = stored.filter((k) => def.includes(k));
  def.forEach((k, i) => {
    if (out.includes(k)) return;
    const pred = def.slice(0, i).reverse().find((p) => out.includes(p));
    out.splice(pred ? out.indexOf(pred) + 1 : 0, 0, k);
  });
  return out;
}
function groupsOf(mode) { return GROUPS[mode].filter((g) => gateStatus(g.gate) !== 'role'); }
function orderedGroups(mode) {
  const gs = groupsOf(mode);
  const o = mergeOrder(gs.map((g) => g.key), S.prefs.order[mode] && S.prefs.order[mode].groups);
  return o.map((k) => gs.find((g) => g.key === k));
}
function orderedPages(mode, g) {
  const def = g.pages.map((p) => p.key);
  const st = S.prefs.order[mode] && S.prefs.order[mode].links[g.key];
  return mergeOrder(def, st);
}
const visibleLinks = (mode, g) => orderedPages(mode, g).filter((k) => status(k) === 'ok' && !isHidden(k));

function logWrite(sections, path) {
  const parts = sections.map((sec) => {
    const old = S.prefs.v[sec] ?? 0;
    S.prefs.v[sec] = old + 1;
    return `${sec} v${old}→v${old + 1}`;
  });
  S.log.unshift(`PUT ${path} · ${parts.join(', ')}`);
  S.log = S.log.slice(0, 14);
}
function logDash(path, from, to) {
  S.log.unshift(`PUT ${path} · v${from}→v${to}`);
  S.log = S.log.slice(0, 14);
}
function announce(msg) { live.textContent = ''; setTimeout(() => { live.textContent = msg; }, 30); }
function toast(text) {
  const id = Math.random().toString(36).slice(2);
  S.toasts.push({ id, text });
  setTimeout(() => { S.toasts = S.toasts.filter((t) => t.id !== id); render(); }, 3000);
}

function toggleFav(key) {
  const e = REG.get(key);
  if (!e) return;
  if (isFav(key)) {
    S.prefs.favourites = S.prefs.favourites.filter((k) => k !== key);
    logWrite(['favourites'], '/api/v1/navigation/favourites');
    toast(`„${entryLabel(key)}" aus den Favoriten entfernt.`);
    return;
  }
  if (favFull()) { toast(`Höchstens ${FAV_CAP} Favoriten. Entferne zuerst einen.`); return; }
  S.prefs.favourites.push(key);
  if (isHidden(key)) {
    S.prefs.hidden = S.prefs.hidden.filter((k) => k !== key);
    logWrite(['favourites', 'hidden'], '/api/v1/navigation/favourites (+hidden)');
    toast(`„${entryLabel(key)}" ist Favorit und wieder eingeblendet.`);
  } else {
    logWrite(['favourites'], '/api/v1/navigation/favourites');
    toast(`„${entryLabel(key)}" ist jetzt Favorit.`);
  }
}
function hidePage(key) {
  const e = REG.get(key);
  if (!e || e.kind !== 'page' || isHidden(key)) return;
  S.prefs.hidden.push(key);
  if (isFav(key)) {
    S.prefs.favourites = S.prefs.favourites.filter((k) => k !== key);
    logWrite(['hidden', 'favourites'], '/api/v1/navigation/hidden (+favourites)');
  } else logWrite(['hidden'], '/api/v1/navigation/hidden');
  toast(`„${e.label}" liegt jetzt unter „Mehr" und fehlt in der Schnellsuche.`);
}
function unhide(key) {
  S.prefs.hidden = S.prefs.hidden.filter((k) => k !== key);
  logWrite(['hidden'], '/api/v1/navigation/hidden');
}
function ensureOrder(mode) {
  if (!S.prefs.order[mode]) S.prefs.order[mode] = { groups: null, links: {} };
  return S.prefs.order[mode];
}
function move(scope, key, d) {
  const mode = S.mode;
  let list;
  let label;
  if (scope === 'fav') {
    const visible = favList(mode);
    const i = visible.indexOf(key);
    const j = i + d;
    if (i < 0 || j < 0 || j >= visible.length) return;
    const other = visible[j];
    const all = S.prefs.favourites;
    const a = all.indexOf(key);
    const b = all.indexOf(other);
    [all[a], all[b]] = [all[b], all[a]];
    logWrite(['favourites'], '/api/v1/navigation/favourites');
    announce(`${entryLabel(key)}: Position ${j + 1} von ${visible.length}`);
    return;
  }
  const o = ensureOrder(mode);
  if (scope === 'group') {
    list = orderedGroups(mode).map((g) => g.key);
    label = GROUPS[mode].find((g) => g.key === key).label;
  } else {
    const gk = scope.slice(5);
    const g = GROUPS[mode].find((x) => x.key === gk);
    list = orderedPages(mode, g);
    label = REG.get(key).label;
  }
  const i = list.indexOf(key);
  const j = i + d;
  if (i < 0 || j < 0 || j >= list.length) return;
  [list[i], list[j]] = [list[j], list[i]];
  if (scope === 'group') o.groups = list;
  else o.links[scope.slice(5)] = list;
  logWrite([`order.${mode}`], `/api/v1/navigation/order/${mode}`);
  announce(`${label}: Position ${j + 1} von ${list.length}`);
}

function tabSlots(mode) {
  const favs = favList(mode).filter((k) => status(k) === 'ok');
  const defs = TAB_DEFAULTS[mode].filter((k) => status(k) === 'ok');
  const stored = S.prefs.tabbar[mode];
  const out = [null, null, null];
  const used = new Set();
  if (stored) stored.forEach((k, i) => { if (k && status(k) === 'ok' && !used.has(k)) { out[i] = { key: k, standIn: false }; used.add(k); } });
  const pool = [...favs, ...defs];
  for (let i = 0; i < 3; i++) {
    if (out[i]) continue;
    const k = pool.find((x) => !used.has(x));
    if (k) { out[i] = { key: k, standIn: !!(stored && stored[i]) }; used.add(k); }
  }
  return out;
}
function freezeTabbar(mode) {
  if (!S.prefs.tabbar[mode]) S.prefs.tabbar[mode] = tabSlots(mode).map((s) => (s ? s.key : null));
  return S.prefs.tabbar[mode];
}
function setSlot(mode, i, key) {
  const st = freezeTabbar(mode);
  const j = st.indexOf(key);
  if (j >= 0 && j !== i) [st[i], st[j]] = [st[j], st[i]];
  else st[i] = key;
  logWrite([`tabbar.${mode}`], `/api/v1/navigation/tabbar/${mode}`);
}
function swapSlots(mode, i, j) {
  const st = freezeTabbar(mode);
  [st[i], st[j]] = [st[j], st[i]];
  logWrite([`tabbar.${mode}`], `/api/v1/navigation/tabbar/${mode}`);
}

function prune() {
  const gone = [];
  const keep = (k) => { const ok = status(k) !== 'role'; if (!ok) gone.push(k); return ok; };
  const f = S.prefs.favourites.filter(keep);
  const h = S.prefs.hidden.filter(keep);
  if (f.length !== S.prefs.favourites.length) { S.prefs.favourites = f; logWrite(['favourites'], '/api/v1/navigation/favourites (Bereinigung)'); }
  if (h.length !== S.prefs.hidden.length) { S.prefs.hidden = h; logWrite(['hidden'], '/api/v1/navigation/hidden (Bereinigung)'); }
  for (const m of ['member', 'admin']) {
    const t = S.prefs.tabbar[m];
    if (t && t.some((k) => k && status(k) === 'role')) { S.prefs.tabbar[m] = t.map((k) => (k && status(k) === 'role' ? null : k)); logWrite([`tabbar.${m}`], `/api/v1/navigation/tabbar/${m} (Bereinigung)`); }
    const sp = S.prefs.display.startPage[m];
    if (sp && status(sp) === 'role') { S.prefs.display.startPage[m] = null; logWrite(['display'], '/api/v1/navigation/display (Bereinigung)'); }
  }
  if (!persona().admin) {
    S.mode = 'member';
  }
  if (gone.length) toast(`Beim Laden entfernt (Berechtigung fehlt): ${gone.map(entryLabel).join(', ')}`);
}

function navigate(key, opts = {}) {
  S.ui.route = key;
  S.ui.menu = false;
  S.ui.flyout = null;
  S.ui.flyoutPinned = false;
  if (!S.ui.editing) S.ui.drawer = false;
  S.ui.filter = opts.filter || null;
  S.ui.highlight = opts.highlight || null;
  if (opts.segment) S.ui.segment = opts.segment;
  if (REG.get(key) && !key.startsWith('home')) {
    S.local.frequent[key] = (S.local.frequent[key] || 0) + 1;
    S.local.recent = [key, ...S.local.recent.filter((k) => k !== key)].slice(0, 4);
  }
  render();
  const sc = device.querySelector('.scroller');
  if (sc) sc.scrollTop = 0;
}
function setMode(mode) {
  S.mode = mode;
  S.ui.editing = false;
  S.ui.dashEdit = false;
  const sp = S.prefs.display.startPage[mode];
  const target = sp && status(sp) === 'ok' ? sp : homeKey(mode);
  toast(mode === 'admin' ? 'Admin-Modus aktiv.' : 'Admin-Modus verlassen.');
  navigate(target);
}
function openEdit() {
  S.ui.editing = true;
  S.ui.menu = false;
  S.ui.flyout = null;
  if (navMode() === 'drawer') S.ui.drawer = true;
  render();
}

function render() {
  const fid = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.fid : null;
  const palInput = device.querySelector('.pal-input input');
  const caret = palInput && document.activeElement === palInput ? palInput.selectionStart : null;
  stage.classList.toggle('is-phone', S.device === 'phone');
  device.classList.toggle('is-admin', S.mode === 'admin');
  device.classList.toggle('is-phone-w', isPhoneW());
  const nm = navMode();
  const overlayNav = (nm === 'drawer' && S.ui.drawer) || (nm === 'rail' && S.ui.editing);
  device.innerHTML = `
    ${headerHtml(nm)}
    <div class="body-row">
      ${nm === 'rail' ? railHtml() : ''}
      ${nm === 'pinned' ? `<aside class="pinned ${S.ui.editing ? 'is-editing' : ''}" aria-label="Navigation">${navPanelHtml(false)}</aside>` : ''}
      <div class="scroller"><main class="main">${pageHtml()}</main>${footHtml()}</div>
    </div>
    ${isPhoneW() ? tabbarHtml() : ''}
    ${overlayNav ? `<div class="scrim" data-a="close-nav"></div><aside class="drawer ${isPhoneW() ? 'is-full' : ''}" aria-label="Navigation">${navPanelHtml(true)}</aside>` : ''}
    ${S.ui.menu ? menuHtml() : ''}
    ${S.ui.palette ? paletteHtml() : ''}
    ${S.ui.modal ? `<div class="modal-layer">${modalHtml()}</div>` : ''}
    <div class="toast-vp">${S.toasts.map((t) => `<div class="notification-toast" role="status"><p>${esc(t.text)}</p></div>`).join('')}</div>`;
  if (S.ui.palette) {
    const inp = device.querySelector('.pal-input input');
    inp.focus();
    const pos = caret ?? inp.value.length;
    inp.setSelectionRange(pos, pos);
    const sel = device.querySelector('.pal-item[aria-selected="true"]');
    if (sel) sel.scrollIntoView({ block: 'nearest' });
  } else if (S.ui.modal && !fid) {
    const first = device.querySelector('.modal-layer [data-autofocus], .modal-layer button, .modal-layer select');
    if (first) first.focus();
  } else if (fid) {
    const el = device.querySelector(`[data-fid="${CSS.escape(fid)}"]`);
    if (el && !el.disabled) el.focus();
    else if (el) { const row = el.closest('[tabindex]'); if (row) row.focus(); }
  }
  renderBar();
  renderState();
}

function headerHtml(nm) {
  const phone = isPhoneW();
  const p = persona();
  const ctxOpts = Object.entries(CONTEXTS).map(([k, v]) => `<option value="${k}" ${S.ctx === k ? 'selected' : ''}>${esc(phone ? CONTEXT_SHORT[k] : v)}</option>`).join('');
  return `<header class="hdr">
    ${nm === 'drawer' && !phone ? `<button class="hdr-burger" data-a="drawer" data-fid="burger" aria-label="Menü öffnen" aria-expanded="${S.ui.drawer}"><span></span><span></span><span></span></button>` : ''}
    <a class="hdr-brand" href="#" data-a="go" data-k="${homeKey()}" title="Zum Dashboard"><img src="basetool-logo.svg" alt="">${phone ? '' : '<span>Profit Basetool</span>'}</a>
    ${phone ? '' : `<button class="hdr-bell" data-a="go" data-k="notifications" aria-label="Benachrichtigungen, 3 ungelesen" title="Benachrichtigungen">${icon('bell')}<span class="badge-dot">3</span></button>`}
    <span class="hdr-spacer"></span>
    ${S.mode === 'admin' ? '<span class="admin-chip">Admin</span>' : ''}
    ${phone ? `<button class="hdr-icon-btn" data-a="palette" aria-label="Suchen">${icon('search')}</button>` : `<button class="hdr-palette" data-a="palette" aria-haspopup="dialog">${icon('search')}<span>Seite, Aktion oder Eintrag suchen …</span><span class="kbd">STRG K</span></button>`}
    <select class="ctx-select" data-a="ctx" aria-label="Aktiver Kontext">${ctxOpts}</select>
    ${nm === 'rail' && !phone ? '' : `<button class="avatar-btn" data-a="menu" data-fid="avatar" aria-expanded="${S.ui.menu}" aria-label="Benutzermenü ${esc(p.handle)}">${esc(p.handle.slice(0, 2).toUpperCase())}</button>`}
  </header>`;
}

function modeSwitchHtml() {
  const m = S.prefs.display.desktopMode;
  const b = (k, l) => `<button type="button" data-a="dmode" data-v="${k}" data-fid="dmode-${k}" aria-pressed="${m === k}" title="${l}">${icon(k)}<span>${l}</span></button>`;
  return `<div class="mode-switch" role="group" aria-label="Darstellung der Navigation">${b('drawer', 'Schublade')}${b('rail', 'Leiste')}${b('pinned', 'Angeheftet')}</div>`;
}
function modeNote() {
  const stored = S.prefs.display.desktopMode;
  const eff = navMode();
  if (stored === eff || isPhoneW()) return '';
  if (stored === 'pinned') return '<span class="mode-note">„Angeheftet" wirkt ab 1280 px Fensterbreite; darunter erscheint die Leiste.</span>';
  return '';
}

function navPanelHtml(closable) {
  const mode = S.mode;
  const ed = S.ui.editing;
  const phone = isPhoneW();
  let h = `<div class="nav-head">
    ${ed ? '<button class="btn btn--cta btn-tiny" data-a="edit-done" data-fid="edit-done">Fertig</button>' : `<button class="btn btn-ghost btn-tiny" data-a="edit" data-fid="edit">${icon('edit')} Anpassen</button>`}
    ${phone ? '' : modeSwitchHtml()}
    <span class="grow"></span>
    ${closable && !ed ? '<button class="nav-close" data-a="close-nav" aria-label="Menü schließen">×</button>' : ''}
    ${phone ? '' : modeNote()}
  </div><div class="nav-scroll">`;
  if (ed) h += '<div class="edit-banner">Stern = Favorit · Auge = ausblenden · Pfeile oder Alt+↑/↓ = verschieben. Ein Eintrag wandert nie in eine andere Gruppe.</div>';
  const favs = favList(mode);
  if (favs.length) h += groupHtml({ key: 'g.fav', label: 'Favoriten', icon: 'star' }, favs, 'fav', 0, 1);
  else if (ed) h += `<div class="nav-group"><div class="nav-group-title">${icon('star')} Favoriten</div><p class="stub-note">Noch keine Favoriten. Setze einen mit dem Stern.</p></div>`;
  const gs = orderedGroups(mode);
  gs.forEach((g, gi) => {
    const links = visibleLinks(mode, g);
    if (!links.length && !ed) return;
    h += groupHtml(g, links, 'group', gi, gs.length);
  });
  const hid = hiddenList(mode);
  if (hid.length) {
    const col = S.local.collapsed['g.more'] ?? true;
    h += `<div class="more-area nav-group ${col && !ed ? 'is-collapsed' : ''}">
      <div class="nav-group-head"><button class="nav-group-title" data-a="collapse" data-k="g.more" data-fid="col-g.more" aria-expanded="${!(col && !ed)}">${icon('more')} Mehr (${hid.length}) ${icon('down', 'chev')}</button></div>
      ${col && !ed ? '' : `<ul class="nav-links">${hid.map((k) => `<li class="nav-row">${linkHtml(k, false)}<button class="mini" data-a="unhide" data-k="${k}" data-fid="unhide-${k}" aria-label="${esc(REG.get(k).label)} wieder einblenden" title="Wieder einblenden">${icon('eye')}</button></li>`).join('')}</ul>`}
      ${ed ? '<div class="reset-row" style="margin-top:.5rem"><button class="btn btn-ghost btn-tiny" data-a="reset-hidden">Alle wieder einblenden</button></div>' : ''}
    </div>`;
  }
  if (ed) h += editSectionsHtml();
  h += '</div>';
  const p = persona();
  h += `<div class="nav-foot">
    ${p.admin ? `<label class="krt-switch"><input type="checkbox" data-a="admin-toggle" ${S.mode === 'admin' ? 'checked' : ''}> Admin-Modus</label>` : '<span class="stub-note">Persönliches: im Benutzermenü</span>'}
    <span class="grow"></span>
  </div>`;
  return h;
}

function editSectionsHtml() {
  const mode = S.mode;
  const slots = tabSlots(mode);
  const auto = !S.prefs.tabbar[mode];
  const choices = favouritableKeys(mode);
  const favs = favList(mode).filter((k) => status(k) === 'ok');
  const sel = (i) => {
    const cur = slots[i] ? slots[i].key : '';
    const opt = (k) => `<option value="${k}" ${k === cur ? 'selected' : ''}>${esc(entryLabel(k))}</option>`;
    const rest = choices.filter((k) => !favs.includes(k));
    return `<select data-a="slot-set" data-i="${i}" data-fid="slot-${i}" aria-label="Platz ${i + 1}">${favs.length ? `<optgroup label="Favoriten">${favs.map(opt).join('')}</optgroup>` : ''}<optgroup label="Seiten und Tabs">${rest.map(opt).join('')}</optgroup></select>`;
  };
  let h = `<div class="edit-section"><h3>Tab-Leiste (Telefon) · ${auto ? 'automatisch belegt' : 'fest belegt'}</h3>`;
  for (let i = 0; i < 3; i++) {
    h += `<div class="slot-row"><span class="slot-n">${i + 1}</span>${sel(i)}
      <button class="mini" data-a="slot-swap" data-i="${i}" data-d="-1" data-fid="sw-${i}-l" ${i === 0 ? 'disabled' : ''} aria-label="Platz ${i + 1} mit links tauschen">${icon('left')}</button>
      <button class="mini" data-a="slot-swap" data-i="${i}" data-d="1" data-fid="sw-${i}-r" ${i === 2 ? 'disabled' : ''} aria-label="Platz ${i + 1} mit rechts tauschen">${icon('right')}</button></div>`;
  }
  h += `<div class="slot-fixed">4 Meldungen · 5 Menü (fest)</div>
    <p class="field-help">${auto ? 'Automatisch: Favoriten in ihrer Reihenfolge, dann Missionen → Lager → Aufträge. Die erste Änderung legt alle drei Plätze fest.' : 'Fest belegt: Favoriten ändern die Tab-Leiste nicht mehr.'}</p>
    <div class="reset-row"><button class="btn btn-ghost btn-tiny" data-a="reset-tabbar" ${auto ? 'disabled' : ''}>Tab-Leiste zurücksetzen</button></div></div>
    <div class="edit-section"><h3>Zurücksetzen</h3><div class="reset-row">
      <button class="btn btn-ghost btn-tiny" data-a="reset-favs" ${favList(mode).length ? '' : 'disabled'}>Alle Favoriten entfernen</button>
      <button class="btn btn-ghost btn-tiny" data-a="reset-order" ${S.prefs.order[mode] ? '' : 'disabled'}>Standardreihenfolge</button>
    </div><p class="field-help">Alles auf einmal: Profil → Darstellung → „Alles zurücksetzen".</p></div>`;
  return h;
}

function linkHtml(key, inFav) {
  const e = REG.get(key);
  const st = status(key);
  const label = inFav ? entryLabel(key) : e.label;
  const n = inFav ? favList('member').indexOf(key) : -1;
  const kb = inFav && S.mode === 'member' && shortcutsActive() && n >= 0 && n < 9 && st === 'ok' ? `Alt+${n + 1}` : '';
  if (st !== 'ok') return `<span class="nav-link is-unavailable" aria-disabled="true">${icon(e.icon)}<span>${esc(label)}<span class="unavail-hint">In ${esc(ctxName())} nicht verfügbar</span></span></span>`;
  return `<a class="nav-link ${isRouteOf(key) ? 'is-active' : ''}" href="#" data-a="go" data-k="${key}" ${isRouteOf(key) ? 'aria-current="page"' : ''} ${kb ? `aria-keyshortcuts="${kb}" title="${kb}"` : ''}>${icon(e.icon)}<span>${esc(label)}</span>${kb ? `<span class="kbd-hint">${kb}</span>` : ''}</a>`;
}
function groupHtml(g, links, type, gi, glen) {
  const ed = S.ui.editing;
  const col = !ed && (S.local.collapsed[g.key] ?? S.mode === 'admin');
  const scope = type === 'fav' ? 'fav' : `link:${g.key}`;
  const rows = links.map((k, i) => {
    if (!ed) return `<li>${linkHtml(k, type === 'fav')}</li>`;
    const e = REG.get(k);
    const lbl = type === 'fav' ? entryLabel(k) : e.label;
    const f = isFav(k);
    return `<li class="nav-row" tabindex="0" data-move="${scope}" data-k="${k}" data-fid="row-${scope}-${k}" aria-label="${esc(lbl)}, Position ${i + 1} von ${links.length}">
      <button class="mini" data-a="mv" data-scope="${scope}" data-k="${k}" data-d="-1" data-fid="mv-${scope}-${k}--1" ${i === 0 ? 'disabled' : ''} aria-label="${esc(lbl)} nach oben">${icon('up')}</button>
      <button class="mini" data-a="mv" data-scope="${scope}" data-k="${k}" data-d="1" data-fid="mv-${scope}-${k}-1" ${i === links.length - 1 ? 'disabled' : ''} aria-label="${esc(lbl)} nach unten">${icon('down')}</button>
      ${linkHtml(k, type === 'fav')}
      <button class="mini ${f ? 'is-on' : ''}" data-a="fav" data-k="${k}" data-fid="fav-${scope}-${k}" aria-pressed="${f}" aria-label="${esc(lbl)} ${f ? 'aus Favoriten entfernen' : 'favorisieren'}" ${!f && favFull() ? `disabled title="Höchstens ${FAV_CAP} Favoriten"` : ''}>${icon('star', f ? 'is-filled' : '')}</button>
      ${type !== 'fav' ? `<button class="mini" data-a="hide" data-k="${k}" data-fid="hide-${k}" aria-label="${esc(lbl)} ausblenden" title="Ausblenden (nach „Mehr")">${icon('eyeoff')}</button>` : ''}
    </li>`;
  }).join('');
  const headCtl = ed && type === 'group'
    ? `<button class="mini" data-a="mv" data-scope="group" data-k="${g.key}" data-d="-1" data-fid="mvg-${g.key}--1" ${gi === 0 ? 'disabled' : ''} aria-label="Gruppe ${esc(g.label)} nach oben">${icon('up')}</button><button class="mini" data-a="mv" data-scope="group" data-k="${g.key}" data-d="1" data-fid="mvg-${g.key}-1" ${gi === glen - 1 ? 'disabled' : ''} aria-label="Gruppe ${esc(g.label)} nach unten">${icon('down')}</button>`
    : '';
  return `<div class="nav-group ${col ? 'is-collapsed' : ''}">
    <div class="nav-group-head" ${ed && type === 'group' ? `tabindex="0" data-move="group" data-k="${g.key}" data-fid="grow-${g.key}"` : ''}>${headCtl}
      <button class="nav-group-title" data-a="collapse" data-k="${g.key}" data-fid="col-${g.key}" aria-expanded="${!col}" ${ed ? 'disabled' : ''}>${icon(g.icon)} ${esc(g.label)} ${ed ? '' : icon('down', 'chev')}</button></div>
    ${col ? '' : links.length ? `<ul class="nav-links">${rows}</ul>` : '<p class="stub-note">Alle Einträge ausgeblendet – unter „Mehr".</p>'}
  </div>`;
}

function railHtml() {
  const mode = S.mode;
  const items = [];
  if (favList(mode).length) items.push({ key: 'g.fav', label: 'Favoriten', icon: 'star' });
  for (const g of orderedGroups(mode)) if (visibleLinks(mode, g).length) items.push(g);
  if (hiddenList(mode).length) items.push({ key: 'g.more', label: 'Mehr', icon: 'more' });
  const curGroup = REG.get(S.ui.route) ? REG.get(S.ui.route).group : null;
  const btn = (it) => `<button class="rail-btn ${curGroup === it.key ? 'is-current' : ''}" data-rail="${it.key}" data-a="rail" data-k="${it.key}" data-fid="rail-${it.key}" aria-expanded="${S.ui.flyout === it.key}" aria-label="${esc(it.label)}">${icon(it.icon, 'ico-lg')}</button>`;
  let h = '<nav class="rail" aria-label="Navigation">';
  items.forEach((it, i) => {
    if (it.key === 'g.more') h += '<span class="rail-sep"></span>';
    h += btn(it);
    if (S.ui.flyout === it.key) h += flyoutHtml(it, `top:${10 + i * 52 + (it.key === 'g.more' ? 12 : 0)}px`);
  });
  h += '<span class="rail-grow"></span>';
  if (persona().admin) h += `<button class="rail-btn ${S.mode === 'admin' ? 'is-current' : ''}" data-a="admin-rail" data-fid="rail-admin" aria-pressed="${S.mode === 'admin'}" aria-label="Admin-Modus ${S.mode === 'admin' ? 'verlassen' : 'aktivieren'}" title="Admin-Modus">${icon('shield', 'ico-lg')}</button>`;
  const av = { key: 'g.avatar', label: persona().handle, icon: 'user' };
  h += btn(av);
  if (S.ui.flyout === 'g.avatar') h += flyoutHtml(av, 'bottom:8px');
  h += '</nav>';
  return h;
}
function flyoutHtml(it, pos) {
  const mode = S.mode;
  let body = '';
  if (it.key === 'g.fav') body = `<ul class="nav-links">${favList(mode).map((k) => `<li>${linkHtml(k, true)}</li>`).join('')}</ul>`;
  else if (it.key === 'g.more') body = `<ul class="nav-links">${hiddenList(mode).map((k) => `<li class="nav-row">${linkHtml(k, false)}<button class="mini" data-a="unhide" data-k="${k}" aria-label="${esc(REG.get(k).label)} wieder einblenden">${icon('eye')}</button></li>`).join('')}</ul>`;
  else if (it.key === 'g.avatar') body = `${menuItemsHtml()}<hr style="border:0;border-top:1px solid var(--color-gray-3)"><div style="padding:.4rem 0">${modeSwitchHtml()}</div>${modeNote()}`;
  else body = `<ul class="nav-links">${visibleLinks(mode, it).map((k) => `<li>${linkHtml(k, false)}</li>`).join('')}</ul>`;
  return `<div class="flyout" data-flyout="${it.key}" style="${pos}"><div class="flyout-head">${esc(it.label)}</div>${body}</div>`;
}

function menuItemsHtml() {
  const p = persona();
  return `<div class="menu-items">
    <div class="who"><b>${esc(p.handle)}</b>${esc(p.label)}</div>
    <a href="#" data-a="go" data-k="profile">${icon('user')} Profil</a>
    <a href="#" data-a="go" data-k="inventory">${icon('box')} Mein Inventar</a>
    <a href="#" data-a="go" data-k="apps">${icon('link')} Verbundene Anwendungen</a>
    <a href="#" data-a="go" data-k="profile" data-anchor="display">${icon('drawer')} Darstellung</a>
    <button data-a="edit">${icon('edit')} Navigation anpassen</button>
    <button data-a="kbd">${icon('key')} Tastenkürzel</button>
    <button data-a="logout">${icon('logout')} Abmelden</button>
  </div>`;
}
function menuHtml() { return `<div class="menu-pop" role="menu">${menuItemsHtml()}</div>`; }

function tabbarHtml() {
  const slots = tabSlots(S.mode);
  const cell = (s, i) => {
    if (!s) return '<span></span>';
    const e = REG.get(s.key);
    const lbl = e.kind === 'tab' ? e.label : e.label;
    return `<button class="tabbar-btn ${isRouteOf(s.key) ? 'is-active' : ''} ${s.standIn ? 'is-standin' : ''}" data-a="tab" data-slot="${i}" data-k="${s.key}" aria-label="${esc(entryLabel(s.key))}${s.standIn ? ' (Ersatz)' : ''}" title="Lange drücken zum Ändern">${icon(e.icon, 'ico-lg')}<span class="lbl">${esc(lbl)}</span></button>`;
  };
  return `<nav class="tabbar" aria-label="Tab-Leiste">${slots.map(cell).join('')}
    <button class="tabbar-btn ${S.ui.route === 'notifications' ? 'is-active' : ''}" data-a="go" data-k="notifications" aria-label="Meldungen, 3 ungelesen">${icon('bell', 'ico-lg')}<span class="lbl">Meldungen</span><span class="badge-dot">3</span></button>
    <button class="tabbar-btn ${S.ui.drawer ? 'is-active' : ''}" data-a="drawer" aria-expanded="${S.ui.drawer}">${icon('menu', 'ico-lg')}<span class="lbl">Menü</span></button>
  </nav>`;
}

function footHtml() {
  return '<footer class="foot"><a href="#" data-a="stub" data-t="Handbuch">Handbuch</a><a href="#" data-a="stub" data-t="Star-Citizen-Links">Star-Citizen-Links</a><a href="#" data-a="stub" data-t="Impressum">Impressum</a><a href="#" data-a="stub" data-t="Datenschutz">Datenschutz</a><span style="margin-left:auto">PROTOTYP · v0.0</span></footer>';
}

function starBtn(key, cls = 'star-btn') {
  const f = isFav(key);
  const dis = !f && favFull();
  return `<button class="${cls} ${f ? 'is-on' : ''}" data-a="fav" data-k="${key}" data-fid="hstar-${key}" aria-pressed="${f}" aria-label="${esc(entryLabel(key))} ${f ? 'aus Favoriten entfernen' : 'favorisieren'}" title="${dis ? `Höchstens ${FAV_CAP} Favoriten` : f ? 'Favorit entfernen' : 'Als Favorit merken'}" ${dis ? 'disabled' : ''}>${icon('star', f ? 'is-filled' : '')}</button>`;
}
function startBtn(key) {
  const on = S.prefs.display.startPage[S.mode] === key;
  return `<button class="btn btn-ghost btn-tiny" data-a="startpage" data-k="${key}" data-fid="sp-${key}" aria-pressed="${on}">${on ? `${icon('check')} Startseite` : 'Als Startseite festlegen'}</button>`;
}

function pageHtml() {
  const r = S.ui.route;
  if (r === 'proto.notes') return notesHtml();
  if (r.startsWith('ent:')) return entityHtml(r);
  if (r === 'home' || r === 'home.admin') return dashboardHtml();
  const e = REG.get(r);
  if (!e) return '<h1>Signal Lost</h1>';
  const st = status(r);
  if (st === 'role') return '<div class="empty-state"><h2>Access Denied</h2><p>Insufficient security clearance. This incident has been logged.</p></div>';
  const pageKey = e.kind === 'tab' ? e.page : r;
  const page = REG.get(pageKey);
  if (st === 'context') return `<div class="empty-state"><h2>${esc(page.label)}</h2><p>In ${esc(ctxName())} nicht verfügbar. Wechsle den Kontext oben rechts.</p></div>`;
  const tabKey = e.kind === 'tab' ? r : firstOkTab(pageKey);
  const grp = page.group ? GROUPS[page.mode].find((g) => g.key === page.group).label : 'Persönlich';
  const tabs = page.tabs.filter((t) => status(t) === 'ok');
  const startKey = tabKey || pageKey;
  let h = `<div class="page-head"><div><div class="page-head__eyebrow">${esc(grp)}</div><h1 class="page-head__title">${esc(page.label)} ${starBtn(pageKey)}</h1></div>
    <div class="page-head__actions">${startBtn(startKey)}</div></div>`;
  if (tabs.length > 1) {
    h += `<div class="tab-nav" role="tablist">${tabs.map((t) => `<a class="tab ${t === tabKey ? 'active' : ''}" role="tab" aria-selected="${t === tabKey}" href="#" data-a="go" data-k="${t}">${esc(REG.get(t).label)}</a>${t === tabKey && REG.get(t).label !== page.label ? starBtn(t, 'tab-star') : ''}`).join('')}</div>`;
  }
  if (pageKey === 'profile') return h + profileHtml();
  h += `<div class="stub">${pageBody(tabKey || pageKey)}</div>`;
  return h;
}

function pageBody(k) {
  if (k === 'orders.list') {
    const segs = [['mine', 'Meine'], ['assigned', 'Ich bearbeite'], ['todo', 'Zu bearbeiten'], ['all', 'Alle']];
    const rows = SAMPLE.orders.filter((o) => (S.ui.segment === 'mine' ? o.mine : S.ui.segment === 'assigned' ? o.assigned && o.status !== 'done' : S.ui.segment === 'todo' ? o.status !== 'done' && ctxUnits().includes(o.unit) : true));
    return `<div class="seg-row krt-segmented" role="group" aria-label="Ansicht">${segs.map(([k2, l]) => `<button class="krt-segmented__item" data-a="seg" data-v="${k2}" aria-pressed="${S.ui.segment === k2}">${l}</button>`).join('')}</div>
      <table class="tbl"><thead><tr><th>Nr.</th><th>Auftrag</th><th>Status</th><th>Priorität</th><th>Einheit</th></tr></thead><tbody>${rows.map((o) => `<tr><td>${o.id}</td><td>${esc(o.title)}</td><td>${statusLabel(o.status)}</td><td>${o.prio}</td><td>${UNITS[o.unit].name}</td></tr>`).join('')}</tbody></table>`;
  }
  if (k === 'storage.minimum') {
    const rows = SAMPLE.lowStock.filter((r) => ctxUnits().includes(r.unit));
    return `<table class="tbl"><thead><tr><th>Material / Gegenstand</th><th>Stufe</th><th class="num">Min.</th><th class="num">Ziel</th><th class="num">Frei</th><th class="num">Angefragt</th><th>Füllung</th><th></th></tr></thead><tbody>${rows.map((r) => `<tr><td>${esc(r.mat)} <span class="stub-note">${UNITS[r.unit].short}</span></td><td>${r.tier}</td><td class="num">${r.min}</td><td class="num">${r.target}</td><td class="num">${r.free}</td><td class="num">${r.req}</td><td><div class="bar ${r.free < r.min ? 'is-low' : ''}"><span style="width:${Math.min(100, Math.round((r.free / r.target) * 100))}%"></span></div></td><td><span class="chip chip--danger">niedrig</span></td></tr>`).join('')}</tbody></table>
      <p class="stub-note">Logistiker sehen hier zusätzlich „Bedarf anlegen" und „Auftrag anlegen" (vorbelegt mit Ziel − Frei).</p>`;
  }
  if (k === 'hangar.own' && S.ui.filter) return `<p><span class="filter-chip">Schiffstyp: ${esc(S.ui.filter)}</span></p><p class="stub-note">Hangar, gefiltert auf den gesuchten Schiffstyp.</p>`;
  if (k === 'org.chart') {
    const people = [['Staffel Alpha', ['Vex (Leitung)', 'Nightjar', 'Mira_K']], ['SK Bravo', ['Corsair_7 (Leitung)', 'Halcyon']]];
    return people.map(([u, ms]) => `<div class="card" style="margin-bottom:.8rem"><div class="card-head"><h3 class="card-title" ${S.ui.highlight === u ? 'style="color:var(--color-primary)"' : ''}>${u}${S.ui.highlight === u ? ' ◀' : ''}</h3></div><ul class="wdg-list">${ms.map((m) => `<li ${S.ui.highlight && m.startsWith(S.ui.highlight) ? 'style="background:rgba(231,126,35,.14)"' : ''}><span>${esc(m)}</span></li>`).join('')}</ul></div>`).join('');
  }
  return `<div class="card"><p class="stub-note">Platzhalter für „${esc(entryLabel(k))}". Der Prototyp zeigt die Navigation, nicht den Seiteninhalt.</p></div>`;
}
const statusLabel = (s) => ({ open: 'Offen', progress: 'In Bearbeitung', done: 'Abgeschlossen' }[s]);

function entityHtml(r) {
  const [, kind, name] = r.split(':');
  return `<div class="page-head"><div><div class="page-head__eyebrow">${esc(KINDS[kind].label)}</div><h1 class="page-head__title">${esc(name)}</h1></div></div>
    <div class="card"><p class="stub-note">Detailseite (Platzhalter). Einträge aus der Suche landen nie in „Zuletzt" oder „Häufig".</p></div>`;
}

function profileHtml() {
  const d = S.prefs.display;
  const opts = (mode) => `<option value="">Dashboard</option>${favouritableKeys(mode).filter((k) => !k.startsWith('home')).map((k) => `<option value="${k}" ${d.startPage[mode] === k ? 'selected' : ''}>${esc(entryLabel(k))}</option>`).join('')}`;
  return `<div class="profile-grid stub">
    <section class="card" id="display"><div class="card-head"><h2 class="card-title">Darstellung</h2></div>
      <div class="field-row"><label>Navigation am Desktop</label>${modeSwitchHtml()}<span class="field-help">Gilt für Mitglieder- und Admin-Modus, auf jedem Gerät. Leiste ab 1025 px, angeheftet ab 1280 px.</span></div>
      <div class="field-row"><label class="krt-switch"><input type="checkbox" data-a="shortcuts" ${d.shortcuts ? 'checked' : ''}> Tastenkürzel Alt+0 … Alt+9</label><span class="field-help">Alt+1 … 9 öffnet Favorit 1 … 9, Alt+0 das Dashboard. Nur im Mitglieder-Modus.</span></div>
      <div class="field-row"><label for="sp-m">Startseite nach dem Anmelden</label><select id="sp-m" data-a="sp-select" data-mode="member">${opts('member')}</select></div>
      ${persona().admin ? `<div class="field-row"><label for="sp-a">Startseite im Admin-Modus</label><select id="sp-a" data-a="sp-select" data-mode="admin">${opts('admin')}</select></div>` : ''}
    </section>
    <section class="card"><div class="card-head"><h2 class="card-title">Zurücksetzen</h2></div>
      <p class="field-help">Entfernt Favoriten, ausgeblendete Seiten, Reihenfolge und Tab-Leiste in beiden Modi und setzt die Darstellung auf „Schublade". Das Dashboard bleibt (eigene Schaltfläche dort).</p>
      <button class="btn btn-quiet-danger" data-a="full-reset">Alles zurücksetzen</button>
    </section></div>`;
}

function favouritableKeys(mode) {
  const out = [];
  for (const [k, e] of REG) {
    if (e.mode !== mode || status(k) !== 'ok') continue;
    if (e.kind === 'page' && e.tabs.length > 1) { out.push(k); continue; }
    if (e.kind === 'page' && e.tabs.length <= 1) { out.push(k); continue; }
    if (e.kind === 'tab' && REG.get(e.page).tabs.length > 1 && e.label !== REG.get(e.page).label) out.push(k);
  }
  return out;
}

function dashboardHtml() {
  const mode = S.mode;
  const d = S.dash[mode];
  const ed = S.ui.dashEdit;
  const p = persona();
  const anyFav = S.prefs.favourites.length > 0;
  let h = `<div class="page-head"><div><div class="page-head__eyebrow">${mode === 'admin' ? 'Administration' : `Kontext: ${esc(ctxName())}`}</div><h1 class="page-head__title">${mode === 'admin' ? 'Admin-Dashboard' : `Willkommen, ${esc(p.handle)}`}</h1></div>
    <div class="page-head__actions">${ed ? `<button class="btn btn-ghost" data-a="dash-reset">Standard wiederherstellen</button><button class="btn btn-ghost" data-a="dash-add" ${d.items.length >= WIDGET_CAP ? 'disabled' : ''}>${icon('plus')} Widget hinzufügen</button><button class="btn btn--cta" data-a="dash-done" data-fid="dash-done">Fertig</button>` : `<button class="btn btn-ghost" data-a="dash-edit" data-fid="dash-edit">${icon('edit')} Anpassen</button>`}</div></div>`;
  if (!S.local.hintDismissed && !anyFav && !S.dash.member.touched && !S.dash.admin.touched) {
    h += `<div class="dash-hint" role="note">${icon('info')}<p>Du kannst Navigation und Startseite an dich anpassen: Favoriten mit dem Stern, Reihenfolge und Ausblenden über „Anpassen" in der Navigation, Widgets über „Anpassen" hier.</p>
      <button class="btn btn-ghost btn-tiny" data-a="edit">Navigation anpassen</button><button class="btn btn-ghost btn-tiny" data-a="hint-off" aria-label="Hinweis schließen">${icon('close')}</button></div>`;
  }
  if (ed) h += `<p class="stub-note">${d.items.length} von ${WIDGET_CAP} Widgets · Pfeile oder Alt+↑/↓ verschieben innerhalb der Spalte, ⇄ wechselt die Spalte.</p>`;
  if (!d.items.length) return h + `<div class="empty-state"><h2>Noch keine Widgets</h2><p>Stelle dir deine Startseite zusammen.</p><button class="btn btn--cta" data-a="dash-add">${icon('plus')} Widget hinzufügen</button></div>`;
  const wide = d.items.filter((i) => i.col === 'wide');
  const narrow = d.items.filter((i) => i.col === 'narrow');
  const col = (list, name) => (list.length ? list.map((it, i) => widgetHtml(it, i, list.length)).join('') : ed ? `<div class="is-empty-col">Spalte leer</div>` : '');
  if (isPhoneW()) return h + `<div class="dash-cols"><div class="dash-col">${col(wide)}${col(narrow)}</div></div>`;
  return h + `<div class="dash-cols"><div class="dash-col ${!wide.length && ed ? 'is-empty-col' : ''}">${col(wide)}</div><div class="dash-col ${!narrow.length && ed ? 'is-empty-col' : ''}">${col(narrow)}</div></div>`;
}

function widgetTitle(it) {
  const w = WIDGETS[it.type];
  if (it.type === 'next7') return `Nächste ${it.opts.range} Tage`;
  if (it.type === 'sync') return `Sync- & Importstatus · ${w.opts[0].values.find((v) => v[0] === it.opts.range)[1]}`;
  return w.label;
}
function widgetUnits(it) {
  if (!('unit' in it.opts)) return null;
  return it.opts.unit === 'ctx' ? ctxUnits() : [it.opts.unit];
}
function widgetHtml(it, i, len) {
  const w = WIDGETS[it.type];
  const ed = S.ui.dashEdit;
  const fixedUnit = it.opts.unit && it.opts.unit !== 'ctx' ? it.opts.unit : null;
  const unavailable = fixedUnit && !ctxUnits().includes(fixedUnit);
  const tools = ed ? `<div class="wdg-tools">
      <button class="mini" data-a="w-mv" data-id="${it.id}" data-d="-1" data-fid="wmv-${it.id}--1" ${i === 0 ? 'disabled' : ''} aria-label="${esc(w.label)} nach oben">${icon('up')}</button>
      <button class="mini" data-a="w-mv" data-id="${it.id}" data-d="1" data-fid="wmv-${it.id}-1" ${i === len - 1 ? 'disabled' : ''} aria-label="${esc(w.label)} nach unten">${icon('down')}</button>
      ${isPhoneW() ? '' : `<button class="mini" data-a="w-col" data-id="${it.id}" data-fid="wcol-${it.id}" aria-label="${esc(w.label)} in die andere Spalte" title="Spalte wechseln">${icon('swap')}</button>`}
      ${w.opts.length ? `<button class="mini" data-a="w-opt" data-id="${it.id}" aria-label="Optionen für ${esc(w.label)}" title="Optionen">${icon('gear')}</button>` : ''}
      <button class="mini" data-a="w-rm" data-id="${it.id}" aria-label="${esc(w.label)} entfernen" title="Entfernen">${icon('trash')}</button></div>` : '';
  const unitChip = fixedUnit ? `<span class="chip chip--muted">${UNITS[fixedUnit].short}</span>` : '';
  return `<section class="wdg ${unavailable ? 'is-unavailable' : ''}" ${ed ? `tabindex="0" data-wmove="${it.id}" data-fid="wrow-${it.id}"` : ''} aria-label="${esc(widgetTitle(it))}">
    <div class="wdg-head"><h2 class="wdg-title">${esc(widgetTitle(it))}</h2>${unitChip}${w.fresh && !ed ? '<span class="chip chip--info">Beispieldaten</span>' : ''}${tools}</div>
    ${unavailable ? `<p class="wdg-unavail">In ${esc(ctxName())} nicht verfügbar – fest auf ${UNITS[fixedUnit].name} eingestellt.</p>` : ''}
    <div class="wdg-body">${widgetBody(it)}</div>
    ${w.link ? `<div class="wdg-foot"><a href="#" data-a="go" data-k="${w.link}" ${it.type === 'assigned' ? 'data-seg="assigned"' : it.type === 'ordersTodo' ? 'data-seg="todo"' : it.type === 'myOrders' ? 'data-seg="mine"' : ''}>Alle anzeigen →</a></div>` : ''}
  </section>`;
}
function spark(series) {
  const max = Math.max(...series);
  const min = Math.min(...series);
  const pts = series.map((v, i) => `${(i / (series.length - 1)) * 100},${24 - ((v - min) / (max - min || 1)) * 22}`).join(' ');
  return `<svg viewBox="0 0 100 26" width="90" height="24" aria-hidden="true"><polyline class="spark" points="${pts}"/></svg>`;
}
function widgetBody(it) {
  const o = it.opts;
  const units = widgetUnits(it);
  const unitTag = (u) => (S.ctx === 'all' && o.unit === 'ctx' ? ` <span class="meta">${UNITS[u].short}</span>` : '');
  switch (it.type) {
    case 'next7': {
      const rows = SAMPLE.missions.filter((m) => m.day <= o.range && units.includes(m.unit)).slice(0, o.rows);
      return rows.length ? `<ul class="wdg-list">${rows.map((m) => `<li><span>${esc(m.name)}${unitTag(m.unit)}</span><span class="meta">${m.kind} · ${m.time}</span></li>`).join('')}</ul>` : '<p class="stub-note">Nichts geplant.</p>';
    }
    case 'nextMission': {
      const m = SAMPLE.missions.find((x) => units.includes(x.unit));
      return m ? `<div class="data-v">${esc(m.name)}</div><div class="meta">${m.time} · Treffpunkt Port Tressler</div>` : '<p class="stub-note">Keine Anmeldung.</p>';
    }
    case 'hangarReady': return `<div class="big-n">${units.length > 1 ? 14 : units[0] === 'alpha' ? 9 : 5}</div><div class="meta">von ${units.length > 1 ? 21 : units[0] === 'alpha' ? 13 : 8} Schiffen einsatzbereit</div>`;
    case 'ordersTodo': case 'myOrders': case 'assigned': {
      let rows = SAMPLE.orders;
      if (it.type === 'ordersTodo') rows = rows.filter((x) => x.status !== 'done' && units.includes(x.unit));
      if (it.type === 'myOrders') rows = rows.filter((x) => x.mine && (o.status === 'all' || (o.status === 'done' ? x.status === 'done' : x.status !== 'done')));
      if (it.type === 'assigned') rows = rows.filter((x) => x.assigned);
      if (o.status === 'open') rows = rows.filter((x) => x.status === 'open');
      if (o.status === 'progress') rows = rows.filter((x) => x.status === 'progress');
      rows = rows.slice(0, o.rows);
      return rows.length ? `<ul class="wdg-list">${rows.map((x) => `<li><span><span class="data-v">${x.id}</span> ${esc(x.title)}</span><span class="meta">${statusLabel(x.status)} · ${x.prio}</span></li>`).join('')}</ul>` : '<p class="stub-note">Keine Aufträge.</p>';
    }
    case 'demand': return `<ul class="wdg-list">${[['Quantanium', '120 SCU'], ['Laranite', '48 SCU'], ['Agricium', '64 SCU'], ['Titan', '300 SCU'], ['Hadanite', '12 SCU']].slice(0, o.rows).map(([a, b]) => `<li><span>${a}</span><span class="data-v">${b}</span></li>`).join('')}</ul>`;
    case 'refineryDone': return `<ul class="wdg-list">${[['Quantanium · ARC-L1', 'abholbereit'], ['Bexalite · CRU-L1', 'in 2 Std.']].slice(0, o.rows).map(([a, b]) => `<li><span>${a}</span><span class="meta">${b}</span></li>`).join('')}</ul>`;
    case 'lowStock': {
      const rows = SAMPLE.lowStock.filter((r) => units.includes(r.unit)).sort((a, b) => a.free / a.min - b.free / b.min).slice(0, o.rows);
      return rows.length ? `<ul class="wdg-list">${rows.map((r) => `<li><span style="flex:1">${esc(r.mat)} ${r.tier !== '—' ? `<span class="meta">${r.tier}</span>` : ''}${S.ctx === 'all' && o.unit === 'ctx' ? ` <span class="meta">${UNITS[r.unit].short}</span>` : ''}<div class="bar is-low"><span style="width:${Math.round((r.free / r.target) * 100)}%"></span></div></span><span class="meta"><span class="data-v">${r.free}</span> / min. ${r.min}</span></li>`).join('')}</ul>` : '<p class="stub-note">Alle Mindestbestände erfüllt.</p>';
    }
    case 'exchange': return `<ul class="wdg-list">${[['Angebot', 'Laranite 32 SCU', 'offers'], ['Gesuch', 'Hadanite 8 SCU', 'requests'], ['Angebot', 'Titan 120 SCU', 'offers']].filter((x) => o.status === 'both' || x[2] === o.status).slice(0, o.rows).map(([a, b]) => `<li><span>${b}</span><span class="meta">${a}</span></li>`).join('')}</ul>`;
    case 'supply': return `<ul class="wdg-list">${[['Medpens ×200', '40 %'], ['Munition S3 ×500', 'ungedeckt']].slice(0, o.rows).map(([a, b]) => `<li><span>${a}</span><span class="meta">${b}</span></li>`).join('')}</ul>`;
    case 'bankRequests': return `<ul class="wdg-list">${[['Auszahlung 250.000 aUEC', 'offen', 'open'], ['Einzahlung 1.000.000 aUEC', 'genehmigt', 'decided']].filter((x) => o.status === 'all' || x[2] === o.status).slice(0, o.rows).map(([a, b]) => `<li><span>${a}</span><span class="meta">${b}</span></li>`).join('')}</ul>`;
    case 'bank': {
      const order = [...SAMPLE.bank].sort((a, b) => (a.unit === null ? -1 : b.unit === null ? 1 : ctxUnits().includes(a.unit) && !ctxUnits().includes(b.unit) ? -1 : 0));
      return `<ul class="wdg-list">${order.slice(0, o.rows).map((b) => `<li><span style="flex:1">${esc(b.acct)}<div class="meta"><span class="data-v">${b.bal}</span> aUEC</div>${b.target ? `<div class="bar"><span style="width:${b.target * 100}%"></span></div>` : ''}</span>${spark(b.series)}</li>`).join('')}</ul>`;
    }
    case 'info': return `<p>Org-Event am Samstag: Treffen 19:30 im TeamSpeak, Abflug 20:00 ab Port Tressler.</p>${persona().admin ? '<button class="btn btn-ghost btn-tiny" data-a="stub" data-t="Information direkt hier bearbeiten">Bearbeiten</button>' : ''}`;
    case 'notifications': {
      const rows = SAMPLE.notifications.filter((n) => o.status === 'all' || n.unread).slice(0, o.rows);
      return `<ul class="wdg-list">${rows.map((n) => `<li><span>${esc(n.t)}</span><span class="meta">${n.at}</span></li>`).join('')}</ul>`;
    }
    case 'favourites': {
      const favs = favList(S.mode);
      if (!favs.length) return `<p class="stub-note">Noch keine Favoriten. Setze einen mit dem Stern ${icon('star')} im Seitenkopf oder über „Anpassen" in der Navigation.</p>`;
      return `<div class="fav-tiles">${favs.map((k) => {
        const n = favList('member').indexOf(k);
        const ok = status(k) === 'ok';
        const kb = ok && shortcutsActive() && n >= 0 && n < 9 ? `Alt+${n + 1}` : '';
        return ok ? `<a class="fav-tile" href="#" data-a="go" data-k="${k}" ${kb ? `aria-keyshortcuts="${kb}"` : ''}>${icon(REG.get(k).icon)}<span>${esc(entryLabel(k))}</span>${kb ? `<span class="kbd-hint">${kb}</span>` : ''}</a>` : `<span class="fav-tile is-unavailable" title="In ${esc(ctxName())} nicht verfügbar">${icon(REG.get(k).icon)}<span>${esc(entryLabel(k))}</span><span class="kbd-hint">nicht verfügbar</span></span>`;
      }).join('')}</div>`;
    }
    case 'hub': return `<div class="hub-groups">${orderedGroups('admin').map((g) => `<div class="hub-group"><h4>${esc(g.label)}</h4>${orderedPages('admin', g).map((k) => `<a class="hub-tile" href="#" data-a="go" data-k="${k}">${esc(REG.get(k).label)}</a>`).join('')}</div>`).join('')}</div>`;
    case 'erasure': return `<div class="big-n">2</div><div class="meta">Löschanträge warten, ältester seit 3 Tagen</div>`;
    case 'discord': return `<ul class="wdg-list">${['Mira_K', 'Talon', 'Ivo_R'].slice(0, o.rows).map((x) => `<li><span>${x}</span><span class="meta">wartet</span></li>`).join('')}</ul>`;
    case 'sync': return `<ul class="wdg-list"><li><span>UEX</span><span class="meta">OK · vor 2 Std.</span></li><li><span>SC Wiki</span><span class="meta">OK · gestern</span></li><li><span>P4K</span><span class="meta" style="color:var(--color-danger-text)">Fehler · vor 4 Tagen</span></li></ul>`;
    case 'terms': return `<div class="big-n">5</div><div class="meta">Mitglieder ohne Zustimmung zur Fassung 3</div>`;
    default: return '';
  }
}
ICONS.info = '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>';
ICONS.check = '<path d="M5 12l5 5 9-10"/>';

function moveWidget(id, d) {
  const items = S.dash[S.mode].items;
  const it = items.find((x) => x.id === id);
  const same = items.filter((x) => x.col === it.col);
  const i = same.indexOf(it);
  const j = i + d;
  if (j < 0 || j >= same.length) return;
  const a = items.indexOf(it);
  const b = items.indexOf(same[j]);
  [items[a], items[b]] = [items[b], items[a]];
  dashWrite();
  announce(`${WIDGETS[it.type].label}: Position ${j + 1} von ${same.length}`);
}
function dashWrite() {
  const d = S.dash[S.mode];
  d.touched = true;
  logDash(`/api/v1/navigation/dashboard/${S.mode}/layout`, d.v, d.v + 1);
  d.v += 1;
}

function modalHtml() {
  const m = S.ui.modal;
  const shell = (title, body, foot, wide) => `<div class="krt-modal-overlay" data-a="modal-scrim"><div class="krt-modal ${wide ? 'krt-modal--wide' : ''}" role="dialog" aria-modal="true" aria-label="${esc(title)}" data-stop>
    <div class="krt-modal-head"><h2>${esc(title)}</h2><button class="krt-modal-close" data-a="modal-close" aria-label="Schließen">×</button></div>
    <div class="krt-modal-body">${body}</div>${foot ? `<div class="krt-modal-foot">${foot}</div>` : ''}</div></div>`;
  if (m.type === 'catalogue') {
    const d = S.dash[S.mode];
    const full = d.items.length >= WIDGET_CAP;
    const list = Object.entries(WIDGETS).filter(([, w]) => w.mode === S.mode).map(([k, w]) => {
      const n = d.items.filter((x) => x.type === k).length;
      return `<li><span class="grow">${esc(w.label)}${w.fresh ? ' <span class="chip chip--info">neu</span>' : ''}<span class="desc">${esc(w.desc)}${n ? ` · ${n}× vorhanden` : ''}${w.opts.length ? ` · Optionen: ${w.opts.map((o) => o.label).join(', ')}` : ''}</span></span><button class="btn btn-ghost btn-tiny" data-a="cat-add" data-t="${k}" ${full ? 'disabled' : ''}>${icon('plus')} Hinzufügen</button></li>`;
    }).join('');
    return shell('Widget hinzufügen', `<p class="field-help">${d.items.length} von ${WIDGET_CAP} Widgets. Ein Widget darf mehrfach vorkommen, etwa einmal je Einheit.</p><ul class="cat-list">${list}</ul>`, '<button class="btn btn-ghost" data-a="modal-close">Schließen</button>', true);
  }
  if (m.type === 'options') {
    const it = S.dash[S.mode].items.find((x) => x.id === m.id);
    const w = WIDGETS[it.type];
    const fields = w.opts.map((o) => `<div class="opt-field"><label for="opt-${o.k}">${o.label}</label><select id="opt-${o.k}" data-opt="${o.k}">${o.values.map(([v, l]) => `<option value="${v}" ${String(it.opts[o.k]) === String(v) ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select></div>`).join('');
    return shell(`Optionen: ${w.label}`, `${fields}<p class="field-help">Gespeichert mit eigener Version (jetzt v${it.v}); kollidiert nie mit dem Umsortieren.</p>`, `<button class="btn btn-ghost" data-a="modal-close">Abbrechen</button><button class="btn btn--cta" data-a="opt-save" data-id="${it.id}">Speichern</button>`);
  }
  if (m.type === 'slot') {
    const slots = tabSlots(S.mode);
    const cur = slots[m.i] ? slots[m.i].key : null;
    const favs = favList(S.mode).filter((k) => status(k) === 'ok');
    const rest = favouritableKeys(S.mode).filter((k) => !favs.includes(k));
    const item = (k, tag) => {
      const other = slots.findIndex((s, j) => s && s.key === k && j !== m.i);
      return `<li><button data-a="slot-pick" data-k="${k}" class="${k === cur ? 'is-current' : ''}">${icon(REG.get(k).icon)} ${esc(entryLabel(k))}<span class="tag">${other >= 0 ? `tauscht mit Platz ${other + 1}` : tag}</span></button></li>`;
    };
    return shell(`Platz ${m.i + 1} der Tab-Leiste`, `<ul class="pick-list">${favs.map((k) => item(k, 'Favorit')).join('')}${rest.map((k) => item(k, '')).join('')}</ul>`, `<button class="btn btn-ghost" data-a="reset-tabbar" ${S.prefs.tabbar[S.mode] ? '' : 'disabled'}>Tab-Leiste zurücksetzen</button><button class="btn btn-ghost" data-a="modal-close">Schließen</button>`);
  }
  if (m.type === 'kbd') {
    const d = S.prefs.display;
    const altRow = S.mode === 'admin' ? '<td>nur im Mitglieder-Modus</td>' : d.shortcuts ? '<td>Favorit 1 … 9 öffnen (Platz in der Favoriten-Gruppe)</td>' : '<td>ausgeschaltet · <a class="link" data-a="go" data-k="profile">Darstellung</a></td>';
    const alt0 = S.mode === 'admin' ? '<td>nur im Mitglieder-Modus</td>' : d.shortcuts ? '<td>Dashboard öffnen</td>' : '<td>ausgeschaltet</td>';
    return shell('Tastenkürzel', `<table class="kbd-table"><tbody>
      <tr><td>Strg+K</td><td>Schnellsuche öffnen</td></tr>
      <tr><td>Alt+1 … Alt+9</td>${altRow}</tr>
      <tr><td>Alt+0</td>${alt0}</tr>
      <tr><td>Strg+Enter</td><td>In der Suche: Treffer in neuem Browser-Tab öffnen</td></tr>
      <tr><td>Tab</td><td>In der Suche: vorgeschlagenes Präfix übernehmen</td></tr>
      <tr><td>Strg+D</td><td>In der Suche: markierte Seite favorisieren / entfernen</td></tr>
      <tr><td>Alt+↑ / Alt+↓</td><td>Im Anpassen-Modus: Eintrag verschieben</td></tr>
    </tbody></table><p class="field-help">Nur die Ziffernreihe oben zählt, nicht der Ziffernblock.</p>`, '<button class="btn btn--cta" data-a="modal-close" data-autofocus>OK</button>');
  }
  if (m.type === 'reset') {
    return `<div class="krt-modal-overlay" data-a="modal-scrim"><div class="krt-modal krt-modal--danger" role="alertdialog" aria-modal="true" aria-label="Alles zurücksetzen" data-stop>
      <div class="krt-modal-head"><h2>Alles zurücksetzen?</h2><button class="krt-modal-close" data-a="modal-close" aria-label="Schließen">×</button></div>
      <div class="krt-modal-body"><p>Favoriten, ausgeblendete Seiten, Reihenfolge und Tab-Leiste beider Modi werden gelöscht, die Darstellung steht wieder auf „Schublade". Das lässt sich nicht rückgängig machen.</p></div>
      <div class="krt-modal-foot"><button class="btn btn-ghost" data-a="modal-close" data-autofocus>Abbrechen</button><button class="btn btn-danger" data-a="full-reset-ok">Zurücksetzen</button></div></div></div>`;
  }
  return '';
}

function parsePrefix(q) {
  const t = q.replace(/^\s+/, '');
  for (const [k, d] of Object.entries(KINDS)) if (d.sym && t.startsWith(d.sym)) return { kind: k, term: t.slice(1).trim() };
  const m = /^([a-zäöü]+):\s*/i.exec(t);
  if (m) {
    const w = m[1].toLowerCase();
    for (const [k, d] of Object.entries(KINDS)) if (d.words.includes(w)) return { kind: k, term: t.slice(m[0].length).trim() };
  }
  return { kind: null, term: t.trim() };
}
function score(label, term) {
  const l = label.toLowerCase();
  const t = term.toLowerCase();
  if (!t) return 1;
  if (l.startsWith(t)) return 3;
  if (l.split(/[\s·–-]+/).some((w) => w.startsWith(t))) return 2;
  if (l.includes(t)) return 1;
  return 0;
}
function pageItems() {
  const out = [];
  for (const [k, e] of REG) {
    if (e.mode !== S.mode || status(k) !== 'ok') continue;
    const pk = e.kind === 'tab' ? e.page : k;
    if (isHidden(pk)) continue;
    if (e.kind === 'tab' && (REG.get(e.page).tabs.length < 2 || e.label === REG.get(e.page).label)) continue;
    out.push({ id: `p:${k}`, kind: 'page', key: k, label: entryLabel(k), ic: e.icon });
  }
  return out;
}
function actionItems() {
  const out = [];
  const cur = S.ui.route;
  const e = REG.get(cur);
  const goto = (k, t) => () => { navigate(k); toast(t); };
  if (e && !cur.startsWith('home')) {
    out.push({ id: 'a:fav', label: isFav(cur) ? `„${entryLabel(cur)}" aus Favoriten entfernen` : `„${entryLabel(cur)}" favorisieren`, run: () => { toggleFav(cur); render(); } });
    const pk = e.kind === 'tab' ? e.page : cur;
    if (REG.get(pk).group) out.push({ id: 'a:hide', label: `„${REG.get(pk).label}" ausblenden`, run: () => { hidePage(pk); render(); } });
  }
  out.push({ id: 'a:edit', label: 'Navigation anpassen', run: openEdit });
  if (!isPhoneW()) {
    out.push({ id: 'a:m1', label: 'Darstellung: Schublade', run: () => setDesktopMode('drawer') });
    out.push({ id: 'a:m2', label: 'Darstellung: Leiste', run: () => setDesktopMode('rail') });
    out.push({ id: 'a:m3', label: 'Darstellung: Angeheftet', run: () => setDesktopMode('pinned') });
  }
  out.push({ id: 'a:kbd', label: 'Tastenkürzel anzeigen', run: () => { S.ui.modal = { type: 'kbd' }; render(); } });
  if (persona().admin) out.push({ id: 'a:admin', label: S.mode === 'admin' ? 'Admin-Modus verlassen' : 'In den Admin-Modus wechseln', run: () => setMode(S.mode === 'admin' ? 'member' : 'admin') });
  if (S.mode === 'member') {
    out.push({ id: 'a:c1', label: 'Neue Mission anlegen', sub: 'Missionen', run: goto('missions.list', 'Öffnet den vorhandenen Dialog „Neue Mission".') });
    out.push({ id: 'a:c2', label: 'Neue Operation anlegen', sub: 'Operationen', run: goto('missions.operations', 'Öffnet den vorhandenen Dialog „Neue Operation".') });
    out.push({ id: 'a:c3', label: 'Neuen Auftrag anlegen', sub: 'Aufträge', run: goto('orders.list', 'Öffnet das Auftragsformular.') });
    out.push({ id: 'a:c4', label: 'Lagereintrag anlegen', sub: 'Lager', run: goto('storage.stock', 'Öffnet den Dialog „Einlagern".') });
    out.push({ id: 'a:c5', label: 'Bedarf anlegen', sub: 'Beschaffung', run: goto('procurement.demand', 'Öffnet das Bedarfsformular.') });
    out.push({ id: 'a:c6', label: 'Schiff hinzufügen', sub: 'Hangar', run: goto('hangar.own', 'Öffnet den Dialog „Schiff hinzufügen".') });
    if (gateStatus('officer') === 'ok') out.push({ id: 'a:c7', label: 'Bewertung anlegen', sub: 'Beförderung', run: goto('promotion.admin', 'Öffnet den Bewertungsdialog.') });
    if (persona().bank) out.push({ id: 'a:c8', label: 'Buchung erfassen', sub: 'Kartellbank', run: goto('bank.manage', 'Öffnet den Buchungsdialog.') });
    if (cur.startsWith('orders')) out.push({ id: 'a:x1', label: 'Aufträge: nur „Ich bearbeite" zeigen', sub: 'Seitenaktion', run: () => navigate('orders.list', { segment: 'assigned' }) });
  } else {
    out.push({ id: 'a:c9', label: 'Staffel anlegen', sub: 'Organisationsstruktur', run: goto('admin.orgstructure.squadrons', 'Öffnet den Dialog „Staffel anlegen".') });
    out.push({ id: 'a:c10', label: 'Material anlegen', sub: 'Materialien', run: goto('admin.materials.list', 'Öffnet den Dialog „Material anlegen".') });
  }
  return out.map((a) => ({ ...a, kind: 'action', ic: 'right' }));
}
function entitySearch(kind, term) {
  const kinds = kind ? [kind] : ENTITY_KINDS;
  const out = [];
  for (const k of kinds) {
    if (!ENTITY_KINDS.includes(k)) continue;
    const t = term.toLowerCase();
    if (k === 'order') { if (!/^a?-?\d/i.test(term)) continue; }
    else if (t.length < 2) continue;
    const hits = SAMPLE.entities[k].filter((n) => (k === 'order' ? n.toLowerCase().replace('a-', '').startsWith(t.replace(/^a-?/, '')) : n.toLowerCase().includes(t))).slice(0, 5);
    for (const n of hits) out.push({ id: `e:${k}:${n}`, kind: k, label: n, ic: k === 'member' ? 'user' : k === 'ship' ? 'hangar' : k === 'order' ? 'clipboard' : k === 'unit' ? 'orgchart' : k === 'material' ? 'diamond' : 'wedge', run: () => openEntity(k, n) });
  }
  return out;
}
function openEntity(k, n) {
  if (k === 'ship') return navigate('hangar.own', { filter: n });
  if (k === 'unit') return navigate('org.chart', { highlight: n });
  if (k === 'member') return S.mode === 'admin' ? navigate('admin.members.list') : navigate('org.chart', { highlight: n });
  S.ui.route = `ent:${k}:${n}`;
  S.ui.menu = false;
  render();
}
function setDesktopMode(m) {
  S.prefs.display.desktopMode = m;
  logWrite(['display'], '/api/v1/navigation/display');
  S.ui.flyout = null;
  S.ui.drawer = false;
  render();
}

function palModel() {
  const P = S.ui.palette;
  const q = P.q;
  if (q.trim() === '?') {
    const rows = Object.entries(KINDS).map(([k, d]) => ({ id: `h:${k}`, kind: 'help', label: `${d.sym ? `${d.sym}  oder  ` : ''}${d.words.map((w) => `${w}:`).join('  ')}`, sub: d.label, apply: d.sym || `${d.words[0]}: ` }));
    return [{ sec: 'Präfixe (Deutsch und Englisch gelten immer)', items: rows }];
  }
  if (!q.trim()) {
    const favs = favList(S.mode).filter((k) => status(k) === 'ok');
    const freq = Object.entries(S.local.frequent).filter(([k]) => REG.get(k) && REG.get(k).mode === S.mode && status(k) === 'ok' && !favs.includes(k) && !isHidden(REG.get(k).kind === 'tab' ? REG.get(k).page : k)).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([k]) => k);
    const rec = S.local.recent.filter((k) => REG.get(k) && REG.get(k).mode === S.mode && status(k) === 'ok' && !favs.includes(k) && !freq.includes(k));
    const mk = (k) => ({ id: `p:${k}`, kind: 'page', key: k, label: entryLabel(k), ic: REG.get(k).icon });
    const secs = [];
    if (favs.length) secs.push({ sec: 'Favoriten', items: favs.map(mk) });
    if (freq.length) secs.push({ sec: 'Häufig', items: freq.map(mk) });
    if (rec.length) secs.push({ sec: 'Zuletzt', items: rec.map(mk) });
    if (!secs.length) secs.push({ sec: 'Tipp', items: [{ id: 'h:tip', kind: 'help', label: 'Tippe einen Seitennamen, ? zeigt die Präfixe', apply: '?' }] });
    return secs;
  }
  const { kind, term } = parsePrefix(q);
  let items = [];
  if (!kind) {
    for (const [k, d] of Object.entries(KINDS)) {
      const w = d.words.find((x) => term.length >= 2 && x.startsWith(term.toLowerCase()) && x !== term.toLowerCase());
      if (w) items.push({ id: `s:${k}`, kind: 'suggest', label: `Nur ${d.pl} durchsuchen`, sub: `Tab → ${w}:`, apply: `${w}: `, sc: 9 });
    }
  }
  if (!kind || kind === 'page') items.push(...pageItems().map((p) => ({ ...p, sc: score(p.label, term) * 10 + Math.min(4, (S.local.frequent[p.key] || 0) * 0.5) })).filter((p) => p.sc >= 10 || !term));
  if (!kind || kind === 'action') items.push(...actionItems().map((a) => ({ ...a, sc: score(a.label, term) * 10 - 1 })).filter((a) => a.sc >= 9 || !term));
  if (!kind || ENTITY_KINDS.includes(kind)) items.push(...P.ents.map((x) => ({ ...x, sc: score(x.label, term) * 10 + 1 })));
  items.sort((a, b) => b.sc - a.sc);
  return [{ sec: kind ? `Nur: ${KINDS[kind].label}` : 'Treffer', items: items.slice(0, 30) }];
}
function palFlat() { return palModel().flatMap((s) => s.items); }
function paletteHtml() {
  const P = S.ui.palette;
  const secs = palModel();
  const flat = secs.flatMap((s) => s.items);
  if (!flat.find((x) => x.id === P.sel)) P.sel = flat.length ? flat[0].id : null;
  const list = secs.map((s) => `<li class="pal-sec" role="presentation">${esc(s.sec)}</li>${s.items.map((it) => `<li class="pal-item ${it.kind === 'suggest' ? 'is-suggest' : ''}" role="option" id="opt-${esc(it.id)}" data-a="pal-run" data-id="${esc(it.id)}" aria-selected="${it.id === P.sel}">${icon(it.ic || 'search')}<span>${esc(it.label)}${it.sub ? ` <span class="sub">${esc(it.sub)}</span>` : ''}</span><span class="kind">${it.kind === 'suggest' ? 'Tab' : it.kind === 'help' ? 'Präfix' : esc((KINDS[it.kind] || { label: '' }).label)}${it.kind === 'page' && isFav(it.key) ? ' ★' : ''}</span></li>`).join('')}`).join('');
  return `<div class="pal-overlay" data-a="pal-scrim"><div class="pal" role="dialog" aria-modal="true" aria-label="Schnellsuche" data-stop>
    <div class="pal-input">${icon('search')}<input type="text" value="${esc(P.q)}" placeholder="Seite, Aktion oder Eintrag … (? für Präfixe)" role="combobox" aria-expanded="true" aria-controls="pal-list" aria-activedescendant="${P.sel ? `opt-${esc(P.sel)}` : ''}"><span class="kbd">ESC</span></div>
    <ul class="pal-list" id="pal-list" role="listbox">${list || '<li class="pal-loading">Keine Treffer.</li>'}</ul>
    ${P.loading ? '<div class="pal-loading">Suche Einträge …</div>' : ''}
    <div class="pal-foot"><span><b>↑↓</b> wählen</span><span><b>Enter</b> öffnen</span><span><b>Strg+Enter</b> neuer Tab</span><span><b>Tab</b> Präfix</span><span><b>Strg+D</b> Favorit</span><span><b>?</b> Präfixe</span></div>
  </div></div>`;
}
let entTimer = null;
function palSetQuery(q) {
  const P = S.ui.palette;
  P.q = q;
  P.ents = [];
  clearTimeout(entTimer);
  const { kind, term } = parsePrefix(q);
  const wantsEnt = (!kind || ENTITY_KINDS.includes(kind)) && q.trim() !== '?' && (term.length >= 2 || (/\d/.test(term) && (kind === 'order' || !kind)));
  P.loading = wantsEnt;
  if (wantsEnt) {
    entTimer = setTimeout(() => {
      if (!S.ui.palette || S.ui.palette.q !== q) return;
      S.ui.palette.ents = entitySearch(kind, term);
      S.ui.palette.loading = false;
      render();
    }, 380);
  }
  render();
}
function palRun(item, newTab) {
  if (!item) return;
  if (item.kind === 'help' || item.kind === 'suggest') { palSetQuery(item.apply); return; }
  if (newTab) { toast(`Öffnet „${item.label}" in einem neuen Browser-Tab.`); return; }
  S.ui.palette = null;
  if (item.kind === 'page') navigate(item.key);
  else if (item.run) item.run();
  else render();
}

function openPalette() {
  if (S.ui.modal) return;
  S.ui.palette = { q: '', sel: null, ents: [], loading: false };
  S.ui.menu = false;
  S.ui.flyout = null;
  render();
}

function onClick(e) {
  if (Date.now() < S.lpUntil) { e.preventDefault(); return; }
  let dirty = false;
  if (!e.target.closest('.menu-pop, [data-a="menu"]') && S.ui.menu) { S.ui.menu = false; dirty = true; }
  if (S.ui.flyout && !e.target.closest('[data-rail], [data-flyout]')) { S.ui.flyout = null; S.ui.flyoutPinned = false; dirty = true; }
  const t = e.target.closest('[data-a]');
  if (!t || t.tagName === 'SELECT' || t.tagName === 'INPUT' || t.tagName === 'OPTION') { if (dirty) render(); return; }
  const a = t.dataset.a;
  if ((a === 'modal-scrim' || a === 'pal-scrim') && e.target.closest('[data-stop]')) return;
  e.preventDefault();
  const k = t.dataset.k;
  const mode = S.mode;
  switch (a) {
    case 'go':
      if (t.dataset.seg) S.ui.segment = t.dataset.seg;
      S.ui.modal = null;
      navigate(k);
      if (t.dataset.anchor) { const el = device.querySelector(`#${t.dataset.anchor}`); if (el) el.scrollIntoView(); }
      return;
    case 'drawer': S.ui.drawer = !S.ui.drawer; if (!S.ui.drawer) S.ui.editing = false; break;
    case 'close-nav': S.ui.drawer = false; S.ui.editing = false; break;
    case 'menu': S.ui.menu = !S.ui.menu; break;
    case 'palette': openPalette(); return;
    case 'edit': openEdit(); return;
    case 'edit-done': S.ui.editing = false; if (navMode() === 'rail') S.ui.drawer = false; toast('Navigation gespeichert.'); break;
    case 'collapse': S.local.collapsed[k] = !(S.local.collapsed[k] ?? (k === 'g.more' ? true : S.mode === 'admin')); break;
    case 'mv': move(t.dataset.scope, k, Number(t.dataset.d)); break;
    case 'fav': toggleFav(k); break;
    case 'hide': hidePage(k); break;
    case 'unhide': unhide(k); toast(`„${REG.get(k).label}" ist wieder eingeblendet.`); break;
    case 'reset-hidden': S.prefs.hidden = S.prefs.hidden.filter((x) => REG.get(x).mode !== mode); logWrite(['hidden'], '/api/v1/navigation/hidden'); break;
    case 'reset-favs': S.prefs.favourites = S.prefs.favourites.filter((x) => REG.get(x).mode !== mode); logWrite(['favourites'], '/api/v1/navigation/favourites'); toast('Favoriten entfernt.'); break;
    case 'reset-order': S.prefs.order[mode] = null; logWrite([`order.${mode}`], `/api/v1/navigation/order/${mode}`); toast('Standardreihenfolge wiederhergestellt.'); break;
    case 'reset-tabbar': S.prefs.tabbar[mode] = null; logWrite([`tabbar.${mode}`], `/api/v1/navigation/tabbar/${mode}`); toast('Tab-Leiste wieder automatisch.'); S.ui.modal = null; break;
    case 'slot-swap': swapSlots(mode, Number(t.dataset.i), Number(t.dataset.i) + Number(t.dataset.d)); break;
    case 'slot-pick': setSlot(mode, S.ui.modal.i, k); S.ui.modal = null; toast(`Platz gesetzt: ${entryLabel(k)}`); break;
    case 'tab': navigate(k); return;
    case 'dmode': setDesktopMode(t.dataset.v); return;
    case 'rail':
      if (S.ui.flyout === k && S.ui.flyoutPinned) { S.ui.flyout = null; S.ui.flyoutPinned = false; } else { S.ui.flyout = k; S.ui.flyoutPinned = true; }
      break;
    case 'admin-rail': setMode(S.mode === 'admin' ? 'member' : 'admin'); return;
    case 'kbd': S.ui.menu = false; S.ui.flyout = null; S.ui.modal = { type: 'kbd' }; break;
    case 'logout': toast('Abmelden (Prototyp).'); S.ui.menu = false; break;
    case 'startpage': {
      const cur = S.prefs.display.startPage[mode];
      S.prefs.display.startPage[mode] = cur === k ? null : k;
      logWrite(['display'], '/api/v1/navigation/display');
      toast(cur === k ? 'Startseite: wieder das Dashboard.' : `Startseite nach dem Anmelden: ${entryLabel(k)}`);
      break;
    }
    case 'seg': S.ui.segment = t.dataset.v; break;
    case 'stub': toast(`${t.dataset.t} (Platzhalter).`); break;
    case 'hint-off': S.local.hintDismissed = true; break;
    case 'dash-edit': S.ui.dashEdit = true; break;
    case 'dash-done': S.ui.dashEdit = false; toast('Dashboard gespeichert.'); break;
    case 'dash-add': S.ui.modal = { type: 'catalogue' }; break;
    case 'dash-reset': S.dash[mode] = freshDash(mode); S.dash[mode].touched = true; logDash(`/api/v1/navigation/dashboard/${mode}/layout (Standard)`, 0, 0); toast('Standard-Dashboard wiederhergestellt.'); break;
    case 'cat-add': {
      const d = S.dash[mode];
      if (d.items.length >= WIDGET_CAP) break;
      d.items.push(inst(t.dataset.t));
      dashWrite();
      toast(`„${WIDGETS[t.dataset.t].label}" hinzugefügt.`);
      break;
    }
    case 'w-mv': moveWidget(t.dataset.id, Number(t.dataset.d)); break;
    case 'w-col': { const it = S.dash[mode].items.find((x) => x.id === t.dataset.id); it.col = it.col === 'wide' ? 'narrow' : 'wide'; dashWrite(); break; }
    case 'w-opt': S.ui.modal = { type: 'options', id: t.dataset.id }; break;
    case 'w-rm': { const d = S.dash[mode]; const it = d.items.find((x) => x.id === t.dataset.id); d.items = d.items.filter((x) => x !== it); dashWrite(); toast(`„${WIDGETS[it.type].label}" entfernt – über „Widget hinzufügen" wieder da.`); break; }
    case 'opt-save': {
      const it = S.dash[mode].items.find((x) => x.id === t.dataset.id);
      device.querySelectorAll('[data-opt]').forEach((sel) => { const o = WIDGETS[it.type].opts.find((x) => x.k === sel.dataset.opt); const raw = sel.value; it.opts[o.k] = typeof o.def === 'number' ? Number(raw) : raw; });
      logDash(`/api/v1/navigation/dashboard/${mode}/widgets/${it.id}/options`, it.v, it.v + 1);
      it.v += 1;
      S.ui.modal = null;
      break;
    }
    case 'modal-close': case 'modal-scrim': S.ui.modal = null; break;
    case 'full-reset': S.ui.modal = { type: 'reset' }; break;
    case 'full-reset-ok': {
      const v = S.prefs.v;
      S.prefs = freshPrefs();
      S.prefs.v = Object.fromEntries(Object.entries(v).map(([kk, x]) => [kk, x + 1]));
      S.log.unshift('DELETE /api/v1/navigation/preferences · alle Abschnitte');
      S.ui.modal = null;
      toast('Navigation vollständig zurückgesetzt.');
      break;
    }
    case 'pal-scrim': S.ui.palette = null; break;
    case 'pal-run': palRun(palFlat().find((x) => x.id === t.dataset.id), e.ctrlKey || e.metaKey); return;
    default: return;
  }
  render();
}

function onChange(e) {
  const t = e.target;
  const a = t.dataset.a;
  if (!a) return;
  if (a === 'ctx') { S.ctx = t.value; toast(`Kontext: ${ctxName()}`); }
  if (a === 'admin-toggle') { setMode(t.checked ? 'admin' : 'member'); return; }
  if (a === 'shortcuts') { S.prefs.display.shortcuts = t.checked; logWrite(['display'], '/api/v1/navigation/display'); }
  if (a === 'slot-set') { setSlot(S.mode, Number(t.dataset.i), t.value); }
  if (a === 'sp-select') { S.prefs.display.startPage[t.dataset.mode] = t.value || null; logWrite(['display'], '/api/v1/navigation/display'); }
  render();
}

function onKey(e) {
  const P = S.ui.palette;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    if (P) { S.ui.palette = null; render(); } else openPalette();
    return;
  }
  if (P) {
    const flat = palFlat();
    const i = flat.findIndex((x) => x.id === P.sel);
    if (e.key === 'Escape') { S.ui.palette = null; render(); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); P.sel = flat[Math.min(flat.length - 1, i + 1)]?.id ?? null; render(); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); P.sel = flat[Math.max(0, i - 1)]?.id ?? null; render(); return; }
    if (e.key === 'Enter') { e.preventDefault(); palRun(flat[i], e.ctrlKey || e.metaKey); return; }
    if (e.key === 'Tab') {
      const sug = flat.find((x) => x.id === P.sel && x.kind === 'suggest') || flat.find((x) => x.kind === 'suggest');
      e.preventDefault();
      if (sug) palSetQuery(sug.apply);
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd') {
      e.preventDefault();
      const it = flat[i];
      if (it && it.kind === 'page') { toggleFav(it.key); render(); } else toast('Strg+D gilt nur für Seiten und Tabs.');
      return;
    }
    return;
  }
  if (e.key === 'Escape') {
    if (S.ui.modal) S.ui.modal = null;
    else if (S.ui.menu) S.ui.menu = false;
    else if (S.ui.flyout) { const k = S.ui.flyout; S.ui.flyout = null; S.ui.flyoutPinned = false; render(); const b = device.querySelector(`[data-rail="${k}"]`); if (b) b.focus(); return; }
    else if (S.ui.drawer && !S.ui.editing) S.ui.drawer = false;
    else return;
    render();
    return;
  }
  if (e.altKey && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
    const d = e.key === 'ArrowUp' ? -1 : 1;
    const row = document.activeElement && document.activeElement.closest('[data-move], [data-wmove]');
    if (row) {
      e.preventDefault();
      if (row.dataset.wmove) moveWidget(row.dataset.wmove, d);
      else move(row.dataset.move, row.dataset.k, d);
      render();
    }
    return;
  }
  if (e.altKey && !e.ctrlKey && !e.metaKey && /^Digit[0-9]$/.test(e.code)) {
    if (S.ui.modal || isPhoneW() || S.mode !== 'member' || !S.prefs.display.shortcuts) return;
    e.preventDefault();
    const n = Number(e.code.slice(5));
    if (n === 0) { navigate('home'); return; }
    const k = favList('member')[n - 1];
    if (!k) { toast(`Kein Favorit auf Platz ${n}.`); render(); return; }
    if (status(k) !== 'ok') { toast(`„${entryLabel(k)}" ist in ${ctxName()} nicht verfügbar.`); render(); return; }
    navigate(k);
  }
}

let openT = null;
let closeT = null;
function onOver(e) {
  if (navMode() !== 'rail') return;
  const z = e.target.closest('[data-rail], [data-flyout]');
  if (!z) return;
  clearTimeout(closeT);
  const id = z.dataset.rail;
  if (id && S.ui.flyout !== id && !S.ui.flyoutPinned) {
    clearTimeout(openT);
    openT = setTimeout(() => { S.ui.flyout = id; render(); }, 200);
  }
}
function onOut(e) {
  if (navMode() !== 'rail') return;
  const from = e.target.closest('[data-rail], [data-flyout]');
  const to = e.relatedTarget && e.relatedTarget.closest ? e.relatedTarget.closest('[data-rail], [data-flyout]') : null;
  if (from && !to) {
    clearTimeout(openT);
    if (!S.ui.flyoutPinned && S.ui.flyout) closeT = setTimeout(() => { S.ui.flyout = null; render(); }, 300);
  }
}
let lpTimer = null;
function onDown(e) {
  const t = e.target.closest('[data-slot]');
  if (!t) return;
  S.lp = false;
  lpTimer = setTimeout(() => { S.lp = true; S.ui.modal = { type: 'slot', i: Number(t.dataset.slot) }; render(); }, 550);
}
const cancelLp = () => { clearTimeout(lpTimer); if (S.lp) { S.lp = false; S.lpUntil = Date.now() + 400; } };

function renderBar() {
  const opt = (obj, cur) => Object.entries(obj).map(([k, v]) => `<option value="${k}" ${k === cur ? 'selected' : ''}>${esc(typeof v === 'string' ? v : v.label)}</option>`).join('');
  bar.classList.toggle('is-min', S.ui.barMin);
  bar.innerHTML = `<b>PROTOTYP</b>
    <label>Person <select data-pb="persona">${opt(PERSONAS, S.persona)}</select></label>
    <label>Kontext <select data-pb="ctx">${opt(CONTEXTS, S.ctx)}</select></label>
    <button data-pb="device" data-v="desktop" aria-pressed="${S.device === 'desktop'}">Desktop</button><button data-pb="device" data-v="phone" aria-pressed="${S.device === 'phone'}">Telefon</button>
    <button data-pb="sample">Beispiel-Personalisierung</button>
    <button data-pb="login">Anmelden simulieren</button>
    <button data-pb="notes">Notizen &amp; Optionsmatrix</button>
    <button data-pb="state" aria-pressed="${S.ui.statePanel}">Zustand</button>
    <button data-pb="wipe">Neu starten</button>
    <button class="min-toggle" data-pb="min" aria-label="${S.ui.barMin ? 'Leiste aufklappen' : 'Leiste einklappen'}">${S.ui.barMin ? '▲' : '▼'}</button>`;
}
function renderState() {
  statePanel.hidden = !S.ui.statePanel;
  if (!S.ui.statePanel) return;
  const server = { favourites: S.prefs.favourites, hidden: S.prefs.hidden, order: S.prefs.order, tabbar: S.prefs.tabbar, display: S.prefs.display, versions: S.prefs.v };
  const dash = Object.fromEntries(['member', 'admin'].map((m) => [m, { layoutVersion: S.dash[m].v, items: S.dash[m].items.map((i) => `${i.id} ${i.type} [${i.col}] ${JSON.stringify(i.opts)} v${i.v}`) }]));
  statePanel.innerHTML = `<button class="close" data-pb="state">Schließen</button>
    <h3>Server · navigation-Modul (5 Abschnitte)</h3><pre>${esc(JSON.stringify(server, null, 2))}</pre>
    <h3>Server · Dashboard</h3><pre>${esc(JSON.stringify(dash, null, 2))}</pre>
    <h3>Browser · localStorage</h3><pre>${esc(JSON.stringify(S.local, null, 2))}</pre>
    <h3>Abgeleitet</h3><pre>${esc(JSON.stringify({ persona: S.persona, kontext: S.ctx, modus: S.mode, navigation: navMode(), breite: width(), tabLeiste: tabSlots(S.mode).map((s) => (s ? `${s.key}${s.standIn ? ' (Ersatz)' : ''}` : null)) }, null, 2))}</pre>
    <h3>Schreibzugriffe (simuliert)</h3><div class="log">${S.log.map((l) => `<div>${esc(l)}</div>`).join('') || '<div>—</div>'}</div>`;
}
function onBar(e) {
  const t = e.target.closest('[data-pb]');
  if (!t || t.tagName === 'SELECT') return;
  const a = t.dataset.pb;
  if (a === 'device') { S.device = t.dataset.v; S.ui.drawer = false; S.ui.editing = false; S.ui.flyout = null; }
  if (a === 'sample') {
    const want = ['storage', 'orders.demand', 'promotion', 'material.profit', 'hangar.catalog', 'org.lead'].filter((k) => status(k) !== 'role');
    S.prefs.favourites = [...new Set([...S.prefs.favourites, ...want])].slice(0, FAV_CAP);
    S.prefs.hidden = [...new Set([...S.prefs.hidden, 'exchange'])];
    S.local.frequent = { 'missions.list': 9, 'orders.list': 6, refinery: 4, 'material.list': 3 };
    S.local.recent = ['refinery', 'orders.list', 'missions.list'];
    const d = S.dash.member;
    if (!d.items.some((i) => i.type === 'assigned')) { d.items.splice(1, 0, inst('assigned', 'wide'), inst('lowStock', 'wide')); d.items.push(inst('bank', 'narrow')); const pinned = inst('lowStock', 'wide'); pinned.opts.unit = 'alpha'; d.items.push(pinned); }
    d.touched = true;
    logWrite(['favourites', 'hidden'], '/api/v1/navigation (Beispiel)');
    toast('Beispiel geladen: 5–6 Favoriten, Materialbörse ausgeblendet, Widgets ergänzt.');
  }
  if (a === 'login') {
    S.mode = 'member';
    const sp = S.prefs.display.startPage.member;
    const target = sp && status(sp) === 'ok' ? sp : 'home';
    toast(`Angemeldet. Startseite: ${target === 'home' ? 'Dashboard' : entryLabel(target)}${sp && target === 'home' ? ' (gewählte Startseite hier nicht verfügbar)' : ''}`);
    navigate(target);
    return;
  }
  if (a === 'notes') { navigate('proto.notes'); return; }
  if (a === 'state') S.ui.statePanel = !S.ui.statePanel;
  if (a === 'min') S.ui.barMin = !S.ui.barMin;
  if (a === 'wipe') {
    S.prefs = freshPrefs(); S.dash = { member: freshDash('member'), admin: freshDash('admin') }; S.local = freshLocal(); S.ui = { ...freshUi(), statePanel: S.ui.statePanel }; S.log = []; S.mode = 'member';
  }
  render();
}
function onBarChange(e) {
  const t = e.target;
  if (t.dataset.pb === 'persona') {
    S.persona = t.value;
    prune();
    if (status(S.ui.route) === 'role' && !S.ui.route.startsWith('proto') && !S.ui.route.startsWith('ent:')) S.ui.route = homeKey();
    toast(`Person: ${persona().label}`);
  }
  if (t.dataset.pb === 'ctx') { S.ctx = t.value; toast(`Kontext: ${ctxName()}`); }
  render();
}

function notesHtml() {
  const optCell = (w, k) => {
    const o = w.opts.find((x) => x.k === k);
    if (!o) return '<td>—</td>';
    return `<td>${o.values.map(([v, l]) => (String(v) === String(o.def) ? `<b>${esc(l)}</b>` : esc(l))).join(' · ')}</td>`;
  };
  const rows = (mode) => Object.entries(WIDGETS).filter(([, w]) => w.mode === mode).map(([, w]) => `<tr><td><b>${esc(w.label)}</b>${w.fresh ? ' <span class="chip chip--info">sample data</span>' : ''}</td>${optCell(w, 'unit')}${optCell(w, 'range')}${optCell(w, 'rows')}${optCell(w, 'status')}<td>${w.col === 'wide' ? 'wide' : 'narrow'}</td></tr>`).join('');
  const groupIcons = [['star', 'Favoriten'], ['wedge', 'Einsatz & Flotte'], ['box', 'Logistik'], ['trade', 'Handel'], ['users', 'Staffel'], ['bank', 'Kartellbank'], ['more', 'Mehr'], ['users', 'Mitglieder & Organisation (admin)'], ['database', 'Stammdaten (admin)'], ['gear', 'Betrieb (admin)'], ['shield', 'Admin mode toggle (rail foot)'], ['user', 'Avatar (rail foot)'], ['bell', 'Meldungen (tab bar)'], ['menu', 'Menü (tab bar)']];
  return `<div class="proto-page">
    <div class="page-head"><div><div class="page-head__eyebrow">Prototype notes</div><h1 class="page-head__title">What this prototype shows, and what to approve</h1></div></div>
    <h2>Question and shape</h2>
    <p>Ticket: “Clickable prototype of the new navigation”. The concept is already decided on the map, so this is <b>one</b> concept, not competing variants. The floating bar switches the scenarios instead: person (role), active org-unit context, desktop / phone, a sample personalisation, a simulated login (start page) and a state panel that shows the five server sections, the dashboard store, the browser-side state and every simulated write with its version bump. Everything is in memory; “Neu starten” wipes it. Throwaway code: no tests, no i18n bundle, no Thymeleaf.</p>
    <h2>Try this</h2>
    <ul>
      <li>As <b>Mitglied</b>, open the menu: 10 links in 4 task groups, no Kartellbank group. Switch to <b>Bankmitarbeiter</b>: 11 links. Switch to <b>Offizier</b>: still 10 links, with the extra tabs Leitung and Verwaltung.</li>
      <li>Star a page in its header, star a tab (the star beside the active tab), then press <b>Alt+1</b> / <b>Alt+0</b>. Set 12 favourites and watch the star disable.</li>
      <li>Switch the context to <b>SK Bravo</b>: Beförderung disappears from the list, a Beförderung favourite turns grey, the officer tabs vanish, a widget pinned to Staffel Alpha greys out, a tab-bar slot falls back to a stand-in.</li>
      <li>Open <b>Anpassen</b>: reorder groups and links (buttons or Alt+↑/↓, focus stays on the row), hide a page into “Mehr”, configure the tab bar, reset per section. Full reset: Profil → Darstellung.</li>
      <li>Switch the desktop mode to <b>Leiste</b> and <b>Angeheftet</b> (sidebar head, profile or Ctrl+K). Hover a rail icon (200 ms open, 300 ms close), click to pin, Escape to close. Narrow the window below 1280 / 1025 px.</li>
      <li>Press <b>Ctrl+K</b>: empty state Favoriten / Häufig / Zuletzt; type “quan”, “#10”, “@vex”, “mis” then Tab, “?”, “>neu”. Entity hits arrive after ~380 ms; the highlighted row stays highlighted. Ctrl+D favourites the highlighted page.</li>
      <li>Switch the device to <b>Telefon</b>: tab bar with 3 chosen slots + Meldungen + Menü; long-press a slot to change it; choosing a taken entry swaps.</li>
      <li>On the dashboard: <b>Anpassen</b>, add widgets from the catalogue (cap 12, repeatable), open a widget's options, move it within or across the columns.</li>
      <li>As <b>Admin</b>: toggle admin mode (sidebar foot or rail shield): darker header rule, 13 links in 3 collapsed groups, the admin dashboard with the hub widget.</li>
    </ul>
    <h2>Proposal 1 · per-widget option matrix (bold = default)</h2>
    <p>Option kinds are the four decided ones: org unit, time range, row count, status filter. “Aktiver Kontext” follows the header context; a fixed unit greys the widget out where that unit is not in the context.</p>
    <table class="tbl"><thead><tr><th>Member widget</th><th>Einheit</th><th>Zeitraum</th><th>Zeilen</th><th>Status</th><th>Default column</th></tr></thead><tbody>${rows('member')}</tbody></table>
    <table class="tbl" style="margin-top:1rem"><thead><tr><th>Admin widget</th><th>Einheit</th><th>Zeitraum</th><th>Zeilen</th><th>Status</th><th>Default column</th></tr></thead><tbody>${rows('admin')}</tbody></table>
    <h2>Proposal 2 · group icons</h2>
    <p>The sprite has no group icons today. Proposed additions in the sprite's style (24×24, stroke 2, round caps, no fill):</p>
    <div class="icon-grid">${groupIcons.map(([n, l]) => `<div>${icon(n)}<span>${esc(l)}</span></div>`).join('')}</div>
    <h2>Proposal 3 · smaller open points</h2>
    <ul>
      <li><b>Admin tab bar default:</b> Admin-Dashboard (hub), Mitgliederverwaltung, Audit-Logs, then Meldungen and Menü.</li>
      <li><b>Member dashboard default arrangement:</b> wide column Nächste 7 Tage, Information; narrow column Favoriten, Benachrichtigungen. Admin: hub wide, the four admin widgets narrow.</li>
      <li><b>Full reset and the dashboard:</b> the prototype keeps the dashboard out of the profile's full reset and gives the dashboard its own “Standard wiederherstellen” in its edit mode.</li>
      <li><b>Storage tabs:</b> the merged storage page is shown with the tabs Bestand · Mindestbestände.</li>
      <li><b>Personal entries as favourites:</b> Profil, Mein Inventar and Verbundene Anwendungen can be starred and used as a start page, although they stay in the user menu.</li>
      <li><b>Edit controls:</b> 32 px on desktop; on the phone the build has to meet the 44 px touch floor (the prototype does not).</li>
      <li><b>Sample-data widgets:</b> Ich bearbeite, Bestand niedrig and Bank are marked “Beispieldaten”; their data sources come from their own tickets.</li>
    </ul>
    <h2>Not in the prototype</h2>
    <ul><li>Real data, persistence, cross-tab or cross-device sync, live updates, monitoring.</li><li>The external assignee (non-profit account with only “Ich bearbeite”).</li><li>Old-URL redirects and the admin page merges themselves; admin pages are placeholders.</li></ul>
  </div>`;
}

device.addEventListener('click', onClick);
device.addEventListener('change', onChange);
device.addEventListener('mouseover', onOver);
device.addEventListener('mouseout', onOut);
device.addEventListener('pointerdown', onDown);
device.addEventListener('pointerup', cancelLp);
device.addEventListener('pointercancel', cancelLp);
device.addEventListener('pointerleave', (e) => { if (e.target.dataset && e.target.dataset.slot !== undefined) cancelLp(); }, true);
device.addEventListener('contextmenu', (e) => { if (e.target.closest('[data-slot]')) e.preventDefault(); });
device.addEventListener('input', (e) => { if (e.target.closest('.pal-input')) palSetQuery(e.target.value); });
document.addEventListener('keydown', onKey);
bar.addEventListener('click', onBar);
bar.addEventListener('change', onBarChange);
statePanel.addEventListener('click', onBar);
window.addEventListener('resize', () => render());
document.addEventListener('click', (e) => { if (!device.contains(e.target) && !bar.contains(e.target) && !statePanel.contains(e.target) && (S.ui.menu || S.ui.flyout)) { S.ui.menu = false; S.ui.flyout = null; S.ui.flyoutPinned = false; render(); } });
render();
