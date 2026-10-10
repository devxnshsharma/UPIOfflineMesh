// Browser checks for the dashboard. Real Chrome, real clicks and keys, real backend. No npm packages.
//
//   ./mvnw -q clean package -DskipTests     (once, builds target.nosync/upi-offline-mesh-1.0.0.jar)
//   node e2e/run.mjs                        (starts the jar on a free port, runs everything, exits non-zero on a failure)
//   BASE_URL=http://localhost:8080 node e2e/run.mjs --only=layout,flow     (use a server that is already running)
//
// Needs Node 22+ and Chrome. Screenshots and report.json go to target.nosync/e2e (override with E2E_OUT).
// Phases: layout (every viewport), flow (every control), a11y (names, keyboard, reduced motion), recovery (backend restart; needs the jar),
// hosted (the hosted profile with an admin token and a tiny rate limit, on instances it starts itself; needs the jar).
import { spawn } from 'node:child_process';
import net from 'node:net';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchChrome, sleep } from './cdp.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = process.env.E2E_OUT || join(root, 'target.nosync', 'e2e');
const JAR = join(root, 'target.nosync', 'upi-offline-mesh-1.0.0.jar');
const only = (process.argv.find(a => a.startsWith('--only=')) || '').slice(7).split(',').filter(Boolean);
const wants = p => !only.length || only.includes(p);

const VIEWPORTS = [  // name, CSS width, CSS height, phone?, device pixel ratio
  ['1920x1080', 1920, 1080, false, 1], ['1664x936', 1664, 936, false, 1], ['1440x900', 1440, 900, false, 1], ['1366x768', 1366, 768, false, 1],
  ['1024x768', 1024, 768, false, 1], ['768x1024', 768, 1024, false, 1], ['390x844', 390, 844, true, 1], ['360x800', 360, 800, true, 1],
  ['zoom125-of-1920x1080', 1536, 864, false, 1.25], ['zoom125-of-1366x768', 1093, 614, false, 1.25], ['narrow-window-500x800', 500, 800, false, 1]
];

// ------------------------------------------------------------------ tiny test kit
const results = [];
let phase = '';
const log = (...a) => console.log(...a);
async function check(name, fn) {
  const t0 = Date.now();
  try { await fn(); results.push({ phase, name, ok: true, ms: Date.now() - t0 }); log(`  PASS  ${name}`); }
  catch (e) { results.push({ phase, name, ok: false, error: e.message, ms: Date.now() - t0 }); log(`  FAIL  ${name}\n        ${e.message.split('\n').join('\n        ')}`); }
}
const assert = (cond, msg) => { if (!cond) throw new Error(typeof msg === 'function' ? msg() : msg); };
const eq = (a, b, what) => assert(JSON.stringify(a) === JSON.stringify(b), () => `${what}: expected ${JSON.stringify(b)}, got ${JSON.stringify(a)}`);

// ------------------------------------------------------------------ backend under test
function freePort() {
  return new Promise((res, rej) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); s.on('error', rej); });
}
class App {
  constructor(port, extra = []) { this.port = port; this.extra = extra; this.proc = null; }
  get url() { return `http://localhost:${this.port}`; }
  async start() {
    this.proc = spawn('java', ['-jar', JAR, `--server.port=${this.port}`, ...this.extra], { stdio: ['ignore', 'ignore', 'ignore'] });
    for (let i = 0; i < 90; i++) { if (await healthy(this.url)) return; await sleep(500); }
    throw new Error('the application did not become healthy');
  }
  async stop() {
    if (!this.proc) return;
    const p = this.proc; this.proc = null;
    await new Promise(r => { p.once('exit', r); p.kill('SIGTERM'); setTimeout(() => { p.kill('SIGKILL'); r(); }, 8000); });
  }
}
async function healthy(url) { try { return (await fetch(url + '/api/health')).ok; } catch { return false; } }

