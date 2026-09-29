// ── GitHub Releases 更新检测（设置页与启动页共用；纯逻辑，无界面文案） ──
// 结果缓存 localStorage；非强制检查 1 小时节流（GitHub 匿名 API 限额 60 次/小时/IP）。
(function () {
    const API_URL = 'https://api.github.com/repos/xiaoze171/cuktech-ble-server/releases/latest';
    const RELEASES_PAGE = 'https://github.com/xiaoze171/cuktech-ble-server/releases';
    const CACHE_KEY = 'cuktech-latest-release';
    const CHECKED_AT_KEY = 'cuktech-latest-release-at';
    const THROTTLE_MS = 3600 * 1000;

    function androidVersion() {
        try {
            if (window.AndroidSettings && typeof window.AndroidSettings.getVersionName === 'function') {
                return window.AndroidSettings.getVersionName() || '';
            }
        } catch (e) { /* 桥不可用（浏览器环境） */ }
        return '';
    }

    async function currentVersion() {
        const av = androidVersion();
        if (av) return av;
        try {
            const res = await fetch('/api/health', { cache: 'no-store' });
            const data = await res.json();
            return data.version || '';
        } catch (e) { return ''; }
    }

    // 解析 'v1.2.3-android.14' / 'V1.1.1' 风格版本；解析失败返回 null
    function parseVersion(v) {
        const m = String(v || '').trim().replace(/^v/i, '').match(/^(\d+(?:\.\d+)*)(?:-android\.(\d+))?/i);
        if (!m) return null;
        return { base: m[1].split('.').map(Number), android: m[2] !== undefined ? Number(m[2]) : null };
    }

    // a > b → 1；a < b → -1；相等或无法比较 → 0
    function compareVersions(a, b) {
        const pa = parseVersion(a), pb = parseVersion(b);
        if (!pa || !pb) return 0;
        const len = Math.max(pa.base.length, pb.base.length);
        for (let i = 0; i < len; i++) {
            const d = (pa.base[i] || 0) - (pb.base[i] || 0);
            if (d) return d > 0 ? 1 : -1;
        }
        // 主版本相同：带 android 后缀的比不带的新（后者是该基础版的原始发布）
        if (pa.android !== pb.android) {
            if (pa.android === null) return -1;
            if (pb.android === null) return 1;
            return pa.android > pb.android ? 1 : -1;
        }
        return 0;
    }

    async function fetchLatest() {
        const res = await fetch(API_URL, {
            cache: 'no-store',
            headers: { 'Accept': 'application/vnd.github+json' },
        });
        if (res.status === 404) throw Object.assign(new Error('no releases'), { code: 'NO_RELEASES' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const rel = await res.json();
        // 版本号优先取 tag（v1.1.1-android.14）；tag 不是版本号时退回 release 名称（如 V1.1.1）
        const version = (parseVersion(rel.tag_name) && rel.tag_name)
            || (parseVersion(rel.name) && rel.name)
            || '';
        return {
            tag: rel.tag_name || '',
            name: rel.name || '',
            version: version ? String(version).replace(/^v/i, '') : '',
            body: rel.body || '',
            url: rel.html_url || RELEASES_PAGE,
            publishedAt: rel.published_at || '',
        };
    }

    function cached() {
        try { return JSON.parse(localStorage.getItem(CACHE_KEY) || 'null'); } catch (e) { return null; }
    }

    // force=true 跳过节流（设置页手动检查）；否则 1 小时内直接返回缓存
    async function check(opts) {
        const force = opts && opts.force;
        if (!force) {
            const last = Number(localStorage.getItem(CHECKED_AT_KEY) || 0);
            if (Date.now() - last < THROTTLE_MS) {
                const c = cached();
                if (c) return c;
            }
        }
        const [latest, current] = await Promise.all([fetchLatest(), currentVersion()]);
        const result = Object.assign({}, latest, {
            current: current,
            isNewer: latest.version ? compareVersions(latest.version, current) > 0 : false,
        });
        localStorage.setItem(CACHE_KEY, JSON.stringify(result));
        localStorage.setItem(CHECKED_AT_KEY, String(Date.now()));
        return result;
    }

    window.CuktechUpdateCheck = { check, cached, compareVersions, parseVersion, RELEASES_PAGE };
})();
