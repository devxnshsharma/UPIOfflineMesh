// Minimal Chrome DevTools Protocol client for the browser checks in this folder.
// No npm dependencies: it needs Node 22+ (global WebSocket) and an installed Chrome/Chromium.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
].filter(Boolean);

export const sleep = ms => new Promise(r => setTimeout(r, ms));

export async function launchChrome({ headless = true } = {}) {
  const exe = CHROME_CANDIDATES.find(existsSync);
  if (!exe) throw new Error('Chrome not found. Install Chrome or set CHROME_PATH.');
  const profile = mkdtempSync(join(tmpdir(), 'upimesh-e2e-'));
  const args = ['--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--disable-background-networking', '--enable-features=OverlayScrollbar', 'about:blank'];
  if (headless) args.unshift('--headless=new');
  const proc = spawn(exe, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  const wsUrl = await new Promise((resolve, reject) => {
    let buf = '';
    const timer = setTimeout(() => reject(new Error('Chrome did not report a DevTools endpoint')), 15000);
    proc.stderr.on('data', d => {
      buf += d;
      const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf);
      if (m) { clearTimeout(timer); resolve(m[1]); }
    });
    proc.on('exit', c => reject(new Error('Chrome exited early (' + c + ')')));
  });
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('DevTools websocket failed')); });
  const browser = new Browser(ws, proc, profile);
  return browser;
}

class Browser {
  constructor(ws, proc, profile) {
    this.ws = ws; this.proc = proc; this.profile = profile; this.seq = 0; this.pending = new Map(); this.listeners = [];
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id) {
        const p = this.pending.get(m.id);
        if (p) { this.pending.delete(m.id); m.error ? p.rej(new Error(m.error.message)) : p.res(m.result); }
      } else this.listeners.forEach(l => l(m));
    };
  }
  send(method, params = {}, sessionId) {
    const id = ++this.seq;
    this.ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((res, rej) => this.pending.set(id, { res, rej }));
  }
  async newPage() {
    const { targetId } = await this.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await this.send('Target.attachToTarget', { targetId, flatten: true });
    const page = new Page(this, sessionId, targetId);
    await page.init();
    return page;
  }
  async close() {
    try { await this.send('Browser.close'); } catch { /* already gone */ }
    try { this.ws.close(); } catch { /* ignore */ }
    this.proc.kill();
    try { rmSync(this.profile, { recursive: true, force: true }); } catch { /* ignore */ }
  }
}

export class Page {
  constructor(browser, sessionId, targetId) {
    this.b = browser; this.sid = sessionId; this.targetId = targetId;
    this.consoleErrors = []; this.consoleWarnings = []; this.exceptions = []; this.failedRequests = []; this.badResponses = [];
    this.requests = new Map();
    this.waiters = [];
    browser.listeners.push(m => { if (m.sessionId === sessionId) this.onEvent(m); });
  }
  send(method, params) { return this.b.send(method, params, this.sid); }
  async init() {
    await Promise.all(['Page.enable', 'Runtime.enable', 'Network.enable', 'Log.enable'].map(m => this.send(m)));
  }
  onEvent({ method, params }) {
    if (method === 'Runtime.consoleAPICalled') {
      const text = params.args.map(a => a.value ?? a.description ?? '').join(' ');
      if (params.type === 'error') this.consoleErrors.push(text);
      if (params.type === 'warning') this.consoleWarnings.push(text);
    } else if (method === 'Runtime.exceptionThrown') {
      this.exceptions.push(params.exceptionDetails.exception?.description || params.exceptionDetails.text);
    } else if (method === 'Log.entryAdded') {
      const e = params.entry;
      // network-level errors also show up as console errors; keep them separate from JS errors
      if (e.level === 'error' && e.source !== 'network') this.consoleErrors.push(`[${e.source}] ${e.text}`);
      if (e.level === 'warning') this.consoleWarnings.push(`[${e.source}] ${e.text}`);
    } else if (method === 'Network.requestWillBeSent') {
      this.requests.set(params.requestId, params.request.method + ' ' + params.request.url);
    } else if (method === 'Network.loadingFailed') {
      if (!params.canceled) this.failedRequests.push((this.requests.get(params.requestId) || params.requestId) + ' :: ' + params.errorText);
    } else if (method === 'Network.responseReceived') {
      if (params.response.status >= 400) this.badResponses.push(params.response.status + ' ' + params.response.url);
    }
    this.waiters = this.waiters.filter(w => { if (w.method === method) { w.res(params); return false; } return true; });
  }
  once(method, timeout = 20000) {
    return new Promise((res, rej) => {
      const w = { method, res };
      this.waiters.push(w);
      setTimeout(() => { this.waiters = this.waiters.filter(x => x !== w); rej(new Error('timeout waiting for ' + method)); }, timeout);
    });
  }
  clearIssues() { this.consoleErrors = []; this.consoleWarnings = []; this.exceptions = []; this.failedRequests = []; this.badResponses = []; }

