/* Wykres świecowy rysowany na <canvas>. Bez zewnętrznych bibliotek.
 * Dane: [[t(s), open, high, low, close], ...] (ceny w groszach). */
(function () {
  'use strict';

  const COLORS = {
    bg: '#111827', grid: '#1f2937', text: '#9ca3af', textStrong: '#e5e7eb',
    up: '#26a69a', down: '#ef5350', cross: '#6b7280', last: '#eab308',
    support: '#22c55e', resistance: '#ef4444'
  };
  const PAD = { right: 64, bottom: 22, top: 8, left: 6 };

  function niceStep(range, target) {
    const raw = range / Math.max(1, target);
    const pow = Math.pow(10, Math.floor(Math.log10(raw)));
    const n = raw / pow;
    const step = n < 1.5 ? 1 : n < 3 ? 2 : n < 7 ? 5 : 10;
    return step * pow;
  }

  class CandleChart {
    constructor(canvas, opts) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.fmtPrice = (opts && opts.fmtPrice) || (c => (c / 100).toFixed(2));
      this.tf = 300;
      this.data = [];
      this.lines = [];
      this.visible = 80;      // ile świec widać
      this.offset = 0;        // przesunięcie w świecach od prawej krawędzi
      this.mouse = null;
      this.onPriceClick = null;
      this.drag = null;
      this._bind();
      this._ro = new ResizeObserver(() => this.resize());
      this._ro.observe(canvas);
      this.resize();
    }

    setData(candles, tf) {
      const prevLen = this.data.length;
      this.data = candles || [];
      if (tf) this.tf = tf;
      if (this.offset > 0 && this.data.length > prevLen) this.offset += this.data.length - prevLen;
      this.draw();
    }

    setLines(lines) { this.lines = lines || []; this.draw(); }

    resetView() { this.visible = 80; this.offset = 0; this.draw(); }

    resize() {
      const dpr = window.devicePixelRatio || 1;
      const r = this.canvas.getBoundingClientRect();
      this.w = Math.max(100, Math.floor(r.width));
      this.h = Math.max(100, Math.floor(r.height));
      this.canvas.width = Math.floor(this.w * dpr);
      this.canvas.height = Math.floor(this.h * dpr);
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      this.draw();
    }

    _bind() {
      const c = this.canvas;
      c.addEventListener('mousemove', e => {
        const r = c.getBoundingClientRect();
        this.mouse = { x: e.clientX - r.left, y: e.clientY - r.top };
        if (this.drag) {
          const dx = e.clientX - this.drag.x;
          const cw = this._candleWidth();
          const shift = Math.round(dx / cw);
          this.offset = Math.max(0, Math.min(Math.max(0, this.data.length - 5), this.drag.offset + shift));
          this.drag.moved = this.drag.moved || Math.abs(dx) > 3;
        }
        this.draw();
      });
      c.addEventListener('mouseleave', () => { this.mouse = null; this.drag = null; this.draw(); });
      c.addEventListener('mousedown', e => { this.drag = { x: e.clientX, offset: this.offset, moved: false }; });
      window.addEventListener('mouseup', () => {
        if (this.drag && !this.drag.moved && this.mouse && this.onPriceClick) {
          const p = this._yToPrice(this.mouse.y);
          if (p !== null && p > 0) this.onPriceClick(Math.round(p));
        }
        this.drag = null;
      });
      c.addEventListener('wheel', e => {
        e.preventDefault();
        const factor = e.deltaY > 0 ? 1.15 : 0.87;
        this.visible = Math.max(15, Math.min(600, Math.round(this.visible * factor)));
        this.draw();
      }, { passive: false });
      c.addEventListener('dblclick', () => this.resetView());
    }

    _slice() {
      const n = this.data.length;
      const end = Math.max(0, n - this.offset);
      const start = Math.max(0, end - this.visible);
      return { start, end, items: this.data.slice(start, end) };
    }

    _candleWidth() { return (this.w - PAD.left - PAD.right) / this.visible; }

    _range(items) {
      let lo = Infinity, hi = -Infinity;
      for (const k of items) { lo = Math.min(lo, k[3]); hi = Math.max(hi, k[2]); }
      for (const l of this.lines) { lo = Math.min(lo, l.price); hi = Math.max(hi, l.price); }
      if (!isFinite(lo)) { lo = 0; hi = 100; }
      if (hi - lo < 1) { hi += 1; lo -= 1; }
      const pad = (hi - lo) * 0.08;
      return { lo: Math.max(0, lo - pad), hi: hi + pad };
    }

    _yToPrice(y) {
      if (!this._view) return null;
      const { lo, hi, top, bottom } = this._view;
      if (y < top || y > bottom) return null;
      return hi - ((y - top) / (bottom - top)) * (hi - lo);
    }

    draw() {
      const ctx = this.ctx, w = this.w, h = this.h;
      ctx.clearRect(0, 0, w, h);
      ctx.fillStyle = COLORS.bg;
      ctx.fillRect(0, 0, w, h);
      const sl = this._slice();
      if (sl.items.length === 0) {
        ctx.fillStyle = COLORS.text;
        ctx.font = '14px system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('Brak danych wykresu', w / 2, h / 2);
        this._view = null;
        return;
      }
      const top = PAD.top, bottom = h - PAD.bottom, left = PAD.left, right = w - PAD.right;
      const { lo, hi } = this._range(sl.items);
      this._view = { lo, hi, top, bottom };
      const yOf = p => top + (hi - p) / (hi - lo) * (bottom - top);
      const cw = (right - left) / this.visible;

      // siatka + oś cen
      ctx.font = '11px system-ui, sans-serif';
      ctx.textBaseline = 'middle';
      const step = niceStep(hi - lo, 6);
      ctx.strokeStyle = COLORS.grid;
      ctx.lineWidth = 1;
      ctx.fillStyle = COLORS.text;
      ctx.textAlign = 'left';
      for (let p = Math.ceil(lo / step) * step; p <= hi; p += step) {
        const y = Math.round(yOf(p)) + 0.5;
        ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke();
        ctx.fillText(this.fmtPrice(Math.round(p)), right + 6, y);
      }

      // oś czasu
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';
      const every = Math.max(1, Math.round(90 / cw));
      for (let i = sl.items.length - 1; i >= 0; i -= every) {
        const k = sl.items[i];
        const px = right - (sl.end - (sl.start + i)) * cw + cw / 2;
        const d = new Date(k[0] * 1000);
        const label = this.tf >= 3600
          ? d.toLocaleDateString('pl-PL', { day: '2-digit', month: '2-digit' }) + ' ' + d.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' })
          : d.toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
        ctx.fillStyle = COLORS.text;
        if (px > left + 24 && px < right - 24) ctx.fillText(label, px, bottom + 5);
      }

      // świece
      const bodyW = Math.max(1, Math.floor(cw * 0.7));
      for (let i = 0; i < sl.items.length; i++) {
        const k = sl.items[i];
        const cx = right - (sl.end - (sl.start + i)) * cw + cw / 2;
        const up = k[4] >= k[1];
        ctx.strokeStyle = ctx.fillStyle = up ? COLORS.up : COLORS.down;
        ctx.lineWidth = 1;
        const xw = Math.round(cx) + 0.5;
        ctx.beginPath(); ctx.moveTo(xw, yOf(k[2])); ctx.lineTo(xw, yOf(k[3])); ctx.stroke();
        const yO = yOf(k[1]), yC = yOf(k[4]);
        const bh = Math.max(1, Math.abs(yC - yO));
        ctx.fillRect(Math.round(cx - bodyW / 2), Math.min(yO, yC), bodyW, bh);
      }

      // linie wsparcia / oporu
      for (const l of this.lines) {
        if (l.price < lo || l.price > hi) continue;
        const y = Math.round(yOf(l.price)) + 0.5;
        const col = l.type === 'support' ? COLORS.support : COLORS.resistance;
        ctx.strokeStyle = col; ctx.setLineDash([6, 4]); ctx.lineWidth = 1.5;
        ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = col; ctx.fillRect(right, y - 8, PAD.right, 16);
        ctx.fillStyle = '#0b0e11'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
        ctx.fillText(this.fmtPrice(l.price), right + 6, y);
      }

      // ostatnia cena
      const lastK = this.data[this.data.length - 1];
      if (lastK && lastK[4] >= lo && lastK[4] <= hi) {
        const y = Math.round(yOf(lastK[4])) + 0.5;
        ctx.strokeStyle = COLORS.last; ctx.setLineDash([2, 3]); ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(right, y); ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = COLORS.last; ctx.fillRect(right, y - 8, PAD.right, 16);
        ctx.fillStyle = '#0b0e11'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
        ctx.font = 'bold 11px system-ui, sans-serif';
        ctx.fillText(this.fmtPrice(lastK[4]), right + 6, y);
        ctx.font = '11px system-ui, sans-serif';
      }

      // celownik + odczyt OHLC
      let hovered = lastK;
      if (this.mouse && this.mouse.x >= left && this.mouse.x <= right && this.mouse.y >= top && this.mouse.y <= bottom) {
        const idx = sl.items.length - 1 - Math.floor((right - this.mouse.x) / cw);
        if (idx >= 0 && idx < sl.items.length) hovered = sl.items[idx];
        ctx.strokeStyle = COLORS.cross; ctx.setLineDash([3, 3]); ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(left, this.mouse.y + 0.5); ctx.lineTo(right, this.mouse.y + 0.5); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(this.mouse.x + 0.5, top); ctx.lineTo(this.mouse.x + 0.5, bottom); ctx.stroke();
        ctx.setLineDash([]);
        const price = this._yToPrice(this.mouse.y);
        if (price !== null) {
          ctx.fillStyle = '#374151'; ctx.fillRect(right, this.mouse.y - 8, PAD.right, 16);
          ctx.fillStyle = COLORS.textStrong; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
          ctx.fillText(this.fmtPrice(Math.round(price)), right + 6, this.mouse.y);
        }
      }
      if (hovered) {
        const d = new Date(hovered[0] * 1000);
        const txt = d.toLocaleString('pl-PL', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
          + '   O ' + this.fmtPrice(hovered[1]) + '   H ' + this.fmtPrice(hovered[2])
          + '   L ' + this.fmtPrice(hovered[3]) + '   C ' + this.fmtPrice(hovered[4]);
        ctx.fillStyle = COLORS.textStrong; ctx.textAlign = 'left'; ctx.textBaseline = 'top';
        ctx.fillText(txt, left + 6, top + 4);
      }
    }
  }

  window.CandleChart = CandleChart;
})();
