'use strict';
/* UPI Offline Mesh dashboard. Vanilla JS, no build step, no CDN: works with the network unplugged.
   Every number and row shown here comes from a backend response; nothing is simulated in the browser.
   Sections: helpers, api, polling, render (overview, map, composer, controls, packets, settlement, log),
   actions, guided scenarios, experiments, page chrome (hero, navigation), boot. */

// ============================================================ helpers
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const short = (s, n = 8) => (s || '').slice(0, n);
const money = v => '₹' + Number(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const pad2 = n => String(n).padStart(2, '0');
const clock = ms => { const d = new Date(ms); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`; };
const stamp = ms => { const d = new Date(ms); return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${clock(ms)}`; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
const num = v => (v === null || v === undefined ? NaN : Number(v)); // the backend sends "NaN" for averages with no data
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const fmtNum = v => (Number.isInteger(v) ? String(v) : String(Math.round(v * 1000) / 1000));
const plural = (n, one, many = one + 's') => `${n} ${n === 1 ? one : many}`;
const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Markup that is already safe. The html tag escapes every interpolated value unless it is itself html or an array of it. */
class Raw { constructor(s) { this.s = s; } toString() { return this.s; } }
const frag = v => (v instanceof Raw ? v.s : Array.isArray(v) ? v.map(frag).join('') : v === false || v === null || v === undefined ? '' : esc(v));
const html = (strings, ...vals) => new Raw(strings.reduce((out, s, i) => out + s + (i < vals.length ? frag(vals[i]) : ''), ''));

/** Replace a container's markup but keep keyboard focus on the same element if it still exists afterwards. */
function setHtml(host, markup) {
  const a = document.activeElement;
  let path = null, tag = null;
  if (a && a !== host && host.contains(a)) {
    path = []; tag = a.tagName;
    for (let n = a; n !== host; n = n.parentNode) path.unshift(Array.prototype.indexOf.call(n.parentNode.children, n));
  }
  host.innerHTML = markup instanceof Raw ? markup.s : String(markup);
  if (path) {
    let n = host;
    for (const i of path) n = n && n.children[i];
    if (n && n.tagName === tag && typeof n.focus === 'function') n.focus({ preventScroll: true });
  }
}

/** Write a status line only when it changed: live regions announce every write, and the page re-renders on every poll. */
function setStatus(el, text, cls) {
  if (el.textContent !== text) el.textContent = text;
  if (cls !== undefined && el.className !== cls) el.className = cls;
}

const OUTCOME_LABEL = { SETTLED: 'SETTLED', DUPLICATE_DROPPED: 'DUPLICATE', REJECTED: 'REJECTED', FAILED: 'FAILED', IN_MESH: 'IN MESH', AT_BRIDGE: 'AT BRIDGE', TTL_EXHAUSTED: 'TTL EXHAUSTED', EXPIRED: 'EXPIRED' };
const badge = s => html`<span class="badge b-${s}">${OUTCOME_LABEL[s] || s}</span>`;
const tally = summary => Object.entries(summary).map(([k, v]) => `${v} ${(OUTCOME_LABEL[k] || k).toLowerCase()}`).join(', ');

let state = null, accounts = [], txs = [], stats = null, detail = null, health = null;
let selectedPacket = null, selectedDevice = null;
let scenarioRunning = false;
const lastKeys = {};

// ============================================================ api
class ApiError extends Error {
  constructor(message, status, code, details) { super(message); this.status = status; this.code = code; this.details = details || []; }
}
let adminToken = '';
try { adminToken = sessionStorage.getItem('upimesh.admin') || ''; } catch { /* storage blocked: the token then lives for this page only */ }
let mutations = 0; // bumped whenever a write finishes, so a poll that started earlier is never shown

async function api(method, path, body) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (adminToken) headers.Authorization = 'Bearer ' + adminToken;
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store' });
  } catch {
    throw new ApiError('Cannot reach the backend', 0, 'NETWORK');
  } finally {
    if (method !== 'GET') mutations++;
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* non-JSON error page */ }
  if (!res.ok) {
    const more = data?.details?.length ? ' (' + data.details.join('; ') + ')' : '';
    throw new ApiError((data?.message || `${res.status} ${res.statusText || 'error'}`) + more, res.status, data?.error, data?.details);
  }
  return data;
}

function toast(msg, kind = '') {
  const host = $('#toasts');
  while (host.children.length >= 4) host.firstChild.remove();
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.textContent = msg;
  host.appendChild(el);
  setTimeout(() => el.remove(), kind === 'err' ? 8000 : 4200);
}

function actionError(e) {
  toast(e.message || 'Something went wrong.', 'err');
  if (e instanceof ApiError && e.code === 'ADMIN_REQUIRED') openAdmin();
}

/** Run a button action: busy state while it is in flight, a toast for the outcome, then a fresh sync with the backend. */
async function act(btn, fn, okMsg, moves = false) {
  if (btn && (btn.disabled || btn.classList.contains('busy'))) return undefined;
  if (scenarioRunning) { toast('A guided scenario is running. Wait for it to finish.', 'err'); return undefined; }
  if (btn) { btn.disabled = true; btn.classList.add('busy'); btn.setAttribute('aria-busy', 'true'); }
  if (moves) pulseMap();
  try {
    const r = await fn();
    if (okMsg) toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 'ok');
    return r;
  } catch (e) {
    actionError(e);
    return undefined;
  } finally {
    await refresh();
    if (moves) pulseMap();
    if (btn) { btn.classList.remove('busy'); btn.removeAttribute('aria-busy'); btn.disabled = false; }
    if (state) { renderControls(); renderComposer(); }
  }
}

// ============================================================ polling
// One request set at a time. A refresh asked for while one is running waits for it and then runs again, so the
// snapshot that follows an action always starts after the action finished. Results of a poll that started before
// a write completed are discarded, so the page never steps backwards.
let running = null, queued = null;
function refresh() {
  if (!running) { running = poll().finally(() => { running = null; }); return running; }
  if (!queued) queued = running.then(() => { queued = null; return refresh(); });
  return queued;
}

async function fetchDetail(id) {
  try { return await api('GET', '/api/mesh/packets/' + encodeURIComponent(id)); }
  catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
}

const link = { mode: 'connecting', lastOk: 0, fails: 0 };
async function poll() {
  const started = mutations;
  try {
    const [st, ac, tx, sx] = await Promise.all([api('GET', '/api/mesh/state'), api('GET', '/api/accounts'), api('GET', '/api/transactions'), api('GET', '/api/stats')]);
    if (started !== mutations) return;
    // Keep the selected packet only while it still exists; otherwise fall back to the newest one.
    let pid = selectedPacket;
    if (pid && !st.packets.some(p => p.packetId === pid)) pid = null;
    if (!pid && st.packets.length) pid = st.packets[0].packetId;
    const d = pid ? await fetchDetail(pid) : null;
    if (started !== mutations) return;
    selectedPacket = d ? pid : null;
    state = st; accounts = ac; txs = tx; stats = sx; detail = d;
    if (selectedDevice && !st.devices.some(x => x.id === selectedDevice)) selectedDevice = null;
    const recovered = link.mode === 'stale' || link.mode === 'down';
    setLink('live');
    render();
    if (recovered) onReconnect();
  } catch (e) {
    if (!(e instanceof ApiError)) console.error('refresh failed:', e);
    setLink('fail', e.message);
  }
}

let linkSig = '';
function setLink(kind, why) {
  if (kind === 'live') { link.mode = 'live'; link.fails = 0; link.lastOk = Date.now(); }
  else { link.fails++; link.mode = link.lastOk ? 'stale' : 'down'; }
  // The "updated" time changes on every poll; everything else is written only when it changes (the banner is a live region).
  const up = $('#statsUpdated');
  up.className = 'stats-updated' + (link.mode === 'live' ? '' : ' stale');
  up.textContent = link.mode === 'live' ? 'updated ' + clock(link.lastOk) : link.lastOk ? 'out of date, last confirmed ' + clock(link.lastOk) : 'waiting for the backend';
  const sig = link.mode + '|' + (link.mode === 'live' ? '' : why + '|' + link.lastOk);
  if (sig === linkSig) return;
  linkSig = sig;
  const pill = $('#connPill');
  pill.className = 'pill link ' + ({ live: 'ok', stale: 'stale', down: 'bad', connecting: '' }[link.mode]);
  $('span', pill).textContent = { live: 'backend live', stale: 'reconnecting', down: 'backend unreachable', connecting: 'connecting' }[link.mode];
  const b = $('#banner');
  b.hidden = link.mode === 'live';
  b.className = 'banner' + (link.mode === 'stale' ? ' stale' : '');
  if (link.mode === 'stale') b.textContent = `Cannot reach the backend (${why || 'network error'}). Showing the last confirmed data from ${clock(link.lastOk)}; it may be out of date. Retrying.`;
  else if (link.mode === 'down') b.textContent = `Cannot reach the backend (${why || 'network error'}). Nothing can be shown yet. Retrying.`;
  document.body.dataset.link = link.mode;
}

let pollTimer = null;
function schedulePoll() {
  clearTimeout(pollTimer);
  const wait = link.fails ? Math.min(8000, 2000 * 2 ** Math.min(link.fails, 2)) : 2000;
  pollTimer = setTimeout(async () => { if (!document.hidden) await refresh(); schedulePoll(); }, wait);
}

async function onReconnect() {
  // After a backend restart the mesh, experiment history and deployment mode may all be new.
  lastKeys.pdetail = null; lastKeys.stages = null;
  loadHealth();
  if (expReady) { try { await loadExpHistory(); renderExpResult(false); } catch { /* experiments panel keeps what it has */ } }
}

async function loadHealth() {
  try {
    health = await api('GET', '/api/health');
    $('#adminBox').hidden = !health.adminRequired;
    $('#adminState').textContent = adminToken ? 'token set for this tab' : 'locked';
    const foot = $('#footMode');
    foot.textContent = health.mode === 'hosted'
      ? 'Hosted demo: the simulated state is shared by everyone using it and may reset when the service restarts. Reset and settings are admin only.'
      : 'Local mode: nothing is protected and the state lives in memory. ' + (health.h2Console ? 'The H2 developer console is at /h2-console.' : '');
  } catch { /* the polling banner already reports an unreachable backend */ }
}

// ============================================================ render (all of it is driven by the last snapshot)
function safe(fn) { try { fn(); } catch (e) { console.error(fn.name + ' failed:', e); } }
function render() {
  safe(renderStats); safe(renderNetSummary); safe(() => renderTopology(false)); safe(renderDock); safe(renderControls);
  safe(renderComposer); safe(renderStages); safe(renderPackets); safe(renderLedger); safe(renderAccounts); safe(renderLog);
}

