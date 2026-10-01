import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const req = createRequire('$MAIN_CHECKOUT/frontend/package.json');
const htmlparser2 = req('htmlparser2');
const espree = req('espree');

const ROOT = '$REPO/frontend/src/main/resources/templates';
const OUT = process.argv[2];

function walkDir(d) {
    const out = [];
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
        const p = path.join(d, e.name);
        if (e.isDirectory()) out.push(...walkDir(p));
        else if (e.name.endsWith('.html')) out.push(p);
    }
    return out;
}

function fragRefs(value) {
    const refs = [];
    const re = /~\{\s*([^:}\s]*)\s*::\s*([A-Za-z0-9_-]+)/g;
    let m;
    while ((m = re.exec(value))) refs.push({ template: m[1] || 'this', fragment: m[2] });
    if (!refs.length) {
        const m2 = /^\s*([A-Za-z0-9_/.-]+)\s*::\s*([A-Za-z0-9_-]+)/.exec(value);
        if (m2) refs.push({ template: m2[1], fragment: m2[2] });
    }
    return refs;
}

function classifyInline(js) {
    const res = { parse: 'ok', data: 0, logic: 0, logicKinds: {}, vars: 0, domContentLoaded: 0, lines: js.split('\n').length };
    let ast;
    try {
        ast = espree.parse(js, { ecmaVersion: 'latest', sourceType: 'script', range: true });
    } catch (e) {
        res.parse = 'fail: ' + e.message;
        return res;
    }
    const isDataExpr = (n) => {
        if (!n) return true;
        switch (n.type) {
            case 'Literal':
            case 'TemplateLiteral':
                return true;
            case 'ObjectExpression':
                return n.properties.every((p) => p.type === 'Property' && !p.method && isDataExpr(p.value));
            case 'ArrayExpression':
                return n.elements.every((e) => isDataExpr(e));
            case 'UnaryExpression':
                return isDataExpr(n.argument);
            case 'LogicalExpression':
                return isDataExpr(n.left) && isDataExpr(n.right);
            case 'Identifier':
                return n.name === 'undefined';
            default:
                return false;
        }
    };
    const kind = (k) => (res.logicKinds[k] = (res.logicKinds[k] || 0) + 1);
    for (const st of ast.body) {
        if (st.type === 'VariableDeclaration') {
            if (st.kind === 'var') res.vars++;
            if (st.declarations.every((d) => isDataExpr(d.init))) res.data++;
            else {
                res.logic++;
                kind('decl-with-expr');
            }
        } else if (st.type === 'ExpressionStatement' && st.expression.type === 'AssignmentExpression' && isDataExpr(st.expression.right)) res.data++;
        else if (st.type === 'FunctionDeclaration') {
            res.logic++;
            kind('function');
        } else if (st.type === 'ExpressionStatement' && st.expression.type === 'CallExpression') {
            res.logic++;
            const src = js.slice(st.range[0], st.range[1]);
            if (/DOMContentLoaded/.test(src)) {
                res.domContentLoaded++;
                kind('DOMContentLoaded');
            } else if (/krtEvents\.on/.test(src)) kind('krtEvents.on');
            else if (/addEventListener/.test(src)) kind('addEventListener');
            else kind('call');
        } else {
            res.logic++;
            kind(st.type + (st.type === 'ExpressionStatement' ? ':' + st.expression.type : ''));
        }
    }
    return res;
}

