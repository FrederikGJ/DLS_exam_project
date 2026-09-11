// Bagage: register baggage on a booking, list bags and update their status/location.
import { baggageApi, bookingApi } from '../api.js';
import { esc, formatDateTime, badge, toast, showError, busy, state } from '../app.js';

const TYPES = ['CHECKED', 'CABIN', 'SPECIAL'];
const STATUSES = ['REGISTERED', 'SECURITY', 'LOADED', 'IN_TRANSIT', 'ARRIVED', 'LOST'];
const LABEL = { CHECKED: 'Indchecket', CABIN: 'Håndbagage', SPECIAL: 'Special',
  REGISTERED: 'Registreret', SECURITY: 'Sikkerhedskontrol', LOADED: 'Lastet', IN_TRANSIT: 'Undervejs', ARRIVED: 'Ankommet', LOST: 'Bortkommet' };

export async function render(container, params) {
  const initialRef = (params.ref || state.bookingRef || '').toUpperCase();
  container.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Bagage</h1>
        <p class="lead">Registrér bagage på en bekræftet booking og følg dens status. Max 3 indcheckede stykker á max 32 kg.</p>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <h2>Registrér bagage</h2>
        <form id="reg-form">
          <div class="field"><label for="ref">Bookingreference</label>
            <input id="ref" maxlength="6" required placeholder="fx K7Q2ZP" value="${esc(initialRef)}" style="text-transform:uppercase"></div>
          <div class="grid-2">
            <div class="field"><label for="weight">Vægt (kg)</label><input id="weight" type="number" min="0.1" max="99" step="0.1" value="23" required></div>
            <div class="field"><label for="type">Type</label>
              <select id="type">${TYPES.map(t => `<option value="${t}">${esc(LABEL[t])}</option>`).join('')}</select></div>
          </div>
          <div id="snapshot" class="help"></div>
          <div style="margin-top:8px"><button class="btn" type="submit" id="reg-btn">Registrér</button></div>
        </form>
        <div id="reg-result" style="margin-top:12px"></div>
      </div>

      <div class="card">
        <h2>Slå bagage op</h2>
        <form id="tag-form" class="row">
          <div><label for="tag">Bagagetag</label><input id="tag" placeholder="BAG-XXXXXXXX" style="text-transform:uppercase" required></div>
          <div class="auto"><button class="btn secondary" type="submit">Find</button></div>
        </form>
        <div id="tag-result" style="margin-top:12px"></div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <h2>Bagage på booking <span id="list-ref" class="mono muted"></span></h2>
        <button class="btn sm secondary" id="reload-btn">⟳ Opdater</button>
      </div>
      <div id="list"><div class="empty">Indtast en bookingreference for at se bagage.</div></div>
    </div>
  `;

  const refInput = container.querySelector('#ref');
  const list = container.querySelector('#list');
  const listRef = container.querySelector('#list-ref');
  const snapshotEl = container.querySelector('#snapshot');

  container.querySelector('#reg-form').addEventListener('submit', (e) => {
    e.preventDefault();
    busy(container.querySelector('#reg-btn'), registerBag);
  });
  container.querySelector('#reload-btn').addEventListener('click', () => loadList(currentRef()));
  refInput.addEventListener('change', () => { loadList(currentRef()); loadSnapshot(currentRef()); });
  container.querySelector('#tag-form').addEventListener('submit', (e) => { e.preventDefault(); lookupTag(); });

  if (initialRef) { await Promise.all([loadList(initialRef), loadSnapshot(initialRef)]); }

  function currentRef() { return refInput.value.trim().toUpperCase(); }

  async function loadSnapshot(ref) {
    if (!ref) { snapshotEl.textContent = ''; return; }
    try {
      const b = await bookingApi.bookingByReference(ref);
      if (!b) { snapshotEl.innerHTML = `<span class="muted">Ukendt booking.</span>`; return; }
      const ok = ['CONFIRMED', 'CHECKED_IN'].includes(b.status);
      snapshotEl.innerHTML = `${esc(b.passenger?.firstName)} ${esc(b.passenger?.lastName)} · ${esc(b.flightNumber)} · ${badge(b.status)}
        ${ok ? '' : '<br><span style="color:var(--warning)">Bagage kræver status Bekræftet eller Checket ind.</span>'}`;
    } catch (_) { snapshotEl.textContent = ''; }
  }

  async function registerBag() {
    const ref = currentRef();
    const weight = Number(container.querySelector('#weight').value);
    const type = container.querySelector('#type').value;
    const result = container.querySelector('#reg-result');
    result.innerHTML = '';
    if (!ref) { toast('Angiv bookingreference', 'error'); return; }
    try {
      const bag = await baggageApi.registerBaggage(ref, weight, type);
      state.bookingRef = ref;
      result.innerHTML = `<div class="alert success">Bagage registreret. Tag: <span class="ref" style="font-size:1.2rem">${esc(bag.tagNumber)}</span><br>
        ${esc(bag.passengerName)} · ${esc(bag.flightNumber)} · ${esc(bag.weightKg)} kg · ${badge(bag.type)} · ${badge(bag.status)}</div>`;
      toast(`Bagage ${bag.tagNumber} registreret`, 'success');
      await loadList(ref);
    } catch (err) {
      const friendly = {
        BAGGAGE_LIMIT_EXCEEDED: 'Grænsen for bagage er nået (max 3 indcheckede stykker, max 32 kg pr. stk.).',
        INVALID_STATE: 'Bookingen skal være bekræftet eller checket ind, før bagage kan registreres.',
        NOT_FOUND: 'Bookingen er ikke kendt af bagagesystemet endnu. Er den betalt og bekræftet?',
        VALIDATION_ERROR: 'Ugyldige oplysninger.',
      }[err.code];
      result.innerHTML = `<div class="alert error">${esc(friendly ? friendly + ' ' : '')}${esc(err.message)} <span class="mono small">(${esc(err.code)})</span></div>`;
      showError(err);
    }
  }

  async function loadList(ref) {
    listRef.textContent = ref || '';
    if (!ref) { list.innerHTML = '<div class="empty">Indtast en bookingreference for at se bagage.</div>'; return; }
    list.innerHTML = '<div class="empty"><span class="spinner"></span> Henter bagage…</div>';
    try {
      const bags = await baggageApi.baggageByBooking(ref);
      drawList(bags);
    } catch (err) {
      list.innerHTML = `<div class="alert error">${esc(err.message)}</div>`;
      showError(err);
    }
  }

  function drawList(bags) {
    if (!bags.length) { list.innerHTML = '<div class="empty">Ingen bagage registreret på denne booking.</div>'; return; }
    list.innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Tag</th><th>Passager</th><th>Fly</th><th>Type</th><th>Vægt</th><th>Status</th><th>Lokation</th><th>Opdateret</th><th class="ops-cell">Opdater status</th></tr></thead>
      <tbody>${bags.map(b => `<tr data-tag="${esc(b.tagNumber)}">
        <td class="mono"><strong>${esc(b.tagNumber)}</strong></td>
        <td>${esc(b.passengerName)}</td><td class="mono">${esc(b.flightNumber)}</td>
        <td>${badge(b.type)}</td><td>${esc(Number(b.weightKg).toLocaleString('da-DK'))} kg</td>
        <td>${badge(b.status)}</td><td>${esc(b.lastLocation || '–')}</td><td class="small">${esc(formatDateTime(b.updatedAt))}</td>
        <td class="ops-cell"><div class="row">
          <select class="st">${STATUSES.map(s => `<option value="${s}" ${s === b.status ? 'selected' : ''}>${esc(LABEL[s])}</option>`).join('')}</select>
          <input class="loc" placeholder="Lokation, fx Belt 4" value="${esc(b.lastLocation || '')}" maxlength="100">
          <button class="btn sm upd">Opdater</button>
        </div></td>
      </tr>`).join('')}</tbody></table></div>`;

    list.querySelectorAll('tr[data-tag]').forEach(tr => {
      tr.querySelector('.upd').addEventListener('click', (e) => busy(e.currentTarget, async () => {
        const tag = tr.dataset.tag;
        const status = tr.querySelector('.st').value;
        const location = tr.querySelector('.loc').value.trim();
        try {
          const updated = await baggageApi.updateBaggageStatus(tag, status, location);
          toast(`${updated.tagNumber}: ${LABEL[updated.status] || updated.status}${updated.lastLocation ? ' @ ' + updated.lastLocation : ''}`, 'success');
          await loadList(currentRef());
        } catch (err) { showError(err); }
      }));
    });
  }

  async function lookupTag() {
    const tag = container.querySelector('#tag').value.trim().toUpperCase();
    const out = container.querySelector('#tag-result');
    out.innerHTML = '<span class="spinner"></span>';
    try {
      const b = await baggageApi.baggage(tag);
      if (!b) { out.innerHTML = `<div class="alert error">Ingen bagage med tag <strong>${esc(tag)}</strong>.</div>`; return; }
      out.innerHTML = `<dl class="kv">
        <dt>Tag</dt><dd class="mono">${esc(b.tagNumber)}</dd>
        <dt>Booking</dt><dd class="mono">${esc(b.bookingReference)}</dd>
        <dt>Passager</dt><dd>${esc(b.passengerName)}</dd>
        <dt>Fly</dt><dd class="mono">${esc(b.flightNumber)}</dd>
        <dt>Type / vægt</dt><dd>${badge(b.type)} ${esc(b.weightKg)} kg</dd>
        <dt>Status</dt><dd>${badge(b.status)} ${esc(b.lastLocation ? '· ' + b.lastLocation : '')}</dd>
        <dt>Opdateret</dt><dd>${esc(formatDateTime(b.updatedAt))}</dd>
      </dl>
      <p style="margin-top:8px"><button class="btn sm secondary" id="use-ref">Vis alle stykker på ${esc(b.bookingReference)}</button></p>`;
      out.querySelector('#use-ref').addEventListener('click', () => { refInput.value = b.bookingReference; loadList(b.bookingReference); loadSnapshot(b.bookingReference); });
    } catch (err) {
      out.innerHTML = `<div class="alert error">${esc(err.message)}</div>`;
      showError(err);
    }
  }
}