// ---- overview tiles
const TILES = [
  ['devicesTotal', 'Simulated devices', 'var(--sky)', 'Phones in the simulated mesh'],
  ['devicesActive', 'In the mesh', 'var(--sky)', 'Devices powered on and in range'],
  ['bridgesOnline', 'Bridges with internet', 'var(--teal)', 'Bridges that can reach the backend right now'],
  ['packetsAwaiting', 'Awaiting delivery', 'var(--signal)', 'Packets still travelling or queued at a bridge'],
  ['packetsForwarded', 'Packets forwarded', 'var(--signal)', 'Successful mesh transmissions since reset'],
  ['settlements', 'Settlements', 'var(--teal)', 'Ledger-effective payments (database)'],
  ['duplicatesDropped', 'Duplicates dropped', 'var(--signal)', 'Byte-identical re-deliveries dropped by the backend'],
  ['invalidRejected', 'Invalid rejected', 'var(--coral)', 'Tampered, stale, unfunded or otherwise rejected packets']
];
const tileVals = {};
function initTiles() {
  setHtml($('#stats'), html`${TILES.map(([k, label, , tip]) =>
    html`<div class="tile na" id="t-${k}" title="${tip}"><div class="k">${label}</div><div class="v">—</div></div>`)}`);
  TILES.forEach(([k, , color], i) => { const el = $('#t-' + k); el.style.setProperty('--c', color); el.style.animationDelay = i * 40 + 'ms'; }); // CSSOM, so a strict CSP can forbid inline style attributes
}
function renderStats() {
  $('#stats').setAttribute('aria-busy', 'false');
  for (const [k] of TILES) {
    const v = stats[k];
    const sub = k === 'devicesActive' ? stats.devicesTotal : k === 'bridgesOnline' ? stats.bridgesTotal : null;
    const el = $('#t-' + k);
    el.classList.remove('na');
    $('.v', el).innerHTML = esc(v) + (sub !== null ? `<small>of ${esc(sub)}</small>` : '');
    if (tileVals[k] !== undefined && tileVals[k] !== v && !reducedMotion()) { el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash'); }
    tileVals[k] = v;
  }
}

function renderNetSummary() {
  const c = state.counters;
  const key = JSON.stringify([state.round, state.topology, c]);
  if (key === lastKeys.net) return;
  lastKeys.net = key;
  $('#netSummary').innerHTML = `Round <b>${esc(state.round)}</b> on the <b>${esc(state.topology)}</b> topology. <b>${esc(c.devicesActive)}</b> of ${esc(c.devicesTotal)} devices in the mesh, `
    + `<b>${esc(c.bridgesOnline)}</b> of ${esc(c.bridgesTotal)} bridges with internet, <b>${esc(c.awaitingDelivery)}</b> ${c.awaitingDelivery === 1 ? 'packet' : 'packets'} awaiting delivery.`;
}

// ---- network map ----------------------------------------------------------------
// The simulator supplies device coordinates. They are scaled to the width of the panel (positions only: icons and
// labels keep a readable size), so the map uses the whole width on a desktop and scrolls sideways on a phone.
// The backend is placed in pixels, not in simulator units, so its upload links always have room for their outcome labels.
const MAP = { zoom: 1, min: 1, max: 2.5, minPlot: 640, dragged: false, hintDone: false };
let topoKey = '';

// One symbol family (24 units, round 1.8 strokes): a phone, a phone with an upload arrow, a phone with a radio signal.
const GLYPH = {
  relay: '<rect x="-7" y="-12" width="14" height="24" rx="3"/><line x1="-2.5" y1="8" x2="2.5" y2="8"/>',
  sender: '<rect x="-7" y="-12" width="14" height="24" rx="3"/><path d="M0 5V-5M-4 -1L0 -5L4 -1"/>',
  bridge: '<rect x="-12" y="-11" width="11" height="22" rx="3"/><path d="M5 -6a6 6 0 0 1 0 12M9 -11a12 12 0 0 1 0 22"/>'
};

function latestUploads() {
  const m = {};
  (detail?.uploads || []).forEach(u => { m[u.deviceId] = u; });
  return m;
}
function trim(a, b, ra, rb, off = 0) {
  const dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy) || 1, nx = -dy / d * off, ny = dx / d * off;
  return { x1: a.x + dx / d * ra + nx, y1: a.y + dy / d * ra + ny, x2: b.x - dx / d * rb + nx, y2: b.y - dy / d * rb + ny };
}
const r1 = v => Math.round(v * 10) / 10;

/** Dashes on the transmission and upload lines move for a moment while the network is advanced, then stop. */
let pulseTimer = 0;
function pulseMap() {
  if (reducedMotion()) return;
  const topo = $('#topo');
  topo.classList.add('run');
  clearTimeout(pulseTimer);
  pulseTimer = setTimeout(() => topo.classList.remove('run'), 1800);
}

function renderTopology(force) {
  const scroll = $('#mapScroll');
  const W = Math.max(280, scroll.clientWidth);
  const uploads = latestUploads();
  const key = JSON.stringify([W, innerHeight, MAP.zoom, state.round, state.topology, state.devices.map(d => [d.id, d.online, d.active, d.x, d.y, d.copies.map(c => [c.packetId, c.ttl, c.hops, c.uploadState, c.expired])]),
    state.lastTransfers, selectedPacket, selectedDevice, uploads, stats.settlements]);
  if (!force && key === topoKey) return;
  topoKey = key;

  const devs = state.devices, byId = Object.fromEntries(devs.map(d => [d.id, d]));
  const xs = devs.map(d => d.x), ys = devs.map(d => d.y), minX = Math.min(...xs), minY = Math.min(...ys);
  const spanX = Math.max(1, Math.max(...xs) - minX), spanY = Math.max(1, Math.max(...ys) - minY);
  const plotW = Math.max(window.innerWidth < 700 ? MAP.minPlot + 80 : MAP.minPlot, Math.round(W * MAP.zoom)); // a phone scrolls the map instead of squeezing the labels together
  // Icons and labels grow with the map but never so much that the rows of devices (tag above, name and status below) need more than about half the window height.
  const u = clamp(Math.min(plotW / 1100, innerHeight * 0.52 * Math.max(1, MAP.zoom) / (150 + 0.9 * spanY)), 1, 1.4);
  const R = 24 * u, padX = 52 * u, padTop = 78 * u, padBottom = 72 * u, bw = 64 * u, gap = 130 * u;
  const sx = (plotW - 2 * padX - R - gap - bw) / spanX;
  const need = padTop + padBottom + spanY * 0.9 * u;                       // least height at which neighbouring rows keep room for their labels
  const maxH = Math.max(need, 440 * Math.max(1, MAP.zoom));
  let sy = Math.max(sx, 0.9 * u), plotH = padTop + padBottom + spanY * sy;
  if (plotH > maxH) { plotH = maxH; sy = (maxH - padTop - padBottom) / spanY; }
  plotH = Math.round(Math.max(plotH, 360));
  const offY = (plotH - padTop - padBottom - spanY * sy) / 2;
  const pos = Object.fromEntries(devs.map(d => [d.id, { x: r1(padX + (d.x - minX) * sx), y: r1(padTop + offY + (d.y - minY) * sy) }]));

  // backend: one box to the right of every device, one port per bridge (top to bottom), each upload link ends on its port
  const bridges = devs.filter(d => d.role === 'BRIDGE').sort((a, b) => pos[a.id].y - pos[b.id].y);
  const bh = Math.max(92 * u, (bridges.length - 1) * 40 * u + 72 * u);
  const bx = r1(Math.max(...devs.map(d => pos[d.id].x)) + R + gap + bw / 2);
  const meanY = bridges.length ? bridges.reduce((s, d) => s + pos[d.id].y, 0) / bridges.length : plotH / 2;
  const by = r1(clamp(meanY, 16 * u + bh / 2, plotH - 44 * u - bh / 2));
  const step = bridges.length > 1 ? Math.min(40 * u, (bh - 52 * u) / (bridges.length - 1)) : 0;
  const port = Object.fromEntries(bridges.map((d, i) => [d.id, { x: r1(bx - bw / 2), y: r1(by + (i - (bridges.length - 1) / 2) * step) }]));

  const arrow = (id, cls) => `<marker id="${id}" viewBox="0 0 10 10" refX="9" refY="5" markerUnits="userSpaceOnUse" markerWidth="${11 * u}" markerHeight="${11 * u}" orient="auto"><path class="${cls}" d="M0 0L10 5L0 10z"/></marker>`;
  let svg = `<defs>${arrow('ah', 'mk-sig')}${arrow('ahl', 'mk-lost')}${arrow('ahn', 'mk-net')}</defs>`;

  for (const l of state.links) {
    const a = byId[l.a], b = byId[l.b];
    svg += `<line class="link ${a.active && b.active ? '' : 'down'}" x1="${pos[l.a].x}" y1="${pos[l.a].y}" x2="${pos[l.b].x}" y2="${pos[l.b].y}"/>`;
  }
  for (const d of bridges) {
    // the link says what the bridge can do (online: solid teal, offline: dotted) and, for the selected packet, what it did (dashed teal arrow)
    const live = d.online && d.active, upl = uploads[d.id], sent = live && upl, t = trim(pos[d.id], port[d.id], R + 3 * u, 0);
    svg += `<path class="net ${live ? 'up' : ''} ${sent ? 'sent' : ''}" d="M${r1(t.x1)} ${r1(t.y1)} L${r1(t.x2)} ${r1(t.y2)}" ${sent ? 'marker-end="url(#ahn)"' : ''}/>`;
    if (upl) {
      const mx = (t.x1 + t.x2) / 2, my = (t.y1 + t.y2) / 2, label = OUTCOME_LABEL[upl.outcome] || upl.outcome;
      svg += `<g class="ubadge u-${esc(upl.outcome)}" transform="translate(${r1(mx)} ${r1(my)})"><rect x="${-44 * u}" y="${-10 * u}" width="${88 * u}" height="${20 * u}" rx="${10 * u}"/><text y="${4 * u}">${esc(label)}</text></g>`;
    }
  }
  const rows = clamp(Math.floor((bh - 24 * u) / (22 * u)), 3, 6);
  let rack = '';
  for (let k = 0; k < rows; k++) {
    const cy = by + (k - (rows - 1) / 2) * 22 * u;
    rack += `<rect class="unit" x="${r1(bx - 20 * u)}" y="${r1(cy - 6 * u)}" width="${40 * u}" height="${12 * u}" rx="${3 * u}"/><circle class="led" cx="${r1(bx + 12 * u)}" cy="${r1(cy)}" r="${1.8 * u}"/>`;
  }
  svg += `<g class="backend"><title>Backend: validates, de-duplicates and settles</title><rect class="box" x="${r1(bx - bw / 2)}" y="${r1(by - bh / 2)}" width="${bw}" height="${r1(bh)}" rx="${10 * u}"/>${rack}`
    + bridges.map(d => `<circle class="port ${d.online && d.active ? 'up' : ''}" cx="${port[d.id].x}" cy="${port[d.id].y}" r="${4.5 * u}"/>`).join('')
    + `<text class="bname" x="${bx}" y="${r1(by + bh / 2 + 17 * u)}">BACKEND</text><text class="bsub ${stats.settlements ? 'ok' : ''}" x="${bx}" y="${r1(by + bh / 2 + 32 * u)}">${esc(stats.settlements)} settled</text></g>`;

  state.lastTransfers.forEach((t, i) => {
    const a = pos[t.from], b = pos[t.to];
    if (!a || !b) return;
    const p = trim(a, b, R + 3 * u, R + 7 * u, (6 + (i % 2) * 2) * u), lost = t.kind === 'LOST';
    svg += `<line class="xfer ${lost ? 'lost' : ''}" x1="${r1(p.x1)}" y1="${r1(p.y1)}" x2="${r1(p.x2)}" y2="${r1(p.y2)}" marker-end="url(#${lost ? 'ahl' : 'ah'})"><title>${esc(short(t.packetId))} ${esc(t.from)} to ${esc(t.to)}${lost ? ' (lost)' : ''}</title></line>`;
  });
  for (const d of devs) {
    const p = pos[d.id], mine = d.copies.find(c => c.packetId === selectedPacket), sel = selectedDevice === d.id;
    const isBridge = d.role === 'BRIDGE', isSender = d.id === state.senderDevice, role = isSender ? 'sender' : isBridge ? 'bridge' : 'relay';
    const waiting = isBridge ? d.copies.filter(c => c.uploadState === 'PENDING' && !c.expired).length : 0; // copies this bridge still has to upload
    const tip = `${d.id}: ${isSender ? 'sender phone' : isBridge ? 'bridge' : 'relay'}, ${d.active ? 'in the mesh' : 'out of the mesh'}${isBridge ? (d.online ? ', has internet' : ', no internet') : ''}, holds ${plural(d.copies.length, 'packet')}${waiting ? `, ${waiting} waiting to upload` : ''}${sel ? ', selected' : ''}`;
    // what the selected packet is doing here: muted TTL and hop, amber only when the TTL is nearly spent
    const ty = -R - 11 * u;
    const tag = !d.active ? `<text class="tag" y="${ty}">out of mesh</text>`
      : !mine ? ''
        : mine.expired ? `<text class="tag" y="${ty}">expired</text>`
          : mine.uploadState === 'ACKED' ? `<text class="tag" y="${ty}">uploaded</text>`
            : `<text class="tag ${mine.ttl <= 1 ? 'low' : ''}" y="${ty - 15 * u}">ttl ${mine.ttl}</text><text class="tag" y="${ty}">hop ${mine.hops}</text>`;
    const sub = isSender ? `<text class="sub send" y="${R + 33 * u}">sender phone</text>`
      : isBridge ? `<text class="sub ${d.online ? 'on' : ''}" y="${R + 33 * u}">${d.online ? 'internet' : 'no internet'}</text>` : '';
    svg += `<g class="node ${role} ${d.active ? '' : 'off'} ${sel ? 'sel' : ''}" data-id="${esc(d.id)}" transform="translate(${p.x} ${p.y})" tabindex="0" role="button" aria-pressed="${sel}" aria-label="${esc(tip)}">`
      + `<title>${esc(tip)}</title><circle class="ring" r="${R + 7 * u}"/><circle class="body" r="${R}"/>`
      + `<g class="glyph" transform="scale(${u})">${GLYPH[role]}</g>`
      + (isBridge ? `<circle class="dot ${d.online ? 'on' : ''}" cx="${r1(R * .72)}" cy="${r1(R * .72)}" r="${5 * u}"/>` : '')
      + (d.copies.length ? `<circle class="cnt ${waiting ? 'wait' : ''}" cx="${R - 2 * u}" cy="${-R + 2 * u}" r="${9.5 * u}"/><text class="badge-t ${waiting ? 'wait' : ''}" x="${R - 2 * u}" y="${-R + 6 * u}">${d.copies.length > 99 ? '99+' : d.copies.length}</text>` : '')
      + `<text class="name" y="${R + 18 * u}">${esc(d.label)}</text>${sub}${tag}</g>`;
  }
  const topo = $('#topo');
  topo.setAttribute('width', plotW); topo.setAttribute('height', plotH);
  topo.setAttribute('viewBox', `0 0 ${plotW} ${plotH}`);
  topo.style.setProperty('--u', u);
  topo.classList.toggle('dense', state.links.length > 14); // a full mesh draws every pair: keep its lines quieter than the packets on them
  setHtml(topo, svg);
  updateMapChrome();
}

