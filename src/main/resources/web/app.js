/* Giełda – logika strony. Wszystkie dane z serwera trafiają do DOM wyłącznie przez textContent. */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const t = (k, v) => window.I18N.t(k, v);
  const ICONS = {
    default: '🪙', GOLD_NUGGET: '🪙', GOLD_INGOT: '🥇', GUNPOWDER: '💥', ENDER_PEARL: '🔮', DIAMOND: '💎', EMERALD: '🟢',
    NETHERITE_INGOT: '🟤', REDSTONE: '🔴', LAPIS_LAZULI: '🔵', IRON_INGOT: '⛓️', COAL: '⚫', BLAZE_ROD: '🔥', NETHER_STAR: '⭐',
    AMETHYST_SHARD: '🟣', SLIME_BALL: '🟢', ECHO_SHARD: '🔷', REDSTONE_DUST: '🔴', COPPER_INGOT: '🟠', EMERALD_BLOCK: '🟩', DIAMOND_BLOCK: '💠'
  };

  const S = {
    market: null, me: null, coin: null, tf: 300, view: 'market',
    candles: [], quoteSeq: 0, adminInfo: null, lastFeedId: 0, prevPrice: {}
  };
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* tryb prywatny */ } }
  };
  const TFS = [60, 300, 900, 3600, 14400];
  const VIEWS = ['market', 'ranking'];
  (function restore() {
    const c = store.get('kg_coin'); if (c && /^[A-Za-z0-9_]{1,32}$/.test(c)) S.coin = c;
    const tf = parseInt(store.get('kg_tf'), 10); if (TFS.includes(tf)) S.tf = tf;
  })();

  // ---------------------------------------------------------------- formatowanie
  const fmtMoney = (cents) => {
    const neg = cents < 0; const a = Math.abs(Math.round(cents));
    const whole = Math.floor(a / 100); const frac = String(a % 100).padStart(2, '0');
    return (neg ? '-' : '') + String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, ' ') + '.' + frac;
  };
  const fmtQty = (q) => {
    const neg = q < 0; const a = Math.abs(Math.round(q));
    const whole = Math.floor(a / 10000); let frac = String(a % 10000).padStart(4, '0').replace(/0+$/, '');
    return (neg ? '-' : '') + whole + (frac ? ',' + frac : '');
  };
  const qtyInput = (q) => { // do pola tekstowego: kropka, bez zer
    const a = Math.max(0, Math.round(q)); const whole = Math.floor(a / 10000);
    const frac = String(a % 10000).padStart(4, '0').replace(/0+$/, '');
    return whole + (frac ? '.' + frac : '');
  };
  const pad2 = (n) => String(n).padStart(2, '0');
  const fmtTime = (ts) => { const d = new Date(ts); return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()); };
  const fmtDateTime = (ts) => { const d = new Date(ts); return pad2(d.getDate()) + '.' + pad2(d.getMonth() + 1) + ' ' + fmtTime(ts); };
  const pct = (a, b) => (b > 0 ? ((a - b) / b) * 100 : 0);
  const fmtPct = (p) => (p >= 0 ? '+' : '') + p.toFixed(2).replace('.', ',') + '%';
  const icon = (c) => (c && ICONS[String(c.icon).toUpperCase()]) || ICONS.default;

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  // ---------------------------------------------------------------- API
  async function api(path, opts) {
    const o = Object.assign({ credentials: 'same-origin' }, opts || {});
    if (o.body !== undefined) {
      o.method = 'POST';
      o.headers = { 'Content-Type': 'application/json' };
      o.body = JSON.stringify(o.body);
    }
    let res;
    try { res = await fetch('/api/' + path, o); } catch (e) { throw { status: 0, message: t('err.network') }; }
    let data = {};
    try { data = await res.json(); } catch (e) { /* pusta odpowiedź */ }
    if (!res.ok) throw { status: res.status, code: data.error, message: errText(data, res.status) };
    return data;
  }

  // Komunikat błędu: po polsku bierzemy tekst z serwera (ma szczegóły), po angielsku tłumaczymy po kodzie.
  function errText(data, status) {
    const code = data && data.error;
    const key = 'e.' + code;
    const known = code && window.I18N.t(key) !== key;
    if (window.I18N.lang() === 'en') {
      if (known) return t(key, { cmd: t('cmd.link') });
      return t('err.http', { status });
    }
    return (data && data.message) || (known ? t(key, { cmd: t('cmd.link') }) : t('err.http', { status }));
  }

  function toast(msg, ok) {
    const tt = el('div', 'toast ' + (ok ? 'ok' : 'err'), msg);
    $('toasts').appendChild(tt);
    setTimeout(() => tt.remove(), ok ? 3500 : 5500);
  }
  function fail(e) {
    if (e && e.status === 401 && S.me) { S.me = null; renderAccount(); }
    toast((e && e.message) || t('err.unknown'), false);
  }
  async function act(fn, okMsg) {
    try { const r = await fn(); if (okMsg) toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, true); return r; }
    catch (e) { fail(e); return null; }
  }

  // ---------------------------------------------------------------- linie wsparcia/oporu
  const linesKey = () => 'kg_lines_' + S.coin;
  function loadLines() {
    try { const v = JSON.parse(localStorage.getItem(linesKey()) || '[]'); return Array.isArray(v) ? v.filter(l => l && l.price > 0 && (l.type === 'support' || l.type === 'resistance')) : []; }
    catch (e) { return []; }
  }
  function saveLines(l) { try { localStorage.setItem(linesKey(), JSON.stringify(l)); } catch (e) { /* tryb prywatny */ } }
  function renderLines() {
    const lines = loadLines();
    chart.setLines(lines);
    const box = $('line-chips'); clear(box);
    lines.forEach((l, i) => {
      const chip = el('span', 'chip ' + l.type);
      chip.appendChild(document.createTextNode((l.type === 'support' ? t('line.support') + ' ' : t('line.resist') + ' ') + fmtMoney(l.price)));
      const x = el('button', null, '×'); x.type = 'button'; x.setAttribute('aria-label', t('line.remove'));
      x.addEventListener('click', () => { const a = loadLines(); a.splice(i, 1); saveLines(a); renderLines(); });
      chip.appendChild(x); box.appendChild(chip);
    });
  }
  function addLine(type) {
    const raw = $('line-price').value.trim().replace(',', '.');
    if (!/^\d{1,9}(\.\d{1,2})?$/.test(raw)) { toast(t('line.bad'), false); return; }
    const price = Math.round(parseFloat(raw) * 100);
    if (price <= 0) { toast(t('line.zero'), false); return; }
    const a = loadLines(); a.push({ price, type }); saveLines(a); $('line-price').value = ''; renderLines();
  }

  // ---------------------------------------------------------------- render: rynek
  const coinById = (id) => (S.market ? S.market.coins.find(c => c.id === id) : null);

  function setCurrency(cur) { document.querySelectorAll('[data-cur]').forEach(n => { n.textContent = cur; }); }

  function renderMarket() {
    const m = S.market; if (!m) return;
    setCurrency(m.currency);
    if (m.title) { $('brand').textContent = m.title; document.title = m.title; }
    if (m.version) $('brand-version').textContent = 'v' + m.version;
    if (!S.coin || !coinById(S.coin)) S.coin = m.coins.length ? m.coins[0].id : null;
    if (S.coin) store.set('kg_coin', S.coin);
    renderStrip(); renderCoin(); renderNews(); renderFeed(); fillCoinSelects();
    $('fee-note').textContent = t('fee.note', { fee: (m.feeBp / 100).toFixed(2).replace('.', window.I18N.lang() === 'pl' ? ',' : '.') });
  }

  function flash(node, dir) {
    node.classList.remove('flash-up', 'flash-down');
    void node.offsetWidth;
    node.classList.add(dir > 0 ? 'flash-up' : 'flash-down');
  }

  function renderStrip() {
    const strip = $('coin-strip');
    const want = S.market.coins.map(c => c.id).join(',');
    if (strip.dataset.ids !== want) {
      clear(strip); strip.dataset.ids = want;
      S.market.coins.forEach(c => {
        const b = el('button', 'coin-tab'); b.type = 'button'; b.setAttribute('role', 'tab'); b.dataset.coin = c.id;
        b.appendChild(el('span', 'ct-name')); b.appendChild(el('span', 'ct-price')); b.appendChild(el('span', 'ct-change'));
        b.addEventListener('click', () => selectCoin(c.id));
        strip.appendChild(b);
      });
    }
    S.market.coins.forEach(c => {
      const b = strip.querySelector('[data-coin="' + c.id.replace(/"/g, '') + '"]'); if (!b) return;
      const act = c.id === S.coin;
      b.classList.toggle('is-active', act); b.setAttribute('aria-selected', act ? 'true' : 'false');
      b.children[0].textContent = icon(c) + ' ' + c.id;
      const pr = b.children[1]; const txt = fmtMoney(c.price) + ' ' + S.market.currency;
      if (pr.textContent !== txt) { pr.textContent = txt; }
      const ch = pct(c.price, c.open24h);
      b.children[2].className = 'ct-change ' + (ch >= 0 ? 't-green' : 't-red');
      b.children[2].textContent = fmtPct(ch) + ' ' + t('h24');
    });
  }

  function renderCoin() {
    const c = coinById(S.coin); if (!c) return;
    $('coin-name').textContent = icon(c) + ' ' + c.id;
    const pe = $('coin-price'); pe.textContent = fmtMoney(c.price);
    const pp = S.prevPrice[c.id];
    if (pp !== undefined && pp !== c.price) flash(pe, c.price - pp);
    S.prevPrice[c.id] = c.price;
    const ch = pct(c.price, c.open24h);
    const badge = $('coin-change'); badge.textContent = fmtPct(ch) + ' ' + t('h24'); badge.className = 'badge ' + (ch >= 0 ? 'up' : 'down');
    $('coin-stats').textContent = t('stats', { min: fmtMoney(c.low24h), max: fmtMoney(c.high24h), sell: fmtMoney(c.sellPrice) });
    const pl = $('coin-pl');
    const h = S.me && S.me.holdings.find(x => x.coin === c.id);
    if (h && h.qty > 0) {
      const diff = h.value - h.cost;
      pl.hidden = false; pl.textContent = t('pl.label', { diff: (diff >= 0 ? '+' : '') + fmtMoney(diff), cur: S.market.currency, pct: fmtPct(pct(h.value, h.cost)) });
      pl.className = 'badge ' + (diff >= 0 ? 'up' : 'down');
    } else { pl.hidden = true; }
    $('sell-max').textContent = h ? fmtQty(h.qty) : '0';
    const mb = S.me && S.me.maxBuy ? (S.me.maxBuy[c.id] || 0) : 0;
    $('buy-max').textContent = fmtQty(mb);
  }

  function renderNews() {
    const box = $('news-list'); clear(box);
    if (!S.market.news.length) { box.appendChild(el('p', 'empty', t('news.empty'))); return; }
    S.market.news.forEach(n => {
      const ty = String(n.type || '').toUpperCase();
      const bull = ty === 'HOSSA' || ty === 'BULL' || ty === 'GREEN', bear = ty === 'BESSA' || ty === 'BEAR' || ty === 'RED';
      const col = bull ? 'green' : bear ? 'red' : (['blue', 'purple'].includes(String(n.type).toLowerCase()) ? String(n.type).toLowerCase() : 'blue');
      const it = el('div', 'item news-' + col);
      const tm = el('div', 'time', fmtDateTime(n.ts));
      if (ty === 'HOSSA' || ty === 'BESSA' || ty === 'BULL' || ty === 'BEAR') tm.appendChild(el('span', 'news-tag ' + col, ty));
      it.appendChild(tm);
      it.appendChild(el('div', null, n.text));
      box.appendChild(it);
    });
  }

  function renderFeed() {
    const box = $('feed-list'); clear(box);
    if (!S.market.feed.length) { box.appendChild(el('p', 'empty', t('feed.empty'))); return; }
    S.market.feed.forEach(f => {
      const buy = f.side === 'BUY';
      const it = el('div', 'item ' + (buy ? 'buy' : 'sell'));
      const r1 = el('div', 'item-row');
      const who = el('span', 'bold', f.name);
      if (S.market.labelBots !== false && String(f.source).toUpperCase() === 'BOT') who.appendChild(el('span', 'tag-bot', t('bot')));
      r1.appendChild(who); r1.appendChild(el('span', 'time', fmtTime(f.ts)));
      const r2 = el('div', 'item-row');
      r2.appendChild(el('span', buy ? 't-green' : 't-red', (buy ? t('feed.bought') + ' ' : t('feed.sold') + ' ') + fmtQty(f.qty) + ' ' + f.coin));
      r2.appendChild(el('span', 't-yellow', fmtMoney(f.value)));
      it.appendChild(r1); it.appendChild(r2); box.appendChild(it);
    });
  }

  function fillCoinSelects() {
    const coins = S.market.coins;
    document.querySelectorAll('[data-coin-select]').forEach(sel => {
      const want = coins.map(c => c.id).join(',');
      if (sel.dataset.filled === want) return;
      const cur = sel.value; clear(sel);
      coins.forEach(c => { const o = el('option', null, c.id); o.value = c.id; sel.appendChild(o); });
      if (cur) sel.value = cur; sel.dataset.filled = want;
    });
    const as = $('admin-asset');
    const want = 'bal,' + coins.map(c => c.id).join(',');
    if (as.dataset.filled !== want) {
      clear(as);
      const b = el('option', null, t('wallet.balance-opt', { cur: S.market.currency })); b.value = 'BALANCE'; as.appendChild(b);
      coins.forEach(c => { const o = el('option', null, c.id); o.value = c.id; as.appendChild(o); });
      as.dataset.filled = want;
    }
  }

  // ---------------------------------------------------------------- render: konto
  function renderAccount() {
    const me = S.me; const logged = !!me;
    $('card-login').hidden = logged;
    $('card-wallet').hidden = !logged; $('card-portfolio').hidden = !logged; $('card-orders').hidden = !logged;
    $('tab-admin').hidden = !(me && me.admin);
    if (!(me && me.admin) && S.view === 'admin') setView('market');
    const ub = $('userbox'); Array.from(ub.querySelectorAll('.userbox-name,.userbox-out')).forEach(n => n.remove());
    if (logged) {
      const nm = el('span', 'userbox-name'); nm.appendChild(document.createTextNode(t('user.player'))); nm.appendChild(el('b', null, me.name));
      const out = el('button', 'btn btn-ghost userbox-out', t('user.logout')); out.type = 'button';
      out.addEventListener('click', async () => { await act(() => api('logout', { body: {} })); S.me = null; renderAccount(); renderCoin(); });
      ub.insertBefore(nm, $('clock')); ub.appendChild(out);
      $('wallet-balance').textContent = fmtMoney(me.balance);
      $('wallet-worth').textContent = fmtMoney(me.netWorth);
      renderPortfolio(); renderOrders();
    }
    document.getElementById('history-box').hidden = !logged;
    renderCoin();
  }

  function renderPortfolio() {
    const ul = $('portfolio-list'); clear(ul);
    const hs = S.me.holdings.filter(h => h.qty > 0);
    if (!hs.length) { ul.appendChild(el('li', 'empty', t('portfolio.empty'))); return; }
    hs.forEach(h => {
      const li = el('li', 'item'); const diff = h.value - h.cost;
      const r1 = el('div', 'item-row'); r1.appendChild(el('span', 'bold', icon(coinById(h.coin)) + ' ' + h.coin)); r1.appendChild(el('span', null, fmtQty(h.qty) + ' ' + t('pcs')));
      const r2 = el('div', 'item-row'); r2.appendChild(el('span', 'muted', t('portfolio.value') + ' ' + fmtMoney(h.value)));
      r2.appendChild(el('span', diff >= 0 ? 't-green' : 't-red', (diff >= 0 ? '+' : '') + fmtMoney(diff)));
      li.appendChild(r1); li.appendChild(r2);
      li.classList.add('clickable'); li.addEventListener('click', () => selectCoin(h.coin));
      ul.appendChild(li);
    });
  }

  function renderOrders() {
    const box = $('orders-list'); clear(box);
    if (!S.me.orders.length) { box.appendChild(el('p', 'empty', t('orders.empty'))); return; }
    S.me.orders.forEach(o => {
      const buy = o.side === 'BUY';
      const it = el('div', 'item ' + (buy ? 'buy' : 'sell'));
      const r1 = el('div', 'item-row');
      r1.appendChild(el('span', 'bold ' + (buy ? 't-green' : 't-red'), (buy ? t('order.buy') + ' ' : t('order.sell') + ' ') + fmtQty(o.qty) + ' ' + o.coin));
      const x = el('button', 'btn btn-small', t('order.cancel')); x.type = 'button';
      x.addEventListener('click', async () => { if (await act(() => api('cancel', { body: { id: String(o.id) } }), t('toast.order-cancelled'))) refreshMe(); });
      r1.appendChild(x);
      const r2 = el('div', 'item-row'); r2.appendChild(el('span', 'muted', (buy ? t('order.when-le') + ' ' : t('order.when-ge') + ' ') + fmtMoney(o.limit)));
      it.appendChild(r1); it.appendChild(r2); box.appendChild(it);
    });
  }

  function renderHistory(trades) {
    $('history-coin').textContent = S.coin || '';
    const tb = $('history-body'); clear(tb);
    if (!trades.length) { const tr = el('tr'); const td = el('td', 'empty', t('history.none')); td.colSpan = 5; tr.appendChild(td); tb.appendChild(tr); return; }
    trades.forEach(x => {
      const buy = x.side === 'BUY'; const tr = el('tr');
      tr.appendChild(el('td', buy ? 't-green bold' : 't-red bold', buy ? t('history.buy') : t('history.sell')));
      tr.appendChild(el('td', null, fmtQty(x.qty)));
      tr.appendChild(el('td', null, x.qty > 0 ? fmtMoney(Math.round(x.value * 10000 / x.qty)) : '—'));
      tr.appendChild(el('td', 't-yellow', fmtMoney(x.value)));
      tr.appendChild(el('td', 'right muted', fmtDateTime(x.ts)));
      tb.appendChild(tr);
    });
  }

  // ---------------------------------------------------------------- odświeżanie
  let chart;
  let marketBusy = false;
  async function refreshMarket() {
    if (marketBusy) return;
    marketBusy = true;
    try { S.market = await api('market'); window.I18N.setServerDefault(S.market.lang); renderMarket(); liveCandle(); } catch (e) { /* ciche – kolejna próba za chwilę */ }
    finally { marketBusy = false; }
  }
  // Ostatnia świeca „żyje” razem z ceną z serwera – bez czekania na pobranie całej serii.
  function liveCandle() {
    const c = coinById(S.coin); if (!c || !S.market || !S.market.time) return;
    const d = chart.data; if (!d || !d.length) return;
    const tfs = S.tf; const nowS = Math.floor(S.market.time / 1000);
    const bucket = nowS - (nowS % tfs);
    const last = d[d.length - 1];
    if (bucket > last[0]) {
      d.push([bucket, last[4], Math.max(last[4], c.price), Math.min(last[4], c.price), c.price]);
    } else {
      last[4] = c.price; if (c.price > last[2]) last[2] = c.price; if (c.price < last[3]) last[3] = c.price;
    }
    chart.draw();
  }
  async function refreshMe() {
    try { S.me = await api('me'); renderAccount(); }
    catch (e) { if (e.status === 401 && S.me) { S.me = null; renderAccount(); } }
  }
  async function refreshCandles() {
    if (!S.coin) return;
    const coin = S.coin, tf = S.tf;
    try {
      const d = await api('candles?coin=' + encodeURIComponent(coin) + '&tf=' + tf + '&limit=300');
      if (coin !== S.coin || tf !== S.tf) return;
      chart.setData(d.candles, tf); liveCandle();
    } catch (e) { /* ignoruj */ }
  }
  async function refreshHistory() {
    if (!S.me || !S.coin) return;
    try { const d = await api('trades?coin=' + encodeURIComponent(S.coin) + '&limit=20'); renderHistory(d.trades); } catch (e) { /* ignoruj */ }
  }
  async function refreshRanking() {
    if (S.view !== 'ranking') return;
    try {
      const d = await api('ranking'); const tb = $('ranking-body'); clear(tb);
      d.rows.forEach((r, i) => {
        const tr = el('tr');
        tr.appendChild(el('td', 'bold', i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : String(i + 1)));
        const who = el('td', S.me && r.name === S.me.name ? 't-yellow bold' : null, r.name); tr.appendChild(who);
        tr.appendChild(el('td', 'right t-yellow bold', fmtMoney(r.worth) + ' ' + (S.market ? S.market.currency : '')));
        tb.appendChild(tr);
      });
    } catch (e) { /* ignoruj */ }
  }

  function selectCoin(id) {
    if (S.coin === id) return;
    S.coin = id; store.set('kg_coin', id); chart.resetView(); chart.setData([], S.tf);
    renderStrip(); renderCoin(); renderLines(); clearQuotes();
    refreshCandles(); refreshHistory();
  }

  function setView(v) {
    S.view = v; if (VIEWS.includes(v)) store.set('kg_view', v);
    ['market', 'ranking', 'admin'].forEach(n => { $('view-' + n).hidden = n !== v; });
    document.querySelectorAll('.tab').forEach(t => t.classList.toggle('is-active', t.dataset.view === v));
    if (v === 'ranking') refreshRanking();
    if (v === 'admin') loadAdmin();
    if (v === 'market') chart.resize();
  }

  // ---------------------------------------------------------------- podgląd kosztu
  function clearQuotes() { $('buy-quote').textContent = ' '; $('sell-quote').textContent = ' '; }
  const timers = {};
  function quote(side) {
    const form = $(side + '-form'); const out = $(side + '-quote');
    const raw = form.elements.qty.value.trim().replace(',', '.');
    clearTimeout(timers[side]);
    if (!raw) { out.textContent = ' '; return; }
    timers[side] = setTimeout(async () => {
      try {
        const q = await api('quote?side=' + side + '&coin=' + encodeURIComponent(S.coin) + '&qty=' + encodeURIComponent(raw));
        const cur = S.market.currency;
        out.textContent = side === 'buy'
          ? t('quote.buy', { value: fmtMoney(q.value), cur, avg: fmtMoney(q.avgPrice), after: fmtMoney(q.priceAfter) })
          : t('quote.sell', { net: fmtMoney(q.net), cur, fee: fmtMoney(q.fee), after: fmtMoney(q.priceAfter) });
      } catch (e) { out.textContent = e.message || ''; }
    }, 250);
  }

  // ---------------------------------------------------------------- zdarzenia
  function bind() {
    document.querySelectorAll('.tab').forEach(t => t.addEventListener('click', () => setView(t.dataset.view)));

    $('login-form').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const code = $('login-code').value.trim();
      if (!code) return;
      const r = await act(() => api('login', { body: { code } }), (x) => t('toast.logged', { name: x.name }));
      if (r) { $('login-code').value = ''; await refreshMe(); refreshHistory(); }
    });

    $('withdraw-max').addEventListener('click', () => { if (S.me) $('withdraw-amount').value = (Math.max(0, S.me.balance) / 100).toFixed(2); });
    $('withdraw-form').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const amount = $('withdraw-amount').value.trim().replace(',', '.');
      const r = await act(() => api('withdraw', { body: { amount } }), () => t('toast.withdraw'));
      if (r) { $('withdraw-amount').value = ''; refreshMe(); }
    });

    ['buy', 'sell'].forEach(side => {
      const form = $(side + '-form');
      form.elements.qty.addEventListener('input', () => quote(side));
      $(side + '-max-btn').addEventListener('click', () => {
        if (!S.me) { toast(t('toast.login-first'), false); return; }
        let q = 0;
        if (side === 'buy') q = S.me.maxBuy[S.coin] || 0;
        else { const h = S.me.holdings.find(x => x.coin === S.coin); q = h ? h.qty : 0; }
        form.elements.qty.value = qtyInput(q); quote(side);
      });
      form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        if (!S.me) { toast(t('toast.login-first'), false); return; }
        const qty = form.elements.qty.value.trim().replace(',', '.');
        const r = await act(() => api('trade', { body: { side, coin: S.coin, qty } }), (f) =>
          t(side === 'buy' ? 'toast.bought' : 'toast.sold', { qty: fmtQty(f.qty), coin: f.coin, value: fmtMoney(f.value), cur: S.market.currency }));
        if (r) { form.elements.qty.value = ''; clearQuotes(); await Promise.all([refreshMe(), refreshMarket()]); refreshCandles(); refreshHistory(); }
      });
    });

    ['buy', 'sell'].forEach(side => {
      const form = $(side + '-limit-form');
      form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        if (!S.me) { toast(t('toast.login-first'), false); return; }
        const qty = form.elements.qty.value.trim().replace(',', '.');
        const price = form.elements.price.value.trim().replace(',', '.');
        const r = await act(() => api('order', { body: { side, coin: S.coin, qty, price } }), () => t('toast.order-saved'));
        if (r) { form.reset(); refreshMe(); }
      });
    });

    $('line-support').addEventListener('click', () => addLine('support'));
    $('line-resist').addEventListener('click', () => addLine('resistance'));
    $('line-clear').addEventListener('click', () => { saveLines([]); renderLines(); });
    $('tf-buttons').addEventListener('click', (ev) => {
      const b = ev.target.closest('.tf-btn'); if (!b) return;
      S.tf = parseInt(b.dataset.tf, 10); store.set('kg_tf', String(S.tf));
      document.querySelectorAll('.tf-btn').forEach(x => x.classList.toggle('is-active', x === b));
      chart.setData([], S.tf); chart.resetView(); refreshCandles();
    });
    chart.onPriceClick = (cents) => { $('line-price').value = (cents / 100).toFixed(2); };

    bindAdmin();
  }

  // ---------------------------------------------------------------- admin
  const num = (v) => String(v).trim().replace(',', '.');
  const fmtNum = (x) => String(Math.round(x * 10000) / 10000);

  async function loadAdmin() {
    if (!(S.me && S.me.admin)) return;
    try { S.adminInfo = await api('admin/info'); renderAdminEvents(); renderAdminCoins(); }
    catch (e) { fail(e); }
  }

  function badge(text, cls) { return el('span', 'src-tag' + (cls ? ' ' + cls : ''), text); }

  function renderAdminEvents() {
    const info = S.adminInfo; if (!info) return;
    const tb = $('admin-events-body'); clear(tb);
    if (!info.events.length) { const tr = el('tr'); const td = el('td', 'empty', t('admin.events.none')); td.colSpan = 6; tr.appendChild(td); tb.appendChild(tr); }
    info.events.forEach(e => {
      const tr = el('tr');
      const ty = String(e.type || '').toUpperCase();
      const up = e.multiplier >= 1;
      const tc = el('td', null, null);
      tc.appendChild(el('b', e.coin ? (up ? 't-green' : 't-red') : null, e.type));
      tc.appendChild(document.createTextNode(' '));
      tc.appendChild(badge(e.custom ? t('admin.events.src-custom') : t('admin.events.src-config'), e.custom ? 'custom' : ''));
      tr.appendChild(tc);
      tr.appendChild(el('td', null, e.coin || '—'));
      tr.appendChild(el('td', e.coin ? (up ? 't-green' : 't-red') : 'muted', e.coin ? fmtPct((e.multiplier - 1) * 100) : '—'));
      tr.appendChild(el('td', null, String(e.weight)));
      tr.appendChild(el('td', 'td-text', e.text));
      const act = el('td', 'td-actions');
      const run = el('button', 'btn btn-small btn-run', t('admin.events.run')); run.type = 'button';
      run.addEventListener('click', async () => { if (await act_(() => api('admin/event', { body: { index: String(e.index) } }), t('admin.events.ran'))) { refreshMarket(); refreshCandles(); } });
      act.appendChild(run);
      if (e.custom) {
        const del = el('button', 'btn btn-small', t('admin.events.delete')); del.type = 'button';
        del.addEventListener('click', async () => {
          if (!window.confirm(t('admin.events.confirm-delete'))) return;
          if (await act_(() => api('admin/event/delete', { body: { id: String(e.id) } }), t('admin.events.deleted'))) loadAdmin();
        });
        act.appendChild(del);
      }
      tr.appendChild(act); tb.appendChild(tr);
    });
  }

  function renderAdminCoins() {
    const info = S.adminInfo; if (!info) return;
    const tb = $('admin-coins-body'); clear(tb);
    const cur = S.market ? S.market.currency : '';
    info.coinDefs.forEach(d => {
      const tr = el('tr');
      tr.appendChild(el('td', 'bold', ICONS[String(d.icon).toUpperCase()] ? ICONS[String(d.icon).toUpperCase()] + ' ' + d.id : d.id));
      tr.appendChild(el('td', null, fmtMoney(d.startPrice) + ' ' + cur));
      tr.appendChild(el('td', null, fmtNum(d.volMin) + ' – ' + fmtNum(d.volMax)));
      tr.appendChild(el('td', null, d.liquidity > 0 ? fmtNum(d.liquidity) : 'auto'));
      const sc = el('td'); sc.appendChild(badge(d.custom ? t('admin.coins.src-custom') : t('admin.coins.src-config'), d.custom ? 'custom' : '')); tr.appendChild(sc);
      const act = el('td', 'td-actions');
      if (d.custom) {
        const ed = el('button', 'btn btn-ghost', t('admin.coins.edit')); ed.type = 'button';
        ed.addEventListener('click', () => startCoinEdit(d));
        const del = el('button', 'btn btn-small', t('admin.coins.delete')); del.type = 'button';
        del.addEventListener('click', async () => {
          if (!window.confirm(t('admin.coins.confirm-delete', { id: d.id }))) return;
          if (await act_(() => api('admin/coin/delete', { body: { id: d.id } }), t('admin.coins.deleted'))) { stopCoinEdit(); loadAdmin(); refreshMarket(); }
        });
        act.appendChild(ed); act.appendChild(del);
      }
      tr.appendChild(act); tb.appendChild(tr);
    });
    // lista ikon do podpowiedzi
    const dl = $('icon-list');
    if (!dl.firstChild) Object.keys(ICONS).filter(k => k !== 'default').forEach(k => { const o = el('option'); o.value = k; dl.appendChild(o); });
    // lista walut w formularzu eventu
    const sel = $('admin-event-coin');
    const want = info.coinDefs.map(d => d.id).join(',') + '|' + window.I18N.lang();
    if (sel.dataset.filled !== want) {
      clear(sel);
      const none = el('option', null, t('admin.events.nocoin')); none.value = ''; sel.appendChild(none);
      info.coinDefs.forEach(d => { const o = el('option', null, d.id); o.value = d.id; sel.appendChild(o); });
      sel.dataset.filled = want;
    }
  }

  let editingCoin = null;
  function startCoinEdit(d) {
    editingCoin = d.id;
    const f = $('admin-coin-form');
    f.elements.id.value = d.id; f.elements.id.readOnly = true;
    f.elements.price.value = (d.startPrice / 100).toFixed(2);
    f.elements.volMin.value = fmtNum(d.volMin); f.elements.volMax.value = fmtNum(d.volMax);
    f.elements.liquidity.value = d.liquidity > 0 ? fmtNum(d.liquidity) : '';
    f.elements.icon.value = d.icon;
    $('admin-coin-form-title').textContent = t('admin.coins.editing', { id: d.id });
    $('admin-coin-submit').textContent = t('admin.coins.save');
    $('admin-coin-cancel').hidden = false;
    f.scrollIntoView({ block: 'nearest' });
  }
  function stopCoinEdit() {
    editingCoin = null;
    const f = $('admin-coin-form'); f.reset(); f.elements.id.readOnly = false;
    $('admin-coin-form-title').textContent = t('admin.coins.new');
    $('admin-coin-submit').textContent = t('admin.coins.add');
    $('admin-coin-cancel').hidden = true;
  }

  // wersja `act` dostępna w panelu (nazwa `act` bywa przesłaniana w pętlach)
  const act_ = (fn, okMsg) => act(fn, okMsg);

  function bindAdmin() {
    const form = (id, path, build, okMsg, after) => {
      $(id).addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const f = ev.target; const body = build(f);
        if (body === null) return;
        const r = await act(() => api(path, { body }), typeof okMsg === 'function' ? okMsg : () => t(okMsg));
        if (r) { if (after) after(f); refreshMarket(); refreshCandles(); }
      });
    };
    form('admin-price-form', 'admin/price', f => ({ coin: f.elements.coin.value, mode: f.elements.mode.value, value: num(f.elements.value.value) }), 'admin.done.price', f => f.elements.value.value = '');
    form('admin-trend-form', 'admin/trend', f => ({ coin: f.elements.coin.value, percent: num(f.elements.percent.value) }), 'admin.done.trend', f => f.elements.percent.value = '');
    form('admin-reset-form', 'admin/reset', f => {
      if (f.elements.confirm.value.trim() !== 'RESET') { toast(t('admin.reset.type'), false); return null; }
      return { confirm: 'RESET', wipePlayers: f.elements.wipe.checked ? 'true' : 'false' };
    }, 'admin.done.reset', f => { f.reset(); refreshMe(); });
    form('admin-player-form', 'admin/player', f => ({
      player: f.elements.player.value.trim(), asset: f.elements.asset.value, op: f.elements.op.value, value: num(f.elements.value.value)
    }), 'admin.done.player', f => f.elements.value.value = '');
    $('admin-force').addEventListener('click', async () => { if (await act(() => api('admin/force', { body: {} }), t('admin.done.force'))) { refreshMarket(); refreshCandles(); } });

    // --- eventy
    $('admin-events-import').addEventListener('click', async () => {
      const r = await act(() => api('admin/event/import', { body: {} }), (x) => t('admin.events.imported', { n: x.added }));
      if (r) loadAdmin();
    });
    $('admin-event-form').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const f = ev.target; const kind = f.elements.kind.value; const coin = f.elements.coin.value;
      const en = window.I18N.lang() === 'en';
      const type = kind === 'bull' ? (en ? 'BULL' : 'HOSSA') : kind === 'bear' ? (en ? 'BEAR' : 'BESSA') : 'INFO';
      const color = kind === 'bull' ? '&a' : kind === 'bear' ? '&c' : '&b';
      let multiplier = '';
      if (coin) {
        const pctv = parseFloat(num(f.elements.change.value));
        if (!isFinite(pctv)) { toast(t('e.BAD_MULTIPLIER'), false); return; }
        multiplier = String(Math.round((1 + pctv / 100) * 10000) / 10000);
      }
      const body = { type, text: f.elements.text.value.trim(), coin, multiplier, weight: f.elements.weight.value.trim() || '1', color };
      if (await act(() => api('admin/event/add', { body }), t('admin.events.added'))) { f.elements.text.value = ''; f.elements.change.value = ''; loadAdmin(); }
    });

    // --- kryptowaluty
    $('admin-coin-cancel').addEventListener('click', stopCoinEdit);
    $('admin-coin-form').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const f = ev.target;
      const body = { id: f.elements.id.value.trim(), price: num(f.elements.price.value), volMin: num(f.elements.volMin.value), volMax: num(f.elements.volMax.value),
        liquidity: num(f.elements.liquidity.value), icon: f.elements.icon.value.trim().toUpperCase() };
      const edit = editingCoin !== null;
      if (await act(() => api(edit ? 'admin/coin/update' : 'admin/coin/add', { body }), t(edit ? 'admin.coins.saved' : 'admin.coins.added'))) {
        stopCoinEdit(); loadAdmin(); refreshMarket(); refreshCandles();
      }
    });
  }

  // ---------------------------------------------------------------- start
  function tickClock() { $('clock').textContent = fmtTime(Date.now()); }

  function relocalize() {
    window.I18N.apply();
    $('lang-toggle').textContent = t('lang.switch');
    if (S.market) renderMarket();
    renderAccount();
    if (S.adminInfo) { renderAdminEvents(); renderAdminCoins(); }
    renderLines();
    if (editingCoin !== null) { $('admin-coin-form-title').textContent = t('admin.coins.editing', { id: editingCoin }); $('admin-coin-submit').textContent = t('admin.coins.save'); }
    refreshHistory(); refreshRanking(); clearQuotes();
    if (chart) chart.draw();
    const fa = $('admin-asset'); if (fa) fa.dataset.filled = '';
    if (S.market) fillCoinSelects();
  }

  function init() {
    window.I18N.apply();
    $('lang-toggle').addEventListener('click', () => window.I18N.set(window.I18N.lang() === 'pl' ? 'en' : 'pl'));
    window.I18N.onChange(relocalize);
    chart = new window.CandleChart($('chart'), { fmtPrice: fmtMoney });
    bind(); tickClock(); setInterval(tickClock, 1000);
    document.querySelectorAll('.tf-btn').forEach(x => x.classList.toggle('is-active', parseInt(x.dataset.tf, 10) === S.tf));
    chart.tf = S.tf;
    const sv = store.get('kg_view'); if (VIEWS.includes(sv) && sv !== 'market') setView(sv);
    refreshMarket().then(() => { renderLines(); refreshCandles(); });
    refreshMe().then(() => { refreshHistory(); });
    renderAccount();
    setInterval(() => { if (!document.hidden) refreshMarket(); }, 1000);
    setInterval(() => { if (!document.hidden && S.me) refreshMe(); }, 4000);
    setInterval(() => { if (!document.hidden) refreshCandles(); }, 6000);
    setInterval(() => { if (!document.hidden) refreshHistory(); }, 10000);
    setInterval(() => { if (!document.hidden) refreshRanking(); }, 15000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { refreshMarket(); refreshCandles(); if (S.me) refreshMe(); } });
  }
  init();
})();