let base = '';
async function api(method, path, body) {
  const r = await fetch(base + path, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  return { status: r.status, body: t ? JSON.parse(t) : null };
}
const balances = async () => Object.fromEntries((await api('GET', '/api/accounts')).body.map(a => [a.vpa, Number(a.balance)]));

// ------------------------------------------------------------------ page helpers
let page;
const q = s => JSON.stringify(s);
const text = sel => page.evaluate(`(document.querySelector(${q(sel)})?.innerText ?? '').trim()`);
const count = sel => page.evaluate(`document.querySelectorAll(${q(sel)}).length`);
const attr = (sel, n) => page.evaluate(`document.querySelector(${q(sel)})?.getAttribute(${q(n)})`);
const prop = (sel, n) => page.evaluate(`document.querySelector(${q(sel)})?.[${q(n)}]`);
const visible = sel => page.evaluate(`(() => { const e = document.querySelector(${q(sel)}); if (!e) return false; const r = e.getBoundingClientRect(); return !e.hidden && r.width > 0 && r.height > 0; })()`);
const setSelect = (sel, v) => page.evaluate(`(() => { const e = document.querySelector(${q(sel)}); e.value = ${q(v)}; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); })()`);
const toastTexts = () => page.evaluate(`[...document.querySelectorAll('#toasts .toast')].map(t => t.innerText)`);
async function waitToast(sub, timeout = 15000) {
  try {
    await page.waitFor(`[...document.querySelectorAll('#toasts .toast')].some(t => t.textContent.includes(${q(sub)}))`, { timeout, label: `toast containing "${sub}"` });
  } catch (e) {
    const seen = await page.evaluate(`[...document.querySelectorAll('#toasts .toast')].map(t => t.textContent)`);
    throw new Error(`${e.message}\n  toasts on screen: ${JSON.stringify(seen)}\n  console errors: ${JSON.stringify(page.consoleErrors)} exceptions: ${JSON.stringify(page.exceptions)}`);
  }
}
const idle = () => page.waitFor(`document.querySelector('#connPill').classList.contains('ok') && !document.querySelector('.btn.busy') && !document.querySelector('.tog.busy')`, { label: 'idle UI' });
async function load(vw = 1440, vh = 900, mobile = false, dpr = 1) {
  await page.viewport(vw, vh, { mobile, dpr });
  await page.goto(base + '/');
  await page.waitFor(`document.querySelector('#connPill').classList.contains('ok') && document.querySelector('#stats .tile:not(.na)')`, { label: 'dashboard live' });
  await page.waitFor(`document.querySelector('#expType').options.length > 0`, { label: 'experiments ready' });
  await sleep(300);
}
/** Smooth scrolling over a long distance takes a while: wait until the position stops changing. */
async function settleScroll() {
  let last = -1, same = 0;
  for (let i = 0; i < 80 && same < 4; i++) {
    const y = await page.evaluate('Math.round(window.scrollY)');
    same = y === last ? same + 1 : 0; last = y; await sleep(80);
  }
}
const resetBackend = (topology = 'demo') => api('POST', '/api/mesh/reset', { topology });
const clearToasts = () => page.evaluate(`document.querySelectorAll('#toasts .toast').forEach(t => t.remove())`);
async function seedPackets() {
  await resetBackend();
  const a = (await api('POST', '/api/demo/send', { senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 500, mode: 'NORMAL' })).body;
  await api('POST', '/api/mesh/gossip', { rounds: 4 });
  await api('PATCH', '/api/mesh/devices/bridge-1', { online: true });
  await api('POST', '/api/mesh/flush');
  await api('POST', '/api/demo/send', { senderVpa: 'carol@demo', receiverVpa: 'alice@demo', amount: 120, mode: 'DUPLICATE' });
  await api('POST', '/api/mesh/gossip', { rounds: 1 });
  await api('PATCH', '/api/mesh/devices/bridge-2', { online: true });
  await api('POST', '/api/mesh/flush');
  await api('POST', '/api/demo/replay', { packetId: a.packetId, kind: 'IDENTICAL', times: 3 });
  if (!(await api('GET', '/api/experiments')).body.length) await api('POST', '/api/experiments/run', { type: 'PACKET_LOSS', runs: 6, seed: 42 });
}

/** The most crowded map: the selected packet on every device, one bridge uploaded, one online and waiting, one offline, a device out of the mesh, lost transmissions. */
async function denseState(topology) {
  await resetBackend(topology);
  await api('PUT', '/api/mesh/settings', { lossProbability: 0.3, seed: 7 });
  await api('POST', '/api/demo/send', { senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 100, mode: 'DUPLICATE', ttl: 6 });
  await api('POST', '/api/mesh/gossip', { rounds: 6 });
  await api('PATCH', '/api/mesh/devices/bridge-1', { online: true }); await api('POST', '/api/mesh/flush');
  if (topology !== 'line') { await api('PATCH', '/api/mesh/devices/bridge-3', { online: true }); await api('PATCH', '/api/mesh/devices/relay-4', { active: false }); }
  await api('POST', '/api/mesh/gossip', { rounds: 1 });
}
const lum = s => { const [r, g, b] = (s.match(/[0-9.]+/g) || []).slice(0, 3).map(Number).map(c => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; }); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
const contrast = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
const mapStyle = sel => page.evaluate(`(() => { const e = document.querySelector(${q(sel)}); if (!e) return null; const c = getComputedStyle(e); return { stroke: c.stroke, fill: c.fill, dash: c.strokeDasharray, width: c.strokeWidth }; })()`);
const RUNNING = `document.getAnimations().filter(a => a.effect && a.effect.target && a.effect.target.closest && a.effect.target.closest('#topo')).length`;

// ------------------------------------------------------------------ in-page measurements
const MEASURE = `(() => {
  const r = e => e.getBoundingClientRect();
  const de = document.documentElement, vw = innerWidth, vh = innerHeight;
  const o = { vw, vh, overflowX: de.scrollWidth - de.clientWidth };
  o.wraps = [...document.querySelectorAll('.wrap')].map(w => { const b = r(w), cs = getComputedStyle(w); return Math.round(b.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)); });
  const nav = r(document.querySelector('.pillnav'));
  o.nav = { l: Math.round(nav.left), r: Math.round(nav.right), t: Math.round(nav.top), b: Math.round(nav.bottom), h: Math.round(nav.height), pos: getComputedStyle(document.querySelector('.pillnav')).position };
  o.navLinks = [...document.querySelectorAll('.pillnav .links a')].map(a => { const b = r(a); return { name: a.innerText.trim(), l: b.left, r: b.right, w: b.width, h: b.height, shown: b.width > 0 }; });
  const scroll = document.querySelector('#mapScroll'), svg = document.querySelector('#topo'), sb = r(svg), sc = r(scroll);
  const plotW = svg.viewBox.baseVal.width, plotH = svg.viewBox.baseVal.height, scale = sb.width / plotW;
  o.map = { frameW: Math.round(sc.width), svgW: Math.round(sb.width), svgH: Math.round(sb.height), scale: +scale.toFixed(3), scrollW: scroll.scrollWidth, clientW: scroll.clientWidth,
            hint: !document.querySelector('#panHint').hidden };
  const texts = [...svg.querySelectorAll('text')].map(t => ({ s: t.textContent.trim(), b: r(t), fs: parseFloat(getComputedStyle(t).fontSize) * scale }));
  o.map.minFont = +Math.min(...texts.map(t => t.fs)).toFixed(1);
  o.map.cut = texts.filter(t => t.b.left < sb.left - 1 || t.b.right > sb.right + 1 || t.b.top < sb.top - 1 || t.b.bottom > sb.bottom + 1).map(t => t.s);
  o.map.overlaps = [];
  for (let i = 0; i < texts.length; i++) for (let j = i + 1; j < texts.length; j++) {
    const a = texts[i].b, b = texts[j].b;
    const w = Math.min(a.right, b.right) - Math.max(a.left, b.left), h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    if (w > 2 && h > 2) o.map.overlaps.push(texts[i].s + ' / ' + texts[j].s);
  }
  // an outcome label must sit on its link, clear of the backend box and of every device
  const hit = (a, b) => Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
  const box = r(svg.querySelector('.backend .box')), bodies = [...svg.querySelectorAll('.node .body')].map(r);
  o.map.badgeHits = [...svg.querySelectorAll('.ubadge rect')].map(r).filter(b => hit(b, box) > 0 || bodies.some(n => hit(b, n) > 0)).length;
  o.map.running = document.getAnimations().filter(a => a.effect && a.effect.target && a.effect.target.closest && a.effect.target.closest('#topo')).length;
  const fr = getComputedStyle(document.querySelector('.map-frame'));
  o.canvas = { bg: fr.backgroundColor, border: fr.borderTopColor, image: fr.backgroundImage, grid: getComputedStyle(svg).backgroundImage };
  const at = s => r(document.querySelector(s));
  o.order = { payBottom: at('#pay').bottom, mapTop: at('.map-panel').top, mapBottom: at('.map-panel').bottom, runTop: at('#ctlPanel').top };
  const ch = document.querySelector('#expChart'), cb = r(ch);
  o.chart = ch.children.length ? { w: Math.round(cb.width), wrapW: Math.round(r(document.querySelector('#expChartWrap')).width), h: Math.round(cb.height),
    cut: [...ch.querySelectorAll('text')].filter(t => { const b = r(t); return b.width && (b.left < cb.left - 1 || b.right > cb.right + 1 || b.top < cb.top - 1 || b.bottom > cb.bottom + 1); }).map(t => t.textContent.trim()) } : null;
  o.tables = [...document.querySelectorAll('.tbl-scroll')].filter(t => t.offsetParent).map(t => ({ label: t.getAttribute('aria-label'), over: t.scrollWidth > t.clientWidth + 1, hint: t.nextElementSibling?.classList.contains('scroll-hint') ? !t.nextElementSibling.hidden : null }));
  const foot = document.querySelector('.foot p'); o.footBottom = null;
  return o;
})()`;

// ------------------------------------------------------------------ phase 1: layout at every viewport
async function layoutPhase() {
  phase = 'layout';
  log('\n== layout at every viewport (100% zoom)');
  const widths = {};
  for (const [name, w, h, mobile, dpr] of VIEWPORTS) {
    await seedPackets();
    await load(w, h, mobile, dpr);
    page.clearIssues();
    const m = await page.evaluate(MEASURE);
    widths[name] = m.wraps;
    await check(`${name}: no horizontal page overflow`, () => assert(m.overflowX <= 0, `scrollWidth exceeds the viewport by ${m.overflowX}px`));
    await check(`${name}: every section shares one content width`, () => assert(new Set(m.wraps).size === 1, `content widths differ: ${JSON.stringify(m.wraps)}`));
    await check(`${name}: floating nav is fixed, centred, inside the viewport and not cramped`, () => {
      assert(m.nav.pos === 'fixed', 'nav is not position:fixed');
      assert(m.nav.l >= 0 && m.nav.r <= m.vw, `nav leaves the viewport (${m.nav.l}..${m.nav.r} of ${m.vw})`);
      assert(Math.abs((m.nav.l + m.nav.r) / 2 - m.vw / 2) <= 2, 'nav is not centred');
      assert(m.nav.h >= 44, `nav is only ${m.nav.h}px tall`);
      assert(m.navLinks.every(l => l.shown && l.h >= 36), 'a nav link is hidden or smaller than 36px tall');
      for (let i = 1; i < m.navLinks.length; i++) assert(m.navLinks[i].l >= m.navLinks[i - 1].r - 1, 'nav links overlap');
    });
    await check(`${name}: network map is readable (font >= 11px), uncut and not overlapping`, () => {
      assert(m.map.minFont >= 11, `smallest map text is ${m.map.minFont}px`);
      eq(m.map.cut, [], 'map text outside the drawing');
      eq(m.map.overlaps, [], 'overlapping map labels');
      eq(m.map.badgeHits, 0, 'outcome labels touching the backend or a device');
      eq(m.map.running, 0, 'map animations running while idle');
    });
    await check(`${name}: Create a payment is above the network and Run the network sits directly under it`, () => {
      assert(m.order.payBottom <= m.order.mapTop + 1, 'the payment form is not above the network');
      const gap = m.order.runTop - m.order.mapBottom;
      assert(gap >= 0 && gap <= 24, `${Math.round(gap)}px between the network and the run controls`);
    });
    await check(`${name}: the map canvas is a flat charcoal slate with a slate edge, not a blue glow`, () => {
      eq(m.canvas.bg, 'rgb(13, 20, 30)', 'canvas colour'); eq(m.canvas.border, 'rgb(38, 52, 71)', 'canvas border'); eq(m.canvas.image, 'none', 'frame gradient');
      const alphas = [...m.canvas.grid.matchAll(/rgba\([^)]*,\s*([0-9.]+)\)/g)].map(a => Number(a[1]));
      assert(m.canvas.grid === 'none' || (alphas.length > 0 && Math.max(...alphas) <= 0.08), 'the grid, if drawn, must be very faint: ' + m.canvas.grid);
    });
    if (w >= 1024 && name.indexOf('zoom125-of-1366') < 0) await check(`${name}: map uses the available width`, () => assert(m.map.svgW >= m.map.frameW - 2 && m.map.frameW >= Math.min(w - 120, 1100) * 0.9, `map ${m.map.svgW}px in a ${m.map.frameW}px frame at ${w}px`));
    else await check(`${name}: map fits or scrolls sideways with a visible hint`, () => assert(m.map.svgW <= m.map.frameW + 1 || (m.map.hint && m.map.scrollW > m.map.clientW), 'map is wider than its frame without a hint'));
    await check(`${name}: experiment chart fills its container and is not clipped`, () => {
      assert(m.chart, 'chart not drawn');
      assert(Math.abs(m.chart.w - (m.chart.wrapW - 32)) <= 2, `chart ${m.chart.w}px in a ${m.chart.wrapW - 32}px container`);
      eq(m.chart.cut, [], 'chart text outside the drawing');
    });
    await check(`${name}: a table that overflows shows the scroll hint`, () => {
      for (const t of m.tables) if (t.over) assert(t.hint === true, `"${t.label}" overflows without a hint`);
    });
    // scroll to the bottom: the footer text must end above the nav
    await page.evaluate('window.scrollTo(0, document.documentElement.scrollHeight)'); await sleep(200);
    const bottom = await page.evaluate(`(() => { const p = [...document.querySelectorAll('.foot p')].pop().getBoundingClientRect(); return { textBottom: p.bottom, navTop: document.querySelector('.pillnav').getBoundingClientRect().top }; })()`);
    await check(`${name}: the last footer line scrolls clear above the floating nav`, () => assert(bottom.textBottom <= bottom.navTop - 6, `footer text ends at ${Math.round(bottom.textBottom)}px, nav starts at ${Math.round(bottom.navTop)}px`));
    // every section can be reached from the nav: it lands flush with the top and fills the screen, so no neighbour's colour shows above or below
    for (const href of ['#mesh', '#packets', '#ledgerBand', '#prove', '#activity', '#overview']) {
      await page.click(`.pillnav .links a[href="${href}"]`); await settleScroll(); await sleep(120);
      const at = await page.evaluate(`(() => {
        const b = document.querySelector(${q(href)}).getBoundingClientRect();
        const owner = (x, y) => { const e = document.elementsFromPoint(x, y).find(n => !n.closest('.pillnav') && !n.closest('.toasts')); const s = e && e.closest('header, section.band, footer'); return s ? s.id : null; };
        return { top: b.top, bottom: b.bottom, vh: innerHeight, topRow: owner(innerWidth / 2, 2), bottomRow: owner(innerWidth / 2, innerHeight - 2), cur: document.querySelector('.pillnav [aria-current=location]')?.getAttribute('href') };
      })()`);
      await check(`${name}: nav link ${href} lands flush on its section, shows no neighbouring colour and marks it current`, () => {
        assert(Math.abs(at.top) <= 1.5, `section top at ${at.top.toFixed(1)}px`);
        assert(at.bottom >= at.vh - 1, `section ends at ${Math.round(at.bottom)}px of ${at.vh}px: the next section would show`);
        eq([at.topRow, at.bottomRow], [href.slice(1), href.slice(1)], 'section on the top and bottom pixel rows');
        assert(at.cur === href, `current is ${at.cur}`);
      });
    }
    await page.scrollTo('#mesh', 0); await sleep(150);
    await page.screenshot(join(OUT, name, 'mesh.jpg'));
    await page.scrollTo('#prove', 0); await sleep(150);
    await page.screenshot(join(OUT, name, 'prove.jpg'));
    await check(`${name}: no console errors, exceptions or failed requests while loading and scrolling`, () => {
      eq(page.consoleErrors, [], 'console errors'); eq(page.exceptions, [], 'exceptions'); eq(page.failedRequests, [], 'failed requests'); eq(page.badResponses, [], 'HTTP errors');
    });
  }
  await topologyChecks();
}