function updateMapChrome() {
  const scroll = $('#mapScroll');
  scroll.classList.toggle('zoomed', MAP.zoom > 1);
  const overflowX = scroll.scrollWidth > scroll.clientWidth + 2;
  $('#panHint').hidden = !overflowX || MAP.hintDone;
  $('#mapOut').disabled = MAP.zoom <= MAP.min;
  $('#mapIn').disabled = MAP.zoom >= MAP.max;
  $('#mapFit').disabled = MAP.zoom === 1 && scroll.scrollLeft === 0 && scroll.scrollTop === 0;
  $('#mapFit').title = `Fit the whole network in view (now ${Math.round(MAP.zoom * 100)}%)`;
}
function setZoom(z) {
  const scroll = $('#mapScroll');
  const fx = (scroll.scrollLeft + scroll.clientWidth / 2) / Math.max(1, scroll.scrollWidth);
  const fy = (scroll.scrollTop + scroll.clientHeight / 2) / Math.max(1, scroll.scrollHeight);
  MAP.zoom = clamp(Math.round(z * 100) / 100, MAP.min, MAP.max);
  renderTopology(true);
  scroll.scrollLeft = fx * scroll.scrollWidth - scroll.clientWidth / 2;
  scroll.scrollTop = fy * scroll.scrollHeight - scroll.clientHeight / 2;
  updateMapChrome();
}
function selectDevice(id) {
  selectedDevice = id; lastKeys.dock = null;
  renderTopology(true); renderDock();
}

// ---- selected device (directly under the map)
function renderDock() {
  const host = $('#deviceDock');
  const d = state.devices.find(x => x.id === selectedDevice);
  const key = JSON.stringify([d, state.senderDevice, selectedPacket]);
  if (key === lastKeys.dock) return;
  lastKeys.dock = key;
  if (!d) { host.className = 'dock empty-dock'; setHtml(host, html`Select a device on the map to see its role, neighbours and stored packets, or to take it out of the mesh.`); return; }
  host.className = 'dock';
  const isBridge = d.role === 'BRIDGE', sender = d.id === state.senderDevice;
  const roleNote = sender ? 'The sender phone: it creates and encrypts payments and hands them to the mesh.'
    : isBridge ? 'A bridge: it relays like any phone and uploads to the backend while it has internet.'
      : 'A relay: it stores packets and forwards them to its neighbours.';
  setHtml(host, html`
    <div>
      <h4>${d.label === d.id ? d.id : d.label + ' (' + d.id + ')'}</h4>
      <div class="badges">
        <span class="chip">${isBridge ? 'Bridge' : 'Relay'}${sender ? ', sender phone' : ''}</span>
        <span class="chip ${d.active ? 'ok' : 'warn'}">${d.active ? 'In the mesh' : 'Out of the mesh'}</span>
        ${isBridge && html`<span class="chip ${d.online ? 'ok' : 'warn'}">${d.online ? 'Internet on' : 'No internet'}</span>`}
      </div>
      <p class="role-note">${roleNote}</p>
      <div class="actions">
        <button class="btn sm" type="button" data-dev-act="active">${d.active ? 'Take out of mesh' : 'Bring back into mesh'}</button>
        ${isBridge && html`<button class="btn sm" type="button" data-dev-act="online">${d.online ? 'Cut internet' : 'Give internet'}</button>`}
      </div>
    </div>
    <dl>
      <dt>Neighbours</dt>
      <dd>${d.neighbors.length ? html`<div class="chips">${d.neighbors.map(n => html`<button class="chip link-chip" type="button" data-select-device="${n}">${n}</button>`)}</div>` : 'none'}</dd>
      <dt>Mesh status</dt>
      <dd>${d.active ? 'Powered on and in range: it forwards and receives.' : 'Out of range: it neither forwards, receives nor uploads. Its stored copies are kept.'}</dd>
      ${isBridge && html`<dt>Internet</dt><dd>${d.online ? (d.active ? 'Connected: it uploads the packets it holds when you press "Bridges upload".' : 'Connected, but a device out of the mesh cannot upload.') : 'Not connected: it holds packets until it gets internet.'}</dd>`}
      <dt>Packets held (${d.copies.length})</dt>
      <dd>${d.copies.length ? html`<ul class="held">${d.copies.slice(0, 12).map(c => html`<li><button class="chip link-chip" type="button" data-select-packet="${c.packetId}">${short(c.packetId)}</button> ttl ${c.ttl}, hop ${c.hops}, from ${c.receivedFrom || 'origin'}${isBridge ? ', ' + (c.expired ? 'expired' : c.uploadState === 'ACKED' ? 'uploaded' : 'waiting to upload' + (c.uploadAttempts ? ' (' + c.uploadAttempts + ' attempts)' : '')) : ''}</li>`)}</ul>${d.copies.length > 12 && html`<p class="note">and ${d.copies.length - 12} more: see Packet inspection for the full list.</p>`}` : html`<span class="muted">none</span>`}</dd>
    </dl>`);
}

// ---- controls ---------------------------------------------------------------
function pendingUploadsAtOnlineBridges() {
  return state.devices.some(d => d.role === 'BRIDGE' && d.online && d.active && d.copies.some(c => c.uploadState === 'PENDING' && !c.expired));
}
function renderControls() {
  const bridges = state.devices.filter(d => d.role === 'BRIDGE');
  const tkey = JSON.stringify(bridges.map(b => [b.id, b.online, b.active]));
  if (tkey !== lastKeys.toggles) {
    lastKeys.toggles = tkey;
    setHtml($('#bridgeToggles'), html`${bridges.map(b => html`<button type="button" class="tog ${b.online ? 'on' : ''}" data-id="${b.id}" aria-pressed="${String(b.online)}" ${b.active ? '' : 'disabled title="Out of the mesh"'}><i></i>${b.id} ${b.online ? 'online' : 'offline'}</button>`)}`);
  }
  const hasPackets = state.packets.length > 0;
  const anyOnline = bridges.some(b => b.online && b.active);
  const canUpload = pendingUploadsAtOnlineBridges();
  const set = (sel, blocked, why) => { const el = $(sel); el.disabled = blocked || el.classList.contains('busy'); el.title = blocked ? why : ''; };
  set('#btnGossip', !hasPackets, 'No packets in the mesh yet: create a payment first.');
  set('#btnGossipN', !hasPackets, 'No packets in the mesh yet: create a payment first.');
  set('#btnFlush', !canUpload, !anyOnline ? 'No bridge has internet: switch a bridge online first.' : 'The online bridges hold nothing to upload yet: run gossip rounds first.');
  const hint = $('#ctlHint');
  if (!hasPackets) setStatus(hint, 'Create a payment to start. Gossip and upload unlock once a packet exists.', 'hint');
  else if (!anyOnline) setStatus(hint, 'All bridges are offline: packets can spread but nothing reaches the backend until a bridge gets internet.', 'hint warn');
  else if (!canUpload) setStatus(hint, 'Online bridges have nothing to upload yet: keep gossiping until a copy reaches one.', 'hint');
  else setStatus(hint, 'An online bridge holds packets: press "Bridges upload".', 'hint');

  const s = state.settings, focus = document.activeElement?.id;
  const setVal = (id, v) => { if (focus !== id) $('#' + id).value = v; };
  setVal('sTtl', s.defaultTtl); setVal('sDelay', s.delayRounds); setVal('sExpiry', s.packetExpirySeconds); setVal('sSeed', s.seed);
  setVal('sLoss', Math.round(s.lossProbability * 100));
  $('#sLossOut').textContent = Math.round(s.lossProbability * 100) + '%';
  if (focus !== 'sTopo' && $('#topoConfirm').hidden) $('#sTopo').value = state.topology;
  $('#settingsSummary').textContent = `${state.topology} topology, loss ${Math.round(s.lossProbability * 100)}%, delay ${s.delayRounds}, TTL ${s.defaultTtl}, expiry ${s.packetExpirySeconds} s, seed ${s.seed}`;
}

// ---- composer ---------------------------------------------------------------
let lastDefaultTtl = null;
function fillSelect(sel, items, preferred) {
  const el = $(sel), key = JSON.stringify(items);
  if (el.dataset.key === key) return;
  const keep = el.value;
  el.dataset.key = key;
  setHtml(el, html`${items.map(i => html`<option value="${i}">${i}</option>`)}`);
  el.value = items.includes(keep) ? keep : (preferred || items[0]);
}
function renderComposer() {
  fillSelect('#fSender', accounts.map(a => a.vpa));
  fillSelect('#fReceiver', accounts.map(a => a.vpa), accounts[1]?.vpa);
  fillSelect('#fStart', state.devices.map(d => d.id), state.senderDevice);
  const s = state.settings, focus = document.activeElement?.id;
  if (lastDefaultTtl !== s.defaultTtl) { lastDefaultTtl = s.defaultTtl; if (focus !== 'fTtl') $('#fTtl').value = s.defaultTtl; }
  validateSend();
}
const MODE_NOTES = {
  NORMAL: '',
  DUPLICATE: 'Duplicate: a copy is also placed on every bridge, so several bridges upload identical bytes.',
  TAMPERED: 'Tampered: one ciphertext byte is flipped; the backend must reject it.',
  EXPIRED: 'Expired: signed outside the 24 h freshness window; the backend must reject it.'
};
function validateSend() {
  const from = $('#fSender').value, to = $('#fReceiver').value, raw = $('#fAmount').value.trim(), amt = parseFloat(raw);
  const ttlRaw = $('#fTtl').value.trim(), ttl = Number(ttlRaw);
  let amountMsg = '', ttlMsg = '', why = '';
  if (!(amt > 0)) amountMsg = 'Enter an amount above zero.';
  else if (!/^\d{1,12}(\.\d{1,2})?$/.test(raw)) amountMsg = 'Use up to 12 digits and 2 decimal places.';
  if (ttlRaw === '' || !Number.isInteger(ttl) || ttl < 1 || ttl > 32) ttlMsg = 'Whole number from 1 to 32.';
  if (from === to) why = 'Sender and receiver must be different accounts.';
  else if (amountMsg || ttlMsg) why = amountMsg || ttlMsg;
  setField('#fAmount', '#fAmountMsg', amountMsg);
  setField('#fTtl', '#fTtlMsg', ttlMsg);
  const btn = $('#btnSend');
  btn.disabled = !!why || btn.classList.contains('busy');
  btn.title = why;
  const mode = $('#fMode').value;
  $('#modeNote').textContent = MODE_NOTES[mode];
  const bal = accounts.find(a => a.vpa === from)?.balance;
  const h = $('#sendHint');
  let text = why, cls = 'hint warn';
  if (!why && bal !== undefined && amt > Number(bal) && mode !== 'TAMPERED' && mode !== 'EXPIRED') {
    text = `${from} has ${money(bal)}. The sender phone is offline and cannot check this: the backend will reject the payment as INSUFFICIENT_FUNDS when it arrives.`;
  } else if (!why) {
    text = `Ready. The payment is encrypted on the sender's phone and injected at ${$('#fStart').value || 'the sender'}.`; cls = 'hint';
  }
  setStatus(h, text, cls);
}
function setField(inputSel, msgSel, msg) {
  const input = $(inputSel);
  $(msgSel).textContent = msg;
  if (msg) input.setAttribute('aria-invalid', 'true'); else input.removeAttribute('aria-invalid');
}

