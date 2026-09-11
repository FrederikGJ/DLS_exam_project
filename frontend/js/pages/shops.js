// Butikker: list/search shops and navigate between locations (Dijkstra route + SVG map).
import { shopApi } from '../api.js';
import { esc, badge, toast, showError, busy } from '../app.js';

const CATEGORIES = ['FOOD', 'RETAIL', 'DUTY_FREE', 'SERVICE', 'LOUNGE'];
const CAT_LABEL = { FOOD: 'Mad & drikke', RETAIL: 'Butik', DUTY_FREE: 'Tax free', SERVICE: 'Service', LOUNGE: 'Lounge' };
const TYPE_LABEL = { SHOP: 'Butikker', GATE: 'Gates', SECURITY: 'Security', ENTRANCE: 'Indgange', JUNCTION: 'Knudepunkter', ELEVATOR: 'Elevatorer/trapper' };
const TYPE_ORDER = ['ENTRANCE', 'SECURITY', 'GATE', 'SHOP', 'ELEVATOR', 'JUNCTION'];
const TYPE_COLOR = { SHOP: '#f59e0b', GATE: '#1f5fbf', SECURITY: '#dc2626', ENTRANCE: '#16a34a', JUNCTION: '#94a3b8', ELEVATOR: '#7c3aed' };
const ROUTE_COLOR = { start: '#16a34a', end: '#dc2626', mid: '#1f5fbf' };

