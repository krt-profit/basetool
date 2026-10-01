import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const req = createRequire('$MAIN_CHECKOUT/frontend/package.json');
const postcss = req('postcss');
const selParser = req('postcss-selector-parser');

const ROOT = '$REPO/frontend/src/main/resources';
const CSS_DIR = path.join(ROOT, 'static/css');
const OUT = process.argv[2];

function walkDir(d) {
    const out = [];
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) out.push(...walkDir(p));
        else if (e.name.endsWith('.css')) out.push(p);
    }
    return out;
}

const PHYSICAL = ['margin-left', 'margin-right', 'margin-top', 'margin-bottom', 'padding-left', 'padding-right', 'padding-top', 'padding-bottom', 'left', 'right', 'top', 'bottom', 'border-left', 'border-right', 'border-top', 'border-bottom', 'border-left-color', 'border-right-color', 'border-left-width', 'border-right-width', 'border-top-left-radius', 'border-top-right-radius', 'border-bottom-left-radius', 'border-bottom-right-radius', 'width', 'height', 'min-width', 'max-width', 'min-height', 'max-height'];
const LOGICAL_RE = /^(margin|padding|border|inset)-(inline|block)(-start|-end)?(-.*)?$|^(inset|inline-size|block-size|min-inline-size|max-inline-size|min-block-size|max-block-size|inset-inline|inset-block)$|^border-(start|end)-(start|end)-radius$/;
const FUNCS = ['color-mix', 'clamp', 'min', 'max', 'calc', 'oklch', 'oklab', 'lab', 'lch', 'hwb', 'rgb', 'rgba', 'hsl', 'hsla', 'light-dark', 'env', 'attr', 'round', 'anchor', 'var', 'linear-gradient', 'radial-gradient', 'conic-gradient', 'repeat', 'minmax', 'fit-content', 'url', 'image-set', 'translate', 'rotate', 'scale', 'cubic-bezier', 'steps'];
const PROPS_OF_INTEREST = ['container-type', 'container-name', 'container', 'aspect-ratio', 'gap', 'row-gap', 'column-gap', 'grid-template-areas', 'grid-template-columns', 'text-wrap', 'accent-color', 'color-scheme', 'scrollbar-gutter', 'scrollbar-color', 'scrollbar-width', 'overscroll-behavior', 'content-visibility', 'contain', 'will-change', 'field-sizing', 'interpolate-size', 'transition-behavior', 'view-transition-name', 'anchor-name', 'position-anchor', 'position-try', 'backdrop-filter', 'inset', 'isolation', 'appearance', 'scroll-behavior', 'scroll-snap-type', 'scroll-margin-top', 'scroll-padding-top', 'touch-action', 'user-select', 'text-overflow', 'hyphens', 'overflow-wrap', 'word-break', 'font-display', 'font-variant-numeric', 'tab-size', 'place-items', 'place-content', 'place-self', 'translate', 'rotate', 'scale', 'animation', 'animation-name', 'transition', 'transition-property', 'caret-color', 'forced-color-adjust', 'print-color-adjust', 'mask', 'mask-image', 'clip-path', 'object-fit', 'resize', 'outline', 'outline-offset'];