// ---- stages
function renderStages() {
  const key = JSON.stringify([detail?.packetId, detail?.status, detail?.events?.length, detail?.uploads?.length, detail?.finalOutcome]);
  if (key === lastKeys.stages) return;
  lastKeys.stages = key;
  const d = detail, has = t => !!d && d.events.some(e => e.type === t);
  const fwd = d ? d.events.filter(e => e.type === 'FORWARDED').length : 0;
  const atBridge = !!d && d.holders.some(h => h.role === 'BRIDGE');
  const out = d?.finalOutcome;
  const tone = out === 'SETTLED' ? 'good' : out === 'REJECTED' ? 'bad' : out ? 'dupe' : '';
  const stages = [
    ['Created', has('CREATED'), '', ''], ['Encrypted', has('ENCRYPTED'), 'AES-GCM + RSA', ''],
    ['Injected', has('INJECTED'), d?.origin || '', ''], ['Forwarded', fwd > 0, fwd ? fwd + ' sent' : '', ''],
    ['At bridge', atBridge, '', ''], ['Submitted', !!d && d.uploads.length > 0, d?.uploads.length ? plural(d.uploads.length, 'upload') : '', ''],
    [out ? (OUTCOME_LABEL[out] || out).toLowerCase().replace(/^./, c => c.toUpperCase()) : 'Outcome', !!out, '', tone]];
  setHtml($('#stages'), html`${stages.map(([n, done, note, t]) => html`<li class="${done ? 'done ' + t : ''}">${n}${note && html`<small>${note}</small>`}</li>`)}`);
}

// ---- packets ----------------------------------------------------------------
function renderPackets() {
  const list = $('#packetList');
  const lk = JSON.stringify([state.packets.map(p => [p.packetId, p.status, p.holders.length]), selectedPacket]);
  if (lk !== lastKeys.plist) {
    lastKeys.plist = lk;
    setHtml(list, state.packets.length ? html`${state.packets.slice(0, 20).map(p => html`
      <button type="button" class="pk ${p.packetId === selectedPacket ? 'sel' : ''}" data-id="${p.packetId}" aria-pressed="${String(p.packetId === selectedPacket)}">
        <span class="row"><b>${short(p.packetId)}</b>${badge(p.status)}</span>
        <span class="row muted"><span>sha ${short(p.fingerprint, 10)}</span><span>${plural(p.holders.length, 'copy', 'copies')}</span></span>
      </button>`)}${state.packets.length > 20 && html`<p class="note">Showing the newest 20 of ${state.packets.length} packets.</p>`}`
      : html`<p class="empty">No packets yet. Create a payment in the Mesh section.</p>`);
  }
  const host = $('#packetDetail');
  if (!detail) { setHtml(host, html`<p class="empty">Select or create a packet to see its fingerprint, holders, forwarding history and backend outcome.</p>`); lastKeys.pdetail = null; return; }
  const mine = txs.filter(t => t.meshPacketId === detail.packetId);
  const dk = JSON.stringify([detail, mine.map(t => t.id)]);
  if (dk === lastKeys.pdetail) return;
  if (host.contains(document.activeElement) && document.activeElement.tagName === 'INPUT') return; // do not steal focus while typing
  lastKeys.pdetail = dk;
  const times = $('#atkTimes')?.value || '3';
  const fullCipher = detail.ciphertext || detail.ciphertextPreview;
  setHtml(host, html`
    <dl class="facts">
      <dt>Packet</dt><dd class="val"><span class="mono">${detail.packetId}</span> ${badge(detail.status)}</dd>
      <dt>Ciphertext SHA-256</dt><dd class="val"><span class="mono">${detail.fingerprint}</span><button class="btn sm" type="button" data-copy="${detail.fingerprint}" data-what="fingerprint">Copy</button></dd>
      <dt>Ciphertext (opaque)</dt><dd>
        <span class="mono muted">${detail.ciphertextPreview}</span>
        <span class="muted small"> ${detail.ciphertextLength} Base64 characters. Relays cannot read it.</span>
        <details class="cipher"><summary>Show the full ciphertext</summary>
          <pre class="mono">${fullCipher}</pre>
          <button class="btn sm" type="button" data-copy="${fullCipher}" data-what="ciphertext">Copy ciphertext</button>
        </details></dd>
      <dt>Created</dt><dd>${clock(detail.createdAt)}, starting TTL ${detail.initialTtl}, origin ${detail.origin}</dd>
      <dt>Backend outcome</dt><dd>${mine.length ? mine.map(t => html`<div class="val">${badge(t.outcome)} <span>${t.reason ? t.reason + ' · ' : ''}via ${t.bridgeNodeId}${t.ledgerEntryId ? ' · ledger #' + t.ledgerEntryId : ''}</span></div>`) : html`<span class="muted">no backend decision yet</span>`}</dd>
    </dl>
    <div class="cols2">
      <div>
        <div class="sub">Devices holding a copy (${detail.holders.length})</div>
        <div class="tbl-scroll short" role="region" aria-label="Devices holding a copy, scrollable" tabindex="0"><table class="data holders"><thead><tr><th scope="col">Device</th><th scope="col" class="num">TTL</th><th scope="col" class="num">Hops</th><th scope="col">From</th><th scope="col">Upload</th></tr></thead><tbody>
        ${detail.holders.map(h => html`<tr><td class="mono">${h.deviceId}</td><td class="num">${h.ttl}</td><td class="num">${h.hops}</td><td class="mono">${h.receivedFrom || 'origin'}</td><td class="mono">${h.role === 'BRIDGE' ? (h.expired ? 'expired' : h.uploadState.toLowerCase() + (h.uploadAttempts ? ' ×' + h.uploadAttempts : '')) : '—'}</td></tr>`)}
        </tbody></table></div>
        <div class="sub">Bridge upload attempts</div>
        ${detail.uploads.length ? html`<div class="uploads">${detail.uploads.map(u => html`<div>${badge(u.outcome)} <span class="mono">${u.deviceId}</span> round ${u.round}${u.reason ? ' · ' + u.reason : ''}<span class="muted"> · ${u.message || ''}</span></div>`)}</div>` : html`<span class="muted small">none yet</span>`}
      </div>
      <div>
        <div class="sub first">Forwarding history</div>
        <ul class="timeline" tabindex="0" aria-label="Forwarding history, scrollable">${detail.events.map(e => html`<li class="t-${e.type}"><span class="r">r${e.round}</span><b>${e.type.toLowerCase().replace('_', ' ')}</b> ${e.from || ''}${e.to ? ' → ' + e.to : ''} <span class="muted">${e.detail || ''}</span></li>`)}</ul>
      </div>
    </div>
    <div class="attack-bar">
      <span class="sub">Replay attacks on this packet</span>
      <button class="btn sm" type="button" data-act="identical">Submit identical bytes</button>
      <input class="times" id="atkTimes" type="number" min="1" max="50" value="${times}" aria-label="Number of copies to submit at once">
      <span class="muted small">copies at once to the backend</span>
      <button class="btn sm" type="button" data-act="repackage">Repackage the same payment (new ciphertext)</button>
      <p class="attack-note">Identical bytes are caught by the ciphertext fingerprint. A repackaged payment has new bytes, so the payment nonce catches it instead.</p>
    </div>`);
}

// ---- settlement ---------------------------------------------------------------
function renderLedger() {
  const key = JSON.stringify(txs.map(t => t.id));
  if (key === lastKeys.ledger) return;
  lastKeys.ledger = key;
  $('#ledgerEmpty').hidden = txs.length > 0;
  $('#ledgerNote').textContent = txs.length ? `${plural(txs.length, 'delivery', 'deliveries')}, newest first${txs.length >= 50 ? ' (the latest 50 are shown)' : ''}` : 'every delivery the backend handled, newest first';
  setHtml($('#ledger tbody'), html`${txs.map(t => html`<tr class="o-${t.outcome}">
    <td class="mono small nw" title="${stamp(Date.parse(t.at))}">${clock(Date.parse(t.at))}</td>
    <td>${badge(t.outcome)}${t.reason && html`<span class="reason">${t.reason}</span>`}</td>
    <td class="mono small">${t.senderVpa ? t.senderVpa + ' → ' + t.receiverVpa : html`<span class="muted">unreadable (encrypted)</span>`}</td>
    <td class="num">${t.amount != null ? money(t.amount) : html`<span class="na" title="The payload could not be read, so there is no amount">n/a</span>`}</td>
    <td class="mono small nw">${t.bridgeNodeId}</td><td class="num">${t.hopCount}</td>
    <td class="mono small nw"><button type="button" class="copy" data-copy="${t.packetHash}" data-what="packet hash" title="Copy the full hash ${t.packetHash}">${short(t.packetHash, 10)}</button></td></tr>`)}`);
}
const prevBal = {};
function renderAccounts() {
  const key = JSON.stringify(accounts.map(a => [a.vpa, a.balance]));
  if (key === lastKeys.accounts) return;
  lastKeys.accounts = key;
  setHtml($('#accounts tbody'), html`${accounts.map(a => {
    const bal = Number(a.balance), p = prevBal[a.vpa];
    const cls = p === undefined || p === bal ? '' : bal > p ? 'up' : 'down';
    prevBal[a.vpa] = bal;
    return html`<tr><td class="mono">${a.vpa}</td><td>${a.holderName}</td><td class="bal ${cls}">${money(bal)}</td></tr>`;
  })}`);
  const total = accounts.reduce((s, a) => s + Number(a.balance), 0);
  $('#moneyTotal').textContent = `Total across accounts: ${money(total)}. It must stay constant: payments only move money.`;
}

// ---- activity log
function renderLog() {
  const ev = [...state.events].reverse();
  const key = ev.map(e => e.seq).join(',');
  if (key === lastKeys.log) return;
  lastKeys.log = key;
  const host = $('#log'), before = host.scrollHeight, top = host.scrollTop;
  setHtml(host, ev.length ? html`${ev.map(e => html`<div class="ev"><span title="${stamp(e.atMillis)}">${clock(e.atMillis)}</span><span class="rd">r${e.round}</span><span class="ty ${e.type}">${e.type}</span>
    <span class="what">${e.packetId && html`<b>${short(e.packetId)}</b> `}${e.from || ''}${e.to ? ' → ' + e.to : ''} <span class="muted">${e.detail || ''}</span></span></div>`)}` : html`<p class="empty">Nothing has happened yet.</p>`);
  if (top > 0) host.scrollTop = top + (host.scrollHeight - before); // new rows arrive on top: keep what the reader was looking at
}

