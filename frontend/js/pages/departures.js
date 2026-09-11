// Afgange: list of flights with filters + optional operations panel (status/gate).
import { flightApi } from '../api.js';
import { esc, formatDateTime, badge, toast, showError, busy, qs } from '../app.js';

const STATUSES = ['SCHEDULED', 'BOARDING', 'DEPARTED', 'DELAYED', 'CANCELLED'];

export async function render(container, params) {
  container.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Afgange</h1>
        <p class="lead">Alle afgange fra CPH. Vælg et fly for at booke en plads.</p>
      </div>
      <label class="check"><input type="checkbox" id="ops-toggle"> Vis operations-panel</label>
    </div>

    <div class="card">
      <form id="filter-form" class="row" autocomplete="off">
        <div><label for="f-dest">Destination</label><input id="f-dest" placeholder="fx LHR" maxlength="3" value="${esc(params.destination || '')}"></div>
        <div><label for="f-date">Dato</label><input id="f-date" type="date" value="${esc(params.date || '')}"></div>
        <div><label for="f-status">Status</label>
          <select id="f-status">
            <option value="">Alle</option>
            ${STATUSES.map(s => `<option value="${s}" ${params.status === s ? 'selected' : ''}>${esc(labelOf(s))}</option>`).join('')}
          </select>
        </div>
        <div class="auto"><button class="btn" type="submit">Filtrér</button></div>
        <div class="auto"><button class="btn secondary" type="button" id="reset-btn">Nulstil</button></div>
        <div class="auto"><button class="btn secondary" type="button" id="refresh-btn" title="Genindlæs">⟳ Opdater</button></div>
      </form>
    </div>

    <div class="card">
      <div class="table-wrap" id="flights-table"><div class="empty"><span class="spinner"></span> Henter afgange…</div></div>
    </div>
  `;

  const form = container.querySelector('#filter-form');
  const opsToggle = container.querySelector('#ops-toggle');
  const tableHost = container.querySelector('#flights-table');
  let flights = [];

  async function load() {
    tableHost.innerHTML = '<div class="empty"><span class="spinner"></span> Henter afgange…</div>';
    const filter = {};
    const dest = container.querySelector('#f-dest').value.trim();
    const date = container.querySelector('#f-date').value;
    const status = container.querySelector('#f-status').value;
    if (dest) filter.destination = dest.toUpperCase();
    if (date) filter.date = date;
    if (status) filter.status = status;
    try {
      flights = await flightApi.flights(filter);
      draw();
    } catch (err) {
      tableHost.innerHTML = `<div class="alert error">${esc(err.message)}</div>`;
      showError(err);
    }
  }

  function draw() {
    const ops = opsToggle.checked;
    if (!flights.length) {
      tableHost.innerHTML = '<div class="empty">Ingen afgange matcher filteret.</div>';
      return;
    }
    tableHost.innerHTML = `
      <table>
        <thead><tr>
          <th>Fly</th><th>Flyselskab</th><th>Destination</th><th>Afgang</th><th>Gate</th><th>Status</th>
          <th class="num">Ledige sæder</th><th></th>${ops ? '<th class="ops-cell">Operations</th>' : ''}
        </tr></thead>
        <tbody>
          ${flights.map(f => `
            <tr data-id="${esc(f.id)}">
              <td><strong class="mono">${esc(f.flightNumber)}</strong></td>
              <td>${esc(f.airline?.name || '')} <span class="muted small">(${esc(f.airline?.iataCode || '')})</span></td>
              <td>${esc(f.origin)} → <strong>${esc(f.destination)}</strong></td>
              <td>${esc(formatDateTime(f.scheduledDeparture))}</td>
              <td class="mono">${esc(f.gate || '–')}</td>
              <td>${badge(f.status)}</td>
              <td class="num">${esc(f.availableSeatCount)}</td>
              <td>
                <a class="btn sm ${bookable(f) ? '' : 'secondary'}" href="#/book${qs({ flightId: f.id })}"
                   ${bookable(f) ? '' : 'aria-disabled="true" tabindex="-1" onclick="return false"'}>Book</a>
              </td>
              ${ops ? `
              <td class="ops-cell">
                <div class="row">
                  <select class="ops-status" ${f.status === 'CANCELLED' ? 'disabled' : ''}>
                    ${STATUSES.map(s => `<option value="${s}" ${s === f.status ? 'selected' : ''}>${esc(labelOf(s))}</option>`).join('')}
                  </select>
                  <button class="btn sm ops-status-btn" ${f.status === 'CANCELLED' ? 'disabled' : ''}>Opdater status</button>
                </div>
                <div class="row" style="margin-top:6px">
                  <input class="ops-gate" placeholder="Gate" value="${esc(f.gate || '')}" maxlength="10">
                  <button class="btn sm secondary ops-gate-btn">Skift gate</button>
                </div>
              </td>` : ''}
            </tr>`).join('')}
        </tbody>
      </table>`;

    if (ops) {
      tableHost.querySelectorAll('tr[data-id]').forEach(tr => {
        const id = tr.dataset.id;
        const flight = flights.find(f => String(f.id) === String(id));
        tr.querySelector('.ops-status-btn').addEventListener('click', (e) => busy(e.currentTarget, async () => {
          const status = tr.querySelector('.ops-status').value;
          if (status === flight.status) return;
          if (status === 'CANCELLED' && !confirm(`Aflys fly ${flight.flightNumber}? Alle bookinger annulleres og betalinger refunderes.`)) return;
          try {
            const updated = await flightApi.updateFlightStatus(id, status);
            replace(updated);
            toast(`${updated.flightNumber} er nu ${labelOf(updated.status).toLowerCase()}`, 'success');
          } catch (err) { showError(err); }
        }));
        tr.querySelector('.ops-gate-btn').addEventListener('click', (e) => busy(e.currentTarget, async () => {
          const gate = tr.querySelector('.ops-gate').value.trim();
          if (!gate) { toast('Angiv en gate', 'error'); return; }
          try {
            const updated = await flightApi.updateGate(id, gate);
            replace(updated);
            toast(`${updated.flightNumber}: gate ændret til ${updated.gate}`, 'success');
          } catch (err) { showError(err); }
        }));
      });
    }
  }

  function replace(updated) {
    const i = flights.findIndex(f => String(f.id) === String(updated.id));
    if (i >= 0) flights[i] = updated;
    draw();
  }

  form.addEventListener('submit', (e) => { e.preventDefault(); load(); });
  container.querySelector('#reset-btn').addEventListener('click', () => { form.reset(); load(); });
  container.querySelector('#refresh-btn').addEventListener('click', load);
  opsToggle.addEventListener('change', draw);

  await load();
}

function bookable(f) {
  return f.status !== 'CANCELLED' && f.status !== 'DEPARTED' && Number(f.availableSeatCount) > 0;
}

function labelOf(s) {
  return { SCHEDULED: 'Planlagt', BOARDING: 'Boarding', DEPARTED: 'Afgået', DELAYED: 'Forsinket', CANCELLED: 'Aflyst' }[s] || s;
}
