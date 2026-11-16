/** PROTOTYPE data for building a route stop by stop: invented price ages and availability ratings; nothing is real or stored. */

const PRICE_AGE_H = { thedus: 216, grimhex: 250, hurL2: 60, orison: 130 };
const PRICE_AGE_DEFAULT_H = 3;
const PRICE_STALE_H = 168;
const PRICE_EXPIRED_H = 360;

const AVAIL_RATING = {
  'micL1|Distilled Spirits|sell': { pct: 40, n: 4 },
  'hurL2|Laranite|sell': { pct: 62, n: 3 },
  'newbabbage|Laranite|sell': { pct: 55, n: 2 },
  'arcL1|Distilled Spirits|buy': { pct: 58, n: 3 },
  'lorville|Medical Supplies|sell': { pct: 70, n: 5 },
  'grimhex|Gold|sell': { pct: 35, n: 2 },
};
const AVAIL_WARN_BELOW = 75;

const BUILD_LIST_SIZE = 10;
const BUILD_DEFAULT_START = 'seraphim';
const BUILD_DEFAULT_END = 'everus';