// ============================================================ actions
function copyText(text, what) {
  const done = () => toast(`Copied the ${what}.`, 'ok');
  if (navigator.clipboard?.writeText) { navigator.clipboard.writeText(text).then(done, () => legacyCopy(text, done)); return; }
  legacyCopy(text, done);
}
function legacyCopy(text, done) {
  const ta = document.createElement('textarea');
  ta.value = text; ta.setAttribute('readonly', ''); ta.className = 'copy-buffer';
  document.body.appendChild(ta); ta.select();
  try { document.execCommand('copy') ? done() : toast('Could not copy. Select the text and copy it manually.', 'err'); }
  catch { toast('Could not copy. Select the text and copy it manually.', 'err'); }
  ta.remove();
}

const whole = v => (/^\d+$/.test(v) ? Number(v) : NaN);
const signed = v => (/^-?\d+$/.test(v) ? Number(v) : NaN);
const settingsRules = {
  sTtl: { field: 'defaultTtl', label: 'Default TTL', parse: whole, ok: v => v >= 1 && v <= 32, hint: 'a whole number from 1 to 32' },
  sDelay: { field: 'delayRounds', label: 'Delay', parse: whole, ok: v => v >= 0 && v <= 20, hint: 'a whole number from 0 to 20' },
  sExpiry: { field: 'packetExpirySeconds', label: 'Packet expiry', parse: whole, ok: v => v >= 1, hint: 'a whole number of seconds, at least 1' },
  sSeed: { field: 'seed', label: 'Seed', parse: signed, ok: Number.isSafeInteger, hint: 'a whole number' }
};

function wire() {
  // composer
  $('#sendForm').addEventListener('submit', e => {
    e.preventDefault();
    if ($('#btnSend').disabled) return;
    const body = {
      senderVpa: $('#fSender').value, receiverVpa: $('#fReceiver').value, amount: parseFloat($('#fAmount').value),
      ttl: parseInt($('#fTtl').value, 10), startDevice: $('#fStart').value, mode: $('#fMode').value
    };
    act($('#btnSend'), async () => {
      const r = await api('POST', '/api/demo/send', body);
      selectedPacket = r.packetId; lastKeys.pdetail = null; lastKeys.stages = null;
      return r;
    }, r => `Packet ${short(r.packetId)} encrypted and injected at ${r.injectedAt} (TTL ${r.ttl}${r.replicatedTo.length ? ', copied to ' + plural(r.replicatedTo.length, 'bridge') : ''}).`);
  });
  ['fSender', 'fReceiver', 'fAmount', 'fMode', 'fTtl', 'fStart'].forEach(id => $('#' + id).addEventListener('input', () => { if (state) validateSend(); }));

  // advance the network
  $('#btnGossip').addEventListener('click', e => act(e.currentTarget, () => api('POST', '/api/mesh/gossip', { rounds: 1 }),
    r => `Gossip round: ${r.transfers} sent, ${r.lost} lost, ${r.suppressed} duplicate offers suppressed.`, true));
  $('#btnGossipN').addEventListener('click', () => {
    const n = whole($('#gRounds').value.trim());
    if (!(n >= 1 && n <= 50)) { toast('Gossip rounds must be a whole number from 1 to 50.', 'err'); $('#gRounds').focus(); return; }
    act($('#btnGossipN'), () => api('POST', '/api/mesh/gossip', { rounds: n }), r => `${n} gossip rounds: ${r.transfers} sent, ${r.lost} lost, ${r.suppressed} suppressed.`, true);
  });
  $('#btnFlush').addEventListener('click', e => act(e.currentTarget, () => api('POST', '/api/mesh/flush'), r => {
    return `${plural(r.uploadsAttempted, 'upload')}: ${tally(r.summary) || 'nothing to upload'}.`;
  }, true));
  $('#bridgeToggles').addEventListener('click', e => {
    const b = e.target.closest('.tog'); if (!b || b.disabled) return;
    const on = !b.classList.contains('on');
    act(b, () => api('PATCH', '/api/mesh/devices/' + encodeURIComponent(b.dataset.id), { online: on }), `${b.dataset.id} is now ${on ? 'online' : 'offline'}.`);
  });

  // simulation settings apply on change
  const put = body => act(null, () => api('PUT', '/api/mesh/settings', body), 'Setting saved.');
  for (const [id, rule] of Object.entries(settingsRules)) {
    $('#' + id).addEventListener('change', e => {
      const v = rule.parse(e.target.value.trim());
      if (Number.isNaN(v) || !rule.ok(v)) {
        $('#settingsMsg').textContent = `${rule.label} must be ${rule.hint}.`; e.target.setAttribute('aria-invalid', 'true'); return;
      }
      $('#settingsMsg').textContent = ''; e.target.removeAttribute('aria-invalid');
      put({ [rule.field]: v });
    });
  }
  $('#sLoss').addEventListener('input', e => { $('#sLossOut').textContent = e.target.value + '%'; });
  $('#sLoss').addEventListener('change', e => put({ lossProbability: parseInt(e.target.value, 10) / 100 }));

  // topology change resets everything, so it asks first
  $('#sTopo').addEventListener('change', e => {
    if (e.target.value === state.topology) { $('#topoConfirm').hidden = true; return; }
    $('#topoConfirmText').textContent = `Switching to the "${e.target.value}" topology rebuilds the mesh and resets balances, ledger and idempotency records.`;
    $('#topoConfirm').hidden = false;
  });
  $('#topoCancel').addEventListener('click', () => { $('#topoConfirm').hidden = true; $('#sTopo').value = state.topology; });
  $('#topoApply').addEventListener('click', async e => {
    const topology = $('#sTopo').value;
    const r = await act(e.currentTarget, () => api('POST', '/api/mesh/reset', { topology }), `Switched to the "${topology}" topology and reset the demo.`);
    $('#topoConfirm').hidden = true;
    if (r) { selectedPacket = null; selectedDevice = null; resetKeys(); }
    $('#sTopo').value = state?.topology || 'demo';
  });

  // reset needs a second click
  let armed = null;
  const disarm = () => { clearTimeout(armed); armed = null; const b = $('#btnReset'); b.classList.remove('armed'); b.textContent = 'Reset to baseline'; };
  $('#btnReset').addEventListener('click', async e => {
    const b = e.currentTarget;
    if (!armed) { b.classList.add('armed'); b.textContent = 'Click again to confirm'; armed = setTimeout(disarm, 3000); return; }
    disarm();
    const r = await act(b, () => api('POST', '/api/mesh/reset', { topology: state.topology }), 'Reset to the baseline: balances, ledger, idempotency state and mesh.');
    if (r) { selectedPacket = null; selectedDevice = null; resetKeys(); await refresh(); }
  });

  // map
  const topo = $('#topo'), scroll = $('#mapScroll');
  topo.addEventListener('click', e => {
    if (MAP.dragged) { MAP.dragged = false; return; }
    const g = e.target.closest('[data-id]'); if (g) selectDevice(g.dataset.id);
  });
  topo.addEventListener('keydown', e => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const g = e.target.closest('[data-id]'); if (g) { e.preventDefault(); selectDevice(g.dataset.id); }
  });
  $('#mapIn').addEventListener('click', () => setZoom(MAP.zoom * 1.25));
  $('#mapOut').addEventListener('click', () => setZoom(MAP.zoom / 1.25));
  $('#mapFit').addEventListener('click', () => { setZoom(1); scroll.scrollLeft = 0; scroll.scrollTop = 0; updateMapChrome(); });
  scroll.addEventListener('scroll', () => { MAP.hintDone = MAP.hintDone || scroll.scrollLeft > 24; updateMapChrome(); }, { passive: true });
  let drag = null;
  scroll.addEventListener('pointerdown', e => {
    if (e.pointerType !== 'mouse' || e.button !== 0 || e.target.closest('.node')) return;
    if (scroll.scrollWidth <= scroll.clientWidth && scroll.scrollHeight <= scroll.clientHeight) return;
    drag = { x: e.clientX, y: e.clientY, l: scroll.scrollLeft, t: scroll.scrollTop };
    scroll.setPointerCapture(e.pointerId);
  });
  scroll.addEventListener('pointermove', e => {
    if (!drag) return;
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    if (Math.abs(dx) + Math.abs(dy) > 4) { MAP.dragged = true; scroll.classList.add('dragging'); }
    scroll.scrollLeft = drag.l - dx; scroll.scrollTop = drag.t - dy;
  });
  const endDrag = () => { drag = null; scroll.classList.remove('dragging'); setTimeout(() => { MAP.dragged = false; }, 0); };
  scroll.addEventListener('pointerup', endDrag); scroll.addEventListener('pointercancel', endDrag);
  new ResizeObserver(() => { if (state) renderTopology(false); }).observe(scroll);

  // device dock
  $('#deviceDock').addEventListener('click', e => {
    const dev = e.target.closest('[data-select-device]');
    if (dev) { selectDevice(dev.dataset.selectDevice); return; }
    const pk = e.target.closest('[data-select-packet]');
    if (pk) { selectedPacket = pk.dataset.selectPacket; lastKeys.pdetail = null; lastKeys.stages = null; lastKeys.plist = null; refresh(); toast(`Packet ${short(selectedPacket)} is now shown in Packet inspection.`); return; }
    const b = e.target.closest('[data-dev-act]'); if (!b) return;
    const d = state.devices.find(x => x.id === selectedDevice); if (!d) return;
    const body = b.dataset.devAct === 'active' ? { active: !d.active } : { online: !d.online };
    act(b, () => api('PATCH', '/api/mesh/devices/' + encodeURIComponent(d.id), body),
      b.dataset.devAct === 'active' ? `${d.id} is now ${d.active ? 'out of' : 'back in'} the mesh.` : `${d.id} now ${d.online ? 'has no' : 'has'} internet.`);
  });

  // packets
  $('#packetList').addEventListener('click', e => {
    const b = e.target.closest('.pk'); if (!b) return;
    selectedPacket = b.dataset.id; lastKeys.pdetail = null; lastKeys.stages = null; lastKeys.plist = null; refresh();
  });
  $('#packetDetail').addEventListener('click', e => {
    const b = e.target.closest('[data-act]'); if (!b || !detail) return;
    if (b.dataset.act === 'identical') {
      const n = whole($('#atkTimes').value.trim());
      if (!(n >= 1 && n <= 50)) { toast('Copies must be a whole number from 1 to 50.', 'err'); $('#atkTimes').focus(); return; }
      act(b, () => api('POST', '/api/demo/replay', { packetId: detail.packetId, kind: 'IDENTICAL', times: n }),
        r => `${plural(r.submissions, 'identical submission')}: ${tally(r.summary)}.`);
    } else {
      act(b, async () => { const r = await api('POST', '/api/demo/replay', { packetId: detail.packetId, kind: 'REPACKAGED' }); selectedPacket = r.injected.packetId; lastKeys.pdetail = null; return r; },
        r => `Same payment re-encrypted as packet ${short(r.injected.packetId)}: new ciphertext, same nonce. Gossip and upload it to see it rejected.`);
    }
  });

  document.addEventListener('click', e => {
    const c = e.target.closest('[data-copy]');
    if (c) copyText(c.dataset.copy, c.dataset.what || 'text');
  });

  // admin token (hosted deployments)
  $('#adminForm').addEventListener('submit', e => {
    e.preventDefault();
    adminToken = $('#adminToken').value.trim();
    try { sessionStorage.setItem('upimesh.admin', adminToken); } catch { /* kept in memory only */ }
    $('#adminToken').value = '';
    $('#adminState').textContent = adminToken ? 'token set for this tab' : 'locked';
    toast(adminToken ? 'Admin token set for this tab. The backend still checks it on every protected request.' : 'Admin token cleared.', 'ok');
  });
  $('#adminClear').addEventListener('click', () => {
    adminToken = ''; try { sessionStorage.removeItem('upimesh.admin'); } catch { /* ignore */ }
    $('#adminState').textContent = 'locked'; toast('Admin token forgotten.', 'ok');
  });
}
function openAdmin() {
  const box = $('#adminBox');
  if (box.hidden) return;
  box.open = true; box.scrollIntoView({ block: 'center', behavior: reducedMotion() ? 'auto' : 'smooth' });
  setTimeout(() => $('#adminToken').focus({ preventScroll: true }), 300);
}
function resetKeys() { for (const k of Object.keys(lastKeys)) delete lastKeys[k]; topoKey = ''; }