/** Every supported topology, crowded with every kind of label, at a desktop, a tablet and a phone width. */
async function topologyChecks() {
  for (const topology of ['demo', 'line', 'full-mesh']) {
    for (const [w, h, mobile] of [[1440, 900, false], [768, 1024, false], [390, 844, true]]) {
      await denseState(topology); await load(w, h, mobile); page.clearIssues();
      const m = await page.evaluate(MEASURE);
      await check(`${topology} topology at ${w}x${h}: every label shown at once, nothing overlaps, is clipped or touches the backend`, () => {
        assert(m.map.minFont >= 11, `smallest map text is ${m.map.minFont}px`);
        eq(m.map.cut, [], 'map text outside the drawing'); eq(m.map.overlaps, [], 'overlapping map labels'); eq(m.map.badgeHits, 0, 'outcome labels touching the backend or a device');
        assert(m.overflowX <= 0, `page overflows by ${m.overflowX}px`);
      });
      eq(page.exceptions, [], 'exceptions'); eq(page.consoleErrors, [], 'console errors');
    }
  }
  await resetBackend();
}

// ------------------------------------------------------------------ phase 2: every control against the real backend
async function flowPhase() {
  phase = 'flow';
  log('\n== controls and workflows at 1440x900');
  await resetBackend();
  await load(1440, 900);
  page.clearIssues();

  await check('overview: eight tiles with live values, backend pill live, updated time shown', async () => {
    eq(await count('#stats .tile'), 8, 'tile count');
    assert((await text('#connPill')).toLowerCase().includes('backend live'), 'pill text');
    assert((await text('#statsUpdated')).startsWith('updated'), 'updated label');
    eq(await text('#t-devicesTotal .v'), '8', 'devices tile');
  });

  await check('composer: invalid amount, TTL and same-account are explained and block the button', async () => {
    await page.fill('#fAmount', '0'); await sleep(100);
    assert(await prop('#btnSend', 'disabled'), 'button should be disabled for amount 0');
    assert((await text('#fAmountMsg')).includes('above zero'), 'amount message');
    await page.fill('#fAmount', '12.345'); await sleep(100);
    assert((await text('#fAmountMsg')).includes('2 decimal'), 'decimal message');
    await page.fill('#fAmount', '500');
    await page.fill('#fTtl', '0'); await sleep(100);
    assert((await text('#fTtlMsg')).includes('1 to 32'), 'ttl message');
    await page.fill('#fTtl', '5');
    await setSelect('#fReceiver', 'alice@demo'); await sleep(100);
    assert((await text('#sendHint')).includes('different accounts') && await prop('#btnSend', 'disabled'), 'same account hint');
    await setSelect('#fReceiver', 'bob@demo'); await sleep(100);
    assert(!(await prop('#btnSend', 'disabled')), 'button should be enabled again');
  });

  await check('composer: an amount above the sender balance is allowed but flagged', async () => {
    await setSelect('#fSender', 'dave@demo'); await page.fill('#fAmount', '600'); await sleep(100);
    assert((await text('#sendHint')).includes('INSUFFICIENT_FUNDS'), 'insufficient funds warning');
    await setSelect('#fSender', 'alice@demo'); await page.fill('#fAmount', '500'); await sleep(100);
  });

  await check('create a payment: real click, toast, packet listed, stages shown, backend untouched', async () => {
    const b0 = await balances();
    await page.click('#btnSend'); await waitToast('encrypted and injected'); await idle();
    eq(await count('#packetList .pk'), 1, 'packets listed');
    assert((await text('#stages')).includes('Encrypted'), 'stage tracker');
    eq(await balances(), b0, 'balances after creating a payment');
    eq((await api('GET', '/api/transactions')).body.length, 0, 'ledger rows');
    assert(!(await prop('#btnGossip', 'disabled')), 'gossip unlocked');
    assert(await prop('#btnFlush', 'disabled'), 'upload stays locked while every bridge is offline');
  });

  await check('gossip round and gossip x N move the packet and its dashes, which stop by themselves; invalid N is rejected', async () => {
    await clearToasts();
    eq(await page.evaluate(RUNNING), 0, 'map animations before the action');
    await page.click('#btnGossip'); await waitToast('Gossip round');
    const moving = await page.evaluate(`({ run: document.querySelector('#topo').classList.contains('run'), lines: document.querySelectorAll('#topo .xfer').length })`);
    await idle();
    assert(moving.run, 'the map is not flagged as running while the network advances');
    await page.waitFor(`document.querySelectorAll('#topo .xfer').length > 0 && ${RUNNING} > 0`, { timeout: 2500, label: 'transmission dashes moving' });
    await page.waitFor(`!document.querySelector('#topo').classList.contains('run') && ${RUNNING} === 0`, { timeout: 5000, label: 'map motion stopped again' });
    assert((await text('#netSummary')).includes('Round 1'), 'round counter');
    await page.fill('#gRounds', '0');
    await page.click('#btnGossipN'); await waitToast('whole number from 1 to 50');
    await page.fill('#gRounds', '3');
    await page.click('#btnGossipN'); await waitToast('3 gossip rounds'); await idle();
    assert((await text('#netSummary')).includes('Round 4'), 'round counter after 3 more');
    const holders = (await api('GET', '/api/mesh/state')).body.packets[0].holders;
    assert(holders.length >= 6, `packet reached ${holders.length} devices`);
  });

  await check('device selection by real click and by keyboard; take out of the mesh and bring back', async () => {
    await page.click('.node[data-id="relay-1"] .body');
    assert((await text('#deviceDock')).includes('relay-1'), 'dock shows relay-1');
    eq(await attr('.node[data-id="relay-1"]', 'aria-pressed'), 'true', 'aria-pressed');
    await page.click('#deviceDock [data-dev-act=active]'); await idle();
    assert((await attr('.node[data-id="relay-1"]', 'class')).includes('off'), 'node drawn as out of the mesh');
    assert((await text('#deviceDock')).includes('Out of the mesh'), 'dock status');
    eq((await api('GET', '/api/mesh/state')).body.devices.find(d => d.id === 'relay-1').active, false, 'backend state');
    await page.click('#deviceDock [data-dev-act=active]'); await idle();
    assert(!(await attr('.node[data-id="relay-1"]', 'class')).includes('off'), 'node back in the mesh');
    await page.evaluate(`document.querySelector('.node[data-id="relay-2"]').focus()`);
    await page.press('Enter'); await sleep(150);
    assert((await text('#deviceDock')).includes('relay-2'), 'keyboard selection');
    await page.click('#deviceDock [data-select-device]');  // neighbour chip selects that device
    assert((await text('#deviceDock h4')).length > 0, 'dock still shows a device');
  });

  await check('bridge internet toggles gate the upload; the first upload settles exactly once', async () => {
    const b0 = await balances();
    assert(await prop('#btnFlush', 'disabled'), 'flush locked');
    await page.click('#bridgeToggles .tog[data-id="bridge-1"]'); await waitToast('bridge-1 is now online'); await idle();
    assert(!(await prop('#btnFlush', 'disabled')), 'flush unlocked');
    await page.click('#btnFlush'); await waitToast('1 upload'); await idle();
    const b1 = await balances();
    eq(b1['alice@demo'], b0['alice@demo'] - 500, 'alice'); eq(b1['bob@demo'], b0['bob@demo'] + 500, 'bob');
    eq((await api('GET', '/api/transactions')).body.filter(t => t.outcome === 'SETTLED').length, 1, 'settled rows');
    assert((await text('#ledger tbody')).includes('SETTLED'), 'ledger shows it');
    assert((await text('#accounts tbody')).includes('₹4,500.00'), 'accounts table shows the new balance');
  });

  await check('the other bridges only produce duplicates: balances and ledger effect unchanged', async () => {
    const b1 = await balances();
    await page.click('#bridgeToggles .tog[data-id="bridge-2"]'); await idle();
    await page.click('#bridgeToggles .tog[data-id="bridge-3"]'); await idle();
    await page.click('#btnFlush'); await waitToast('duplicate'); await idle();
    eq(await balances(), b1, 'balances after duplicate uploads');
    eq((await api('GET', '/api/transactions')).body.filter(t => t.outcome === 'SETTLED').length, 1, 'settled rows');
    assert((await text('#ledger tbody')).includes('DUPLICATE'), 'duplicates listed');
  });

  await check('replay: identical bytes are dropped; the same payment re-encrypted is rejected by its nonce', async () => {
    const b1 = await balances();
    await clearToasts();
    await page.fill('#atkTimes', '3');
    await page.click('[data-act=identical]'); await waitToast('3 identical submissions'); await idle();
    eq(await balances(), b1, 'balances after identical replay');
    assert((await toastTexts()).join(' ').includes('3 duplicate'), 'summary says 3 duplicates');
    await page.click('[data-act=repackage]'); await waitToast('re-encrypted'); await idle();
    eq(await count('#packetList .pk'), 2, 'a second packet exists');
    await page.click('#btnGossipN'); await waitToast('gossip rounds'); await idle();
    await page.click('#btnFlush'); await idle(); await sleep(300);
    eq(await balances(), b1, 'balances after the repackaged payment');
    assert((await text('#ledger tbody')).includes('NONCE_REUSED'), 'the repackaged copy was rejected for nonce reuse');
  });

  await check('packet inspection: full ciphertext is opaque, matches its fingerprint and can be copied', async () => {
    const d = (await api('GET', '/api/mesh/state')).body.packets[0];
    const det = (await api('GET', '/api/mesh/packets/' + d.packetId)).body;
    assert(det.ciphertext && det.ciphertext.length === det.ciphertextLength, 'full ciphertext present');
    assert(!JSON.stringify(det).includes('bob@demo') && !JSON.stringify(det).includes('alice@demo'), 'no plaintext payment fields');
    await page.evaluate(`document.querySelector('.cipher summary').click()`); await sleep(100);
    assert(await visible('.cipher pre'), 'ciphertext opens');
    assert((await text('.cipher pre')).length === det.ciphertextLength, 'whole ciphertext shown');
    await page.click('[data-what=fingerprint]'); await waitToast('Copied the fingerprint');
  });

  await check('map zoom: buttons enlarge and restore the map, keyboard works, Fit returns to the start', async () => {
    const w0 = await page.evaluate(`document.querySelector('#topo').getBoundingClientRect().width`);
    await page.click('#mapIn'); await sleep(200);
    const w1 = await page.evaluate(`document.querySelector('#topo').getBoundingClientRect().width`);
    assert(w1 > w0 * 1.2, `zoom in changed ${w0} to ${w1}`);
    assert(await page.evaluate(`document.querySelector('#mapScroll').classList.contains('zoomed')`), 'zoomed class');
    await page.evaluate(`document.querySelector('#mapIn').focus()`); await page.press('Enter'); await sleep(200);
    const w2 = await page.evaluate(`document.querySelector('#topo').getBoundingClientRect().width`);
    assert(w2 > w1, 'keyboard zoom in');
    const mz = await page.evaluate(MEASURE);
    eq(mz.map.cut, [], 'map text outside the drawing when zoomed'); eq(mz.map.overlaps, [], 'overlapping labels when zoomed'); eq(mz.map.badgeHits, 0, 'outcome labels touching the backend when zoomed');
    await page.click('#mapOut'); await page.click('#mapOut'); await page.click('#mapOut'); await sleep(200);
    assert(await prop('#mapOut', 'disabled'), 'zoom out stops at fit');
    await page.click('#mapIn'); await page.click('#mapFit'); await sleep(200);
    const w3 = await page.evaluate(`document.querySelector('#topo').getBoundingClientRect().width`);
    assert(Math.abs(w3 - w0) < 2, `Fit restores the width (${w3} vs ${w0})`);
  });

  await check('simulation settings: values apply, bad values are explained, topology change asks first', async () => {
    await page.evaluate(`document.querySelector('#advSettings').open = true`);
    await page.fill('#sTtl', '0'); await page.evaluate(`document.querySelector('#sTtl').dispatchEvent(new Event('change', { bubbles: true }))`);
    assert((await text('#settingsMsg')).includes('1 to 32'), 'ttl message');
    await page.fill('#sTtl', '7'); await page.evaluate(`document.querySelector('#sTtl').dispatchEvent(new Event('change', { bubbles: true }))`);
    await waitToast('Setting saved'); await idle();
    eq((await api('GET', '/api/mesh/settings')).body.defaultTtl, 7, 'backend default TTL');
    await sleep(200);
    eq(await prop('#fTtl', 'value'), '7', 'composer TTL follows the default');
    await page.evaluate(`(() => { const e = document.querySelector('#sLoss'); e.value = 30; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await idle(); await sleep(200);
    eq((await api('GET', '/api/mesh/settings')).body.lossProbability, 0.3, 'backend loss');
    assert((await text('#settingsSummary')).includes('loss 30%'), 'summary updated');
    await setSelect('#sTopo', 'line'); await sleep(100);
    assert(await visible('#topoConfirm'), 'confirmation shown'); eq((await api('GET', '/api/mesh/state')).body.topology, 'demo', 'nothing changed yet');
    await page.click('#topoCancel'); eq(await prop('#sTopo', 'value'), 'demo', 'cancel restores the select');
    await setSelect('#sTopo', 'line'); await page.click('#topoApply'); await idle(); await sleep(300);
    eq((await api('GET', '/api/mesh/state')).body.topology, 'line', 'topology switched');
    eq(await count('.node'), 6, 'six devices drawn for the line topology');
  });

  await check('reset needs a second click, restores the baseline and clears selections', async () => {
    await page.click('.node[data-id="bridge-1"] .body');
    await page.click('#btnReset'); assert((await text('#btnReset')).includes('confirm'), 'armed');
    await page.click('#btnReset'); await waitToast('Reset to the baseline'); await idle(); await sleep(300);
    eq(await balances(), { 'alice@demo': 5000, 'bob@demo': 1000, 'carol@demo': 2500, 'dave@demo': 500 }, 'balances');
    eq((await api('GET', '/api/transactions')).body.length, 0, 'ledger');
    eq(await count('#packetList .pk'), 0, 'packets');
    assert((await text('#deviceDock')).includes('Select a device'), 'device selection cleared');
    eq(await count('.node'), 6, 'topology kept (line)');
  });

  await check('guided scenarios: each one individually, then all eight, all checks pass against the live state', async () => {
    await resetBackend();
    await load(1440, 900); page.clearIssues();
    await page.click('[data-run="2"]');
    await page.waitFor(`document.querySelector('#scenarioList .scn[data-i="2"]').dataset.state !== 'running' && document.querySelector('#scenarioList .scn[data-i="2"]').dataset.state !== 'idle'`, { timeout: 60000, label: 'scenario 3' });
    eq(await attr('#scenarioList .scn[data-i="2"]', 'data-state'), 'pass', 'tampered scenario');
    await page.click('#btnRunAll');
    await page.waitFor(`/passed|Failed/.test(document.querySelector('#scnSummary').innerText) && !document.body.dataset.scenario`, { timeout: 180000, label: 'all scenarios finished' });
    const s = await text('#scnSummary');
    assert(s.includes('All passed') && s.includes('8 of 8 scenarios passed') && s.includes('27 checks'), `summary: ${s}`);
    eq(await page.evaluate(`[...document.querySelectorAll('#scenarioList .scn')].map(l => l.dataset.state)`), Array(8).fill('pass'), 'scenario states');
    assert(!(await prop('#btnRunAll', 'disabled')), 'controls restored');
    await page.scrollTo('#scenarioPanel', 20); await sleep(150);
    await page.screenshot(join(OUT, 'flow', 'scenarios-done.jpg'));
  });

  await check('experiments: validation, run, chart + summary + diagnostic tables agree, earlier runs, form edits do not touch the shown result', async () => {
    await resetBackend();
    await load(1440, 900); page.clearIssues();
    await page.scrollTo('#expPanel', 20);
    // field level validation
    const tryValues = async (v, expect) => { await page.fill('#expValues', v); await sleep(80); assert((await text('#expValuesMsg')).includes(expect), `"${v}" should say "${expect}", says "${await text('#expValuesMsg')}"`); assert(await prop('#btnExp', 'disabled'), 'run disabled'); };
    await tryValues('1,,2', 'empty entry'); await tryValues('abc', 'not a number'); await tryValues('1, 1', 'twice'); await tryValues('999', 'outside');
    await setSelect('#expType', 'TTL'); await sleep(80);
    await tryValues('2.5', 'whole number'); await tryValues('0', 'outside');
    await page.fill('#expValues', '1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13'); await sleep(80); assert((await text('#expValuesMsg')).includes('At most 12'), 'too many values');
    await setSelect('#expType', 'PACKET_LOSS'); await page.fill('#expValues', '0, 0.5'); await page.fill('#expRuns', '0'); await sleep(80);
    assert((await text('#expRunsMsg')).includes('1 to 100'), 'runs message');
    await page.fill('#expRuns', '100'); await page.fill('#expValues', '0,0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1'); await sleep(80);
    assert((await text('#expValuesMsg')).includes('limit is 600') && (await text('#expEstimate')).includes('1100 simulations'), 'budget message');
    await page.fill('#expSeed', '4.5'); await sleep(80); assert((await text('#expSeedMsg')).includes('Whole number'), 'seed message');
    // run two real experiments
    await setSelect('#expType', 'TTL'); await page.fill('#expValues', '1, 2, 3, 4, 5'); await page.fill('#expRuns', '3'); await page.fill('#expSeed', '7'); await sleep(100);
    await page.click('#btnExp');
    assert(await prop('#btnExp', 'disabled'), 'run button locked while running');
    await page.waitFor(`!document.querySelector('#btnExp').classList.contains('busy') && document.querySelector('#expHead').innerText.includes('exp-')`, { timeout: 60000, label: 'first experiment' });
    const first = await text('#expHead h4');
    eq(first, 'TTL (hop budget)', 'result title');
    await setSelect('#expType', 'PACKET_LOSS'); await page.fill('#expValues', '0, 0.3, 0.6, 0.9'); await page.fill('#expRuns', '4'); await page.fill('#expSeed', '9'); await sleep(100);
    eq(await text('#expHead h4'), 'TTL (hop budget)', 'editing the form does not change the shown result');
    await page.click('#btnExp');
    await page.waitFor(`!document.querySelector('#btnExp').classList.contains('busy') && document.querySelector('#expHead h4').innerText.startsWith('Packet loss')`, { timeout: 60000, label: 'second experiment' });
    // consistency between chart, tables and the stored result
    const hist = (await api('GET', '/api/experiments')).body;
    const latest = hist[0], earlier = hist[1];
    const shownState = async () => page.evaluate(`({ id: document.querySelector('#expHead .chip.mono').innerText, points: document.querySelectorAll('#expChart circle.cp').length,
      sumRows: document.querySelectorAll('#expSummary tbody tr').length, diagRows: document.querySelectorAll('#expDiag tbody tr').length,
      sumCols: document.querySelectorAll('#expSummary thead th').length, diagCols: document.querySelectorAll('#expDiag thead th').length,
      firstParam: document.querySelector('#expSummary tbody tr td').innerText, diagFirst: document.querySelector('#expDiag tbody tr td').innerText,
      params: document.querySelector('#expParams').textContent, earlierChip: !!document.querySelector('#expHead .chip.warn') })`);
    let st = await shownState();
    eq(st.id, latest.id, 'shown id is the latest'); eq(st.points, latest.rows.length, 'chart points'); eq(st.sumRows, latest.rows.length, 'summary rows'); eq(st.diagRows, latest.rows.length, 'diagnostic rows');
    eq(st.sumCols, 6, 'summary columns (parameter, runs, 4 metrics)'); eq(st.diagCols, 7, 'diagnostic columns (parameter, 6 metrics)');
    assert(st.params.includes('"seed": 9') && st.params.includes('"runsPerPoint": 4'), 'parameters disclosure matches'); assert(!st.earlierChip, 'not marked as an earlier run');
    // every metric the backend returned appears in one of the two tables
    const heads = await page.evaluate(`[...document.querySelectorAll('#expSummary thead th, #expDiag thead th')].map(t => t.innerText.split('\\n')[0].trim())`);
    for (const m of latest.metrics) assert(heads.includes(m.label), `metric "${m.label}" missing from the tables (${heads.join(' | ')})`);
    // earlier run
    await setSelect('#expHistory', earlier.id); await sleep(200);
    st = await shownState();
    eq(st.id, earlier.id, 'earlier run shown'); eq(st.points, earlier.rows.length, 'earlier chart points'); eq(st.sumRows, earlier.rows.length, 'earlier summary rows');
    assert(st.earlierChip, 'earlier-run chip'); assert(st.params.includes('"seed": 7'), 'earlier parameters');
    assert((await text('#expChartTitle')).includes(earlier.id), 'chart caption names the earlier run');
    // metric selector changes the chart
    await setSelect('#expMetric', earlier.metrics[1].key); await sleep(150);
    assert((await text('#expChartTitle')).includes(earlier.metrics[1].label), 'metric change reaches the chart caption');
    // use these settings
    await page.click('#expHead [data-act=reuse]'); await sleep(150);
    eq(await prop('#expType', 'value'), earlier.type, 'form type'); eq(await prop('#expSeed', 'value'), '7', 'form seed');
    // numbers shown for a missing average are n/a, not zero
    const na = await page.evaluate(`document.querySelectorAll('#expSummary .na').length`);
    assert(na >= 0, 'n/a cells are counted');
    await page.scrollTo('#expChartWrap', 120); await sleep(150);
    await page.screenshot(join(OUT, 'flow', 'experiments.jpg'));
    eq(page.exceptions, [], 'exceptions'); eq(page.consoleErrors, [], 'console errors');
  });

  await check('resizing and scrolling while an experiment is running does not break the page or lose the result', async () => {
    await page.scrollTo('#expPanel', 20);
    await setSelect('#expType', 'PACKET_LOSS'); await page.fill('#expValues', '0, 0.1, 0.2, 0.3, 0.4, 0.5'); await page.fill('#expRuns', '30'); await page.fill('#expSeed', '11'); await sleep(100);
    await page.click('#btnExp');
    for (const [w, h, mobile] of [[1100, 800, false], [390, 844, true], [1440, 900, false]]) {
      await page.viewport(w, h, { mobile }); await page.evaluate('window.scrollBy(0, 240)'); await sleep(250);
    }
    await page.waitFor(`!document.querySelector('#btnExp').classList.contains('busy') && document.querySelector('#expHead').innerText.includes('seed 11')`, { timeout: 60000, label: 'experiment finished after resizing' });
    const m = await page.evaluate(MEASURE);
    assert(m.overflowX <= 0, 'horizontal overflow after resizing'); assert(m.chart && Math.abs(m.chart.w - (m.chart.wrapW - 32)) <= 2, 'chart redrawn to the final width');
    eq(page.exceptions, [], 'exceptions');
  });

  await check('experiments: a request over the budget is refused by the server with a clear message', async () => {
    const r = await api('POST', '/api/experiments/run', { type: 'PACKET_LOSS', runs: 100, seed: 1, values: [0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1] });
    eq(r.status, 400, 'status'); assert(r.body.message.includes('limit is 600'), r.body.message);
  });

  await check('resize while the app is live: map and chart redraw, no overflow, no errors', async () => {
    await page.scrollTo('#mesh', 0);
    for (const [w, h, mobile] of [[1100, 800, false], [800, 900, false], [390, 844, true], [1440, 900, false]]) {
      await page.viewport(w, h, { mobile }); await sleep(450);
      const m = await page.evaluate(MEASURE);
      assert(m.overflowX <= 0, `overflow ${m.overflowX}px at ${w}`);
      assert(m.map.svgW >= Math.min(m.map.frameW, 640) - 1, `map width ${m.map.svgW} in frame ${m.map.frameW} at ${w}`);
      eq(m.map.cut, [], `map text outside the drawing at ${w}`); eq(m.map.overlaps, [], `overlapping map labels at ${w}`); eq(m.map.badgeHits, 0, `outcome labels on the backend or a device at ${w}`);
      if (m.chart) assert(Math.abs(m.chart.w - (m.chart.wrapW - 32)) <= 2, `chart ${m.chart.w} in ${m.chart.wrapW - 32} at ${w}`);
    }
  });

  await check('map colours and patterns: four role colours, a bridge keeps its colour online or offline, every kind of line has its own pattern, all of it legible on the canvas', async () => {
    await resetBackend();
    await api('POST', '/api/demo/send', { senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 50, mode: 'DUPLICATE', ttl: 5 });
    await api('POST', '/api/mesh/gossip', { rounds: 1 });
    await api('PATCH', '/api/mesh/devices/bridge-1', { online: true }); await api('POST', '/api/mesh/flush');     // bridge-1 uploaded the selected packet
    await api('PATCH', '/api/mesh/devices/bridge-2', { online: true });                                        // bridge-2 online, nothing sent; bridge-3 offline
    await load(1440, 900);
    const bg = 'rgb(13, 20, 30)';
    const sender = await mapStyle('.node.sender .body'), relay = await mapStyle('.node.relay .body'), bridge1 = await mapStyle('.node.bridge[data-id="bridge-1"] .body'),
      bridge3 = await mapStyle('.node.bridge[data-id="bridge-3"] .body'), server = await mapStyle('.backend .box');
    eq(await count('.node.sender'), 1, 'sender phones drawn'); eq(await count('.node.bridge'), 3, 'bridges drawn');
    eq(new Set([sender.stroke, relay.stroke, bridge1.stroke, server.stroke]).size, 4, 'distinct role colours (sender, relay, bridge, backend)');
    eq(bridge1.stroke, bridge3.stroke, 'a bridge keeps its role colour whether its internet is on or off');
    assert((await mapStyle('.node.bridge[data-id="bridge-1"] .dot')).fill !== (await mapStyle('.node.bridge[data-id="bridge-3"] .dot')).fill, 'the internet dot must differ between an online and an offline bridge');
    const link = await mapStyle('#topo .link'), sent = await mapStyle('#topo .xfer:not(.lost)'), up = await mapStyle('#topo .net.up.sent'), idleUp = await mapStyle('#topo .net.up:not(.sent)'), down = await mapStyle('#topo .net:not(.up)');
    assert(link && sent && up && idleUp && down, 'every kind of line is drawn: ' + JSON.stringify({ link: !!link, sent: !!sent, up: !!up, idleUp: !!idleUp, down: !!down }));
    eq(link.dash, 'none', 'neighbour links are solid'); assert(sent.dash !== 'none', 'transmissions are dashed'); assert(up.dash !== 'none', 'uploads are dashed');
    eq(idleUp.dash, 'none', 'an online bridge link is solid'); assert(down.dash !== 'none' && down.stroke !== up.stroke, 'an offline link is dotted and not teal');
    eq(sent.stroke, 'rgb(255, 179, 71)', 'transmissions are amber'); eq(up.stroke, idleUp.stroke, 'both online-bridge links are teal');
    const ink = await page.evaluate(`(() => { const f = s => { const e = document.querySelector(s); return e ? getComputedStyle(e).fill : null; };
      return { name: f('.node .name'), tag: f('.node .tag'), sub: f('.node .sub'), backend: f('.backend .bname'), waiting: f('.node .cnt.wait'),
        badges: [...document.querySelectorAll('#topo .ubadge')].map(g => ({ text: getComputedStyle(g.querySelector('text')).fill, rect: getComputedStyle(g.querySelector('rect')).fill })) }; })()`);
    assert(ink.waiting, 'a bridge that still has a copy to upload shows an amber count badge');
    // lost transmissions: a fresh network where every transmission is lost, so the sender's offer to relay-1 fails
    await resetBackend(); await api('PUT', '/api/mesh/settings', { lossProbability: 1 });
    await api('POST', '/api/demo/send', { senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 50, mode: 'NORMAL', ttl: 5 }); await api('POST', '/api/mesh/gossip', { rounds: 1 });
    await page.evaluate('refresh()'); await page.waitFor(`document.querySelector('#topo .xfer.lost')`, { timeout: 5000, label: 'a lost transmission' });
    const lost = await mapStyle('#topo .xfer.lost');
    eq(lost.stroke, 'rgb(255, 138, 125)', 'lost transmissions are coral'); assert(lost.dash !== sent.dash, 'a lost transmission has its own dash pattern');
    for (const [what, c, min] of [['neighbour links', link.stroke, 3], ['transmissions', sent.stroke, 3], ['uploads', up.stroke, 3], ['lost transmissions', lost.stroke, 3], ['offline links', down.stroke, 3],
      ['device names', ink.name, 4.5], ['ttl and hop labels', ink.tag, 4.5], ['status lines', ink.sub, 4.5], ['backend label', ink.backend, 4.5]]) {
      assert(c, what + ' not found'); assert(contrast(c, bg) >= min, `${what} ${c} has contrast ${contrast(c, bg).toFixed(2)} on the canvas (needs ${min})`);
    }
    for (const b of ink.badges) assert(contrast(b.text, b.rect) >= 4.5, `outcome label ${b.text} on ${b.rect}: ${contrast(b.text, b.rect).toFixed(2)}`);
    assert(contrast(link.stroke, sent.stroke) > 1.2, 'ordinary links must stay quieter than active transmissions');
    // the legend explains each convention once
    const legend = await page.evaluate(`[...document.querySelectorAll('.legend i.lg')].map(i => i.className.replace('lg ', '') + '|' + i.parentElement.textContent.trim())`);
    eq(new Set(legend).size, legend.length, 'legend entries are unique'); assert(legend.length >= 14, 'legend lists ' + legend.length + ' conventions');
    await api('PUT', '/api/mesh/settings', { lossProbability: 0 }); await resetBackend();
  });

  await check('console and network stayed clean through all workflows (HTTP 4xx only where a test asked for one)', async () => {
    eq(page.exceptions, [], 'exceptions'); eq(page.consoleErrors, [], 'console errors'); eq(page.failedRequests, [], 'failed requests');
  });
}

// ------------------------------------------------------------------ phase 3: accessibility basics and reduced motion
async function a11yPhase() {
  phase = 'a11y';
  log('\n== accessibility, keyboard and motion');
  await seedPackets();
  await load(1440, 900); page.clearIssues();
  await check('landmarks, headings and a skip link exist; the skip link is the first tab stop', async () => {
    const m = await page.evaluate(`({ main: !!document.querySelector('main'), nav: !!document.querySelector('nav[aria-label]'), header: !!document.querySelector('header'), footer: !!document.querySelector('footer'),
      h1: document.querySelectorAll('h1').length, h2: document.querySelectorAll('h2').length, lang: document.documentElement.lang })`);
    assert(m.main && m.nav && m.header && m.footer && m.h1 === 1 && m.h2 >= 6 && m.lang === 'en', JSON.stringify(m));
    await page.press('Tab');
    eq(await page.evaluate('document.activeElement.className'), 'skip', 'first tab stop');
  });
  await check('every button, link, field and select has an accessible name', async () => {
    const bad = await page.evaluate(`[...document.querySelectorAll('button, a[href], input, select, [role=button]')].filter(e => {
      if (e.hidden || e.closest('[hidden]')) return false;
      const name = (e.getAttribute('aria-label') || e.textContent || e.value && e.type === 'submit' || (e.labels && [...e.labels].map(l => l.textContent).join('')) || e.getAttribute('title') || e.getAttribute('aria-labelledby') || '').toString().trim();
      return !name; }).map(e => e.outerHTML.slice(0, 90))`);
    eq(bad, [], 'controls without a name');
  });
  await check('state is never colour only: toggles expose aria-pressed, the current section aria-current, the selected device a ring and aria-pressed', async () => {
    assert((await attr('#bridgeToggles .tog', 'aria-pressed')) !== null, 'bridge toggles aria-pressed');
    assert((await count('.pillnav [aria-current=location]')) === 1, 'one current nav item');
    await page.click('.node[data-id="relay-3"] .body');
    eq(await attr('.node[data-id="relay-3"]', 'aria-pressed'), 'true', 'selected node');
    const ring = await page.evaluate(`getComputedStyle(document.querySelector('.node[data-id="relay-3"] .ring')).opacity`);
    eq(ring, '1', 'selected ring visible');
  });
  await check('keyboard: a focused control shows a visible focus indicator', async () => {
    await page.evaluate(`document.querySelector('#btnGossip').focus()`);
    await page.press('Tab'); await page.press('Tab', { shift: true });
    const o = await page.evaluate(`(() => { const e = document.activeElement; const cs = getComputedStyle(e); return { id: e.id, outline: cs.outlineStyle, w: cs.outlineWidth }; })()`);
    assert(o.outline !== 'none' && o.w !== '0px', JSON.stringify(o));
  });
  await check('the live-region and alert roles exist only where updates matter (toasts, banner, field messages), not on polled data', async () => {
    const live = await page.evaluate(`[...document.querySelectorAll('[aria-live], [role=status], [role=alert]')].map(e => e.id || e.className)`);
    assert(!live.some(x => /^(stats|ledger|accounts|log|packetList|topo)$/.test(x)), 'polled regions must not be live: ' + live.join(', '));
  });
  await check('polled updates leave the live regions alone (a screen reader would otherwise repeat them every 2 seconds)', async () => {
    await page.evaluate(`window.__muts = 0; window.__mo = new MutationObserver(r => { window.__muts += r.length; });
      ['#ctlHint', '#sendHint', '#toasts', '#banner', '#scnSummary', '#deviceDock'].forEach(s => window.__mo.observe(document.querySelector(s), { childList: true, subtree: true, characterData: true, attributes: true }));`);
    await sleep(5500); // at least two polls
    const n = await page.evaluate('window.__muts');
    await page.evaluate('window.__mo.disconnect()');
    eq(n, 0, 'writes to live regions while nothing changed');
  });
  await check('idle polling does not redraw the map (no wasted rendering while nothing changes)', async () => {
    await page.evaluate(`window.__draws = 0; window.__mo2 = new MutationObserver(r => { window.__draws += r.length; }); window.__mo2.observe(document.querySelector('#topo'), { childList: true, subtree: true, attributes: true });`);
    await sleep(5500); // at least two polls
    const n = await page.evaluate('window.__draws');
    await page.evaluate('window.__mo2.disconnect()');
    eq(n, 0, 'changes to the map while nothing changed');
  });
  await check('prefers-reduced-motion switches the animations off', async () => {
    await page.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    await page.reload(); await page.waitFor(`document.querySelector('#connPill').classList.contains('ok')`);
    const o = await page.evaluate(`({ clouds: getComputedStyle(document.querySelector('.clouds')).animationName, hero: getComputedStyle(document.querySelector('.hero .display')).animationName, smooth: getComputedStyle(document.documentElement).scrollBehavior })`);
    eq(o, { clouds: 'none', hero: 'none', smooth: 'auto' }, 'reduced-motion styles');
    await page.click('#btnGossip'); await waitToast('Gossip round'); await sleep(400);
    eq(await page.evaluate(`({ run: document.querySelector('#topo').classList.contains('run'), moving: ${RUNNING} })`), { run: false, moving: 0 }, 'map motion although reduced motion is requested');
    await page.send('Emulation.setEmulatedMedia', { features: [] });
  });
  await check('console clean in the accessibility phase', () => { eq(page.exceptions, [], 'exceptions'); eq(page.consoleErrors, [], 'console errors'); });
}

// ------------------------------------------------------------------ phase 4: the backend restarts under a live page
async function recoveryPhase(app) {
  phase = 'recovery';
  log('\n== polling recovery after a backend restart');
  if (!app) { log('  (skipped: needs the jar so the test can stop and start the backend)'); return; }
  await seedPackets();
  await load(1440, 900); page.clearIssues();
  const before = await text('#t-settlements .v');
  await check('backend stops: the page says so, keeps the last data and marks it out of date', async () => {
    await app.stop();
    await page.waitFor(`!document.querySelector('#banner').hidden`, { timeout: 20000, label: 'banner' });
    const o = await page.evaluate(`({ banner: document.querySelector('#banner').innerText, pill: document.querySelector('#connPill').innerText.toLowerCase(), pillCls: document.querySelector('#connPill').className,
      updated: document.querySelector('#statsUpdated').innerText, settlements: document.querySelector('#t-settlements .v').innerText, packets: document.querySelectorAll('#packetList .pk').length })`);
    assert(o.banner.includes('last confirmed data'), o.banner); assert(o.pill.includes('reconnecting') && o.pillCls.includes('stale'), o.pill);
    assert(o.updated.includes('out of date'), o.updated); eq(o.settlements, before, 'last valid data kept'); assert(o.packets > 0, 'packet list kept');
  });
  await check('actions fail clearly while the backend is down and the button recovers', async () => {
    await page.click('#btnGossip'); await waitToast('Cannot reach the backend');
    assert(!(await attr('#btnGossip', 'aria-busy')), 'button no longer busy');
  });
  await check('backend returns: polling recovers by itself, the banner clears and the state is the fresh baseline', async () => {
    await app.start();
    await page.waitFor(`document.querySelector('#banner').hidden && document.querySelector('#connPill').classList.contains('ok')`, { timeout: 30000, label: 'recovery' });
    await page.waitFor(`document.querySelectorAll('#packetList .pk').length === 0 && document.querySelector('#t-settlements .v').innerText === '0'`, { timeout: 10000, label: 'fresh state shown' });
    assert((await text('#statsUpdated')).startsWith('updated'), 'updated label live again');
    eq(page.exceptions, [], 'exceptions');
  });
}

// ------------------------------------------------------------------ phase 5: the hosted profile (admin token, limits, headers)
async function hostedPhase() {
  phase = 'hosted';
  log('\n== hosted profile: admin token, rate limit, headers, no developer console');
  if (process.env.BASE_URL || !existsSync(JAR)) { log('  (skipped: needs the jar so the test can start its own hosted instance)'); return; }
  const TOKEN = 'e2e-secret-token';
  const saved = base;
  const hosted = new App(await freePort(), ['--spring.profiles.active=hosted', `--ADMIN_TOKEN=${TOKEN}`, '--WRITES_PER_MINUTE=100000']);
  await hosted.start();
  base = hosted.url;
  try {
    await load(1440, 900); page.clearIssues();
    await check('hosted: the page says it is hosted, offers admin access and has no database console link', async () => {
      assert((await text('#footMode')).startsWith('Hosted demo'), await text('#footMode'));
      assert(await visible('#adminBox'), 'admin box visible');
      eq(await count('a[href*="h2-console"]'), 0, 'console links');
    });
    await check('hosted: strict security headers on the page and no store on the API; no policy violation reached the console', async () => {
      const h = await page.evaluate(`(async () => { const p = await fetch('/index.html'); const a = await fetch('/api/health'); return { csp: p.headers.get('content-security-policy'), nosniff: p.headers.get('x-content-type-options'), frame: p.headers.get('x-frame-options'), store: a.headers.get('cache-control') }; })()`);
      assert(h.csp.includes("script-src 'self'") && !h.csp.includes('unsafe-inline'), h.csp); eq(h.nosniff, 'nosniff', 'nosniff'); eq(h.frame, 'DENY', 'frame'); eq(h.store, 'no-store', 'api cache');
      eq(page.consoleErrors, [], 'console errors (a CSP violation would appear here)');
    });
    await check('hosted: reset without the token is refused by the backend, the admin box opens and nothing changed', async () => {
      await api('POST', '/api/demo/send', { senderVpa: 'alice@demo', receiverVpa: 'bob@demo', amount: 5 });
      await page.reload(); await page.waitFor(`document.querySelector('#connPill').classList.contains('ok') && document.querySelectorAll('#packetList .pk').length === 1`);
      await page.click('#btnReset'); await page.click('#btnReset');
      await waitToast('admin token'); await idle();
      assert(await page.evaluate(`document.querySelector('#adminBox').open`), 'admin box opened');
      eq((await api('GET', '/api/mesh/state')).body.packets.length, 1, 'packet survived the refused reset');
    });
    await check('hosted: scenarios and experiments ask for the token up front instead of failing halfway', async () => {
      await clearToasts();
      await page.click('#btnRunAll'); await waitToast('admin token');
      eq(await attr('#scenarioList .scn[data-i="0"]', 'data-state'), 'idle', 'no scenario started');
      await clearToasts();
      await page.click('#btnExp'); await waitToast('admin token');
    });
    await check('hosted: a wrong token is rejected by the server', async () => {
      await clearToasts();
      await page.fill('#adminToken', 'not-the-token'); await page.click('#adminForm button[type=submit]');
      await page.click('#btnReset'); await page.click('#btnReset'); await waitToast('admin token'); await idle();
      eq((await api('GET', '/api/mesh/state')).body.packets.length, 1, 'still not reset');
    });
    await check('hosted: with the right token reset, experiments and a guided scenario all work', async () => {
      await clearToasts();
      await page.fill('#adminToken', TOKEN); await page.click('#adminForm button[type=submit]'); await waitToast('Admin token set');
      assert((await text('#adminState')).includes('token set'), 'state label');
      await page.click('#btnReset'); await page.click('#btnReset'); await waitToast('Reset to the baseline'); await idle(); await sleep(300);
      eq((await api('GET', '/api/mesh/state')).body.packets.length, 0, 'reset happened');
      await setSelect('#expType', 'TTL'); await page.fill('#expValues', '2, 3'); await page.fill('#expRuns', '2');
      await page.click('#btnExp');
      await page.waitFor(`!document.querySelector('#btnExp').classList.contains('busy') && document.querySelector('#expHead h4')?.innerText.startsWith('TTL')`, { timeout: 60000, label: 'experiment' });
      await page.click('[data-run="2"]');
      await page.waitFor(`['pass', 'fail'].includes(document.querySelector('#scenarioList .scn[data-i="2"]').dataset.state)`, { timeout: 60000, label: 'scenario' });
      eq(await attr('#scenarioList .scn[data-i="2"]', 'data-state'), 'pass', 'tampered scenario on the hosted profile');
    });
    await check('hosted: ordinary visitor actions never needed the token', async () => {
      await page.fill('#adminToken', ''); await page.click('#adminClear'); await clearToasts();
      await page.click('#btnSend'); await waitToast('encrypted and injected'); await idle();
      await page.click('#btnGossip'); await waitToast('Gossip round'); await idle();
    });
    eq(page.exceptions, [], 'exceptions');
  } finally { base = saved; await hosted.stop(); }

  // the rate limit as a visitor sees it (a separate instance with a tiny budget)
  const tight = new App(await freePort(), ['--spring.profiles.active=hosted', `--ADMIN_TOKEN=${TOKEN}`, '--WRITES_PER_MINUTE=4']);
  await tight.start();
  base = tight.url;
  try {
    await check('hosted: writes beyond the per-minute budget are refused with a clear message and the UI recovers', async () => {
      await load(1440, 900); page.clearIssues();
      await page.click('#btnSend'); await waitToast('encrypted and injected'); await idle();
      for (let i = 0; i < 8; i++) {   // writes 2, 3, 4 are accepted, the 5th is over the budget of 4
        const limited = await page.evaluate(`[...document.querySelectorAll('#toasts .toast')].some(t => t.textContent.includes('Too many requests'))`);
        if (limited) break;
        await page.click('#btnGossip'); await idle();
      }
      await waitToast('Too many requests');
      assert(!(await attr('#btnGossip', 'aria-busy')), 'button no longer busy');
      const r = await fetch(base + '/api/mesh/flush', { method: 'POST' });
      eq(r.status, 429, 'direct call'); assert(Number(r.headers.get('retry-after')) >= 1, 'Retry-After header');
    });
  } finally { base = saved; await tight.stop(); }
}

// ------------------------------------------------------------------ main
let app = null;
try {
  if (process.env.BASE_URL) base = process.env.BASE_URL.replace(/\/$/, '');
  else {
    if (!existsSync(JAR)) throw new Error('No jar at ' + JAR + '. Run ./mvnw -q clean package -DskipTests first, or set BASE_URL.');
    app = new App(await freePort());
    await app.start();
    base = app.url;
  }
  log('testing', base);
  mkdirSync(OUT, { recursive: true });
  const browser = await launchChrome();
  page = await browser.newPage();
  try {
    if (wants('layout')) await layoutPhase();
    if (wants('flow')) await flowPhase();
    if (wants('a11y')) await a11yPhase();
    if (wants('recovery')) await recoveryPhase(app);
    if (wants('hosted')) await hostedPhase();
  } finally { await browser.close(); }
} catch (e) {
  results.push({ phase: 'setup', name: 'run', ok: false, error: e.stack || e.message });
  log('SETUP FAILURE', e.stack || e.message);
} finally {
  if (app) await app.stop();
}
const failed = results.filter(r => !r.ok);
const byPhase = {};
for (const r of results) { byPhase[r.phase] ??= { pass: 0, fail: 0 }; byPhase[r.phase][r.ok ? 'pass' : 'fail']++; }
writeFileSync(join(OUT, 'report.json'), JSON.stringify({ at: new Date().toISOString(), base, byPhase, results }, null, 1));
log('\n' + results.length + ' checks: ' + (results.length - failed.length) + ' passed, ' + failed.length + ' failed  ' + JSON.stringify(byPhase));
process.exit(failed.length ? 1 : 0);
