// TimeBus CurseForge 发布脚本（Puppeteer + 真实 Chrome）
//
// 用法：
//   node gradle/scripts/publish-curseforge.js [版本号] [jar路径]
//   （省略参数时自动读取 gradle.properties 的 mod_version 和 build/libs 下的 jar）
//
// 背景：
//   CurseForge 上传 API 前有 Cloudflare 人机验证（"Just a moment..."），纯 curl /
//   HTTP 客户端的 TLS 指纹无法通过（v1.0.7 实测 403）。本项目用 Puppeteer 驱动
//   真实 Chrome 打开站点，让挑战自动通过后，再在页面上下文内用同源 fetch 上传，
//   复用了浏览器会话与 cf_clearance cookie，稳定拿到 HTTP 200。
//
// 依赖：
//   npm install puppeteer-core（需要本机有 Chrome/Edge；也可用 CHROME_PATH 指定）
//   首次使用：cd 到任意临时目录执行 npm init -y && npm install puppeteer-core，
//   然后 NODE_PATH 指向该目录，或在本目录执行 npm install
//
// 凭证（按优先级）：
//   1. 环境变量 CURSEFORGE_TOKEN
//   2. %USERPROFILE%\.gradle\gradle.properties 的 curseforge_token
//
// 版本 ID（6756=1.12.2, 7498=Forge, 9638=Client, 9639=Server）与项目 ID（1638678）
// 已固化在脚本内。

const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const projectId = 1638678;
const chromePath = process.env.CHROME_PATH
    || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const version = process.argv[2] || (() => {
    const props = fs.readFileSync(path.join(root, 'gradle.properties'), 'utf8');
    const m = props.split(/\r?\n/).find(l => /^mod_version\s*=/.test(l));
    return m ? m.split('=', 2)[1].trim() : null;
})();
if (!version) {
    console.error('无法确定版本号，请用参数指定（node publish-curseforge.js 1.0.7）');
    process.exit(1);
}

const jar = process.argv[3] || path.join(root, 'build', 'libs', `timebus-${version}.jar`);
if (!fs.existsSync(jar)) {
    console.error('找不到 jar：' + jar + '（先执行 gradlew build）');
    process.exit(1);
}

let token = process.env.CURSEFORGE_TOKEN;
if (!token) {
    const userProps = path.join(process.env.USERPROFILE || '', '.gradle', 'gradle.properties');
    if (fs.existsSync(userProps)) {
        const line = fs.readFileSync(userProps, 'utf8').split(/\r?\n/)
            .find(l => /^curseforge_token\s*=/.test(l));
        if (line) token = line.split('=', 2)[1].trim();
    }
}
if (!token) {
    console.error('缺少 CurseForge token（环境变量 CURSEFORGE_TOKEN 或用户级 gradle.properties 的 curseforge_token）');
    process.exit(1);
}

// changelog：取 CHANGELOG.md 中 "## vX.Y.Z" 段落
const changelog = (() => {
    const cl = fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8');
    const lines = cl.split(/\r?\n/);
    let start = -1;
    let end = lines.length;
    for (let i = 0; i < lines.length; i++) {
        if (new RegExp('^## v' + version.replace('.', '\\.') + '\\s*$').test(lines[i])) {
            start = i;
        } else if (start >= 0 && /^## /.test(lines[i])) {
            end = i;
            break;
        }
    }
    return start >= 0
        ? lines.slice(start, end).join('\n').trim()
        : 'See GitHub releases: https://github.com/zhenzi233/TimeBus/releases';
})();

const meta = {
    changelog,
    changelogType: 'markdown',
    displayName: `Time Bus v${version}`,
    gameVersions: [6756, 7498, 9638, 9639],
    releaseType: 'beta',
    relations: { projects: [{ slug: 'ae2-extended-life', type: 'requiredDependency' }] },
};
const metaJson = JSON.stringify(meta);
const jarB64 = fs.readFileSync(jar).toString('base64');
const jarName = path.basename(jar);

console.log(`上传 ${jar} 到 CurseForge 项目 ${projectId} (v${version}) ...`);

(async () => {
    const browser = await puppeteer.launch({
        executablePath: chromePath,
        headless: false,
        defaultViewport: null,
        args: ['--disable-blink-features=AutomationControlled', '--start-maximized'],
    });
    try {
        const page = await browser.newPage();
        await page.goto(
            `https://www.curseforge.com/minecraft/mc-mods/${projectId}/files`,
            { waitUntil: 'domcontentloaded', timeout: 60000 }
        ).catch(() => {});

        console.log('等待 Cloudflare 挑战通过...');
        for (let i = 0; i < 90; i++) {
            const t = await page.title().catch(() => '');
            if (!/Just a moment|请稍候/i.test(t)) break;
            if (i % 5 === 0) console.log(`  挑战中... ${i}s`);
            await new Promise(r => setTimeout(r, 1000));
        }
        console.log('页面标题:', await page.title().catch(() => ''));

        const result = await page.evaluate(async ({ metaJson, jarB64, token, jarName }) => {
            function b64ToBytes(b64) {
                const bin = atob(b64);
                const bytes = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                return bytes;
            }
            const fd = new FormData();
            fd.append('metadata', metaJson);
            fd.append('file', new Blob([b64ToBytes(jarB64)], { type: 'application/java-archive' }), jarName);
            const resp = await fetch('/api/projects/1638678/upload-file', {
                method: 'POST',
                headers: { 'X-Api-Token': token },
                body: fd,
            });
            return { status: resp.status, body: await resp.text() };
        }, { metaJson, jarB64, token, jarName });

        console.log('HTTP', result.status);
        if (result.status === 200) {
            const json = JSON.parse(result.body);
            console.log('上传成功！文件 ID：' + json.id);
        } else {
            console.log('失败响应：' + result.body.slice(0, 500));
            process.exitCode = 1;
        }
    } finally {
        await browser.close();
    }
})().catch(e => {
    console.error('失败:', e);
    process.exit(1);
});