// ============================================================ guided scenarios
const sameBalances = (a, b) => Object.keys(a).every(k => a[k] === b[k]);
const S = {
  reset: () => api('POST', '/api/mesh/reset', { topology: 'demo' }),
  send: o => api('POST', '/api/demo/send', o),
  gossip: n => api('POST', '/api/mesh/gossip', { rounds: n }),
  flush: () => api('POST', '/api/mesh/flush'),
  online: (id, on) => api('PATCH', '/api/mesh/devices/' + id, { online: on }),
  packet: id => api('GET', '/api/mesh/packets/' + id),
  txs: () => api('GET', '/api/transactions'),
  accts: async () => Object.fromEntries((await api('GET', '/api/accounts')).map(a => [a.vpa, Number(a.balance)]))
};
const cnt = (f, o) => f.results.filter(r => r.outcome === o).length;
const allOnline = async () => { for (const b of ['bridge-1', 'bridge-2', 'bridge-3']) await S.online(b, true); };
const pay = (from, to, amount, extra = {}) => ({ senderVpa: from + '@demo', receiverVpa: to + '@demo', amount, ...extra });

const SCENARIOS = [
  ['Offline to online', 'encrypt, gossip, bridge online, settle once', async c => {
    await S.reset(); const b0 = await S.accts();
    c.say('Alice (offline) sends ₹500 to Bob; every bridge is offline.');
    const sent = await c.focus(await S.send(pay('alice', 'bob', 500)));
    c.check('packet is ciphertext: fingerprint present, payment details absent', sent.fingerprint.length === 64 && !JSON.stringify(sent).includes('bob@demo'));
    c.check('backend untouched: no ledger rows, balances unchanged', (await S.txs()).length === 0 && sameBalances(b0, await S.accts()));
    for (let i = 0; i < 4; i++) { await S.gossip(1); await c.pause(); }
    let d = await S.packet(sent.packetId);
    c.check(`gossip replicated it: ${d.holders.length} devices hold a copy, all 3 bridges included`, ['bridge-1', 'bridge-2', 'bridge-3'].every(b => d.holders.some(h => h.deviceId === b)));
    let f = await S.flush();
    c.check('bridges offline: 0 uploads, packet queued, still unsettled', f.uploadsAttempted === 0 && (await S.packet(sent.packetId)).status === 'AT_BRIDGE' && sameBalances(b0, await S.accts()));
    await S.online('bridge-1', true); await c.pause();
    f = await S.flush(); await c.pause();
    c.check('bridge-1 uploads: SETTLED', f.uploadsAttempted === 1 && cnt(f, 'SETTLED') === 1);
    const b1 = await S.accts();
    c.check('Alice -₹500, Bob +₹500', b1['alice@demo'] === b0['alice@demo'] - 500 && b1['bob@demo'] === b0['bob@demo'] + 500);
    c.check('exactly one SETTLED ledger row', (await S.txs()).filter(t => t.outcome === 'SETTLED').length === 1);
  }],
  ['Duplicate delivery', 'three bridges upload at once', async c => {
    await S.reset(); const b0 = await S.accts();
    c.say('A packet sits on all three bridges; all three get internet and upload at the same instant.');
    const sent = await c.focus(await S.send(pay('alice', 'bob', 100, { mode: 'DUPLICATE' })));
    await allOnline(); await c.pause();
    const f = await S.flush(); await c.pause();
    c.check(`3 concurrent uploads: ${cnt(f, 'SETTLED')} settled, ${cnt(f, 'DUPLICATE_DROPPED')} duplicates dropped`, f.uploadsAttempted === 3 && cnt(f, 'SETTLED') === 1 && cnt(f, 'DUPLICATE_DROPPED') === 2);
    const b1 = await S.accts();
    c.check('balances moved exactly once', b1['alice@demo'] === b0['alice@demo'] - 100 && b1['bob@demo'] === b0['bob@demo'] + 100);
    const storm = await api('POST', '/api/demo/replay', { packetId: sent.packetId, kind: 'IDENTICAL', times: 6 }); await c.pause();
    c.check('6 more identical submissions: all dropped as duplicates', storm.summary.DUPLICATE_DROPPED === 6 && !storm.summary.SETTLED);
    c.check('ledger still has one SETTLED row, balances unchanged', (await S.txs()).filter(t => t.outcome === 'SETTLED').length === 1 && sameBalances(b1, await S.accts()));
  }],
  ['Tampered ciphertext', 'one flipped byte fails GCM authentication', async c => {
    await S.reset(); const b0 = await S.accts();
    const sent = await c.focus(await S.send(pay('alice', 'bob', 100, { mode: 'TAMPERED' })));
    for (let i = 0; i < 4; i++) await S.gossip(1);
    await S.online('bridge-1', true); await c.pause();
    const f = await S.flush(); await c.pause();
    c.check('backend rejected it: DECRYPTION_FAILED', f.results.some(r => r.outcome === 'REJECTED' && r.reason === 'DECRYPTION_FAILED'));
    c.check('no balance changed, no ledger entry', sameBalances(b0, await S.accts()) && (await S.txs()).every(t => t.outcome !== 'SETTLED'));
    c.check('packet status shows REJECTED', (await S.packet(sent.packetId)).status === 'REJECTED');
  }],
  ['Expired packet', 'signed outside the freshness window', async c => {
    await S.reset(); const b0 = await S.accts();
    await c.focus(await S.send(pay('alice', 'bob', 100, { mode: 'EXPIRED' })));
    for (let i = 0; i < 4; i++) await S.gossip(1);
    await S.online('bridge-1', true); await c.pause();
    const f = await S.flush(); await c.pause();
    c.check('freshness validation rejected it: STALE_TIMESTAMP', f.results.some(r => r.reason === 'STALE_TIMESTAMP'));
    c.check('balances unchanged', sameBalances(b0, await S.accts()));
  }],
  ['TTL exhaustion', 'TTL 2 cannot reach a bridge 3+ hops away', async c => {
    await S.reset(); const b0 = await S.accts();
    const sent = await c.focus(await S.send(pay('alice', 'bob', 100, { ttl: 2 })));
    for (let i = 0; i < 6; i++) { await S.gossip(1); await c.pause(); }
    const d = await S.packet(sent.packetId);
    c.check(`forwarding stopped: only ${d.holders.map(h => h.deviceId).join(', ')} hold it`, !d.holders.some(h => h.role === 'BRIDGE'));
    c.check('final delivery status: TTL_EXHAUSTED', d.status === 'TTL_EXHAUSTED');
    await allOnline(); const f = await S.flush();
    c.check('even with every bridge online there is nothing to upload', f.uploadsAttempted === 0 && sameBalances(b0, await S.accts()));
  }],
  ['Interrupted connectivity', 'relays store, settlement happens later', async c => {
    await S.reset(); const b0 = await S.accts();
    const sent = await c.focus(await S.send(pay('alice', 'bob', 250)));
    await S.gossip(8); await c.pause();
    let f = await S.flush();
    c.check('all bridges offline: packets stored, nothing settled', f.uploadsAttempted === 0 && sameBalances(b0, await S.accts()) && (await S.packet(sent.packetId)).status === 'AT_BRIDGE');
    c.say('Connectivity returns at bridge-2. The sender does nothing.');
    await S.online('bridge-2', true); await c.pause();
    f = await S.flush(); await c.pause();
    const b1 = await S.accts();
    c.check('the same packet settles: Alice -₹250, Bob +₹250', cnt(f, 'SETTLED') === 1 && b1['alice@demo'] === b0['alice@demo'] - 250 && b1['bob@demo'] === b0['bob@demo'] + 250);
    c.check('only one payment was ever created', state.packets.length === 1);
  }],
  ['Insufficient funds', 'Dave has ₹500, tries ₹600', async c => {
    await S.reset(); const b0 = await S.accts();
    await c.focus(await S.send(pay('dave', 'bob', 600)));
    for (let i = 0; i < 4; i++) await S.gossip(1);
    await allOnline(); await c.pause();
    const f = await S.flush(); await c.pause();
    c.check('rejected: INSUFFICIENT_FUNDS', f.results.some(r => r.reason === 'INSUFFICIENT_FUNDS'));
    c.check('no partial update, no SETTLED row', sameBalances(b0, await S.accts()) && (await S.txs()).every(t => t.outcome !== 'SETTLED'));
  }],
  ['Concurrent updates', '12 payments, 3 bridges, shared accounts', async c => {
    await S.reset(); const b0 = await S.accts(); const total0 = Object.values(b0).reduce((a, b) => a + b, 0);
    const flows = [['alice', 'bob', 10], ['bob', 'carol', 20], ['carol', 'alice', 30], ['alice', 'carol', 5]];
    for (let r = 0; r < 3; r++) for (const [a, b, amt] of flows) await S.send(pay(a, b, amt, { mode: 'DUPLICATE' }));
    await allOnline(); await c.pause();
    const f = await S.flush(); await c.pause();
    c.check(`36 simultaneous uploads: ${cnt(f, 'SETTLED')} settled, ${cnt(f, 'DUPLICATE_DROPPED')} duplicates`, f.uploadsAttempted === 36 && cnt(f, 'SETTLED') === 12 && cnt(f, 'DUPLICATE_DROPPED') === 24);
    const b1 = await S.accts();
    c.check('balances equal the exact sum of the 12 payments', b1['alice@demo'] === b0['alice@demo'] + 45 && b1['bob@demo'] === b0['bob@demo'] - 30 && b1['carol@demo'] === b0['carol@demo'] - 15);
    c.check('money conserved across accounts', Object.values(b1).reduce((a, b) => a + b, 0) === total0);
  }]
];

function initScenarios() {
  setHtml($('#scenarioList'), html`${SCENARIOS.map(([t, d], i) => html`
    <li class="scn" data-i="${i}" data-state="idle">
      <div class="scn-main">
        <span class="scn-no" aria-hidden="true">${i + 1}</span>
        <div class="scn-text"><b>${t}</b><small>${d}</small></div>
        <span class="chip" data-chip>Not run</span>
        <button class="btn sm" type="button" data-run="${i}" aria-label="Run scenario ${i + 1}: ${t}">Run</button>
      </div>
      <details class="scn-detail" hidden><summary data-sum></summary><ul class="checks"></ul></details>
    </li>`)}`);
  $('#scenarioList').addEventListener('click', e => {
    const b = e.target.closest('[data-run]'); if (b) runScenarios([parseInt(b.dataset.run, 10)]);
  });
  $('#btnRunAll').addEventListener('click', () => runScenarios(SCENARIOS.map((_, i) => i)));
}

/** On a hosted deployment reset, settings and experiments need the admin token: ask for it up front instead of failing halfway. */
function needsAdminToken(what) {
  if (!health?.adminRequired || adminToken) return false;
  toast(`${what} needs the admin token on this deployment. Enter it under "Admin access" in the Mesh section.`, 'err');
  openAdmin();
  return true;
}

