// Behavior tests for the phone chart and recovery notices using real page code.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(storedRange = null) {
    let now = 1700000000000;
    const timers = [], sources = [], charts = [], elements = new Map(), listeners = {};
    function element(id = '') {
        if (elements.has(id)) return elements.get(id);
        const el = {
            style: {}, dataset: {}, innerHTML: '', textContent: '',
            classList: { add() {}, remove() {}, toggle() {} },
            addEventListener() {}, appendChild() {},
            setAttribute() {}, getAttribute() { return null; }, removeAttribute() {},
            querySelector: () => element('child'),
            closest: () => element('parent'),
            getBoundingClientRect: () => ({ top: 0, height: 100 }),
        };
        elements.set(id, el);
        return el;
    }
    const ctx = vm.createContext({
        console, URL, Promise,
        Date: class extends Date { static now() { return now; } },
        location: { origin: 'http://charger.test' },
        localStorage: { getItem: () => storedRange, setItem() {} },
        I18N: { t: key => key, onChange() {} },
        document: {
            body: element('body'), documentElement: element('html'),
            getElementById: element, querySelector: element, querySelectorAll: () => [],
            createElement: () => element('created'), addEventListener() {},
        },
        addEventListener(type, fn) { (listeners[type] ||= []).push(fn); },
        setInterval(fn, ms) { const timer = { fn, ms }; timers.push(timer); return timer; },
        clearInterval(timer) { const i = timers.indexOf(timer); if (i >= 0) timers.splice(i, 1); },
        setTimeout() {}, clearTimeout() {},
        fetch: async () => ({ ok: true, json: async () => ({}) }),
        EventSource: function () { this.close = () => { this.closed = true; }; sources.push(this); },
        Chart: function (canvas, config) {
            Object.assign(this, config, { canvas, scales: {}, update() {}, destroy() {} });
            charts.push(this);
        },
    });
    ctx.window = ctx;
    vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../web/static/phone.js'), 'utf8'), ctx);
    const run = code => vm.runInContext(code, ctx);
    return { ctx, run, timers, sources, charts, elements, listeners,
        advance(ms) { now += ms; },
        event(data) { sources.at(-1).onmessage({ data: JSON.stringify(data) }); },
    };
}

test('chart default is live (5 min per-second); saved ranges are honored', () => {
    assert.equal(page().run('phoneChartRange'), 'live');
    assert.equal(page('live').run('phoneChartRange'), 'live');
    assert.equal(page('120').run('phoneChartRange'), '120');
});

test('live range renders the local per-second buffer padded to 5 minutes', () => {
    const p = page();
    p.event({ type: 'init', connected: true, authenticated: true, ports: { 1: { power: 25 } } });
    for (let i = 0; i < 10; i++) { p.advance(1000); p.run('phonePushData()'); }
    p.run('renderCharts()');
    const chart = p.charts[0];
    assert.equal(chart.data.labels.length, 300);
    assert.equal(chart.data.datasets[0].data.length, 300);
    assert.equal(chart.data.datasets[0].data.filter(w => w === 25).length, 10);
    assert.match(chart.data.labels.at(-1), /^\d{2}:\d{2}:\d{2}$/);
    assert.equal(chart.data.labels[0], '');
});

test('returning after a long background pause discards expired live samples', () => {
    const p = page();
    p.event({ type: 'init', connected: true, authenticated: true, ports: { 1: { power: 90 } } });
    p.run('phonePushData()');
    p.advance(601000);
    p.event({ type: 'port_update', port_id: 1, data: { power: 20 } });
    p.run('phonePushData(); renderCharts()');
    assert.equal(p.run('phoneChartData.length'), 1);
    assert.ok(!p.charts[0].data.datasets[0].data.includes(90));
});

for (const [range, interval] of [['30', 20], ['60', 20], ['120', 30], ['1440', 300]]) {
    test(`history range ${range} feeds server data to all chart series`, async () => {
        const p = page();
            const requests = [];
        p.ctx.fetch = async url => {
            requests.push(url);
            return { ok: true, json: async () => ({ ok: true, labels: ['a', 'b'],
                datasets: { power: [1, 2, 3, 4].map(w => ({ data: [w, w + 10] })) } }) };
        };
        p.run(`setChartRange('${range}')`);
        await new Promise(resolve => setImmediate(resolve));
        assert.ok(requests[0].endsWith(`hours=${Number(range) / 60}&interval=${interval}`));
        assert.deepEqual(Array.from(p.charts[0].data.labels), ['a', 'b']);
        for (let i = 0; i < 4; i++) assert.deepEqual(Array.from(p.charts[0].data.datasets[i].data), [i + 1, i + 11]);
    });
}

test('SSE notice clear removes the persistent warning while still disconnected', () => {
    const p = page();
    p.event({ type: 'status', connected: false, authenticated: false, notice: 'ble_stuck_need_radio_reset' });
    assert.equal(p.elements.get('toast').style.opacity, '1');
    p.event({ type: 'status', connected: false, authenticated: false, notice: '' });
    assert.equal(p.elements.get('toast').style.opacity, '0');
});

test('an older history response cannot replace the newly selected range', async () => {
    const p = page();
    const pending = [];
    p.ctx.fetch = () => new Promise(resolve => pending.push(resolve));
    p.run("setChartRange('30'); setChartRange('1440')");
    function response(label, value) {
        return { ok: true, json: async () => ({ ok: true, labels: [label],
            datasets: { power: [1, 2, 3, 4].map(() => ({ data: [value] })) } }) };
    }
    pending[1](response('24 hours', 24));
    await new Promise(resolve => setImmediate(resolve));
    pending[0](response('30 minutes', 30));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(Array.from(p.charts[0].data.labels), ['24 hours']);
    assert.equal(p.charts[0].data.datasets[0].data[0], 24);
});

test('page hide and restore keep exactly one active SSE connection', () => {
    const p = page();
    for (const fn of [...(p.listeners.pageshow || [])]) fn({ persisted: false });
    assert.equal(p.sources.filter(s => !s.closed).length, 1);
    for (let round = 0; round < 3; round++) {
        for (const fn of [...(p.listeners.pagehide || [])]) fn({ persisted: true });
        assert.equal(p.sources.filter(s => !s.closed).length, 0);
        for (const fn of [...(p.listeners.pageshow || [])]) fn({ persisted: true });
        assert.equal(p.sources.filter(s => !s.closed).length, 1);
    }
});
