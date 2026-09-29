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
        const apkAsset = (Array.isArray(rel.assets) ? rel.assets : [])
            .find(a => /\.apk$/i.test(a.name || '')) || null;
        return {
            tag: rel.tag_name || '',
            name: rel.name || '',
            version: version ? String(version).replace(/^v/i, '') : '',
            body: rel.body || '',
            url: rel.html_url || RELEASES_PAGE,
            publishedAt: rel.published_at || '',
            apkUrl: apkAsset ? (apkAsset.browser_download_url || '') : '',
        };
    }

    // 受限 Markdown → 安全 HTML（先整体转义防注入，只放行标题/加粗/行内代码/链接/列表/分隔线）
    function renderNotes(text) {
        const esc = String(text || '')
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
        const inline = s => s
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/`([^`]+)`/g, '<code>$1</code>')
            .replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
            .replace(/(^|[\s(])((?:https?:\/\/)[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noopener noreferrer">$2</a>');
        const out = [];
        for (const raw of esc.split(/\r?\n/)) {
            const line = raw.trim();
            if (!line) continue;
            const head = line.match(/^#{1,4}\s+(.*)$/);
            if (head) { out.push('<div class="md-h">' + inline(head[1]) + '</div>'); continue; }
            const li = line.match(/^[-*]\s+(.*)$/);
            if (li) { out.push('<div class="md-li">' + inline(li[1]) + '</div>'); continue; }
            if (/^(-{3,}|\*{3,})$/.test(line)) { out.push('<div class="md-hr"></div>'); continue; }
            out.push('<div class="md-p">' + inline(line) + '</div>');
        }
        return out.join('');
    }

    function cached() {
        try { return JSON.parse(localStorage.getItem(CACHE_KEY) || 'null'); } catch (e) { return null; }
    }

    // 缓存仍对应当前安装版本才可用：应用升级/降级后旧结果作废，
    // 否则会按旧缓存提示「发现新版本」，下载的却是已安装的同版本包。
    async function freshCached() {
        const c = cached();
        if (!c) return null;
        const cur = await currentVersion();
        if (cur && c.current && cur !== c.current) return null;
        return c;
    }

    // 结果相对当前安装版本是否仍是新版本（下载/安装前复核，挡住陈旧状态触发的下载）
    async function stillNewer(result) {
        if (!result || !result.version) return false;
        return compareVersions(result.version, await currentVersion()) > 0;
    }

    // force=true 跳过节流（设置页手动检查）；否则 1 小时内直接返回缓存
    async function check(opts) {
        const force = opts && opts.force;
        if (!force) {
            const last = Number(localStorage.getItem(CHECKED_AT_KEY) || 0);
            if (Date.now() - last < THROTTLE_MS) {
                const c = await freshCached();
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

    window.CuktechUpdateCheck = {
        check, cached, freshCached, stillNewer, compareVersions, parseVersion, renderNotes, RELEASES_PAGE,
    };
})();