async function runScenarios(indices) {
  if (scenarioRunning || !state) return;
  if (needsAdminToken('Guided scenarios start with a reset, which')) return;
  scenarioRunning = true;
  document.body.dataset.scenario = 'running';
  const buttons = [$('#btnRunAll'), ...$$('#scenarioList [data-run]')];
  buttons.forEach(b => { b.disabled = true; });
  $('#btnRunAll').classList.add('busy');
  const summary = $('#scnSummary');
  summary.className = 'scn-summary'; $('#scnProgress').hidden = false;
  $$('#scenarioList .scn').forEach(li => { if (indices.includes(+li.dataset.i)) { li.dataset.state = 'idle'; $('[data-chip]', li).className = 'chip'; $('[data-chip]', li).textContent = 'Queued'; $('.scn-detail', li).hidden = true; } });
  const t0 = performance.now();
  let passed = 0, failed = 0, okScn = 0, badScn = 0;
  try {
    for (const [k, i] of indices.entries()) {
      const li = $(`#scenarioList .scn[data-i="${i}"]`), [title, , run] = SCENARIOS[i];
      const chip = $('[data-chip]', li), det = $('.scn-detail', li), list = $('.checks', det);
      li.dataset.state = 'running'; chip.className = 'chip run'; chip.textContent = 'Running';
      list.innerHTML = ''; det.hidden = false; det.open = true; $('[data-sum]', det).textContent = 'Checks so far';
      summary.innerHTML = `<b>Running ${k + 1} of ${indices.length}:</b> ${esc(title)}`;
      let p = 0, f = 0;
      const line = (cls, mk, text) => { const item = document.createElement('li'); item.className = cls; item.innerHTML = `<span class="mk">${mk}</span><span></span>`; item.lastChild.textContent = text; list.appendChild(item); };
      const ctx = {
        say: t => line('say', '•', t),
        check: (t, ok) => { ok ? p++ : f++; line(ok ? 'ok' : 'bad', ok ? '✓' : '✗', ok ? t : 'FAILED: ' + t); },
        pause: async () => { await refresh(); await sleep(reducedMotion() ? 120 : 420); },
        focus: async r => { selectedPacket = r.packetId; lastKeys.pdetail = null; lastKeys.plist = null; await refresh(); await sleep(reducedMotion() ? 60 : 350); return r; }
      };
      try { await run(ctx); } catch (e) { f++; line('bad', '✗', 'ERROR: ' + e.message); }
      await refresh();
      passed += p; failed += f;
      const good = f === 0;
      good ? okScn++ : badScn++;
      li.dataset.state = good ? 'pass' : 'fail';
      chip.className = 'chip ' + (good ? 'ok' : 'bad');
      chip.textContent = good ? `${p} of ${p + f} checks passed` : `${f} failed of ${p + f}`;
      $('[data-sum]', det).textContent = good ? 'Show the checks' : 'Show what failed';
      det.open = !good;
    }
    const secs = ((performance.now() - t0) / 1000).toFixed(1);
    const msg = `${okScn} of ${indices.length} ${indices.length === 1 ? 'scenario' : 'scenarios'} passed, ${plural(passed + failed, 'check')} in ${secs} s${failed ? `, ${plural(failed, 'check')} failed` : ''}.`;
    summary.className = 'scn-summary ' + (failed ? 'bad' : 'ok');
    summary.innerHTML = `<b>${esc(failed ? 'Failed' : 'All passed')}.</b> ${esc(msg)} The demo now shows the state the last scenario left behind.`;
    toast(msg, failed ? 'err' : 'ok');
  } finally {
    scenarioRunning = false;
    delete document.body.dataset.scenario;
    $('#scnProgress').hidden = true;
    $('#btnRunAll').classList.remove('busy');
    buttons.forEach(b => { b.disabled = false; });
    await refresh();
  }
}

// ============================================================ experiments
let expReady = false;
const EXP = { types: [], history: [], shownId: null, running: false };
const SUMMARY_KEYS = ['deliveryRatePct', 'avgSettlementDelayRounds', 'maxSettlementDelayRounds', 'avgTimeToFirstUploadRounds'];
const expType = () => EXP.types.find(t => t.type === $('#expType').value);
const shown = () => EXP.history.find(r => r.id === EXP.shownId);
const sortedRows = r => [...r.rows].sort((a, b) => a.param - b.param);
const NA = html`<span class="na" title="Not available: no run settled, so there is no delay to average">n/a</span>`;
const cell = (v, unit) => { const n = num(v); return Number.isNaN(n) ? NA : html`${fmtNum(n)}${unit === '%' && html`<span class="u">%</span>`}`; };

async function initExperiments() {
  EXP.types = await api('GET', '/api/experiments/types');
  setHtml($('#expType'), html`${EXP.types.map(t => html`<option value="${t.type}">${t.title}</option>`)}`);
  $('#expType').addEventListener('change', fillExpForm);
  ['expValues', 'expRuns', 'expSeed'].forEach(id => $('#' + id).addEventListener('input', validateExp));
  $('#expForm').addEventListener('submit', e => { e.preventDefault(); runExperiment(); });
  $('#expMetric').addEventListener('change', drawChart);
  $('#expHistory').addEventListener('change', e => { EXP.shownId = e.target.value; renderExpResult(false); });
  $('#expHead').addEventListener('click', e => { if (e.target.closest('[data-act=reuse]')) useShownSettings(); });
  $('#btnCopyParams').addEventListener('click', () => copyText($('#expParams').textContent, 'experiment request'));
  new ResizeObserver(() => { if (shown()) drawChart(); }).observe($('#expChartWrap'));
  fillExpForm();
  await loadExpHistory();
  if (EXP.history.length) renderExpResult(true);
  expReady = true;
}

/** Show a hint under a scroll region only while it really overflows sideways. */
function watchScroll(el) {
  const hint = document.createElement('p');
  hint.className = 'scroll-hint'; hint.hidden = true; hint.textContent = 'This table is wider than the screen: scroll sideways to see every column.';
  el.insertAdjacentElement('afterend', hint);
  const check = () => { hint.hidden = !(el.scrollWidth > el.clientWidth + 1); };
  const ro = new ResizeObserver(check);
  ro.observe(el); if (el.firstElementChild) ro.observe(el.firstElementChild);
}

function fillExpForm() {
  const t = expType();
  $('#expValues').value = t.defaultValues.join(', ');
  $('#expRuns').value = t.defaultRuns;
  const lo = fmtNum(t.valueMin), hi = fmtNum(t.valueMax);
  $('#expDesc').textContent = `${t.description} Varies "${t.parameterName}" (${t.parameterUnit}); accepts ${t.wholeValues ? 'whole numbers' : 'numbers'} from ${lo} to ${hi}, at most ${t.maxValues} values.`;
  $('#expRuns').max = t.maxRuns;
  validateExp();
}

/** Same rules as the server, so a mistake is explained next to its field instead of after a failed request. */
function parseValues(text, t) {
  const tokens = text.split(',').map(s => s.trim());
  if (tokens.length && tokens[tokens.length - 1] === '') tokens.pop();
  if (!tokens.length) return { error: 'Enter at least one value, separated by commas.' };
  const values = [];
  for (const tok of tokens) {
    if (tok === '') return { error: 'There is an empty entry between two commas.' };
    if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(tok)) return { error: `"${tok}" is not a number.` };
    const v = Number(tok);
    if (v < t.valueMin || v > t.valueMax) return { error: `${tok} is outside the accepted range ${fmtNum(t.valueMin)} to ${fmtNum(t.valueMax)} for ${t.parameterName}.` };
    if (t.wholeValues && !Number.isInteger(v)) return { error: `${tok} must be a whole number.` };
    if (values.includes(v)) return { error: `${tok} is listed twice.` };
    values.push(v);
  }
  if (values.length > t.maxValues) return { error: `At most ${t.maxValues} values (you entered ${values.length}).` };
  return { values };
}
function validateExp() {
  const t = expType(); if (!t) return null;
  const parsed = parseValues($('#expValues').value, t);
  const runs = whole($('#expRuns').value.trim()), seed = signed($('#expSeed').value.trim());
  const msgs = { values: parsed.error || '', runs: '', seed: '' };
  if (!(runs >= 1 && runs <= t.maxRuns)) msgs.runs = `Whole number, 1 to ${t.maxRuns}.`;
  if (!Number.isSafeInteger(seed)) msgs.seed = 'Whole number.';
  let estimate = '', over = false;
  if (parsed.values && !msgs.runs) {
    const sims = parsed.values.length * runs;
    estimate = `${parsed.values.length} values × ${runs} runs = ${plural(sims, 'simulation')} (limit ${t.maxSimulations})`;
    if (sims > t.maxSimulations) { over = true; msgs.values = msgs.values || `Too many simulations: ${sims}. The limit is ${t.maxSimulations}; use fewer values or runs.`; }
    if (t.type === 'DUPLICATE_DELIVERY') {
      const subs = runs * parsed.values.reduce((a, b) => a + b, 0);
      estimate += `, ${plural(subs, 'submission')} (limit ${t.maxSubmissions})`;
      if (subs > t.maxSubmissions) { over = true; msgs.values = msgs.values || `Too many submissions: ${subs}. The limit is ${t.maxSubmissions}; use fewer copies or runs.`; }
    }
  }
  setField('#expValues', '#expValuesMsg', msgs.values);
  setField('#expRuns', '#expRunsMsg', msgs.runs);
  setField('#expSeed', '#expSeedMsg', msgs.seed);
  const est = $('#expEstimate');
  est.textContent = estimate; est.className = 'exp-estimate' + (over ? ' warn' : '');
  const ok = !msgs.values && !msgs.runs && !msgs.seed;
  $('#btnExp').disabled = EXP.running || !ok;
  return ok ? { type: t.type, runs, seed, values: parsed.values } : null;
}

async function loadExpHistory() {
  EXP.history = await api('GET', '/api/experiments');
  if (!EXP.history.some(r => r.id === EXP.shownId)) EXP.shownId = EXP.history[0]?.id ?? null;
}

async function runExperiment() {
  if (EXP.running) return;
  const body = validateExp();
  if (!body) { $('#expForm [aria-invalid=true]')?.focus(); return; }
  if (scenarioRunning) { toast('A guided scenario is running. Wait for it to finish.', 'err'); return; }
  if (needsAdminToken('Running an experiment')) return;
  EXP.running = true;
  const btn = $('#btnExp');
  const total = body.values.length * body.runs;
  btn.disabled = true; btn.classList.add('busy'); btn.textContent = 'Running';
  $$('#expForm input, #expForm select').forEach(el => { el.disabled = true; });
  $('#expError').textContent = '';
  $('#expBusy').hidden = false;
  $('#expEstimate').textContent = `Running ${plural(total, 'simulation')} on the real mesh and backend. This can take a few seconds.`;
  $('#expOut').setAttribute('aria-busy', 'true');
  try {
    const r = await api('POST', '/api/experiments/run', body);
    await loadExpHistory();
    EXP.shownId = r.id;
    renderExpResult(true);
    toast(`${r.title}: ${r.rows.length} points × ${r.parameters.runsPerPoint} runs in ${(r.durationMs / 1000).toFixed(1)} s.`, 'ok');
  } catch (e) {
    $('#expError').textContent = e.message;
    actionError(e);
  } finally {
    EXP.running = false;
    $$('#expForm input, #expForm select').forEach(el => { el.disabled = false; });
    btn.classList.remove('busy'); btn.textContent = 'Run experiment';
    $('#expBusy').hidden = true;
    $('#expOut').removeAttribute('aria-busy');
    validateExp();
    refresh();
  }
}

function useShownSettings() {
  const r = shown(); if (!r) return;
  $('#expType').value = r.type;
  fillExpForm();
  $('#expValues').value = r.parameters.values.join(', ');
  $('#expRuns').value = r.parameters.runsPerPoint;
  $('#expSeed').value = r.parameters.seed;
  validateExp();
  $('#expForm').scrollIntoView({ block: 'center', behavior: reducedMotion() ? 'auto' : 'smooth' });
  toast('The form now holds the settings of the result shown below.', 'ok');
}