export async function render(container, params) {
  container.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Butikker & navigation</h1>
        <p class="lead">Find butikker i lufthavnen og få en rute fra hvor du er til hvor du skal hen.</p>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><h2>Find vej</h2>
        <label class="check"><input type="checkbox" id="acc-only"> Kun tilgængelig rute (ingen trapper)</label>
      </div>
      <form id="route-form" class="row">
        <div><label for="from">Hvor er jeg?</label><select id="from" required><option value="">Henter…</option></select></div>
        <div><label for="to">Hvor skal jeg hen?</label><select id="to" required><option value="">Henter…</option></select></div>
        <div class="auto"><button class="btn" type="submit" id="route-btn">Find rute</button></div>
        <div class="auto"><button class="btn secondary" type="button" id="swap-btn" title="Byt om">⇄</button></div>
      </form>
      <p class="help">Tip: klik på et punkt på kortet for at vælge start og derefter destination.</p>

      <div style="margin-top:12px">
        <div class="map-wrap">
          <div class="map-toolbar">
            <div>Etage:
              <select id="floor" style="width:auto;display:inline-block;min-height:30px;padding:2px 8px"></select>
            </div>
            <label class="check small"><input type="checkbox" id="map-text" checked> Tekst på kortet</label>
            <div class="map-legend">
              ${TYPE_ORDER.map(t => `<span><i style="background:${TYPE_COLOR[t]}"></i>${esc(TYPE_LABEL[t])}</span>`).join('')}
              <span><i class="line"></i>Gang</span>
              <span><i class="line dashed"></i>Trappe (ikke tilgængelig)</span>
            </div>
          </div>
          <div id="map-summary" class="map-summary" hidden></div>
          <div id="map"></div>
        </div>
        <div id="route-host" style="margin-top:14px"><div class="empty">Vælg start og destination – ruten vises på kortet og som trin-for-trin liste her.</div></div>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><h2>Butikker</h2><span class="muted small" id="shop-count"></span></div>
      <form id="shop-filter" class="row">
        <div><label for="q">Søg</label><input id="q" placeholder="fx kaffe, tax free, bøger…"></div>
        <div><label for="terminal">Terminal</label><select id="terminal"><option value="">Alle</option></select></div>
        <div><label for="category">Kategori</label><select id="category"><option value="">Alle</option>${CATEGORIES.map(c => `<option value="${c}">${esc(CAT_LABEL[c])}</option>`).join('')}</select></div>
        <div class="auto"><label class="check" style="min-height:40px"><input type="checkbox" id="open-now"> Kun åbne nu</label></div>
        <div class="auto"><button class="btn" type="submit">Søg</button></div>
      </form>
      <div id="shop-list" style="margin-top:14px"><div class="empty"><span class="spinner"></span> Henter butikker…</div></div>
    </div>
  `;

  const fromSel = container.querySelector('#from');
  const toSel = container.querySelector('#to');
  const floorSel = container.querySelector('#floor');
  const mapEl = container.querySelector('#map');
  const mapSummary = container.querySelector('#map-summary');
  const mapTextToggle = container.querySelector('#map-text');
  const routeHost = container.querySelector('#route-host');
  const shopList = container.querySelector('#shop-list');

  let nodes = [];
  let edges = [];
  let shops = [];
  let route = null;
  let floor = 0;

  // ---- load nodes + walkway network (edges) + shops
  const [nodesRes, edgesRes, shopsRes] = await Promise.allSettled([shopApi.navNodes(), shopApi.navEdges(), shopApi.shops({})]);
  if (nodesRes.status === 'fulfilled') {
    nodes = nodesRes.value || [];
  } else {
    showError(nodesRes.reason);
    fromSel.innerHTML = toSel.innerHTML = '<option value="">Kunne ikke hente lokationer</option>';
  }
  if (edgesRes.status === 'fulfilled') edges = edgesRes.value || [];
  else showError(edgesRes.reason);           // the map still works without edges, just without the walkway network
  if (shopsRes.status === 'fulfilled') shops = shopsRes.value || [];
  else showError(shopsRes.reason);

  fillNodeSelects();
  fillTerminalFilter();
  fillFloors();
  drawShops(shops);
  drawMap();

  if (params.from) fromSel.value = String(params.from);
  if (params.to) toSel.value = String(params.to);
  if (params.from && params.to) findRoute();      // deep link #/shops?from=<id>&to=<id> shows the route directly

  // ---- events
  container.querySelector('#route-form').addEventListener('submit', (e) => {
    e.preventDefault();
    busy(container.querySelector('#route-btn'), findRoute);
  });
  container.querySelector('#swap-btn').addEventListener('click', () => {
    const a = fromSel.value; fromSel.value = toSel.value; toSel.value = a;
  });
  floorSel.addEventListener('change', () => { floor = Number(floorSel.value); drawMap(); });
  mapTextToggle.addEventListener('change', drawMap);
  container.querySelector('#shop-filter').addEventListener('submit', (e) => { e.preventDefault(); searchShops(); });
  container.querySelector('#acc-only').addEventListener('change', () => { if (route) findRoute(); });

  // ------------------------------------------------------------ helpers
  function nodeLabel(n) { return `${n.terminal} · ${n.name}${n.floor ? ` (etage ${n.floor})` : ''}`; }

  function fillNodeSelects() {
    const groups = [];
    const terminals = [...new Set(nodes.map(n => n.terminal))].sort();
    for (const t of terminals) {
      for (const type of TYPE_ORDER) {
        const list = nodes.filter(n => n.terminal === t && n.type === type)
          .sort((a, b) => a.name.localeCompare(b.name, 'da', { numeric: true }));
        if (list.length) groups.push({ label: `${t} – ${TYPE_LABEL[type]}`, list });
      }
    }
    const html = '<option value="">– vælg –</option>' + groups.map(g =>
      `<optgroup label="${esc(g.label)}">${g.list.map(n => `<option value="${esc(n.id)}">${esc(nodeLabel(n))}</option>`).join('')}</optgroup>`).join('');
    fromSel.innerHTML = html;
    toSel.innerHTML = html;
  }

  function fillTerminalFilter() {
    const sel = container.querySelector('#terminal');
    const terminals = [...new Set([...nodes.map(n => n.terminal), ...shops.map(s => s.terminal)])].sort();
    sel.innerHTML = '<option value="">Alle</option>' + terminals.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join('');
  }

  function fillFloors() {
    const floors = [...new Set(nodes.map(n => n.floor))].sort((a, b) => a - b);
    if (!floors.length) floors.push(0);
    floor = floors.includes(0) ? 0 : floors[0];
    floorSel.innerHTML = floors.map(f => `<option value="${f}" ${f === floor ? 'selected' : ''}>${f === 0 ? 'Stueetage (0)' : 'Etage ' + f}</option>`).join('');
  }

  async function searchShops() {
    const q = container.querySelector('#q').value.trim();
    const terminal = container.querySelector('#terminal').value;
    const category = container.querySelector('#category').value;
    const openNow = container.querySelector('#open-now').checked;
    shopList.innerHTML = '<div class="empty"><span class="spinner"></span> Søger…</div>';
    try {
      let result;
      if (q) {
        result = await shopApi.searchShops(q);
        // apply the remaining filters client-side
        result = result.filter(s => (!terminal || s.terminal === terminal) && (!category || s.category === category) && (!openNow || s.openNow));
      } else {
        const filter = {};
        if (terminal) filter.terminal = terminal;
        if (category) filter.category = category;
        if (openNow) filter.openNow = true;
        result = await shopApi.shops(filter);
      }
      drawShops(result);
    } catch (err) {
      shopList.innerHTML = `<div class="alert error">${esc(err.message)}</div>`;
      showError(err);
    }
  }

  function shopCard(s, extra = '') {
    return `<div class="shop">
      <div class="card-head" style="margin-bottom:2px"><h3>${esc(s.name)}</h3>${badge(s.category)}</div>
      <div class="meta">${esc(s.terminal)} · ${esc(s.zone)} · ${s.floor === 0 ? 'stueetage' : 'etage ' + esc(s.floor)} · ${esc(s.openingHours)}
        ${s.openNow ? '<span class="badge green">Åben</span>' : '<span class="badge gray">Lukket</span>'}</div>
      ${s.description ? `<div class="desc">${esc(s.description)}</div>` : ''}
      ${s.node ? `<div style="margin-top:8px"><button class="btn link small goto" data-node="${esc(s.node.id)}">Vis vej hertil →</button></div>` : ''}
      ${extra}
    </div>`;
  }

  function drawShops(list) {
    container.querySelector('#shop-count').textContent = `${list.length} butik${list.length === 1 ? '' : 'ker'}`;
    if (!list.length) { shopList.innerHTML = '<div class="empty">Ingen butikker matcher.</div>'; return; }
    shopList.innerHTML = `<div class="shop-list">${list.map(s => shopCard(s)).join('')}</div>`;
    bindGoto(shopList);
  }

  function bindGoto(root) {
    root.querySelectorAll('.goto').forEach(b => b.addEventListener('click', () => {
      toSel.value = b.dataset.node;
      if (!fromSel.value) { toast('Vælg nu hvor du er, og tryk "Find rute"', 'info'); fromSel.focus(); fromSel.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
      else findRoute();
    }));
  }

  async function findRoute() {
    const from = fromSel.value, to = toSel.value;
    if (!from || !to) { toast('Vælg både start og destination', 'error'); return; }
    if (from === to) { toast('Start og destination er den samme', 'error'); return; }
    const accessibleOnly = container.querySelector('#acc-only').checked;
    routeHost.innerHTML = '<div class="empty"><span class="spinner"></span> Beregner rute…</div>';
    try {
      route = await shopApi.route(from, to, accessibleOnly);
      drawRoute();
      // jump to the floor where the route starts
      const startFloor = route.steps[0]?.node?.floor;
      if (startFloor !== undefined && startFloor !== floor) { floor = startFloor; floorSel.value = String(floor); }
      drawMap();
    } catch (err) {
      route = null;
      routeHost.innerHTML = `<div class="alert error">${esc(err.message)} <span class="mono small">(${esc(err.code)})</span></div>`;
      showError(err);
      drawMap();
    }
  }

  function drawRoute() {
    const steps = route.steps || [];
    routeHost.innerHTML = `<div class="grid-2"><div>
      <div class="route-total">
        <span>📏 ${esc(route.totalDistanceM)} m</span>
        <span>🚶 ca. ${esc(route.estimatedMinutes)} min</span>
        <span class="muted small">${esc(steps.length)} trin</span>
      </div>
      <ol class="route-steps">
        ${steps.map((s, i) => `<li>
          <span class="n">${i + 1}</span>
          <div><div>${esc(s.instruction)}</div>
            <div class="small muted">${esc(s.node.terminal)} · ${esc(s.node.name)}${s.node.floor ? ' · etage ' + esc(s.node.floor) : ''}</div></div>
          <span class="dist">${s.distance ? esc(s.distance) + ' m' : ''}</span>
        </li>`).join('')}
      </ol></div>
      <div><h3>Butikker undervejs</h3>
      ${(route.shopsAlongRoute || []).length
        ? `<div class="shop-list">${route.shopsAlongRoute.map(s => shopCard(s)).join('')}</div>`
        : '<div class="muted small">Ingen butikker direkte på ruten.</div>'}
      </div></div>`;
    bindGoto(routeHost);
  }

  // ------------------------------------------------------------ SVG map
  // Draws, in this order: terminal boxes, the walkway network (nav_edge), the route (one line per
  // segment with a direction arrow), the nodes, distance labels per segment and finally the numbered
  // route steps with their instruction text. Only nodes on the selected floor are drawn; the summary
  // strip above the map says when the route continues on another floor.
  function drawMap() {
    const W = 1000, H = 600, PAD = 50;
    const visible = nodes.filter(n => n.floor === floor);
    if (!visible.length) { mapEl.innerHTML = '<div class="empty">Ingen punkter på denne etage.</div>'; return; }
    const showText = mapTextToggle.checked;

    // fit node coordinates into the viewBox (same scale on every floor)
    const xs = nodes.map(n => n.x), ys = nodes.map(n => n.y);
    const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
    const sx = (W - 2 * PAD) / Math.max(1, maxX - minX);
    const sy = (H - 2 * PAD) / Math.max(1, maxY - minY);
    const px = n => Math.round(PAD + (n.x - minX) * sx);
    const py = n => Math.round(PAD + (n.y - minY) * sy);

    const byId = new Map(nodes.map(n => [String(n.id), n]));
    const onFloor = id => byId.get(String(id))?.floor === floor;
    const steps = route?.steps || [];
    const pts = steps.map(s => byId.get(String(s.node.id)) || s.node);
    const stepOf = new Map();                       // node id -> first step index on the route
    steps.forEach((s, i) => { const k = String(s.node.id); if (!stepOf.has(k)) stepOf.set(k, i); });
    const fromId = String(fromSel.value), toId = String(toSel.value);

    // ---- terminal background boxes
    const terminals = [...new Set(visible.map(n => n.terminal))];
    const boxes = terminals.map(t => {
      const tn = visible.filter(n => n.terminal === t);
      const x0 = Math.min(...tn.map(px)) - 30, x1 = Math.max(...tn.map(px)) + 30;
      const y0 = Math.min(...tn.map(py)) - 30, y1 = Math.max(...tn.map(py)) + 30;
      return `<rect x="${x0}" y="${y0}" width="${x1 - x0}" height="${y1 - y0}" rx="14" fill="#eef2f7" stroke="#dbe2ea"/>
              <text x="${x0 + 10}" y="${y0 + 18}" class="nlabel" style="font-weight:700;fill:#64748b">${esc(t)}</text>`;
    }).join('');

    // ---- walkway network: every edge with both ends on this floor
    const edgeSvg = edges.filter(e => onFloor(e.from.id) && onFloor(e.to.id)).map(e => {
      const a = byId.get(String(e.from.id)), b = byId.get(String(e.to.id));
      const title = `${a.name} – ${b.name}: ${e.distanceM} m${e.accessible ? '' : ' (trappe – ikke tilgængelig)'}`;
      return `<line class="edge${e.accessible ? '' : ' inaccessible'}" x1="${px(a)}" y1="${py(a)}" x2="${px(b)}" y2="${py(b)}"><title>${esc(title)}</title></line>`;
    }).join('');

    // ---- links to other floors (elevators/stairs): small hint next to the node on this floor
    const floorLinks = new Map();
    for (const e of edges) {
      const a = byId.get(String(e.from.id)), b = byId.get(String(e.to.id));
      if (!a || !b || a.floor === b.floor) continue;
      const here = a.floor === floor ? a : b.floor === floor ? b : null;
      if (!here) continue;
      const other = here === a ? b : a;
      if (!floorLinks.has(String(here.id))) floorLinks.set(String(here.id), new Set());
      floorLinks.get(String(here.id)).add(other.floor);
    }
    const linkSvg = [...floorLinks].map(([id, floors]) => {
      const n = byId.get(id);
      return `<text x="${px(n) + 11}" y="${py(n) + 16}" class="nlabel hint">↕ etage ${[...floors].sort().join(', ')}</text>`;
    }).join('');

    // ---- label placement: greedy, each label takes the first candidate spot that hits nothing placed before it
    const placed = [];
    const box = (cx, cy, halfW, halfH = 7) => ({ x0: cx - halfW, y0: cy - halfH, x1: cx + halfW, y1: cy + halfH });
    const overlaps = b => placed.some(o => b.x0 < o.x1 && b.x1 > o.x0 && b.y0 < o.y1 && b.y1 > o.y0);
    function placeText(x, y, text, cls, candidates, optional = false) {
      const halfW = text.length * (cls === 'step-label' ? 3.5 : 3) + 4;
      for (const [cx0, cy] of candidates(halfW)) {
        const cx = Math.min(W - 6 - halfW, Math.max(6 + halfW, cx0));
        const b = box(cx, cy - 4, halfW);
        if (!overlaps(b)) { placed.push(b); return `<text x="${cx.toFixed(0)}" y="${cy.toFixed(0)}" class="${cls}" text-anchor="middle">${esc(text)}</text>`; }
      }
      if (optional) return '';
      const [cx0, cy] = candidates(halfW)[0];
      const cx = Math.min(W - 6 - halfW, Math.max(6 + halfW, cx0));
      placed.push(box(cx, cy - 4, halfW));
      return `<text x="${cx.toFixed(0)}" y="${cy.toFixed(0)}" class="${cls}" text-anchor="middle">${esc(text)}</text>`;
    }
    // step markers are obstacles for every label
    pts.forEach(n => { if (n.floor === floor) placed.push(box(px(n), py(n), 12, 12)); });

    // ---- route: one line per segment (with arrow head); distance labels are placed after the step labels
    let routeSvg = '';
    const segLabels = [];
    const R = 13;                                   // radius of the step marker – lines stop at its edge
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i];
      if (a.floor !== floor || b.floor !== floor) continue;
      const x1 = px(a), y1 = py(a), x2 = px(b), y2 = py(b);
      const len = Math.hypot(x2 - x1, y2 - y1);
      if (len < 1) continue;
      const ux = (x2 - x1) / len, uy = (y2 - y1) / len;
      const trim = len > 2 * R + 6 ? R : 0;
      routeSvg += `<line class="route" x1="${(x1 + ux * trim).toFixed(1)}" y1="${(y1 + uy * trim).toFixed(1)}" ` +
                  `x2="${(x2 - ux * trim).toFixed(1)}" y2="${(y2 - uy * trim).toFixed(1)}" marker-end="url(#arrow)"/>`;
      const d = steps[i].distance;
      if (showText && d) segLabels.push({ mx: (x1 + x2) / 2, my: (y1 + y2) / 2, ux, uy, text: `${d} m` });
    }

    // ---- nodes
    const nodeSvg = visible.map(n => {
      const x = px(n), y = py(n), c = TYPE_COLOR[n.type] || '#94a3b8';
      const onRoute = stepOf.has(String(n.id));
      const isFrom = fromId === String(n.id), isTo = toId === String(n.id);
      const stroke = isFrom ? ROUTE_COLOR.start : isTo ? ROUTE_COLOR.end : onRoute ? ROUTE_COLOR.mid : '#fff';
      const sw = (isFrom || isTo || onRoute) ? 3 : 1.5;
      const shape = n.type === 'GATE'
        ? `<rect x="${x - 8}" y="${y - 8}" width="16" height="16" rx="3" fill="${c}" stroke="${stroke}" stroke-width="${sw}"/>`
        : n.type === 'JUNCTION'
          ? `<circle cx="${x}" cy="${y}" r="4" fill="${c}" stroke="${stroke}" stroke-width="${sw}"/>`
          : `<circle cx="${x}" cy="${y}" r="${n.type === 'SHOP' ? 6 : 8}" fill="${c}" stroke="${stroke}" stroke-width="${sw}"/>`;
      // route nodes get their name from the step label when text is on, so skip the plain label then
      const showLabel = !(showText && onRoute) && (['GATE', 'SECURITY', 'ENTRANCE'].includes(n.type) || onRoute || isFrom || isTo);
      const lw = n.name.length * 6, left = x + 13 + lw > W - 4;   // near the right edge: put the name on the left
      const label = showLabel ? `<text x="${left ? x - 11 : x + 11}" y="${y + 4}" class="nlabel" text-anchor="${left ? 'end' : 'start'}">${esc(n.name)}</text>` : '';
      if (showLabel) placed.push(left ? { x0: x - 13 - lw, y0: y - 6, x1: x - 9, y1: y + 6 } : { x0: x + 9, y0: y - 6, x1: x + 13 + lw, y1: y + 6 });   // obstacle for route labels
      return `<g class="node" data-id="${esc(n.id)}"><title>${esc(nodeLabel(n))}</title>${shape}${label}</g>`;
    }).join('');

    // ---- numbered step markers + instruction text ("3. Gå 70 m til Junction Pier B")
    const stepSvg = steps.map((s, i) => {
      const n = pts[i];
      if (n.floor !== floor || stepOf.get(String(n.id)) !== i) return '';
      const x = px(n), y = py(n);
      const fill = i === 0 ? ROUTE_COLOR.start : i === steps.length - 1 ? ROUTE_COLOR.end : ROUTE_COLOR.mid;
      let g = `<circle cx="${x}" cy="${y}" r="11" fill="${fill}" stroke="#fff" stroke-width="2"/>` +
              `<text x="${x}" y="${y + 4}" class="step-n" text-anchor="middle">${i + 1}</text>`;
      if (showText) {
        // try above, below, right, left, then further above/below
        g += placeText(x, y, `${i + 1}. ${s.instruction}`, 'step-label', hw => [
          [x, y - 18], [x, y + 28], [x + 18 + hw, y + 4], [x - 18 - hw, y + 4], [x, y - 34], [x, y + 44]]);
      }
      return `<g class="step">${g}</g>`;
    }).join('');
    // distance per segment beside the midpoint (either side); dropped if there is no free spot – the step text has it too
    const distSvg = segLabels.map(l => placeText(l.mx, l.my, l.text, 'dist-label', hw => [
      [l.mx - l.uy * 14, l.my + l.ux * 14 + 4], [l.mx + l.uy * 14, l.my - l.ux * 14 + 4]], true)).join('');

    // ---- summary strip above the map
    if (route) {
      const first = steps[0]?.node, last = steps[steps.length - 1]?.node;
      const otherFloors = [...new Set(pts.filter(n => n.floor !== floor).map(n => n.floor))].sort((a, b) => a - b);
      const hidden = steps.map((s, i) => (pts[i].floor !== floor ? i + 1 : null)).filter(Boolean);
      mapSummary.innerHTML = `
        <span><strong>${esc(first?.name)} → ${esc(last?.name)}</strong></span>
        <span>📏 ${esc(route.totalDistanceM)} m · 🚶 ca. ${esc(route.estimatedMinutes)} min · ${esc(steps.length)} trin</span>
        ${otherFloors.length ? `<span class="warn">Trin ${esc(hidden.join(', '))} ligger på etage ${esc(otherFloors.join(', '))}
          ${otherFloors.map(f => `<button type="button" class="btn link small floor-jump" data-floor="${esc(f)}">Vis etage ${esc(f)}</button>`).join(' ')}</span>` : ''}`;
      mapSummary.querySelectorAll('.floor-jump').forEach(b => b.addEventListener('click', () => {
        floor = Number(b.dataset.floor); floorSel.value = String(floor); drawMap();
      }));
      mapSummary.hidden = false;
    } else {
      mapSummary.hidden = true;
      mapSummary.innerHTML = '';
    }

    mapEl.innerHTML = `<svg viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="Kort over lufthavnen, etage ${floor}">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="3" markerHeight="3" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" fill="${ROUTE_COLOR.mid}"/>
        </marker>
      </defs>
      <rect width="${W}" height="${H}" fill="#fbfcfe"/>
      ${boxes}${edgeSvg}${linkSvg}${routeSvg}${nodeSvg}${distSvg}${stepSvg}
    </svg>`;

    mapEl.querySelectorAll('g.node').forEach(g => g.addEventListener('click', () => {
      const id = g.dataset.id;
      if (!fromSel.value || (fromSel.value && toSel.value)) { fromSel.value = id; toSel.value = ''; toast(`Start: ${nodeLabel(byId.get(id))}`, 'info', null, 2500); }
      else { toSel.value = id; findRoute(); }
      drawMap();
    }));
  }
}
