/* Giełda – logika strony. Wszystkie dane z serwera trafiają do DOM wyłącznie przez textContent. */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
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
    const t = parseInt(store.get('kg_tf'), 10); if (TFS.includes(t)) S.tf = t;
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
    try { res = await fetch('/api/' + path, o); } catch (e) { throw { status: 0, message: 'Brak połączenia z serwerem.' }; }
    let data = {};
    try { data = await res.json(); } catch (e) { /* pusta odpowiedź */ }
    if (!res.ok) throw { status: res.status, code: data.error, message: data.message || ('Błąd ' + res.status) };
    return data;
  }

  function toast(msg, ok) {
    const t = el('div', 'toast ' + (ok ? 'ok' : 'err'), msg);
    $('toasts').appendChild(t);
    setTimeout(() => t.remove(), ok ? 3500 : 5500);
  }
  function fail(e) {
    if (e && e.status === 401 && S.me) { S.me = null; renderAccount(); }
    toast((e && e.message) || 'Nieoczekiwany błąd.', false);
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
      chip.appendChild(document.createTextNode((l.type === 'support' ? 'Wsparcie ' : 'Opór ') + fmtMoney(l.price)));
      const x = el('button', null, '×'); x.type = 'button'; x.setAttribute('aria-label', 'Usuń linię');
      x.addEventListener('click', () => { const a = loadLines(); a.splice(i, 1); saveLines(a); renderLines(); });
      chip.appendChild(x); box.appendChild(chip);
    });
  }
  function addLine(type) {
    const raw = $('line-price').value.trim().replace(',', '.');
    if (!/^\d{1,9}(\.\d{1,2})?$/.test(raw)) { toast('Podaj cenę linii, np. 120.50', false); return; }
    const price = Math.round(parseFloat(raw) * 100);
    if (price <= 0) { toast('Cena musi być większa od zera.', false); return; }
    const a = loadLines(); a.push({ price, type }); saveLines(a); $('line-price').value = ''; renderLines();
  }

  // ---------------------------------------------------------------- render: rynek
  const coinById = (id) => (S.market ? S.market.coins.find(c => c.id === id) : null);

  function setCurrency(cur) { document.querySelectorAll('[data-cur]').forEach(n => { n.textContent = cur; }); }

  function renderMarket() {
    const m = S.market; if (!m) return;
    setCurrency(m.currency);
    if (m.title) { $('brand').textContent = m.title; document.title = m.title; }
    if (!S.coin || !coinById(S.coin)) S.coin = m.coins.length ? m.coins[0].id : null;
    if (S.coin) store.set('kg_coin', S.coin);
    renderStrip(); renderCoin(); renderNews(); renderFeed(); fillCoinSelects();
    $('fee-note').textContent = 'Prowizja przy sprzedaży: ' + (m.feeBp / 100).toFixed(2).replace('.', ',') + '%. Duże zlecenia przesuwają cenę – podgląd kosztu pokazuje dokładną kwotę.';
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
      b.children[2].textContent = fmtPct(ch) + ' (24h)';
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
    const badge = $('coin-change'); badge.textContent = fmtPct(ch) + ' (24h)'; badge.className = 'badge ' + (ch >= 0 ? 'up' : 'down');
    $('coin-stats').textContent = '24h: min ' + fmtMoney(c.low24h) + ' · max ' + fmtMoney(c.high24h) + ' · cena sprzedaży ' + fmtMoney(c.sellPrice);
    const pl = $('coin-pl');
    const h = S.me && S.me.holdings.find(x => x.coin === c.id);
    if (h && h.qty > 0) {
      const diff = h.value - h.cost;
      pl.hidden = false; pl.textContent = 'Twój zysk/strata: ' + (diff >= 0 ? '+' : '') + fmtMoney(diff) + ' ' + S.market.currency + ' (' + fmtPct(pct(h.value, h.cost)) + ')';
      pl.className = 'badge ' + (diff >= 0 ? 'up' : 'down');
    } else { pl.hidden = true; }
    $('sell-max').textContent = h ? fmtQty(h.qty) : '0';
    const mb = S.me && S.me.maxBuy ? (S.me.maxBuy[c.id] || 0) : 0;
    $('buy-max').textContent = fmtQty(mb);
  }

  function renderNews() {
    const box = $('news-list'); clear(box);
    if (!S.market.news.length) { box.appendChild(el('p', 'empty', 'Brak wiadomości.')); return; }
    S.market.news.forEach(n => {
      const ty = String(n.type || '').toUpperCase();
      const col = ty === 'HOSSA' || ty === 'GREEN' ? 'green' : ty === 'BESSA' || ty === 'RED' ? 'red' : (['blue', 'purple'].includes(String(n.type).toLowerCase()) ? String(n.type).toLowerCase() : 'blue');
      const it = el('div', 'item news-' + col);
      const tm = el('div', 'time', fmtDateTime(n.ts));
      if (ty === 'HOSSA' || ty === 'BESSA') tm.appendChild(el('span', 'news-tag ' + col, ty === 'HOSSA' ? 'HOSSA' : 'BESSA'));
      it.appendChild(tm);
      it.appendChild(el('div', null, n.text));
      box.appendChild(it);
    });
  }

  function renderFeed() {
    const box = $('feed-list'); clear(box);
    if (!S.market.feed.length) { box.appendChild(el('p', 'empty', 'Jeszcze nic się nie wydarzyło.')); return; }
    S.market.feed.forEach(t => {
      const buy = t.side === 'BUY';
      const it = el('div', 'item ' + (buy ? 'buy' : 'sell'));
      const r1 = el('div', 'item-row');
      const who = el('span', 'bold', t.name);
      if (S.market.labelBots !== false && String(t.source).toUpperCase() === 'BOT') who.appendChild(el('span', 'tag-bot', 'bot'));
      r1.appendChild(who); r1.appendChild(el('span', 'time', fmtTime(t.ts)));
      const r2 = el('div', 'item-row');
      r2.appendChild(el('span', buy ? 't-green' : 't-red', (buy ? 'kupił ' : 'sprzedał ') + fmtQty(t.qty) + ' ' + t.coin));
      r2.appendChild(el('span', 't-yellow', fmtMoney(t.value)));
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
      const b = el('option', null, 'Saldo (' + S.market.currency + ')'); b.value = 'BALANCE'; as.appendChild(b);
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
      const nm = el('span', 'userbox-name'); nm.appendChild(document.createTextNode('Gracz: ')); nm.appendChild(el('b', null, me.name));
      const out = el('button', 'btn btn-ghost userbox-out', 'Wyloguj'); out.type = 'button';
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
    if (!hs.length) { ul.appendChild(el('li', 'empty', 'Nie masz jeszcze żadnych kryptowalut.')); return; }
    hs.forEach(h => {
      const li = el('li', 'item'); const diff = h.value - h.cost;
      const r1 = el('div', 'item-row'); r1.appendChild(el('span', 'bold', icon(coinById(h.coin)) + ' ' + h.coin)); r1.appendChild(el('span', null, fmtQty(h.qty) + ' szt.'));
      const r2 = el('div', 'item-row'); r2.appendChild(el('span', 'muted', 'wartość ' + fmtMoney(h.value)));
      r2.appendChild(el('span', diff >= 0 ? 't-green' : 't-red', (diff >= 0 ? '+' : '') + fmtMoney(diff)));
      li.appendChild(r1); li.appendChild(r2);
      li.classList.add('clickable'); li.addEventListener('click', () => selectCoin(h.coin));
      ul.appendChild(li);
    });
  }

  function renderOrders() {
    const box = $('orders-list'); clear(box);
    if (!S.me.orders.length) { box.appendChild(el('p', 'empty', 'Brak aktywnych zleceń.')); return; }
    S.me.orders.forEach(o => {
      const buy = o.side === 'BUY';
      const it = el('div', 'item ' + (buy ? 'buy' : 'sell'));
      const r1 = el('div', 'item-row');
      r1.appendChild(el('span', 'bold ' + (buy ? 't-green' : 't-red'), (buy ? 'KUP ' : 'SPRZEDAJ ') + fmtQty(o.qty) + ' ' + o.coin));
      const x = el('button', 'btn btn-small', 'Anuluj'); x.type = 'button';
      x.addEventListener('click', async () => { if (await act(() => api('cancel', { body: { id: String(o.id) } }), 'Zlecenie anulowane.')) refreshMe(); });
      r1.appendChild(x);
      const r2 = el('div', 'item-row'); r2.appendChild(el('span', 'muted', (buy ? 'gdy cena ≤ ' : 'gdy cena ≥ ') + fmtMoney(o.limit)));
      it.appendChild(r1); it.appendChild(r2); box.appendChild(it);
    });
  }

  function renderHistory(trades) {
    $('history-coin').textContent = S.coin || '';
    const tb = $('history-body'); clear(tb);
    if (!trades.length) { const tr = el('tr'); const td = el('td', 'empty', 'Brak transakcji.'); td.colSpan = 5; tr.appendChild(td); tb.appendChild(tr); return; }
    trades.forEach(t => {
      const buy = t.side === 'BUY'; const tr = el('tr');
      tr.appendChild(el('td', buy ? 't-green bold' : 't-red bold', buy ? 'KUPNO' : 'SPRZEDAŻ'));
      tr.appendChild(el('td', null, fmtQty(t.qty)));
      tr.appendChild(el('td', null, t.qty > 0 ? fmtMoney(Math.round(t.value * 10000 / t.qty)) : '—'));
      tr.appendChild(el('td', 't-yellow', fmtMoney(t.value)));
      tr.appendChild(el('td', 'right muted', fmtDateTime(t.ts)));
      tb.appendChild(tr);
    });
  }

  // ---------------------------------------------------------------- odświeżanie
  let chart;
  let marketBusy = false;
  async function refreshMarket() {
    if (marketBusy) return;
    marketBusy = true;
    try { S.market = await api('market'); renderMarket(); liveCandle(); } catch (e) { /* ciche – kolejna próba za chwilę */ }
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
          ? 'Koszt: ' + fmtMoney(q.value) + ' ' + cur + ' (śr. ' + fmtMoney(q.avgPrice) + ', cena po zakupie ' + fmtMoney(q.priceAfter) + ')'
          : 'Dostaniesz: ' + fmtMoney(q.net) + ' ' + cur + ' (prowizja ' + fmtMoney(q.fee) + ', cena po sprzedaży ' + fmtMoney(q.priceAfter) + ')';
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
      const r = await act(() => api('login', { body: { code } }), (x) => 'Zalogowano jako ' + x.name);
      if (r) { $('login-code').value = ''; await refreshMe(); refreshHistory(); }
    });

    $('withdraw-max').addEventListener('click', () => { if (S.me) $('withdraw-amount').value = (Math.max(0, S.me.balance) / 100).toFixed(2); });
    $('withdraw-form').addEventListener('submit', async (ev) => {
      ev.preventDefault();
      const amount = $('withdraw-amount').value.trim().replace(',', '.');
      const r = await act(() => api('withdraw', { body: { amount } }), 'Wypłata zlecona – środki trafią do Twojego konta w grze za chwilę.');
      if (r) { $('withdraw-amount').value = ''; refreshMe(); }
    });

    ['buy', 'sell'].forEach(side => {
      const form = $(side + '-form');
      form.elements.qty.addEventListener('input', () => quote(side));
      $(side + '-max-btn').addEventListener('click', () => {
        if (!S.me) { toast('Zaloguj się, aby handlować.', false); return; }
        let q = 0;
        if (side === 'buy') q = S.me.maxBuy[S.coin] || 0;
        else { const h = S.me.holdings.find(x => x.coin === S.coin); q = h ? h.qty : 0; }
        form.elements.qty.value = qtyInput(q); quote(side);
      });
      form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        if (!S.me) { toast('Zaloguj się, aby handlować.', false); return; }
        const qty = form.elements.qty.value.trim().replace(',', '.');
        const r = await act(() => api('trade', { body: { side, coin: S.coin, qty } }), (f) =>
          (side === 'buy' ? 'Kupiono ' : 'Sprzedano ') + fmtQty(f.qty) + ' ' + f.coin + ' za ' + fmtMoney(f.value) + ' ' + S.market.currency);
        if (r) { form.elements.qty.value = ''; clearQuotes(); await Promise.all([refreshMe(), refreshMarket()]); refreshCandles(); refreshHistory(); }
      });
    });

    ['buy', 'sell'].forEach(side => {
      const form = $(side + '-limit-form');
      form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        if (!S.me) { toast('Zaloguj się, aby handlować.', false); return; }
        const qty = form.elements.qty.value.trim().replace(',', '.');
        const price = form.elements.price.value.trim().replace(',', '.');
        const r = await act(() => api('order', { body: { side, coin: S.coin, qty, price } }), 'Zlecenie limit zostało zapisane.');
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
  async function loadAdmin() {
    if (!(S.me && S.me.admin)) return;
    try {
      const d = await api('admin/info'); S.adminInfo = d;
      const sel = $('admin-event-select'); clear(sel);
      d.events.forEach(e => { const o = el('option', null, (e.coin ? '[' + e.coin + '] ' : '') + e.text); o.value = String(e.index); sel.appendChild(o); });
    } catch (e) { fail(e); }
  }
  function bindAdmin() {
    const form = (id, path, build, okMsg, after) => {
      $(id).addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const f = ev.target; const body = build(f);
        if (body === null) return;
        const r = await act(() => api(path, { body }), okMsg);
        if (r) { if (after) after(f); refreshMarket(); refreshCandles(); }
      });
    };
    form('admin-price-form', 'admin/price', f => ({ coin: f.elements.coin.value, mode: f.elements.mode.value, value: f.elements.value.value.trim().replace(',', '.') }), 'Cena zmieniona.', f => f.elements.value.value = '');
    form('admin-trend-form', 'admin/trend', f => ({ coin: f.elements.coin.value, percent: f.elements.percent.value.trim().replace(',', '.') }), 'Cichy trend załadowany.', f => f.elements.percent.value = '');
    form('admin-reset-form', 'admin/reset', f => {
      if (f.elements.confirm.value.trim() !== 'RESET') { toast('Wpisz dokładnie RESET.', false); return null; }
      return { confirm: 'RESET', wipePlayers: f.elements.wipe.checked ? 'true' : 'false' };
    }, 'Giełda została zresetowana.', f => { f.reset(); refreshMe(); });
    form('admin-player-form', 'admin/player', f => ({
      player: f.elements.player.value.trim(), asset: f.elements.asset.value, op: f.elements.op.value, value: f.elements.value.value.trim().replace(',', '.')
    }), 'Zapisano.', f => f.elements.value.value = '');
    $('admin-force').addEventListener('click', async () => { if (await act(() => api('admin/force', { body: {} }), 'Rynek zaktualizowany.')) { refreshMarket(); refreshCandles(); } });
    $('admin-event-run').addEventListener('click', async () => {
      const idx = $('admin-event-select').value; if (idx === '') return;
      if (await act(() => api('admin/event', { body: { index: idx } }), 'Event uruchomiony.')) { refreshMarket(); refreshCandles(); }
    });
  }

  // ---------------------------------------------------------------- start
  function tickClock() { $('clock').textContent = fmtTime(Date.now()); }

  function init() {
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