const files = walkDir(ROOT).sort();
const out = [];
for (const f of files) {
    const rel = path.relative(ROOT, f).replace(/\\/g, '/');
    const src = fs.readFileSync(f, 'utf8');
    const r = {
        file: rel,
        lines: src.split('\n').length,
        bytes: Buffer.byteLength(src),
        scripts: [],
        links: [],
        fragRefs: [],
        fragDefs: [],
        attrs: {},
        dataTriggers: {},
        styleElements: 0,
        templateElements: 0,
        dialogElements: 0,
        utext: [],
        unescapedInline: 0,
        forms: { post: 0, get: 0, thAction: 0 },
        buttonsNoType: 0,
        imgNoAlt: 0,
        modalWrapperCalls: 0,
    };
    const inc = (k) => (r.attrs[k] = (r.attrs[k] || 0) + 1);
    const stack = [];
    let curScript = null;
    const parser = new htmlparser2.Parser(
        {
            onopentag(name, attribs) {
                stack.push(name);
                for (const [k, v] of Object.entries(attribs)) {
                    if (k === 'style') inc('style=');
                    if (k === 'th:style' || k === 'th:styleappend') inc(k);
                    if (/^on[a-z]+$/.test(k)) inc('on*=');
                    if (/^th:on[a-z]+$/.test(k)) inc('th:on*=');
                    if (k === 'th:utext') r.utext.push(v);
                    if (k === 'th:attr' && /style\s*=/.test(v)) inc('th:attr style');
                    if (/javascript:/i.test(v) && (k === 'href' || k === 'th:href' || k === 'src')) inc('javascript: url');
                    if (k === 'th:replace' || k === 'th:insert' || k === 'th:include') {
                        for (const fr of fragRefs(v)) {
                            r.fragRefs.push(fr);
                            if (fr.template === 'fragments/modal-wrapper' && fr.fragment === 'modal') r.modalWrapperCalls++;
                        }
                    }
                    if (k === 'th:fragment') r.fragDefs.push(v.split('(')[0].trim());
                    if (k === 'data-trigger' || k === 'th:data-trigger') r.dataTriggers[v] = (r.dataTriggers[v] || 0) + 1;
                    if (/^aria-/.test(k) || /^th:aria-/.test(k)) inc('aria-*');
                    if (k === 'role') inc('role=');
                    if (k === 'popover') inc('popover');
                    if (k === 'inert' || k === 'th:inert') inc('inert');
                    if (k === 'hidden' || k === 'th:hidden') inc('hidden');
                    if (k === 'loading') inc('loading=');
                    if (k === 'autocomplete') inc('autocomplete=');
                    if (k === 'inputmode') inc('inputmode=');
                    if (k === 'enterkeyhint') inc('enterkeyhint=');
                    if (k === 'th:classappend') inc('th:classappend');
                    if (k === 'th:inline') inc('th:inline=' + v);
                    if (k === 'sec:authorize') inc('sec:authorize');
                    if (k.startsWith('data-live') || k.startsWith('th:data-live')) inc('data-live*');
                    if (k === 'data-version' || k === 'th:data-version') inc('data-version');
                }
                if (name === 'script') {
                    const src = attribs['th:src'] || attribs['src'] || null;
                    curScript = {
                        src: src ? src.replace(/^@\{\/?/, '').replace(/\}$/, '') : null,
                        defer: 'defer' in attribs,
                        async: 'async' in attribs,
                        type: attribs['type'] || null,
                        thInline: attribs['th:inline'] || null,
                        nonce: /cspNonce/.test(attribs['th:attr'] || '') || 'th:nonce' in attribs,
                        cond: attribs['th:if'] || attribs['th:unless'] ? (attribs['th:if'] ? 'if ' + attribs['th:if'] : 'unless ' + attribs['th:unless']) : attribs['sec:authorize'] ? 'sec ' + attribs['sec:authorize'] : null,
                        text: '',
                    };
                }
                if (name === 'link' && (attribs['rel'] || '').includes('stylesheet')) {
                    r.links.push((attribs['th:href'] || attribs['href'] || '').replace(/^@\{\/?/, '').replace(/\}$/, ''));
                }
                if (name === 'style') r.styleElements++;
                if (name === 'template') r.templateElements++;
                if (name === 'dialog') r.dialogElements++;
                if (name === 'form') {
                    const m = (attribs['method'] || attribs['th:method'] || 'get').toLowerCase();
                    if (m === 'post') r.forms.post++;
                    else r.forms.get++;
                    if (attribs['th:action']) r.forms.thAction++;
                }
                if (name === 'button' && !('type' in attribs) && !('th:type' in attribs)) r.buttonsNoType++;
                if (name === 'img' && !('alt' in attribs) && !('th:alt' in attribs)) r.imgNoAlt++;
            },
            ontext(t) {
                if (curScript) curScript.text += t;
                if (/\[\(\$\{|\[\(#\{|\[\(\*\{/.test(t)) r.unescapedInline += (t.match(/\[\(/g) || []).length;
            },
            onclosetag(name) {
                stack.pop();
                if (name === 'script' && curScript) {
                    const s = curScript;
                    if (!s.src) {
                        s.inline = classifyInline(s.text);
                        s.lines = s.text.trim() ? s.text.trim().split('\n').length : 0;
                    }
                    delete s.text;
                    r.scripts.push(s);
                    curScript = null;
                }
            },
        },
        { lowerCaseAttributeNames: true, recognizeSelfClosing: true }
    );
    parser.write(src);
    parser.end();
    out.push(r);
}

const byFile = new Map(out.map((r) => [r.file, r]));
function resolveScripts(file, seen = new Set()) {
    if (seen.has(file)) return [];
    seen.add(file);
    const r = byFile.get(file);
    if (!r) return [];
    const list = [];
    const own = r.scripts.filter((s) => s.src).map((s) => s.src);
    for (const fr of r.fragRefs) {
        if (fr.template === 'this' || fr.template === '') continue;
        const t = fr.template.replace(/^\//, '') + '.html';
        list.push(...resolveScripts(t, seen));
    }
    list.push(...own);
    return list;
}
function resolveLinks(file, seen = new Set()) {
    if (seen.has(file)) return [];
    seen.add(file);
    const r = byFile.get(file);
    if (!r) return [];
    const list = [];
    for (const fr of r.fragRefs) {
        if (fr.template === 'this' || fr.template === '') continue;
        const t = fr.template.replace(/^\//, '') + '.html';
        list.push(...resolveLinks(t, seen));
    }
    list.push(...r.links);
    return list;
}
for (const r of out) {
    r.effectiveScripts = [...new Set(resolveScripts(r.file))];
    r.effectiveLinks = [...new Set(resolveLinks(r.file))];
}
fs.writeFileSync(OUT, JSON.stringify(out, null, 1));
console.log('templates', out.length);