  async goto(url) {
    const loaded = this.once('Page.loadEventFired');
    await this.send('Page.navigate', { url });
    await loaded;
  }
  async reload(ignoreCache = true) {
    const loaded = this.once('Page.loadEventFired');
    await this.send('Page.reload', { ignoreCache });
    await loaded;
  }
  async evaluate(expression) {
    // Runs the expression inside the remote page through DevTools (Runtime.evaluate); nothing is evaluated in Node.
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error('evaluate failed: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text) + '\n' + expression.slice(0, 200));
    return r.result.value;
  }
  async viewport(width, height, { mobile = false, dpr = 1 } = {}) {
    await this.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: dpr, mobile });
    if (mobile) await this.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    else await this.send('Emulation.setTouchEmulationEnabled', { enabled: false });
  }
  async waitFor(expression, { timeout = 15000, interval = 100, label } = {}) {
    const end = Date.now() + timeout;
    let last;
    while (Date.now() < end) {
      try { last = await this.evaluate(expression); if (last) return last; } catch (e) { last = e.message; }
      await sleep(interval);
    }
    throw new Error('timed out waiting for ' + (label || expression.slice(0, 120)) + ' (last: ' + JSON.stringify(last) + ')');
  }
  async screenshot(path, { full = false, quality = 80, format = 'jpeg' } = {}) {
    const params = { format, ...(format === 'jpeg' ? { quality } : {}) };
    if (full) {
      const m = await this.send('Page.getLayoutMetrics');
      const { width, height } = m.cssContentSize || m.contentSize;
      params.captureBeyondViewport = true;
      params.clip = { x: 0, y: 0, width: Math.ceil(width), height: Math.min(Math.ceil(height), 16000), scale: 1 };
    }
    const { data } = await this.send('Page.captureScreenshot', params);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, Buffer.from(data, 'base64'));
    return path;
  }
  /** Real mouse click at the centre of the element (after scrolling it into view and waiting until it stops moving). Fails if another element would receive it. */
  async click(selector, { scroll = true, force = false } = {}) {
    const measure = () => this.evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return { missing: true };
      const r = el.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      const top = document.elementFromPoint(x, y);
      return { x, y, w: r.width, h: r.height, hit: !!top && (top === el || el.contains(top) || top.contains(el)),
               covered: top ? (top.id || top.className || top.tagName) : null, disabled: !!el.disabled };
    })()`);
    if (scroll) await this.evaluate(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (el) el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); })()`);
    let info = await measure();
    for (let i = 0; i < 20 && !info.missing; i++) {          // a smooth scroll or an animation may still be moving the element
      await sleep(60);
      const again = await measure();
      const still = Math.abs(again.x - info.x) < 0.5 && Math.abs(again.y - info.y) < 0.5;
      info = again;
      if (still) break;
    }
    if (info.missing) throw new Error('click: no element ' + selector);
    if (!info.hit && !force) throw new Error(`click: ${selector} is covered by <${info.covered}>`);
    await this.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: info.x, y: info.y });
    await this.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: info.x, y: info.y, button: 'left', clickCount: 1 });
    await this.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: info.x, y: info.y, button: 'left', clickCount: 1 });
    return info;
  }
  async press(key, { shift = false } = {}) {
    const map = { Enter: 13, Tab: 9, Escape: 27, ' ': 32, ArrowRight: 39, ArrowLeft: 37, ArrowDown: 40, ArrowUp: 38, Home: 36, End: 35 };
    const code = map[key] || key.toUpperCase().charCodeAt(0);
    const base = { key, windowsVirtualKeyCode: code, nativeVirtualKeyCode: code, modifiers: shift ? 8 : 0 };
    const text = key === 'Enter' ? '\r' : key === ' ' ? ' ' : undefined;   // a key that types a character needs keyDown so Chrome also fires keypress (button activation)
    await this.send('Input.dispatchKeyEvent', { type: text ? 'keyDown' : 'rawKeyDown', ...base, ...(text ? { text } : {}) });
    await this.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
  }
  async type(text) { await this.send('Input.insertText', { text }); }
  /** Focus an input, replace its value with real key events (select-all + type) and fire change on blur. */
  async fill(selector, value) {
    await this.evaluate(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); el.scrollIntoView({ block: 'center' }); el.focus(); el.select && el.select(); })()`);
    await this.send('Input.insertText', { text: String(value) });
  }
  async scrollTo(selector, offset = 0) {
    await this.evaluate(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - ${offset}, behavior: 'instant' }); })()`);
    await sleep(120);
  }
}
