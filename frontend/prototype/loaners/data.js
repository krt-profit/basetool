/**
 * Ship types of the prototype catalogue. `kind` is `flight` (offered game type), `concept`
 * (not flight ready) or `notInGame` (no P4K GUID, not a concept ship); `status` is RSI's status from
 * p4kx, absent when only the matrix or the override marks the type.
 * @type {Record<string, {maker: string, name: string, kind: string, status?: string, guid?: boolean, override?: string, p4kx?: string, matrix?: boolean}>}
 */
const TYPES = {
  avenger: { maker: 'Aegis', name: 'Avenger Titan', kind: 'flight', guid: true },
  idrisp: { maker: 'Aegis', name: 'Idris-P', kind: 'flight', guid: true },
  javelin: { maker: 'Aegis', name: 'Javelin', kind: 'concept', status: 'IN_CONCEPT', guid: true, p4kx: 'IN_CONCEPT', matrix: true },
  arrow: { maker: 'Anvil', name: 'Arrow', kind: 'flight', guid: true },
  carrack: { maker: 'Anvil', name: 'Carrack', kind: 'flight', guid: true },
  mpuv: { maker: 'Argo', name: 'MPUV Cargo', kind: 'flight', guid: true },
  pioneer: { maker: 'Consolidated Outland', name: 'Pioneer', kind: 'concept', status: 'IN_CONCEPT', p4kx: 'IN_CONCEPT', matrix: true },
  buccaneer: { maker: 'Drake', name: 'Buccaneer', kind: 'flight', guid: true },
  cutlass: { maker: 'Drake', name: 'Cutlass Black', kind: 'flight', guid: true },
  ironclad: { maker: 'Drake', name: 'Ironclad Assault', kind: 'flight', guid: true },
  kraken: { maker: 'Drake', name: 'Kraken', kind: 'concept', status: 'IN_CONCEPT', p4kx: 'IN_CONCEPT', matrix: true },
  endeavor: { maker: 'MISC', name: 'Endeavor', kind: 'concept', matrix: true },
  hullc: { maker: 'MISC', name: 'Hull C', kind: 'flight', guid: true },
  hulld: { maker: 'MISC', name: 'Hull D', kind: 'concept', status: 'IN_PRODUCTION', p4kx: 'IN_PRODUCTION', matrix: true },
  hulle: { maker: 'MISC', name: 'Hull E', kind: 'concept', status: 'IN_CONCEPT', p4kx: 'IN_CONCEPT', matrix: true },
  galaxy: { maker: 'RSI', name: 'Galaxy', kind: 'flight', guid: true, p4kx: 'IN_CONCEPT', override: 'FLIGHT' },
  phoenixEm: { maker: 'RSI', name: 'Constellation Phoenix Emerald', kind: 'notInGame' },
  retaliator: { maker: 'Aegis', name: 'Retaliator Bomber', kind: 'notInGame' },
  polaris: { maker: 'RSI', name: 'Polaris', kind: 'flight', guid: true },
};

/**
 * Effective pairs of the applied revision plus the administrator's overrides: granting type →
 * loaner types with the source of each pair (`article`, `own`, `off`) and its flags.
 * @type {Record<string, Array<{loaner: string, source: string, flag?: string}>>}
 */
const PAIRS = {
  javelin: [{ loaner: 'idrisp', source: 'article' }, { loaner: 'mpuv', source: 'article' }],
  kraken: [{ loaner: 'polaris', source: 'article' }, { loaner: 'ironclad', source: 'article' }, { loaner: 'buccaneer', source: 'article' }],
  hulld: [{ loaner: 'hullc', source: 'article' }],
  hulle: [{ loaner: 'hullc', source: 'article' }, { loaner: 'arrow', source: 'own', flag: 'covered' }, { loaner: 'retaliator', source: 'article', flag: 'resting' }],
  pioneer: [{ loaner: 'hullc', source: 'article' }, { loaner: 'carrack', source: 'off' }],
  endeavor: [{ loaner: 'carrack', source: 'article' }],
};

/** Ordered list of locations the pickers offer. */
const LOCATIONS = ['Area18', 'Lorville', 'New Babbage', 'Orison', 'Everus Harbor', 'Seraphim Station'];

/** Org units the viewer may stamp, by shorthand. */
const UNITS = { ALP: 'Alpha-Staffel', BRV: 'Bravo-Staffel', SAR: 'Search and Rescue' };

/**
 * The viewer's own ships in „Meine Schiffe".
 * `formerLoaner` marks an import-locked ship without a pledge id whose type the member now holds as
 * a loaner: a candidate of „Im Hangar, vermutlich frühere Loaner".
 * @type {Array<{id: number, type: string, name: string|null, origin: string|null, ins: string|null, loc: string|null, fitted: boolean, unit: string|null, pledge?: string, formerLoaner?: boolean}>}
 */
