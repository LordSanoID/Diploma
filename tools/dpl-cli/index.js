#!/usr/bin/env node
// dpl - alat Termux untuk project Diploma (tanpa buka website GitHub). Butuh: nodejs, git, unzip.
const fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process'), rl = require('readline');
const CFG = path.join(os.homedir(), '.dpl.json');
const cfg = () => { try { return JSON.parse(fs.readFileSync(CFG, 'utf8')); } catch (e) { return {}; } };
const sh = (c, o = {}) => cp.spawnSync(c, { shell: true, stdio: 'inherit', ...o }).status === 0;
const out = (c, o = {}) => cp.spawnSync(c, { shell: true, encoding: 'utf8', ...o }).stdout.trim();
const ask = q => new Promise(r => { const i = rl.createInterface({ input: process.stdin, output: process.stdout }); i.question(q, a => { i.close(); r(a.trim()); }); });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const need = () => { const c = cfg(); if (!c.token) { console.log('Belum login. Jalankan: dpl login'); process.exit(1); } return c; };
async function api(p, o = {}) {
  const c = need();
  return fetch(p.startsWith('http') ? p : 'https://api.github.com' + p, { ...o, headers: { Authorization: 'Bearer ' + c.token, Accept: 'application/vnd.github+json', 'User-Agent': 'dpl-cli', ...(o.headers || {}) } });
}
const repo = () => { const c = need(); return `${c.user}/${c.repo}`; };
async function latestRun(sha) {
  const r = await api(`/repos/${repo()}/actions/runs?per_page=5${sha ? '&head_sha=' + sha : ''}`); const j = await r.json();
  return (j.workflow_runs || [])[0];
}
const cmds = {
  async login() {
    const user = await ask('Username GitHub: '), repoName = (await ask('Nama repo [Diploma]: ')) || 'Diploma';
    const token = await ask('Token (ghp_...): ');
    const dir = (await ask('Folder project [~/storage/downloads/Diploma]: ')) || path.join(os.homedir(), 'storage/downloads/Diploma');
    fs.writeFileSync(CFG, JSON.stringify({ user, repo: repoName, token, dir }, null, 2), { mode: 0o600 }); console.log('Tersimpan di ' + CFG);
  },
  async extract(zip) {
    const c = need(); const dl = path.join(os.homedir(), 'storage/downloads');
    if (!zip) { const z = fs.readdirSync(dl).filter(f => /^Diploma.*\.zip$/i.test(f)).map(f => ({ f, t: fs.statSync(path.join(dl, f)).mtimeMs })).sort((a, b) => b.t - a.t)[0]; if (!z) return console.log('Tidak ada Diploma*.zip di Download'); zip = path.join(dl, z.f); }
    const tmp = path.join(os.tmpdir(), 'dpl-x'); sh(`rm -rf ${tmp} && mkdir -p ${tmp} && unzip -oq "${zip}" -d ${tmp}`);
    const top = fs.readdirSync(tmp); const src = top.length === 1 && fs.statSync(path.join(tmp, top[0])).isDirectory() ? path.join(tmp, top[0]) : tmp;
    sh(`mkdir -p "${c.dir}" && cp -r "${src}/." "${c.dir}/"`); console.log('Diekstrak ke ' + c.dir);
  },
  async push(msg) {
    const c = need(); const o = { cwd: c.dir };
    if (!fs.existsSync(path.join(c.dir, '.git'))) { sh('git init -q && git branch -M main', o); }
    sh('git config user.name "dpl" && git config user.email "dpl@local" && git add -A', o);
    sh(`git commit -qm "${msg || 'update'}" || true`, o);
    const ok = sh(`git push https://${c.user}:${c.token}@github.com/${c.user}/${c.repo}.git HEAD:main --force`, o);
    const sha = out('git rev-parse HEAD', o); console.log(ok ? 'Push OK ' + sha.slice(0, 7) : 'Push GAGAL'); return ok ? sha : null;
  },
  async status() { const r = await latestRun(); if (!r) return console.log('Belum ada build'); console.log(`#${r.run_number} ${r.status} ${r.conclusion || ''} (${r.head_sha.slice(0, 7)})`); },
  async wait(sha) {
    process.stdout.write('Menunggu build'); let r;
    for (let i = 0; i < 90; i++) { r = await latestRun(sha); if (r && r.status === 'completed') break; process.stdout.write('.'); await sleep(8000); }
    console.log(r ? `\nSelesai: ${r.conclusion}` : '\nTidak ketemu build'); return r;
  },
  async errors() {
    const r = await latestRun(); if (!r) return console.log('Belum ada build');
    const jobs = (await (await api(`/repos/${repo()}/actions/runs/${r.id}/jobs`)).json()).jobs || [];
    for (const j of jobs.filter(x => x.conclusion === 'failure')) {
      const t = await (await api(`/repos/${repo()}/actions/jobs/${j.id}/logs`)).text();
      const lines = t.split('\n').map(l => l.replace(/^\S+Z\s/, '').replace(/file:\/\/\/home\/runner\/work\/\w+\/\w+\//, ''))
        .filter(l => /^(e: |error: |.*Execution failed|.*What went wrong|> .*not found|.*Unresolved|.*cannot find)/i.test(l.trim()));
      console.log(lines.length ? lines.slice(0, 40).join('\n') : t.split('\n').slice(-30).join('\n'));
    }
    if (!jobs.some(x => x.conclusion === 'failure')) console.log('Tidak ada error (' + r.conclusion + ')');
  },
  async apk() {
    const r = await latestRun(); if (!r || r.conclusion !== 'success') return console.log('Build terakhir belum sukses. Jalankan: dpl errors');
    const arts = (await (await api(`/repos/${repo()}/actions/runs/${r.id}/artifacts`)).json()).artifacts || []; if (!arts[0]) return console.log('Artifact kosong');
    const res = await api(`/repos/${repo()}/actions/artifacts/${arts[0].id}/zip`); const dir = path.join(os.homedir(), 'storage/downloads/dpl');
    fs.mkdirSync(dir, { recursive: true }); const z = path.join(dir, 'app.zip'); fs.writeFileSync(z, Buffer.from(await res.arrayBuffer()));
    sh(`unzip -oq ${z} -d ${dir} && rm ${z}`); const apk = path.join(dir, 'app-debug.apk'); console.log('APK: ' + apk); sh(`termux-open "${apk}" || true`); return apk;
  },
  async release(tag, notes) {
    const apk = path.join(os.homedir(), 'storage/downloads/dpl/app-debug.apk'); if (!fs.existsSync(apk)) await cmds.apk();
    tag = tag || 'v' + (await ask('Tag rilis (contoh v5.4.1): '));
    const rel = await (await api(`/repos/${repo()}/releases`, { method: 'POST', body: JSON.stringify({ tag_name: tag, name: 'Diploma ' + tag, body: notes || 'Rilis ' + tag, target_commitish: 'main' }) })).json();
    if (!rel.upload_url) return console.log('Gagal buat rilis: ' + JSON.stringify(rel));
    const up = rel.upload_url.replace('{?name,label}', '?name=Diploma-' + tag + '.apk');
    const r = await api(up, { method: 'POST', headers: { 'Content-Type': 'application/vnd.android.package-archive' }, body: fs.readFileSync(apk) });
    console.log(r.ok ? 'Rilis terbit: ' + rel.html_url : 'Upload APK gagal: ' + r.status);
  },
  async all(msg) {
    await cmds.extract(); const sha = await cmds.push(msg); if (!sha) return;
    const r = await cmds.wait(sha); if (r && r.conclusion === 'success') await cmds.apk(); else await cmds.errors();
  },
  help() { console.log(`dpl <perintah>
  login            simpan username, repo, token, folder project
  extract [zip]    ekstrak zip terbaru dari Download ke folder project
  push [pesan]     git add + commit + push (tanpa buka GitHub)
  status           status build terakhir
  wait             tunggu build selesai
  errors           tampilkan error build terakhir
  apk              unduh APK dari build sukses lalu buka installer
  release [tag]    buat rilis GitHub + upload APK
  all [pesan]      extract + push + tunggu + (apk atau errors)`); }
};
const [c, ...a] = process.argv.slice(2);
(cmds[c] || cmds.help)(...a).catch?.(e => console.error(e.message));