function renderExpResult(resetMetric) {
  const r = shown();
  $('#expOut').hidden = !r;
  setHtml($('#expHistory'), html`${EXP.history.map((x, i) => html`<option value="${x.id}">${i === 0 ? 'Latest: ' : ''}${x.title} · ${x.id} · ${clock(Date.parse(x.ranAt))} · seed ${x.parameters.seed} · ${x.parameters.runsPerPoint} runs</option>`)}`);
  if (!r) return;
  $('#expHistory').value = r.id;
  const latest = EXP.history[0]?.id === r.id;
  setHtml($('#expHead'), html`
    <h4>${r.title}</h4>
    <span class="chip mono" title="Identifier of this result">${r.id}</span>
    ${!latest && html`<span class="chip warn">Earlier run</span>`}
    <span class="chip">seed ${r.parameters.seed}</span>
    <span class="chip">${plural(r.parameters.runsPerPoint, 'run')} per value</span>
    <span class="chip">${plural(r.rows.length, 'value')}</span>
    <span class="chip" title="${stamp(Date.parse(r.ranAt))}">ran ${clock(Date.parse(r.ranAt))} in ${(r.durationMs / 1000).toFixed(1)} s</span>
    <span class="grow"></span>
    <button class="btn sm" type="button" data-act="reuse" title="Copy this result's type, values, runs and seed into the form above">Use these settings</button>`);
  const sel = $('#expMetric'), keep = sel.value;
  setHtml(sel, html`${r.metrics.map(m => html`<option value="${m.key}">${m.label} (${m.unit})</option>`)}`);
  if (!resetMetric && r.metrics.some(m => m.key === keep)) sel.value = keep;
  renderExpTables(r);
  $('#expParams').textContent = 'POST /api/experiments/run\n' + JSON.stringify({ type: r.type, runs: r.parameters.runsPerPoint, seed: r.parameters.seed, values: r.parameters.values }) +
    '\n\nFixed conditions of this result:\n' + JSON.stringify(r.parameters, null, 2);
  drawChart();
}

function renderExpTables(r) {
  const rows = sortedRows(r);
  let summary = r.metrics.filter(m => SUMMARY_KEYS.includes(m.key)), diag = r.metrics.filter(m => !SUMMARY_KEYS.includes(m.key));
  if (!summary.length) { summary = r.metrics; diag = []; } // duplicate-delivery results have only a few metrics: all are the summary
  const paramHead = html`<th scope="col">${r.parameterName}<small>${r.parameterUnit}</small></th>`;
  const metricHeads = ms => ms.map(m => html`<th scope="col" class="num">${m.label}<small>${m.unit}</small></th>`);
  const metricCells = (row, ms) => ms.map(m => html`<td class="num">${cell(row.metrics[m.key], m.unit)}</td>`);
  const ident = html`<small>${r.id} · ${r.title} · ${plural(r.parameters.runsPerPoint, 'run')} per value · sorted by ${r.parameterName}</small>`;
  setHtml($('#expSummary'), html`<caption>Summary ${ident}</caption>
    <thead><tr>${paramHead}<th scope="col" class="num">Runs<small>per value</small></th>${metricHeads(summary)}</tr></thead>
    <tbody>${rows.map(row => html`<tr><td>${fmtNum(row.param)}</td><td class="num">${row.samples}</td>${metricCells(row, summary)}</tr>`)}</tbody>`);
  const diagBox = $('#expDiag').closest('.exp-table');
  diagBox.hidden = diag.length === 0;
  if (diag.length) {
    setHtml($('#expDiag'), html`<caption>Diagnostic metrics <small>${r.id} · ${r.title} · the same ${plural(rows.length, 'row')} as the summary</small></caption>
      <thead><tr>${paramHead}${metricHeads(diag)}</tr></thead>
      <tbody>${rows.map(row => html`<tr><td>${fmtNum(row.param)}</td>${metricCells(row, diag)}</tr>`)}</tbody>`);
  }
}

function niceTicks(max) {
  if (!(max > 0)) return { ymax: 1, ticks: [0, 0.25, 0.5, 0.75, 1] };
  const raw = max / 4, mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map(f => f * mag).find(s => s >= raw);
  const n = Math.ceil(max / step - 1e-9);
  const ticks = Array.from({ length: n + 1 }, (_, i) => +(i * step).toFixed(6));
  return { ymax: ticks[n], ticks };
}

function drawChart() {
  const r = shown(); if (!r) return;
  const m = r.metrics.find(x => x.key === $('#expMetric').value) || r.metrics[0];
  const rows = sortedRows(r);
  const wrap = $('#expChartWrap');
  const W = Math.max(300, Math.floor(wrap.clientWidth - 32));
  const H = clamp(Math.round(W * 0.27), 290, 400);
  const L = 70, Rm = 28, T = 36, B = 64;
  const pts = rows.map(row => ({ x: row.param, y: num(row.metrics[m.key]) }));
  const xs = pts.map(p => p.x), xmin = Math.min(...xs), xmax = Math.max(...xs);
  const ys = pts.filter(p => !Number.isNaN(p.y)).map(p => p.y);
  const { ymax, ticks } = m.unit === '%' ? { ymax: 100, ticks: [0, 25, 50, 75, 100] } : niceTicks(Math.max(0, ...ys));
  const pw = W - L - Rm;
  const X = x => L + (xmax === xmin ? pw / 2 : (x - xmin) / (xmax - xmin) * pw);
  const Y = y => H - B - y / ymax * (H - B - T);
  const gap = pts.length > 1 ? pw / (pts.length - 1) : pw;
  const everyX = Math.max(1, Math.ceil(54 / gap)), everyV = Math.max(1, Math.ceil(46 / gap));
  let s = '';
  for (const v of ticks) s += `<line class="${v === 0 ? 'ca' : 'cg'}" x1="${L}" x2="${W - Rm}" y1="${Y(v)}" y2="${Y(v)}"/><text x="${L - 10}" y="${Y(v) + 4}" text-anchor="end">${fmtNum(+v.toFixed(4))}${m.unit === '%' ? '%' : ''}</text>`;
  pts.forEach((p, i) => { if (i % everyX === 0 || i === pts.length - 1 && gap * (i % everyX) > 40) s += `<text x="${X(p.x)}" y="${H - B + 20}" text-anchor="middle">${fmtNum(p.x)}</text>`; });
  s += `<text class="at" x="${(L + W - Rm) / 2}" y="${H - 10}" text-anchor="middle">${esc(r.parameterName)} (${esc(r.parameterUnit)})</text>`;
  s += `<text class="at" transform="translate(16 ${(T + H - B) / 2}) rotate(-90)" text-anchor="middle">${esc(m.label)} (${esc(m.unit)})</text>`;
  let path = '', pen = false;
  pts.forEach(p => { if (Number.isNaN(p.y)) { pen = false; return; } path += `${pen ? 'L' : 'M'}${X(p.x)} ${Y(p.y)} `; pen = true; });
  s += `<path class="cl" d="${path}"/>`;
  pts.forEach((p, i) => {
    if (Number.isNaN(p.y)) { s += `<text class="cn" x="${X(p.x)}" y="${Y(0) - 10}">n/a<title>No value: no run settled, so there is nothing to average</title></text>`; return; }
    s += `<circle class="cp" cx="${X(p.x)}" cy="${Y(p.y)}" r="4.5"><title>${esc(r.parameterName)} ${p.x}: ${p.y} ${esc(m.unit)}</title></circle>`;
    if (i % everyV === 0 || i === pts.length - 1 && gap * (i % everyV) > 40) s += `<text class="cv" x="${X(p.x)}" y="${Y(p.y) - 11}">${fmtNum(p.y)}${m.unit === '%' ? '%' : ''}</text>`;
  });
  const svg = $('#expChart');
  svg.setAttribute('width', W); svg.setAttribute('height', H); svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('aria-label', `${m.label} (${m.unit}) by ${r.parameterName} (${r.parameterUnit}), result ${r.id}`);
  svg.innerHTML = s;
  $('#expChartTitle').innerHTML = `<b>${esc(m.label)}</b> by ${esc(r.parameterName)} (${esc(r.parameterUnit)}), result ${esc(r.id)}, seed ${esc(r.parameters.seed)}, ${esc(plural(r.parameters.runsPerPoint, 'run'))} per value`;
}

// ============================================================ page chrome: hero, navigation
function initHero() {
  const tick = () => {
    const d = new Date();
    $('#clock').innerHTML = `Local ${pad2(d.getHours())}:${pad2(d.getMinutes())}<b>:${pad2(d.getSeconds())}</b>`;
  };
  tick(); setInterval(tick, 1000);

  // Decorative halftone ridge (no data behind it).
  const cv = $('#halftone');
  const hash = (a, b) => { const x = Math.sin(a * 127.1 + b * 311.7) * 43758.5453; return x - Math.floor(x); };
  const draw = k => {
    const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
    if (!w || !h) return;
    cv.width = w * dpr; cv.height = h * dpr;
    const ctx = cv.getContext('2d'); ctx.scale(dpr, dpr); ctx.fillStyle = '#fff';
    const step = 13, cols = Math.ceil(w / step), rows = Math.ceil(h / step);
    for (let c = 0; c < cols; c++) {
      const t = c / cols;
      const ridge = h * Math.max(0.1, 0.9 * Math.exp(-Math.pow((t - 0.8) / 0.13, 2)) + 0.6 * Math.exp(-Math.pow((t - 0.48) / 0.16, 2)) + 0.25 * Math.exp(-Math.pow((t - 0.2) / 0.1, 2)) + 0.05 * hash(c, 1));
      for (let r = 0; r < rows; r++) {
        const above = r * step - ridge * k;
        const edge = above < 0 ? Math.min(1, -above / (step * 6)) : Math.max(0, 1 - above / (step * 3)) * 0.35 * hash(c, r);
        const rad = step * 0.5 * edge * (0.6 + 0.4 * hash(r, c));
        if (rad < 0.6) continue;
        ctx.beginPath(); ctx.arc(c * step + step / 2, h - r * step - step / 2, rad, 0, 6.2832); ctx.fill();
      }
    }
  };
  let t0 = null;
  const grow = ts => { t0 = t0 || ts; const k = Math.min(1, (ts - t0) / 1400); draw(1 - Math.pow(1 - k, 3)); if (k < 1) requestAnimationFrame(grow); };
  if (reducedMotion()) draw(1); else requestAnimationFrame(grow);
  let resizeT = null;
  window.addEventListener('resize', () => { clearTimeout(resizeT); resizeT = setTimeout(() => draw(1), 120); });

  // Section that is current in the floating nav: the last one whose top has passed 40% of the viewport.
  const ids = ['overview', 'mesh', 'packets', 'ledgerBand', 'prove', 'activity'];
  const links = $$('.pillnav .links a');
  let ticking = false;
  const mark = () => {
    ticking = false;
    const doc = document.documentElement;
    let cur = ids[0];
    for (const id of ids) if ($('#' + id).getBoundingClientRect().top <= window.innerHeight * 0.4) cur = id;
    if (window.scrollY + window.innerHeight >= doc.scrollHeight - 4) cur = ids[ids.length - 1];
    links.forEach(a => { if (a.getAttribute('href') === '#' + cur) a.setAttribute('aria-current', 'location'); else a.removeAttribute('aria-current'); });
  };
  const onScroll = () => { if (!ticking) { ticking = true; requestAnimationFrame(mark); } };
  window.addEventListener('scroll', onScroll, { passive: true }); window.addEventListener('resize', onScroll);
  mark();

  // "Run all scenarios" in the hero: scroll to the panel, then start (an explicit click, never automatic).
  $('#ctaScen').addEventListener('click', () => {
    $('#prove').scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth' });
    setTimeout(() => runScenarios(SCENARIOS.map((_, i) => i)), reducedMotion() ? 0 : 500);
  });
}

// ============================================================ boot
(async function init() {
  initTiles();
  wire();
  initHero();
  initScenarios();
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  await Promise.all([refresh(), loadHealth()]);
  schedulePoll();
  $$('.tbl-scroll').forEach(watchScroll);
  try { await initExperiments(); } catch (e) { toast('Experiments unavailable: ' + e.message, 'err'); }
})();
