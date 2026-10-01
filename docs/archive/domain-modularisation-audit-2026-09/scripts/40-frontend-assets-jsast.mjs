import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const req = createRequire('$MAIN_CHECKOUT/frontend/package.json');
const espree = req('espree');
const eslintScope = req('eslint-scope');
const { KEYS } = req('eslint-visitor-keys');
const globalsPkg = req('globals');

const ROOT = '$REPO/frontend';
const JS_DIR = path.join(ROOT, 'src/main/resources/static/js');
const BOOT_DTS = path.join(ROOT, 'types/thymeleaf-bootstrap.d.ts');
const GLOBALS_DTS = path.join(ROOT, 'types/globals.d.ts');
const OUT = process.argv[2];

const knownGlobals = new Set();
for (const [k, v] of Object.entries(globalsPkg)) {
    if (k === 'browser' || k === 'builtin' || /^es20\d\d$/.test(k) || k === 'es5' || k === 'es2015') {
        for (const name of Object.keys(v)) knownGlobals.add(name);
    }
}

const bootNames = new Set();
{
    const t = fs.readFileSync(BOOT_DTS, 'utf8');
    for (const m of t.matchAll(/declare\s+(?:const|let|var|function)\s+([A-Za-z_$][\w$]*)/g)) bootNames.add(m[1]);
}
const globalsDtsNames = new Set();
{
    const t = fs.readFileSync(GLOBALS_DTS, 'utf8');
    for (const m of t.matchAll(/declare\s+(?:const|let|var|function)\s+([A-Za-z_$][\w$]*)/g)) globalsDtsNames.add(m[1]);
    const w = t.indexOf('interface Window {');
    if (w >= 0) {
        let depth = 0;
        let i = t.indexOf('{', w);
        const start = i;
        for (; i < t.length; i++) {
            if (t[i] === '{') depth++;
            else if (t[i] === '}') {
                depth--;
                if (depth === 0) break;
            }
        }
        const body = t.slice(start + 1, i);
        for (const m of body.matchAll(/^\s{4}(?:readonly\s+)?([A-Za-z_$][\w$]*)\??\s*[:(]/gm)) globalsDtsNames.add('window.' + m[1]);
    }
}

const INSTANCE_METHODS = [
    'at', 'toSorted', 'toReversed', 'toSpliced', 'with', 'findLast', 'findLastIndex', 'flat', 'flatMap',
    'includes', 'replaceAll', 'matchAll', 'padStart', 'padEnd', 'trimStart', 'trimEnd',
    'union', 'intersection', 'difference', 'symmetricDifference', 'isSubsetOf', 'isSupersetOf', 'isDisjointFrom',
    'hasOwnProperty', 'substr', 'forEach', 'map', 'filter', 'reduce', 'some', 'every', 'find', 'findIndex',
    'sort', 'reverse', 'closest', 'matches', 'querySelector', 'querySelectorAll', 'getElementById',
    'getElementsByClassName', 'getElementsByTagName', 'addEventListener', 'removeEventListener', 'dispatchEvent',
    'insertAdjacentHTML', 'insertAdjacentElement', 'insertAdjacentText', 'replaceChildren', 'replaceWith',
    'append', 'prepend', 'before', 'after', 'remove', 'appendChild', 'removeChild', 'insertBefore', 'replaceChild',
    'cloneNode', 'createElement', 'createTextNode', 'createDocumentFragment', 'setAttribute', 'getAttribute',
    'toggleAttribute', 'hasAttribute', 'removeAttribute', 'showModal', 'requestSubmit', 'checkVisibility',
    'scrollIntoView', 'focus', 'animate', 'then', 'catch', 'finally', 'bind', 'call', 'apply',
    'toLocaleString', 'toLocaleDateString', 'toLocaleTimeString', 'toFixed', 'localeCompare', 'parseFromString',
    'createContextualFragment', 'setHTMLUnsafe', 'setHTML', 'write', 'writeln', 'execCommand', 'writeText',
    'showPopover', 'hidePopover', 'togglePopover', 'setProperty', 'startViewTransition', 'toArray', 'drop', 'take',
];
const STATIC_CALLS = [
    'Object.hasOwn', 'Object.groupBy', 'Map.groupBy', 'Promise.withResolvers', 'Promise.all', 'Promise.allSettled',
    'Promise.any', 'Promise.race', 'Promise.resolve', 'Promise.reject', 'Array.from', 'Array.isArray', 'Array.of',
    'Object.entries', 'Object.fromEntries', 'Object.assign', 'Object.keys', 'Object.values', 'Object.freeze',
    'Object.defineProperty', 'Object.create', 'JSON.parse', 'JSON.stringify', 'AbortSignal.timeout', 'AbortSignal.any',
    'crypto.randomUUID', 'crypto.getRandomValues', 'CSS.escape', 'Iterator.from', 'Number.isNaN', 'Number.isFinite',
    'Number.parseFloat', 'Number.parseInt', 'Date.now', 'Math.max', 'Math.min', 'Math.round', 'Intl.DateTimeFormat',
    'Intl.NumberFormat', 'String.raw', 'Array.prototype.slice.call', 'Object.prototype.hasOwnProperty.call',
    'document.execCommand', 'navigator.clipboard.writeText', 'navigator.sendBeacon', 'history.replaceState',
    'history.pushState', 'window.history.replaceState', 'window.history.pushState', 'document.startViewTransition',
];
const GLOBAL_FNS = [
    'structuredClone', 'queueMicrotask', 'requestAnimationFrame', 'requestIdleCallback', 'setTimeout', 'setInterval',
    'clearTimeout', 'clearInterval', 'fetch', 'eval', 'alert', 'confirm', 'prompt', 'parseInt', 'parseFloat', 'isNaN',
    'isFinite', 'escape', 'unescape', 'escapeHtml', 'escapeAttr', 'encodeURIComponent', 'encodeURI', 'decodeURIComponent',
];
const NEW_CTORS = [
    'AbortController', 'XMLHttpRequest', 'EventSource', 'WebSocket', 'Promise', 'Map', 'Set', 'WeakMap', 'WeakSet',
    'WeakRef', 'FinalizationRegistry', 'URL', 'URLSearchParams', 'FormData', 'CustomEvent', 'Event', 'MutationObserver',
    'IntersectionObserver', 'ResizeObserver', 'Date', 'RegExp', 'Function', 'Blob', 'DOMParser', 'BroadcastChannel',
    'Worker', 'SharedWorker', 'Headers', 'Request', 'Response', 'Error', 'TypeError', 'Image', 'Option', 'File',
    'FileReader', 'TextEncoder', 'TextDecoder',
];

function norm(s) {
    return s.replace(/\s+/g, '');
}

function propName(m) {
    if (!m || m.type !== 'MemberExpression') return null;
    if (!m.computed && m.property.type === 'Identifier') return m.property.name;
    if (!m.computed && m.property.type === 'PrivateIdentifier') return '#' + m.property.name;
    if (m.computed && m.property.type === 'Literal' && typeof m.property.value === 'string') return m.property.value;
    return null;
}

function memberPath(n) {
    const parts = [];
    let cur = n;
    while (cur) {
        if (cur.type === 'MemberExpression') {
            const p = propName(cur);
            if (p === null) return null;
            parts.unshift(p);
            cur = cur.object;
        } else if (cur.type === 'Identifier') {
            parts.unshift(cur.name);
            return parts.join('.');
        } else if (cur.type === 'ThisExpression') {
            parts.unshift('this');
            return parts.join('.');
        } else if (cur.type === 'ChainExpression') {
            cur = cur.expression;
        } else {
            return null;
        }
    }
    return null;
}

function calleeName(call) {
    let c = call.callee;
    if (c.type === 'ChainExpression') c = c.expression;
    if (c.type === 'Identifier') return c.name;
    if (c.type === 'MemberExpression') return propName(c);
    return null;
}

function calleePath(call) {
    let c = call.callee;
    if (c.type === 'ChainExpression') c = c.expression;
    return memberPath(c);
}

function returnsOf(fn) {
    if (!fn) return [];
    if (fn.type === 'ArrowFunctionExpression' && fn.expression) return [fn.body];
    if (fn.type === 'ArrowFunctionExpression' || fn.type === 'FunctionExpression') {
        const out = [];
        const visit = (n) => {
            if (!n || typeof n.type !== 'string') return;
            if (n !== fn && (n.type === 'FunctionExpression' || n.type === 'FunctionDeclaration' || n.type === 'ArrowFunctionExpression')) return;
            if (n.type === 'ReturnStatement' && n.argument) out.push(n.argument);
            for (const k of KEYS[n.type] || []) {
                const ch = n[k];
                if (Array.isArray(ch)) ch.forEach(visit);
                else visit(ch);
            }
        };
        visit(fn.body);
        return out;
    }
    return [];
}

function sinkLeaves(n, acc, depth = 0) {
    if (!n || depth > 40) {
        acc.push('other');
        return;
    }
    switch (n.type) {
        case 'Literal':
            acc.push(typeof n.value === 'string' ? 'static' : 'static-nonstring');
            return;
        case 'TemplateLiteral':
            acc.push('static');
            for (const e of n.expressions) sinkLeaves(e, acc, depth + 1);
            return;
        case 'BinaryExpression':
            if (n.operator === '+') {
                sinkLeaves(n.left, acc, depth + 1);
                sinkLeaves(n.right, acc, depth + 1);
            } else acc.push('arith');
            return;
        case 'ConditionalExpression':
            sinkLeaves(n.consequent, acc, depth + 1);
            sinkLeaves(n.alternate, acc, depth + 1);
            return;
        case 'LogicalExpression':
            sinkLeaves(n.left, acc, depth + 1);
            sinkLeaves(n.right, acc, depth + 1);
            return;
        case 'CallExpression': {
            const name = calleeName(n);
            if (name === 'escapeHtml' || name === 'escapeAttr') {
                acc.push('escaped');
                return;
            }
            if (name === 'join' && n.callee.type === 'MemberExpression') {
                const obj = n.callee.object;
                if (obj.type === 'CallExpression' && calleeName(obj) === 'map' && obj.arguments[0]) {
                    const rs = returnsOf(obj.arguments[0]);
                    if (rs.length === 0) acc.push('call:map-join-unknown');
                    for (const r of rs) sinkLeaves(r, acc, depth + 1);
                    return;
                }
                acc.push('call:join');
                return;
            }
            if (['toFixed', 'toLocaleString', 'toLocaleDateString', 'toLocaleTimeString', 'String', 'Number'].includes(name)) {
                acc.push('call:' + name);
                return;
            }
            acc.push('call:' + (name || '?'));
            return;
        }
        case 'Identifier':
            acc.push('identifier');
            return;
        case 'MemberExpression':
            acc.push('member');
            return;
        case 'UnaryExpression':
        case 'UpdateExpression':
            acc.push('arith');
            return;
        case 'ChainExpression':
            sinkLeaves(n.expression, acc, depth + 1);
            return;
        default:
            acc.push(n.type);
    }
}

function classifySink(expr) {
    const acc = [];
    sinkLeaves(expr, acc);
    const dyn = acc.filter((x) => x !== 'static' && x !== 'static-nonstring' && x !== 'escaped' && x !== 'arith');
    const esc = acc.filter((x) => x === 'escaped').length;
    let cls;
    if (expr && expr.type === 'Literal' && expr.value === '') cls = 'empty';
    else if (dyn.length === 0 && esc === 0) cls = 'static';
    else if (dyn.length === 0) cls = 'escaped';
    else cls = 'unescaped-dynamic';
    return { cls, leaves: acc };
}

function parseFile(src) {
    const opts = { ecmaVersion: 'latest', comment: true, tokens: true, range: true, loc: true };
    try {
        return { ast: espree.parse(src, { ...opts, sourceType: 'script' }), sourceType: 'script' };
    } catch (e) {
        return { ast: espree.parse(src, { ...opts, sourceType: 'module' }), sourceType: 'module' };
    }
}

const files = fs.readdirSync(JS_DIR).filter((f) => f.endsWith('.js')).sort();
const results = [];
for (const f of files) {
    const full = path.join(JS_DIR, f);
    const src = fs.readFileSync(full, 'utf8');
    const { ast, sourceType } = parseFile(src);
    const m = {};
    const inc = (k, n = 1) => (m[k] = (m[k] || 0) + n);
    const r = {
        file: f,
        lines: src.split('\n').length - (src.endsWith('\n') ? 1 : 0),
        bytes: Buffer.byteLength(src, 'utf8'),
        sourceType,
        tsCheck: false,
        globalHeader: [],
        exportedHeader: [],
        comments: { jsdoc: 0, jsdocLines: 0, lineProse: 0, blockProse: 0, directives: 0, typeCasts: 0, eslintDisable: [] },
        topLevel: { functions: [], classes: [], vars: [], lets: [], consts: [], iife: 0, otherStatements: 0, wrappedInIife: false },
        useStrictFile: false,
        useStrictFn: 0,
        metrics: m,
        sinks: [],
        fetchCalls: [],
        windowWrites: new Set(),
        windowReads: new Set(),
        krtApiUse: {},
        krtEventsActions: [],
        through: [],
        implicitGlobals: [],
        candidates: { optChain: [], nullish: [], atMinus1: [], hasOwnProperty: [], jsonClone: [], sliceSort: [], indexOfCmp: [], concatStr: 0, forIndex: 0, objectAssignEmpty: 0, promiseCtor: 0, thenCalls: 0, awaitExpr: 0, selfThis: 0, bindThis: 0, fnExprCallbackNoThis: 0, styleDisplayWrites: 0, styleWrites: 0, setAttrStyle: 0, keyCode: 0 },
    };

    const firstCodeStart = ast.body.length ? ast.body[0].range[0] : src.length;
    r.licenseHeader = /SPDX-License-Identifier/.test(src.slice(0, 1200));
    for (const c of ast.comments) {
        const v = c.value;
        const trimmed = v.trim();
        if (c.type === 'Block' && /SPDX-License-Identifier/.test(v)) {
            r.comments.license = (r.comments.license || 0) + 1;
            continue;
        }
        if (c.type === 'Line') {
            if (/^\s*@ts-(check|nocheck|expect-error|ignore)/.test(v)) {
                r.comments.directives++;
                if (/^\s*@ts-check/.test(v) && c.range[1] <= firstCodeStart) {
                    r.tsCheck = true;
                    r.tsCheckLine = c.loc.start.line;
                }
            } else if (/^\s*eslint-(disable|enable)/.test(v)) {
                r.comments.directives++;
                r.comments.eslintDisable.push(trimmed);
            } else r.comments.lineProse++;
        } else {
            if (v.startsWith('*')) {
                if (/^\*\s*@type\s*\{/.test(v) && !v.includes('\n')) r.comments.typeCasts++;
                else {
                    r.comments.jsdoc++;
                    r.comments.jsdocLines += v.split('\n').length;
                }
            } else if (/^\s*global\s/.test(v)) {
                r.comments.directives++;
                r.globalHeader.push(...trimmed.replace(/^global\s+/, '').split(/[,\s]+/).map((s) => s.split(':')[0]).filter(Boolean));
            } else if (/^\s*exported\s/.test(v)) {
                r.comments.directives++;
                r.exportedHeader.push(...trimmed.replace(/^exported\s+/, '').split(/[,\s]+/).filter(Boolean));
            } else if (/^\s*eslint[\s-]/.test(v)) {
                r.comments.directives++;
                r.comments.eslintDisable.push(trimmed);
            } else if (/^\s*@ts-/.test(v)) r.comments.directives++;
            else r.comments.blockProse++;
        }
    }

    const setParents = (n, p) => {
        n.parent = p;
        for (const k of KEYS[n.type] || []) {
            const ch = n[k];
            if (Array.isArray(ch)) {
                for (const c of ch) if (c && typeof c.type === 'string') setParents(c, n);
            } else if (ch && typeof ch.type === 'string') setParents(ch, n);
        }
    };
    setParents(ast, null);

    if (ast.body.length && ast.body[0].type === 'ExpressionStatement' && ast.body[0].directive === 'use strict') r.useStrictFile = true;
    let iifeAliases = new Set();
    for (const st of ast.body) {
        if (st.type === 'FunctionDeclaration') r.topLevel.functions.push(st.id.name);
        else if (st.type === 'ClassDeclaration') r.topLevel.classes.push(st.id.name);
        else if (st.type === 'VariableDeclaration') {
            const names = st.declarations.flatMap((d) => (d.id.type === 'Identifier' ? [d.id.name] : ['<pattern>']));
            if (st.kind === 'var') r.topLevel.vars.push(...names);
            else if (st.kind === 'let') r.topLevel.lets.push(...names);
            else r.topLevel.consts.push(...names);
        } else if (st.type === 'ExpressionStatement') {
            let e = st.expression;
            if (e.type === 'UnaryExpression') e = e.argument;
            if (e.type === 'CallExpression' && (e.callee.type === 'FunctionExpression' || e.callee.type === 'ArrowFunctionExpression')) {
                r.topLevel.iife++;
                e.callee.params.forEach((p, i) => {
                    const a = e.arguments[i];
                    if (p.type === 'Identifier' && a && a.type === 'Identifier' && (a.name === 'window' || a.name === 'globalThis' || a.name === 'self')) iifeAliases.add(p.name);
                });
            } else if (!st.directive) r.topLevel.otherStatements++;
        } else r.topLevel.otherStatements++;
    }
    const nonDirective = ast.body.filter((s) => !(s.type === 'ExpressionStatement' && s.directive));
    r.topLevel.wrappedInIife = nonDirective.length > 0 && nonDirective.every((s) => {
        if (s.type !== 'ExpressionStatement') return false;
        let e = s.expression;
        if (e.type === 'UnaryExpression') e = e.argument;
        return e.type === 'CallExpression' && (e.callee.type === 'FunctionExpression' || e.callee.type === 'ArrowFunctionExpression');
    });

    const isWindowObj = (o) => o && o.type === 'Identifier' && (o.name === 'window' || iifeAliases.has(o.name));

    const visit = (n) => {
        inc('node:' + n.type);
        switch (n.type) {
            case 'VariableDeclaration':
                inc('decl:' + n.kind);
                break;
            case 'FunctionDeclaration':
            case 'FunctionExpression':
            case 'ArrowFunctionExpression':
                if (n.async) inc('fn:async');
                if (n.generator) inc('fn:generator');
                if (n.type === 'FunctionExpression' && n.parent && n.parent.type === 'CallExpression' && n.parent.arguments.includes(n)) {
                    let usesThis = false;
                    const scan = (x) => {
                        if (!x || typeof x.type !== 'string' || usesThis) return;
                        if (x !== n && (x.type === 'FunctionExpression' || x.type === 'FunctionDeclaration')) return;
                        if (x.type === 'ThisExpression' || (x.type === 'Identifier' && x.name === 'arguments')) {
                            usesThis = true;
                            return;
                        }
                        for (const k of KEYS[x.type] || []) {
                            const ch = x[k];
                            if (Array.isArray(ch)) ch.forEach(scan);
                            else scan(ch);
                        }
                    };
                    scan(n.body);
                    if (!usesThis) r.candidates.fnExprCallbackNoThis++;
                }
                if (n.body && n.body.type === 'BlockStatement' && n.body.body.length && n.body.body[0].directive === 'use strict') r.useStrictFn++;
                break;
            case 'ClassDeclaration':
            case 'ClassExpression':
                inc('class');
                break;
            case 'PrivateIdentifier':
                inc('privateName');
                break;
            case 'PropertyDefinition':
                inc('classField');
                break;
            case 'StaticBlock':
                inc('staticBlock');
                break;
            case 'ChainExpression':
                inc('optionalChain');
                break;
            case 'LogicalExpression':
                inc('logical:' + n.operator);
                if (n.operator === '&&') {
                    const l = norm(src.slice(n.left.range[0], n.left.range[1]));
                    let rt = n.right;
                    if (rt.type === 'ChainExpression') rt = rt.expression;
                    if (rt.type === 'MemberExpression' || rt.type === 'CallExpression') {
                        let base = rt.type === 'CallExpression' ? rt.callee : rt;
                        let found = false;
                        while (base && (base.type === 'MemberExpression' || base.type === 'CallExpression')) {
                            const obj = base.type === 'MemberExpression' ? base.object : base.callee;
                            if (norm(src.slice(obj.range[0], obj.range[1])) === l) {
                                found = true;
                                break;
                            }
                            base = obj;
                        }
                        if (found && (n.left.type === 'Identifier' || n.left.type === 'MemberExpression')) r.candidates.optChain.push(n.loc.start.line);
                    }
                }
                break;
            case 'AssignmentExpression':
                inc('assign:' + n.operator);
                if (n.left.type === 'MemberExpression') {
                    const p = propName(n.left);
                    if (p === 'innerHTML' || p === 'outerHTML' || p === 'srcdoc') {
                        const c = classifySink(n.right);
                        r.sinks.push({ kind: p + (n.operator === '+=' ? '+=' : ''), line: n.loc.start.line, cls: c.cls, leaves: [...new Set(c.leaves)] });
                    }
                    if (p === 'textContent' || p === 'innerText') inc('assign:' + p);
                    if (n.left.object.type === 'MemberExpression' && propName(n.left.object) === 'style') {
                        r.candidates.styleWrites++;
                        if (p === 'display') r.candidates.styleDisplayWrites++;
                    }
                    if (p === 'cssText') inc('assign:cssText');
                    if (isWindowObj(n.left.object) && !n.left.computed) r.windowWrites.add(n.left.property.name);
                }
                break;
            case 'MemberExpression': {
                const p = propName(n);
                if (p === 'keyCode' || p === 'which' || p === 'charCode') r.candidates.keyCode++;
                if (p === 'currentScript') inc('documentCurrentScript');
                if (p === 'localStorage' || p === 'sessionStorage') inc('storage:' + p);
                if (isWindowObj(n.object) && !n.computed && n.property.type === 'Identifier') {
                    const isWrite = n.parent && n.parent.type === 'AssignmentExpression' && n.parent.left === n;
                    if (!isWrite) r.windowReads.add(n.property.name);
                }
                if (n.object.type === 'Identifier' && n.object.name === 'window' && n.property.type === 'Identifier' && /^krt/.test(n.property.name)) {
                    const api = n.property.name;
                    const parent = n.parent;
                    if (parent && parent.type === 'MemberExpression' && parent.object === n) {
                        const meth = propName(parent);
                        r.krtApiUse[api + '.' + meth] = (r.krtApiUse[api + '.' + meth] || 0) + 1;
                    } else {
                        r.krtApiUse[api] = (r.krtApiUse[api] || 0) + 1;
                    }
                }
                if (n.computed && n.property.type === 'BinaryExpression' && n.property.operator === '-' && n.property.right.type === 'Literal' && n.property.right.value === 1) {
                    const lp = n.property.left;
                    if (lp.type === 'MemberExpression' && propName(lp) === 'length' && norm(src.slice(lp.object.range[0], lp.object.range[1])) === norm(src.slice(n.object.range[0], n.object.range[1]))) r.candidates.atMinus1.push(n.loc.start.line);
                }
                break;
            }
            case 'CallExpression': {
                const name = calleeName(n);
                const cp = calleePath(n);
                if (cp && STATIC_CALLS.includes(cp)) inc('static:' + cp);
                if (n.callee.type === 'Identifier' && GLOBAL_FNS.includes(name)) inc('global:' + name);
                if (n.callee.type === 'MemberExpression' && name && INSTANCE_METHODS.includes(name)) {
                    const objPath = memberPath(n.callee.object);
                    if (!(objPath && STATIC_CALLS.includes(objPath + '.' + name))) inc('method:' + name);
                }
                if (cp === 'window.fetch' || (n.callee.type === 'Identifier' && name === 'fetch')) {
                    let method = 'GET(default)';
                    const init = n.arguments[1];
                    if (init && init.type === 'ObjectExpression') {
                        const mp = init.properties.find((p) => p.type === 'Property' && ((p.key.type === 'Identifier' && p.key.name === 'method') || (p.key.type === 'Literal' && p.key.value === 'method')));
                        if (mp) method = mp.value.type === 'Literal' ? String(mp.value.value).toUpperCase() : 'DYNAMIC(' + src.slice(mp.value.range[0], mp.value.range[1]) + ')';
                    } else if (init) method = 'INIT-VAR(' + src.slice(init.range[0], init.range[1]) + ')';
                    r.fetchCalls.push({ line: n.loc.start.line, method });
                }
                if (name === 'insertAdjacentHTML') {
                    const c = classifySink(n.arguments[1]);
                    r.sinks.push({ kind: 'insertAdjacentHTML', line: n.loc.start.line, cls: c.cls, leaves: [...new Set(c.leaves)] });
                }
                if (['write', 'writeln'].includes(name) && memberPath(n.callee.object) === 'document') r.sinks.push({ kind: 'document.' + name, line: n.loc.start.line, cls: 'n/a', leaves: [] });
                if (['createContextualFragment', 'setHTMLUnsafe', 'parseFromString'].includes(name)) r.sinks.push({ kind: name, line: n.loc.start.line, cls: 'n/a', leaves: [] });
                if (name === 'setTrustedHtml' || name === 'replaceWithTrustedHtml') inc('trustedSink:' + name);
                if ((name === 'setTimeout' || name === 'setInterval') && n.arguments[0] && n.arguments[0].type === 'Literal') inc('stringTimer');
                if (name === 'setAttribute' && n.arguments[0] && n.arguments[0].type === 'Literal' && n.arguments[0].value === 'style') r.candidates.setAttrStyle++;
                if (name === 'hasOwnProperty' || cp === 'Object.prototype.hasOwnProperty.call') r.candidates.hasOwnProperty.push(n.loc.start.line);
                if (cp === 'JSON.parse' && n.arguments[0] && n.arguments[0].type === 'CallExpression' && calleePath(n.arguments[0]) === 'JSON.stringify') r.candidates.jsonClone.push(n.loc.start.line);
                if ((name === 'sort' || name === 'reverse') && n.callee.type === 'MemberExpression') {
                    const o = n.callee.object;
                    if ((o.type === 'CallExpression' && calleeName(o) === 'slice' && o.arguments.length === 0) || (o.type === 'ArrayExpression' && o.elements.length === 1 && o.elements[0] && o.elements[0].type === 'SpreadElement') || (o.type === 'CallExpression' && calleePath(o) === 'Array.from')) r.candidates.sliceSort.push(name + ':' + n.loc.start.line);
                }
                if (name === 'then') r.candidates.thenCalls++;
                if (name === 'bind' && n.arguments[0] && n.arguments[0].type === 'ThisExpression') r.candidates.bindThis++;
                if (cp === 'Object.assign' && n.arguments[0] && n.arguments[0].type === 'ObjectExpression' && n.arguments[0].properties.length === 0) r.candidates.objectAssignEmpty++;
                if (cp === 'window.krtEvents.on' || cp === 'krtEvents.on') {
                    const a = n.arguments;
                    r.krtEventsActions.push((a[0] && a[0].type === 'Literal' ? a[0].value : '?') + ':' + (a[1] && a[1].type === 'Literal' ? a[1].value : '?'));
                }
                if (name === 'addEventListener' && n.arguments[0] && n.arguments[0].type === 'Literal') {
                    inc('listen:' + n.arguments[0].value);
                }
                break;
            }
            case 'NewExpression': {
                let c = n.callee;
                const nm = c.type === 'Identifier' ? c.name : memberPath(c);
                if (nm && (NEW_CTORS.includes(nm) || /^Intl\./.test(nm))) inc('new:' + nm);
                if (nm === 'Promise') r.candidates.promiseCtor++;
                break;
            }
            case 'AwaitExpression':
                r.candidates.awaitExpr++;
                break;
            case 'TemplateLiteral':
                if (n.parent && n.parent.type === 'TaggedTemplateExpression') inc('taggedTemplate');
                else if (n.expressions.length) inc('templateLiteral:interp');
                else inc('templateLiteral:plain');
                break;
            case 'BinaryExpression': {
                if (n.operator === '+' && !(n.parent && n.parent.type === 'BinaryExpression' && n.parent.operator === '+')) {
                    let hasStr = false;
                    const scan = (x) => {
                        if (x.type === 'BinaryExpression' && x.operator === '+') {
                            scan(x.left);
                            scan(x.right);
                        } else if ((x.type === 'Literal' && typeof x.value === 'string') || x.type === 'TemplateLiteral') hasStr = true;
                    };
                    scan(n);
                    if (hasStr) r.candidates.concatStr++;
                }
                if (['===', '!==', '>=', '>', '<', '=='].includes(n.operator)) {
                    const sides = [n.left, n.right];
                    const call = sides.find((s) => s.type === 'CallExpression' && calleeName(s) === 'indexOf');
                    const other = sides.find((s) => s !== call);
                    if (call && other && ((other.type === 'UnaryExpression' && other.operator === '-' && other.argument.value === 1) || (other.type === 'Literal' && other.value === 0))) r.candidates.indexOfCmp.push(n.loc.start.line);
                }
                if ((n.operator === '!=' || n.operator === '!==' || n.operator === '==' || n.operator === '===') && n.parent && n.parent.type === 'ConditionalExpression' && n.parent.test === n) {
                    const isNullish = (x) => (x.type === 'Literal' && x.value === null) || (x.type === 'Identifier' && x.name === 'undefined');
                    const subj = isNullish(n.right) ? n.left : isNullish(n.left) ? n.right : null;
                    if (subj) {
                        const s = norm(src.slice(subj.range[0], subj.range[1]));
                        const ce = n.parent;
                        const pick = n.operator.startsWith('!') ? ce.consequent : ce.alternate;
                        if (norm(src.slice(pick.range[0], pick.range[1])) === s) r.candidates.nullish.push(n.loc.start.line);
                    }
                }
                break;
            }
            case 'ForStatement':
                if (n.test && n.test.type === 'BinaryExpression' && n.test.operator === '<' && n.test.right.type === 'MemberExpression' && propName(n.test.right) === 'length') r.candidates.forIndex++;
                break;
            case 'ForOfStatement':
                inc('forOf');
                break;
            case 'ForInStatement':
                inc('forIn');
                break;
            case 'SpreadElement':
                inc('spread');
                break;
            case 'RestElement':
                inc('rest');
                break;
            case 'ObjectPattern':
                inc('destructure:object');
                break;
            case 'ArrayPattern':
                inc('destructure:array');
                break;
            case 'ImportExpression':
                inc('dynamicImport');
                break;
            case 'ThisExpression': {
                let p = n.parent;
                let inFn = false;
                while (p) {
                    if (p.type === 'FunctionExpression' || p.type === 'FunctionDeclaration' || p.type === 'ClassBody') {
                        inFn = true;
                        break;
                    }
                    p = p.parent;
                }
                if (!inFn) inc('topLevelThis');
                break;
            }
            case 'VariableDeclarator':
                if (n.init && n.init.type === 'ThisExpression' && n.id.type === 'Identifier') r.candidates.selfThis++;
                break;
            case 'Literal':
                if (n.regex) {
                    inc('regex');
                    if (n.regex.flags.includes('v')) inc('regex:v');
                    if (n.regex.flags.includes('d')) inc('regex:d');
                    if (/\(\?<[A-Za-z]/.test(n.regex.pattern)) inc('regex:namedGroup');
                    if (/\(\?<[=!]/.test(n.regex.pattern)) inc('regex:lookbehind');
                }
                if (typeof n.value === 'bigint') inc('bigint');
                if (n.raw && /\d_\d/.test(n.raw)) inc('numericSeparator');
                break;
            case 'Identifier':
                if (n.name === 'arguments' && !(n.parent && n.parent.type === 'MemberExpression' && n.parent.property === n && !n.parent.computed)) inc('argumentsRef');
                if (n.name === 'globalThis') inc('globalThisRef');
                break;
        }
    };
    const walk = (n) => {
        visit(n);
        for (const k of KEYS[n.type] || []) {
            const ch = n[k];
            if (Array.isArray(ch)) {
                for (const c of ch) if (c && typeof c.type === 'string') walk(c);
            } else if (ch && typeof ch.type === 'string') walk(ch);
        }
    };
    walk(ast);

    const sm = eslintScope.analyze(ast, { ecmaVersion: 2022, sourceType: sourceType, childVisitorKeys: KEYS, fallback: 'iteration' });
    const gs = sm.globalScope;
    const through = new Set(gs.through.map((ref) => ref.identifier.name));
    r.through = [...through].filter((n) => !knownGlobals.has(n)).sort();
    r.implicitGlobals = gs.implicit ? gs.implicit.variables.map((v) => v.name) : [];
    r.declaredTopLevel = gs.variables.filter((v) => v.defs.length > 0).map((v) => v.name);
    r.windowWrites = [...r.windowWrites].sort();
    r.windowReads = [...r.windowReads].sort();
    for (const k of Object.keys(r.candidates)) if (Array.isArray(r.candidates[k])) r.candidates[k] = r.candidates[k];
    results.push(r);
}

const providers = new Map();
const addProv = (name, f, how) => {
    if (!providers.has(name)) providers.set(name, []);
    providers.get(name).push({ file: f, how });
};
for (const r of results) {
    for (const n of r.declaredTopLevel) addProv(n, r.file, 'toplevel');
    for (const n of r.windowWrites) addProv(n, r.file, 'window');
}
for (const r of results) {
    const deps = new Map();
    const unresolved = [];
    for (const n of r.through) {
        const prov = (providers.get(n) || []).filter((p) => p.file !== r.file);
        if (prov.length) prov.forEach((p) => deps.set(p.file, (deps.get(p.file) || new Set()).add(n)));
        else unresolved.push(n);
    }
    for (const n of r.windowReads) {
        if (knownGlobals.has(n)) continue;
        const prov = (providers.get(n) || []).filter((p) => p.file !== r.file);
        if (prov.length) prov.forEach((p) => deps.set(p.file, (deps.get(p.file) || new Set()).add('window.' + n)));
        else if (!r.windowWrites.includes(n)) unresolved.push('window.' + n);
    }
    r.deps = Object.fromEntries([...deps.entries()].map(([k, v]) => [k, [...v].sort()]));
    r.unresolved = unresolved.sort();
    r.unresolvedBoot = r.unresolved.filter((n) => bootNames.has(n));
    r.unresolvedOther = r.unresolved.filter((n) => !bootNames.has(n));
}
const collisions = [];
for (const [name, provs] of providers.entries()) {
    const tl = provs.filter((p) => p.how === 'toplevel');
    if (tl.length > 1) collisions.push({ name, files: tl.map((p) => p.file) });
}
fs.writeFileSync(OUT, JSON.stringify({ generated: new Date().toISOString(), jsDir: JS_DIR, bootNamesCount: bootNames.size, globalsDtsNames: [...globalsDtsNames].sort(), results, collisions }, null, 1));
console.log('files', results.length, 'collisions', collisions.length);
