#!/usr/bin/env node
/**
 * Unit tests for web/static/update-check.js — release check + cache freshness.
 *
 * Guards the "已是最新版却仍提示下载" bug: the cached release result must be
 * discarded once the installed version changes, and any download trigger must
 * re-verify against the *current* version instead of trusting stale state.
 *
 * The module is an IIFE assigned to window.CuktechUpdateCheck and resolves
 * window/fetch/localStorage lazily at call time, so it is loaded once while
 * each case only swaps the global stubs it reads through.
 *
 * Usage: node tests/js/update_check_test.js
 */
'use strict';

global.window = global;

let stub = null;
global.localStorage = {
    getItem: (k) => (stub && k in stub.store ? stub.store[k] : null),
    setItem: (k, v) => { if (stub) stub.store[k] = String(v); },
    removeItem: (k) => { if (stub) delete stub.store[k]; },
};
global.AndroidSettings = { getVersionName: () => (stub ? stub.version : '') };
global.fetch = (url) => {
    if (stub) stub.apiCalls++;
    const release = stub ? stub.release : {};
    const body = String(url).indexOf('api.github.com') >= 0 ? release : { version: stub ? stub.version : '' };
    return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
};

require('../../web/static/update-check.js');
const UC = global.CuktechUpdateCheck;

function setup(opts) {
    stub = {
        version: (opts && opts.version) || '1.1.4',
        store: Object.assign({}, (opts && opts.store) || {}),
        apiCalls: 0,
        release: Object.assign(
            { tag_name: 'V1.1.4', name: 'V1.1.4', body: '', html_url: '' },
            (opts && opts.release) || {},
        ),
    };
    return stub;
}

let failed = 0;
let passed = 0;
function eq(actual, expected, label) {
    if (JSON.stringify(actual) === JSON.stringify(expected)) {
        passed++;
        console.log(`  ok   ${label}`);
    } else {
        failed++;
        console.log(`  FAIL ${label}: got ${JSON.stringify(actual)} want ${JSON.stringify(expected)}`);
    }
}

async function main() {
    console.log('\n-- compareVersions 基础 --');
    setup({});
    eq(UC.compareVersions('1.1.4', '1.1.3'), 1, '1.1.4 > 1.1.3');
    eq(UC.compareVersions('1.1.4', '1.1.4'), 0, '1.1.4 == 1.1.4');
    eq(UC.compareVersions('1.1.3', '1.1.4'), -1, '1.1.3 < 1.1.4');

    console.log('\n-- 缓存陈旧：current 与当前版本不一致时必须作废 --');
    const stale = setup({
        version: '1.1.4',
        store: {
            'cuktech-latest-release': JSON.stringify({ version: '1.1.4', current: '1.1.3', isNewer: true }),
            'cuktech-latest-release-at': String(Date.now()),
        },
    });
    eq(await UC.freshCached(), null, '升级后旧缓存（current=1.1.3）返回 null');
    const r1 = await UC.check();
    eq(r1.isNewer, false, '节流窗口内陈旧缓存被跳过，重新请求后 isNewer=false');
    eq(stale.apiCalls > 0, true, '发生了一次真实 API 请求');

    console.log('\n-- 缓存有效：current 与当前版本一致时沿用缓存 --');
    const fresh = setup({
        version: '1.1.3',
        store: {
            'cuktech-latest-release': JSON.stringify({ version: '1.1.4', current: '1.1.3', isNewer: true }),
            'cuktech-latest-release-at': String(Date.now()),
        },
    });
    eq((await UC.freshCached()).version, '1.1.4', '未升级时缓存可用');
    eq(fresh.apiCalls, 0, '节流窗口内不发起 API 请求');

    console.log('\n-- stillNewer：下载前复核 --');
    setup({ version: '1.1.4' });
    eq(await UC.stillNewer({ version: '1.1.4' }), false, '与当前同版本 → false（不下载）');
    setup({ version: '1.1.3' });
    eq(await UC.stillNewer({ version: '1.1.4' }), true, '低于当前版本 → true（可下载）');
    eq(await UC.stillNewer(null), false, '空结果 → false');

    console.log(`\n${failed === 0 ? 'PASS' : 'FAIL'}: ${passed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