const files = walkDir(CSS_DIR).sort();
const results = [];
const tokenDefs = new Map();
const tokenUses = new Map();
for (const f of files) {
    const rel = path.relative(CSS_DIR, f).replace(/\\/g, '/');
    const src = fs.readFileSync(f, 'utf8');
    const root = postcss.parse(src, { from: f });
    const m = {};
    const inc = (k, n = 1) => (m[k] = (m[k] || 0) + n);
    const r = { file: rel, lines: src.split('\n').length, bytes: Buffer.byteLength(src), metrics: m, layerStatement: null, layersUsed: {}, unlayered: 0, comments: [], important: 0, nestedRules: 0, ampersand: 0, mediaParams: {}, pseudo: {}, customPropsDefined: 0, varUses: 0, hex: 0, maxSpecificity: [0, 0, 0], idSelectors: 0 };
    root.walkComments((c) => {
        r.comments.push(c.text.slice(0, 60));
    });
    root.nodes.forEach((n, i) => {
        if (n.type === 'atrule' && n.name === 'layer' && !n.nodes) {
            if (!r.layerStatement) r.layerStatement = { index: i, params: n.params };
        }
    });
    root.walk((node) => {
        let layer = null;
        let p = node.parent;
        let insideRule = false;
        while (p && p.type !== 'root') {
            if (p.type === 'atrule' && p.name === 'layer') layer = p.params;
            if (p.type === 'rule') insideRule = true;
            p = p.parent;
        }
        if (node.type === 'rule') {
            inc('rules');
            if (!layer) {
                let inAtLayer = false;
                let q = node.parent;
                while (q && q.type !== 'root') {
                    if (q.type === 'atrule' && q.name === 'layer') inAtLayer = true;
                    q = q.parent;
                }
                if (!inAtLayer && !(node.parent && node.parent.type === 'atrule' && node.parent.name === 'keyframes')) {
                    let kf = false;
                    let q2 = node.parent;
                    while (q2 && q2.type !== 'root') {
                        if (q2.type === 'atrule' && /keyframes$/.test(q2.name)) kf = true;
                        q2 = q2.parent;
                    }
                    if (!kf) r.unlayered++;
                }
            } else r.layersUsed[layer] = (r.layersUsed[layer] || 0) + 1;
            if (insideRule) r.nestedRules++;
            if (node.selector.includes('&')) r.ampersand++;
            let isKf = false;
            let q3 = node.parent;
            while (q3 && q3.type !== 'root') {
                if (q3.type === 'atrule' && /keyframes$/.test(q3.name)) isKf = true;
                q3 = q3.parent;
            }
            if (!isKf) {
                try {
                    selParser((sels) => {
                        sels.each((sel) => {
                            let a = 0, b = 0, c = 0;
                            const count = (s, zero) => {
                                s.walk((x) => {
                                    if (x.type === 'id') {
                                        if (!zero) a++;
                                        r.idSelectors++;
                                    } else if (x.type === 'class' || x.type === 'attribute') {
                                        if (!zero) b++;
                                    } else if (x.type === 'pseudo') {
                                        const v = x.value.toLowerCase();
                                        r.pseudo[v] = (r.pseudo[v] || 0) + 1;
                                    } else if (x.type === 'tag') {
                                        if (!zero) c++;
                                    }
                                });
                            };
                            count(sel, false);
                            if (a > r.maxSpecificity[0] || (a === r.maxSpecificity[0] && (b > r.maxSpecificity[1] || (b === r.maxSpecificity[1] && c > r.maxSpecificity[2])))) r.maxSpecificity = [a, b, c];
                        });
                    }).processSync(node.selector);
                } catch (e) {
                    inc('selectorParseError');
                }
            }
        } else if (node.type === 'atrule') {
            inc('@' + node.name);
            if (node.name === 'media' || node.name === 'container' || node.name === 'supports') {
                const prm = node.params;
                for (const feat of ['prefers-reduced-motion', 'prefers-color-scheme', 'prefers-contrast', 'forced-colors', 'hover', 'pointer', 'any-pointer', 'print', 'orientation', 'display-mode', 'max-width', 'min-width', 'max-height', 'min-height']) {
                    if (prm.includes(feat)) r.mediaParams[feat] = (r.mediaParams[feat] || 0) + 1;
                }
                if (/\(\s*(width|height|inline-size)\s*[<>]=?/.test(prm) || /[<>]=?\s*(width|height)\s*\)/.test(prm)) r.mediaParams['range-syntax'] = (r.mediaParams['range-syntax'] || 0) + 1;
            }
            if (insideRule) inc('nestedAtRule');
        } else if (node.type === 'decl') {
            inc('decls');
            const prop = node.prop.toLowerCase();
            if (node.important) r.important++;
            if (prop.startsWith('--')) {
                r.customPropsDefined++;
                if (!tokenDefs.has(prop)) tokenDefs.set(prop, []);
                tokenDefs.get(prop).push(rel);
            }
            if (PHYSICAL.includes(prop)) inc('phys:' + prop);
            if (LOGICAL_RE.test(prop)) inc('logical:' + prop);
            if (PROPS_OF_INTEREST.includes(prop)) inc('prop:' + prop);
            if (prop.startsWith('-webkit-') || prop.startsWith('-moz-') || prop.startsWith('-ms-')) inc('vendorProp');
            const v = node.value;
            if (/-webkit-|-moz-/.test(v)) inc('vendorValue');
            for (const fn of FUNCS) {
                const re = new RegExp('(^|[^a-zA-Z-])' + fn.replace('-', '\\-') + '\\(', 'g');
                const c = (v.match(re) || []).length;
                if (c) inc('fn:' + fn, c);
            }
            for (const u of v.matchAll(/var\(\s*(--[\w-]+)/g)) {
                r.varUses++;
                tokenUses.set(u[1], (tokenUses.get(u[1]) || 0) + 1);
            }
            r.hex += (v.match(/#[0-9a-fA-F]{3,8}\b/g) || []).length;
            if (/\b(dvh|svh|lvh|dvw|svw|lvw)\b/.test(v) || /\d(dvh|svh|lvh|dvw)\b/.test(v)) inc('unit:dynamic-viewport');
            if (/\d(vh|vw)\b/.test(v)) inc('unit:vh/vw');
            if (/\d(cqi|cqw|cqh|cqb|cqmin|cqmax)\b/.test(v)) inc('unit:cq');
            if (/\d(rem)\b/.test(v)) inc('unit:rem');
            if (/\dpx\b/.test(v)) inc('unit:px');
            if (/\dch\b/.test(v)) inc('unit:ch');
            if (prop === 'display') inc('display:' + v.trim());
            if (prop === 'text-align' && /\b(left|right)\b/.test(v)) inc('text-align:physical');
            if (prop === 'text-align' && /\b(start|end)\b/.test(v)) inc('text-align:logical');
            if (/subgrid/.test(v)) inc('subgrid');
            if (/\bfrom\s+(#|rgb|hsl|oklch|var)/.test(v) && /(rgb|hsl|oklch|oklab|color)\(\s*from/.test(v)) inc('relativeColor');
            if (prop === 'transition' || prop === 'animation' || prop === 'animation-name' || prop === 'transition-property') inc('motion-decl');
        }
    });
    results.push(r);
}

const allText = [];
for (const d of ['static/js', 'templates']) {
    const walk = (dd) => {
        for (const e of fs.readdirSync(dd, { withFileTypes: true })) {
            const p = path.join(dd, e.name);
            if (e.isDirectory()) walk(p);
            else allText.push(fs.readFileSync(p, 'utf8'));
        }
    };
    walk(path.join(ROOT, d));
}
const bigText = allText.join('\n');
const deadTokens = [];
for (const [tok, defs] of tokenDefs.entries()) {
    const cssUses = tokenUses.get(tok) || 0;
    const other = bigText.split(tok).length - 1;
    if (cssUses === 0 && other === 0) deadTokens.push({ tok, defs });
}
const undefinedTokens = [];
for (const [tok, n] of tokenUses.entries()) {
    if (!tokenDefs.has(tok)) undefinedTokens.push({ tok, uses: n, inJsOrTpl: bigText.includes(tok + ':') || bigText.includes("'" + tok + "'") });
}

const mig = fs.readFileSync(path.join(CSS_DIR, 'inline-migration.css'), 'utf8');
const migClasses = [...new Set([...mig.matchAll(/\.(krtm-[\w-]+)/g)].map((x) => x[1]))];
const deadMig = migClasses.filter((c) => !bigText.includes(c));
fs.writeFileSync(OUT, JSON.stringify({ results, tokenDefsCount: tokenDefs.size, tokenUsesDistinct: tokenUses.size, deadTokens, undefinedTokens, migClasses: migClasses.length, deadMig }, null, 1));
console.log('css files', results.length, 'tokens', tokenDefs.size, 'dead tokens', deadTokens.length, 'undefined', undefinedTokens.length, 'krtm classes', migClasses.length, 'dead krtm', deadMig.length);