const MY_SHIPS = [
  { id: 1, type: 'avenger', name: 'Lastesel', origin: 'IN_GAME', ins: '6', loc: 'Area18', fitted: true, unit: 'ALP' },
  { id: 2, type: 'javelin', name: 'Schwerer Gang', origin: 'PLEDGE', ins: 'LTI', loc: null, fitted: false, unit: 'ALP', pledge: 'Pledge #40211873 · 03.08.2019' },
  { id: 3, type: 'arrow', name: null, origin: 'PLEDGE', ins: 'LTI', loc: 'Lorville', fitted: true, unit: 'ALP' },
  { id: 4, type: 'cutlass', name: 'Rostlaube', origin: 'PLEDGE', ins: '120', loc: 'Area18', fitted: false, unit: 'BRV' },
  { id: 5, type: 'kraken', name: 'Mutterschiff', origin: 'PLEDGE', ins: 'LTI', loc: null, fitted: false, unit: 'ALP', pledge: 'Pledge #51290044 · 22.11.2020' },
  { id: 6, type: 'hulld', name: null, origin: 'PLEDGE', ins: 'LTI', loc: null, fitted: false, unit: 'ALP' },
  { id: 7, type: 'hulle', name: null, origin: 'PLEDGE', ins: 'LTI', loc: null, fitted: false, unit: 'BRV' },
  { id: 8, type: 'pioneer', name: 'Baustelle', origin: 'PLEDGE', ins: 'LTI', loc: null, fitted: false, unit: 'ALP' },
  { id: 9, type: 'phoenixEm', name: null, origin: 'PLEDGE', ins: 'LTI', loc: null, fitted: false, unit: 'ALP' },
  { id: 10, type: 'mpuv', name: null, origin: 'PLEDGE', ins: null, loc: 'Area18', fitted: false, unit: 'ALP', formerLoaner: true },
  { id: 11, type: 'buccaneer', name: null, origin: 'PLEDGE', ins: null, loc: 'Area18', fitted: false, unit: 'ALP', formerLoaner: true },
];

/**
 * The viewer's loaner state per loaner type: what a member may change (location, fitted, unit,
 * hidden). Which loaners are held follows from MY_SHIPS and PAIRS.
 * @type {Record<string, {loc: string|null, fitted: boolean, unit: string|null, hidden: boolean}>}
 */
const MY_LOANER_STATE = {
  idrisp: { loc: 'Area18', fitted: false, unit: 'ALP', hidden: false },
  mpuv: { loc: 'Area18', fitted: true, unit: 'ALP', hidden: false },
  arrow: { loc: 'Area18', fitted: true, unit: null, hidden: false },
  polaris: { loc: 'Area18', fitted: true, unit: 'ALP', hidden: false },
  ironclad: { loc: 'Area18', fitted: false, unit: 'ALP', hidden: true },
  buccaneer: { loc: 'Area18', fitted: false, unit: 'ALP', hidden: false },
  hullc: { loc: 'Area18', fitted: false, unit: null, hidden: false },
};

/**
 * Entries of the unit overview of ALP beyond the viewer's: owner, type, loaner flag, fitted,
 * location, and for a loaner its granting types (shown only in own and admin hangars).
 * @type {Array<{owner: string, type: string, loaner?: boolean, fitted: boolean, loc: string|null}>}
 */
const UNIT_OTHERS = [
  { owner: 'Vex', type: 'cutlass', fitted: true, loc: 'Area18' },
  { owner: 'Vex', type: 'polaris', loaner: true, fitted: true, loc: 'Orison' },
  { owner: 'Vex', type: 'kraken', fitted: false, loc: null },
  { owner: 'Morrow', type: 'arrow', fitted: true, loc: 'New Babbage' },
  { owner: 'Morrow', type: 'arrow', fitted: false, loc: 'New Babbage' },
  { owner: 'Morrow', type: 'hullc', loaner: true, fitted: false, loc: 'Lorville' },
  { owner: 'Morrow', type: 'hulld', fitted: false, loc: null },
  { owner: 'Talon', type: 'carrack', fitted: true, loc: 'Seraphim Station' },
  { owner: 'Talon', type: 'carrack', loaner: true, fitted: false, loc: 'Seraphim Station' },
  { owner: 'Talon', type: 'endeavor', fitted: false, loc: null },
  { owner: 'Talon', type: 'cutlass', fitted: false, loc: 'Orison' },
  { owner: 'Talon', type: 'mpuv', fitted: true, loc: 'Everus Harbor' },
];

/** The viewer's display name. */
const ME = 'Kestrel';

/** The member whose hangar the admin view shows. */
const ADMIN_MEMBER = 'Vex';
