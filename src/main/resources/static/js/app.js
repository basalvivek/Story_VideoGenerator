(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const MAX_BYTES = 50 * 1024 * 1024;
  const svg = (id, cls) => { const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); if (cls) s.setAttribute('class', cls); const u = document.createElementNS('http://www.w3.org/2000/svg', 'use'); u.setAttribute('href', '#' + id); s.appendChild(u); return s; };
  const el = (tag, props = {}, ...kids) => { const e = document.createElement(tag); Object.assign(e, props); kids.forEach(k => e.append(k)); return e; };

  const state = {
    file: null, length: 5, style: 'CINEMATIC', lang: 'en-GB', voiceType: 'male',
    project: null, polling: null, languages: [], busy: false,
  };

  // ---------- helpers ----------
  const fmtTime = s => { s = Math.max(0, Math.floor(s || 0)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
  const fmtSize = b => b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB';
  function toast(msg, err) {
    const t = el('div', { className: 'toast' + (err ? ' err' : ''), textContent: msg });
    $('#toasts').append(t); setTimeout(() => t.remove(), err ? 6000 : 3200);
  }
  async function api(url, opts) {
    const r = await fetch(url, opts);
    if (!r.ok) {
      let m = 'Request failed (' + r.status + ')';
      try { const j = await r.json(); if (j.detail) m = j.detail; } catch (_) { /* ignore */ }
      throw new Error(m);
    }
    return r.status === 204 ? null : r.json();
  }
  function setStep(n) {
    $$('.step').forEach(s => { const i = +s.dataset.step; s.classList.toggle('active', i === n); s.classList.toggle('done', i < n); });
  }

  // ---------- custom selects ----------
  const GB = '<svg viewBox="0 0 60 40"><rect width="60" height="40" fill="#012169"/><path d="M0 0l60 40M60 0L0 40" stroke="#fff" stroke-width="8"/><path d="M0 0l60 40M60 0L0 40" stroke="#c8102e" stroke-width="3"/><path d="M30 0v40M0 20h60" stroke="#fff" stroke-width="13"/><path d="M30 0v40M0 20h60" stroke="#c8102e" stroke-width="8"/></svg>';
  const US = '<svg viewBox="0 0 60 40"><rect width="60" height="40" fill="#fff"/><g fill="#b22234"><rect width="60" height="3.1"/><rect y="6.2" width="60" height="3.1"/><rect y="12.3" width="60" height="3.1"/><rect y="18.5" width="60" height="3.1"/><rect y="24.6" width="60" height="3.1"/><rect y="30.8" width="60" height="3.1"/><rect y="36.9" width="60" height="3.1"/></g><rect width="26" height="21.5" fill="#3c3b6e"/></svg>';
  function flag(code) {
    const f = el('span', { className: 'flag' });
    if (code === 'en-GB') f.innerHTML = GB; else if (code === 'en-US') f.innerHTML = US; else f.textContent = code.slice(0, 2).toUpperCase();
    return f;
  }
  function makeSelect(root, options, getValue, onChange) {
    const render = () => {
      root.replaceChildren();
      const cur = options().find(o => o.value === getValue()) || options()[0];
      const btn = el('button', { type: 'button', className: 'sel-btn' }, cur.icon(), el('span', { className: 'grow', textContent: cur.label }), svg('i-down', 'caret'));
      const list = el('div', { className: 'sel-list', hidden: true });
      options().forEach(o => {
        const b = el('button', { type: 'button', className: o.value === cur.value ? 'sel' : '' }, o.icon(), el('span', { textContent: o.label }));
        b.onclick = () => { onChange(o.value); list.hidden = true; render(); };
        list.append(b);
      });
      btn.onclick = e => { e.stopPropagation(); const open = list.hidden; $$('.sel-list').forEach(l => l.hidden = true); list.hidden = !open; };
      root.append(btn, list);
    };
    render();
    return render;
  }
  document.addEventListener('click', () => { $$('.sel-list').forEach(l => l.hidden = true); $('#moreMenu').hidden = true; $('#speedMenu').hidden = true; });

  let renderLang;
  const typeOptions = () => [
    { value: 'male', label: 'Male (Natural)', icon: () => svg('i-person-s', 'person') },
    { value: 'female', label: 'Female (Natural)', icon: () => svg('i-person-s', 'person') },
  ];
  makeSelect($('#typeSelect'), typeOptions, () => state.voiceType, v => { state.voiceType = v; });

  async function loadVoices() {
    let langs = [{ code: 'en-GB', label: 'English (UK)' }];
    try {
      const v = await api('/api/voices'); if (v.languages.length) langs = v.languages;
      if (!v.ttsAvailable) toast('No text-to-speech voice found on this machine. Narration will be skipped.', true);
    } catch (_) { /* keep default */ }
    state.languages = langs;
    if (!langs.some(l => l.code === state.lang)) state.lang = langs[0].code;
    renderLang = makeSelect($('#langSelect'), () => state.languages.map(l => ({ value: l.code, label: l.label, icon: () => flag(l.code) })), () => state.lang, v => { state.lang = v; });
  }

  // ---------- upload ----------
  const dz = $('#dropzone'), fileInput = $('#fileInput');
  $('#chooseBtn').onclick = e => { e.stopPropagation(); fileInput.click(); };
  dz.onclick = () => fileInput.click();
  dz.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); } };
  ['dragenter', 'dragover'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.add('drag'); }));
  ['dragleave', 'drop'].forEach(ev => dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.remove('drag'); }));
  dz.addEventListener('drop', e => { if (e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]); });
  fileInput.onchange = () => { if (fileInput.files[0]) setFile(fileInput.files[0]); fileInput.value = ''; };

  function setFile(f) {
    if (!/\.(pdf|docx?|txt)$/i.test(f.name)) return toast('Please choose a PDF, DOC, DOCX or TXT file.', true);
    if (f.size > MAX_BYTES) return toast('That file is larger than 50 MB.', true);
    if (f.size === 0) return toast('That file is empty.', true);
    state.file = f;
    const title = $('#title');
    if (!title.value.trim()) { title.value = f.name.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' ').trim().slice(0, 100); updateTitle(); }
    renderFile(); setStep(2); refreshGenerate();
  }
  function renderFile() {
    const slot = $('#fileSlot'); slot.replaceChildren();
    if (!state.file) return;
    const pdf = /\.pdf$/i.test(state.file.name);
    const x = el('button', { className: 'fx', type: 'button', title: 'Remove', ariaLabel: 'Remove file' }, svg('i-x'));
    x.onclick = () => { state.file = null; renderFile(); setStep(1); refreshGenerate(); };
    slot.append(el('div', { className: 'file-chip' }, svg(pdf ? 'i-pdf' : 'i-docx', 'ficon'),
      el('div', { className: 'finfo' }, el('div', { className: 'fname', textContent: state.file.name, title: state.file.name }), el('div', { className: 'fsize', textContent: fmtSize(state.file.size) })), x));
  }

  // ---------- form ----------
  const titleEl = $('#title');
  function updateTitle() { $('#titleCount').textContent = titleEl.value.length; refreshGenerate(); }
  titleEl.oninput = updateTitle;
  $$('#lengthPills .pill').forEach(p => p.onclick = () => { state.length = +p.dataset.v; $$('#lengthPills .pill').forEach(q => q.classList.toggle('active', q === p)); });
  $$('#styles .style').forEach(s => s.onclick = () => { state.style = s.dataset.v; $$('#styles .style').forEach(q => q.classList.toggle('active', q === s)); });
  function refreshGenerate() { $('#generateBtn').disabled = state.busy || !state.file || !titleEl.value.trim(); }

  $('#generateBtn').onclick = async () => {
    if (state.busy) return;
    const fd = new FormData();
    fd.append('file', state.file); fd.append('title', titleEl.value.trim());
    fd.append('lengthMinutes', state.length); fd.append('style', state.style);
    fd.append('narration', $('#optNarration').checked); fd.append('music', $('#optMusic').checked); fd.append('subtitles', $('#optSubs').checked);
    fd.append('voiceLanguage', state.lang); fd.append('voiceType', state.voiceType);
    setBusy(true);
    try {
      const p = await api('/api/projects', { method: 'POST', body: fd });
      localStorage.setItem('lastProject', p.id);
      showProject(p);
      startPolling(p.id);
    } catch (e) { toast(e.message, true); setBusy(false); }
  };
  function setBusy(b) {
    state.busy = b; $('#generateBtn').classList.toggle('busy', b);
    $('#generateLabel').textContent = b ? 'Generating…' : 'Generate Video'; refreshGenerate();
  }

  // ---------- project view ----------
  const video = $('#video');
  function show(id, on) { $(id).hidden = !on; }
  function showProject(p) {
    state.project = p;
    const done = p.status === 'COMPLETED', failed = p.status === 'FAILED', running = !done && !failed;
    show('#placeholder', false); show('#progressPanel', running); show('#errorPanel', failed);
    $('#controls').classList.toggle('disabled', !done);
    $('#resultTitle').textContent = done ? 'Generated Video' : running ? 'Generating Video' : 'Generation Failed';
    $('#resultSub').textContent = done ? 'Your AI-generated video is ready. Click play to watch.' : running ? p.title : 'Something went wrong while creating this video.';
    const dl = $('#downloadBtn');
    if (done) { dl.href = p.downloadUrl; dl.removeAttribute('aria-disabled'); } else { dl.removeAttribute('href'); dl.setAttribute('aria-disabled', 'true'); }
    $('#shareBtn').disabled = !done;
    if (running) {
      $('#ppTitle').textContent = p.message || 'Working…';
      $('#ppBar').style.width = p.progress + '%'; $('#ppPct').textContent = p.progress + '%';
      const order = ['EXTRACTING', 'PLANNING', 'RENDERING', 'NARRATING', 'ASSEMBLING']; const at = order.indexOf(p.status);
      $$('#ppSteps li').forEach((li, i) => { li.classList.toggle('done', i < at); li.classList.toggle('on', i === at); });
      $('#bigPlay').hidden = true; setStep(3); renderScenes([], true);
    }
    if (failed) { $('#errMsg').textContent = p.error || 'Unknown error'; $('#bigPlay').hidden = true; setStep(3); renderScenes([]); }
    if (done) {
      setStep(4);
      if (!video.src || !video.src.endsWith(p.videoUrl)) {
        if (p.scenes.length) video.poster = p.scenes[0].thumbnailUrl;
        video.src = p.videoUrl; video.load();
        $('#cTime').textContent = `0:00 / ${fmtTime(p.durationSeconds)}`;
      }
      $('#bigPlay').hidden = !video.paused;
      renderScenes(p.scenes);
    }
  }
  function startPolling(id) {
    stopPolling();
    const tick = async () => {
      try {
        const p = await api('/api/projects/' + id);
        showProject(p);
        if (p.status === 'COMPLETED' || p.status === 'FAILED') {
          stopPolling(); setBusy(false);
          toast(p.status === 'COMPLETED' ? 'Your video is ready!' : 'Video generation failed.', p.status === 'FAILED');
        }
      } catch (e) { /* transient; keep polling */ }
    };
    state.polling = setInterval(tick, 1500); tick();
  }
  function stopPolling() { clearInterval(state.polling); state.polling = null; }

  function renderScenes(scenes, skeleton) {
    const box = $('#scenes'); box.replaceChildren();
    if (skeleton) { for (let i = 0; i < 6; i++) box.append(el('div', { className: 'scene skel' }, el('span', { className: 'thumb' }), el('span', { className: 'lbl', textContent: ' ' }))); return; }
    scenes.forEach(s => {
      const b = el('button', { className: 'scene', type: 'button', title: s.title });
      b.dataset.start = s.startSeconds; b.dataset.end = s.startSeconds + s.durationSeconds;
      b.append(el('span', { className: 'thumb' }, el('img', { src: s.thumbnailUrl, alt: '', loading: 'lazy' }), el('span', { className: 'tc', textContent: fmtTime(s.startSeconds) })),
        el('span', { className: 'lbl', textContent: `${s.index + 1}. ${s.title}` }));
      b.onclick = () => { video.currentTime = s.startSeconds + 0.05; video.play(); };
      box.append(b);
    });
    syncScene();
  }
  function syncScene() {
    const t = video.currentTime; let active = null;
    $$('#scenes .scene').forEach(b => { const on = t >= +b.dataset.start && t < +b.dataset.end; b.classList.toggle('active', on); if (on) active = b; });
    if (active && !video.paused) { const box = $('#scenes'); const r = active.getBoundingClientRect(), br = box.getBoundingClientRect(); if (r.left < br.left || r.right > br.right) box.scrollTo({ left: active.offsetLeft - box.offsetLeft - 8, behavior: 'smooth' }); }
  }

  // ---------- player controls ----------
  const seek = $('#cSeek'), vol = $('#cVol');
  const paint = (r) => r.style.setProperty('--p', (r.value - r.min) / (r.max - r.min) * 100 + '%');
  const playIcon = () => { $('#cPlay use').setAttribute('href', video.paused ? '#i-play' : '#i-pause'); $('#bigPlay').hidden = !video.paused || !state.project || state.project.status !== 'COMPLETED'; };
  const toggle = () => { if (!state.project || state.project.status !== 'COMPLETED') return; video.paused ? video.play() : video.pause(); };
  $('#cPlay').onclick = toggle; $('#bigPlay').onclick = toggle; video.onclick = toggle;
  video.onplay = video.onpause = video.onended = playIcon;
  video.ontimeupdate = () => {
    const d = video.duration || (state.project && state.project.durationSeconds) || 0;
    $('#cTime').textContent = `${fmtTime(video.currentTime)} / ${fmtTime(d)}`;
    if (!seeking) { seek.value = d ? video.currentTime / d * 1000 : 0; paint(seek); }
    syncScene();
  };
  video.onloadedmetadata = video.ontimeupdate;
  let seeking = false;
  seek.oninput = () => { seeking = true; paint(seek); const d = video.duration || 0; if (d) video.currentTime = seek.value / 1000 * d; };
  seek.onchange = () => { seeking = false; };
  vol.oninput = () => { video.volume = vol.value / 100; video.muted = video.volume === 0; paint(vol); muteIcon(); };
  function muteIcon() { $('#cMute use').setAttribute('href', video.muted || video.volume === 0 ? '#i-mute' : '#i-vol'); }
  $('#cMute').onclick = () => { video.muted = !video.muted; muteIcon(); };
  $('#cFull').onclick = () => { const p = $('#player'); document.fullscreenElement ? document.exitFullscreen() : p.requestFullscreen && p.requestFullscreen(); };
  $('#cSettings').onclick = e => { e.stopPropagation(); const m = $('#speedMenu'); m.hidden = !m.hidden; };
  $$('#speedMenu [data-speed]').forEach(b => b.onclick = e => { e.stopPropagation(); video.playbackRate = +b.dataset.speed; $$('#speedMenu [data-speed]').forEach(x => x.classList.toggle('sel', x === b)); $('#speedMenu').hidden = true; });
  document.addEventListener('keydown', e => {
    if (/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) || !state.project || state.project.status !== 'COMPLETED') return;
    if (e.key === ' ' && document.activeElement.tagName !== 'BUTTON') { e.preventDefault(); toggle(); }
    if (e.key === 'ArrowRight') video.currentTime += 5; if (e.key === 'ArrowLeft') video.currentTime -= 5;
    if (e.key === 'f') $('#cFull').click();
  });
  paint(seek); paint(vol);

  // ---------- share / menu ----------
  $('#shareBtn').onclick = async () => {
    const p = state.project; if (!p || p.status !== 'COMPLETED') return;
    const url = `${location.origin}/watch/${p.shareToken}`;
    try { if (navigator.share) { await navigator.share({ title: p.title, url }); return; } } catch (e) { if (e.name === 'AbortError') return; }
    try { await navigator.clipboard.writeText(url); toast('Share link copied to clipboard'); }
    catch (_) { openModal('Share link', el('input', { className: 'text-input', value: url, readOnly: true, onfocus: e => e.target.select() })); }
  };
  $('#moreBtn').onclick = e => {
    e.stopPropagation(); const m = $('#moreMenu'); const p = state.project; const done = p && p.status === 'COMPLETED';
    $$('#moreMenu button').forEach(b => { const n = b.dataset.needs; b.disabled = (n === 'done' && !done) || (n === 'project' && !p); });
    m.hidden = !m.hidden;
  };
  $('#moreMenu').onclick = async e => {
    const b = e.target.closest('button'); if (!b) return; $('#moreMenu').hidden = true;
    const p = state.project;
    switch (b.dataset.act) {
      case 'history': return openHistory();
      case 'script': return openScript(p);
      case 'source': location.href = `/api/projects/${p.id}/source`; return;
      case 'new': return resetAll();
      case 'delete': {
        if (!confirm(`Delete "${p.title}" and its files permanently?`)) return;
        try { await api('/api/projects/' + p.id, { method: 'DELETE' }); toast('Video deleted'); resetAll(); } catch (err) { toast(err.message, true); }
      }
    }
  };
  function resetAll() {
    stopPolling(); localStorage.removeItem('lastProject'); state.project = null; setBusy(false);
    video.pause(); video.removeAttribute('src'); video.load();
    show('#placeholder', true); show('#progressPanel', false); show('#errorPanel', false); $('#bigPlay').hidden = true;
    $('#controls').classList.add('disabled'); $('#resultTitle').textContent = 'Generated Video'; $('#resultSub').textContent = 'Your AI-generated video will appear here.';
    $('#downloadBtn').removeAttribute('href'); $('#downloadBtn').setAttribute('aria-disabled', 'true'); $('#shareBtn').disabled = true;
    $('#scenes').replaceChildren(); $('#cTime').textContent = '0:00 / 0:00'; seek.value = 0; paint(seek); playIcon();
    state.file = null; renderFile(); titleEl.value = ''; updateTitle(); setStep(1);
  }

  // ---------- modal ----------
  function openModal(title, body) { $('#modalTitle').textContent = title; $('#modalBody').replaceChildren(body); $('#modal').hidden = false; }
  const closeModal = () => { $('#modal').hidden = true; };
  $('#modalClose').onclick = closeModal; $('#modal').onclick = e => { if (e.target.id === 'modal') closeModal(); };
  document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

  async function openHistory() {
    const box = el('div');
    openModal('My videos', box);
    try {
      const list = await api('/api/projects?limit=50');
      if (!list.length) return box.append(el('div', { className: 'empty', textContent: 'No videos yet.' }));
      list.forEach(p => {
        const row = el('div', { className: 'hist' }, el('div', { className: 'hi' },
          el('div', { className: 'ht', textContent: p.title }),
          el('div', { className: 'hs', textContent: `${new Date(p.createdAt).toLocaleString()} · ${p.sourceFilename} · ${p.lengthMinutes} min · ${p.style.toLowerCase()}` })),
          el('span', { className: 'tag ' + p.status, textContent: p.status === 'COMPLETED' ? 'Ready' : p.status === 'FAILED' ? 'Failed' : 'In progress' }));
        row.onclick = () => { closeModal(); openProject(p.id); };
        box.append(row);
      });
    } catch (e) { box.append(el('div', { className: 'empty', textContent: e.message })); }
  }
  function openScript(p) {
    const box = el('div');
    (p.scenes || []).forEach(s => box.append(el('div', { className: 'script-scene' }, el('h4', { textContent: `${s.index + 1}. ${s.title}  ·  ${fmtTime(s.startSeconds)}` }), el('p', { textContent: s.narration }))));
    openModal(p.title + ' — narration script', box);
  }
  async function openProject(id) {
    try {
      stopPolling(); const p = await api('/api/projects/' + id); localStorage.setItem('lastProject', id);
      video.pause(); video.removeAttribute('src');
      showProject(p);
      if (p.status !== 'COMPLETED' && p.status !== 'FAILED') { setBusy(true); startPolling(id); }
      else setBusy(false);
      titleEl.value = p.title; updateTitle();
    } catch (e) { toast(e.message, true); localStorage.removeItem('lastProject'); }
  }

  // ---------- init ----------
  loadVoices();
  updateTitle(); renderScenes([], false);
  $('#controls').classList.add('disabled');
  const last = localStorage.getItem('lastProject');
  if (last) openProject(last);
})();
