// Book: choose flight -> passenger details -> seat map -> createBooking (PENDING_PAYMENT) -> payment page.
import { flightApi, bookingApi } from '../api.js';
import { esc, formatDateTime, formatMoney, badge, toast, showError, busy, navigate, state, qs } from '../app.js';

export async function render(container, params) {
  container.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Book en plads</h1>
        <p class="lead">Vælg fly og sæde, indtast passageroplysninger og bekræft. Bookingen holdes indtil betaling.</p>
      </div>
    </div>

    <div class="grid-2">
      <div class="stack">
        <div class="card">
          <h2>1. Vælg fly</h2>
          <div class="field">
            <label for="flight-select">Afgang</label>
            <select id="flight-select"><option value="">Henter afgange…</option></select>
          </div>
          <div id="flight-info" class="summary-box hidden"></div>
        </div>

        <div class="card">
          <h2>2. Passager</h2>
          <form id="passenger-form" autocomplete="on">
            <div class="grid-2">
              <div class="field"><label for="p-first">Fornavn</label><input id="p-first" name="firstName" required maxlength="100"></div>
              <div class="field"><label for="p-last">Efternavn</label><input id="p-last" name="lastName" required maxlength="100"></div>
            </div>
            <div class="field"><label for="p-email">E-mail</label><input id="p-email" name="email" type="email" required maxlength="200"></div>
            <div class="grid-2">
              <div class="field"><label for="p-passport">Pasnummer</label><input id="p-passport" name="passportNumber" required maxlength="20" placeholder="fx 200012345"></div>
              <div class="field"><label for="p-dob">Fødselsdato</label><input id="p-dob" name="dateOfBirth" type="date"></div>
            </div>
          </form>
        </div>
      </div>

      <div class="stack">
        <div class="card">
          <h2>3. Vælg sæde</h2>
          <div id="seatmap-host"><div class="empty">Vælg et fly for at se sædekortet.</div></div>
          <div class="legend">
            <span><i class="sw" style="background:#fef3c7;border-color:#fcd34d"></i>Business (2,5× basispris)</span>
            <span><i class="sw" style="background:#e0f2fe;border-color:#7dd3fc"></i>Economy</span>
            <span><i class="sw" style="background:#e5e7eb;border-color:#d1d5db"></i>Optaget</span>
            <span><i class="sw" style="background:#1f5fbf;border-color:#1f5fbf"></i>Valgt</span>
          </div>
        </div>

        <div class="card">
          <h2>4. Bekræft</h2>
          <div id="summary" class="summary-box muted">Intet sæde valgt.</div>
          <div style="margin-top:12px">
            <button class="btn" id="confirm-btn" disabled>Bekræft booking</button>
          </div>
          <div id="result" style="margin-top:12px"></div>
        </div>
      </div>
    </div>
  `;

  const select = container.querySelector('#flight-select');
  const flightInfo = container.querySelector('#flight-info');
  const seatHost = container.querySelector('#seatmap-host');
  const summary = container.querySelector('#summary');
  const confirmBtn = container.querySelector('#confirm-btn');
  const result = container.querySelector('#result');
  const form = container.querySelector('#passenger-form');

  let flight = null;
  let selectedSeat = null;

  // ---- flights dropdown
  try {
    const flights = await flightApi.flights({});
    const open = flights.filter(f => f.status !== 'CANCELLED' && f.status !== 'DEPARTED');
    select.innerHTML = '<option value="">– vælg afgang –</option>' + open.map(f =>
      `<option value="${esc(f.id)}">${esc(f.flightNumber)} · ${esc(f.destination)} · ${esc(formatDateTime(f.scheduledDeparture))} · ${esc(f.availableSeatCount)} ledige</option>`
    ).join('');
    if (params.flightId && open.some(f => String(f.id) === String(params.flightId))) {
      select.value = String(params.flightId);
      await loadFlight(params.flightId);
    } else if (params.flightId) {
      toast('Det valgte fly kan ikke bookes (aflyst eller afgået).', 'error');
    }
  } catch (err) {
    select.innerHTML = '<option value="">Kunne ikke hente afgange</option>';
    showError(err);
  }

  select.addEventListener('change', () => {
    selectedSeat = null;
    if (select.value) loadFlight(select.value);
    else { flight = null; flightInfo.classList.add('hidden'); seatHost.innerHTML = '<div class="empty">Vælg et fly for at se sædekortet.</div>'; updateSummary(); }
  });

  async function loadFlight(id) {
    seatHost.innerHTML = '<div class="empty"><span class="spinner"></span> Henter sædekort…</div>';
    try {
      flight = await flightApi.flightWithSeats(id);
      if (!flight) throw Object.assign(new Error('Flyet blev ikke fundet'), { code: 'NOT_FOUND' });
      flightInfo.classList.remove('hidden');
      flightInfo.innerHTML = `
        <dl class="kv">
          <dt>Fly</dt><dd><strong>${esc(flight.flightNumber)}</strong> · ${esc(flight.airline?.name || '')}</dd>
          <dt>Rute</dt><dd>${esc(flight.origin)} → ${esc(flight.destination)}</dd>
          <dt>Afgang</dt><dd>${esc(formatDateTime(flight.scheduledDeparture))} · Gate ${esc(flight.gate || '–')}</dd>
          <dt>Status</dt><dd>${badge(flight.status)}</dd>
          <dt>Fly-type</dt><dd>${esc(flight.aircraft?.model || '')} (${esc(flight.aircraft?.totalSeats)} sæder)</dd>
          <dt>Basispris</dt><dd>${esc(formatMoney(flight.basePrice, flight.currency))}</dd>
        </dl>`;
      drawSeats();
      updateSummary();
    } catch (err) {
      seatHost.innerHTML = `<div class="alert error">${esc(err.message)}</div>`;
      showError(err);
    }
  }

  function drawSeats() {
    const rows = new Map();
    for (const s of flight.seats || []) {
      const m = /^(\d+)([A-Za-z]+)$/.exec(s.seatNumber);
      const row = m ? Number(m[1]) : 0;
      const letter = m ? m[2].toUpperCase() : s.seatNumber;
      if (!rows.has(row)) rows.set(row, new Map());
      rows.get(row).set(letter, s);
    }
    const letters = ['A', 'B', 'C', 'D', 'E', 'F'];
    const rowNumbers = [...rows.keys()].sort((a, b) => a - b);
    seatHost.innerHTML = `
      <div class="seatmap" role="group" aria-label="Sædekort">
        <div class="seat-row"><span class="rownum"></span>${letters.map((l, i) =>
          `${i === 3 ? '<span class="aisle"></span>' : ''}<span style="width:34px;text-align:center" class="small muted">${l}</span>`).join('')}</div>
        ${rowNumbers.map(r => `
          <div class="seat-row">
            <span class="rownum">${r}</span>
            ${letters.map((l, i) => {
              const s = rows.get(r).get(l);
              const aisle = i === 3 ? '<span class="aisle"></span>' : '';
              if (!s) return `${aisle}<span style="width:34px"></span>`;
              const cls = `seat ${s.seatClass.toLowerCase()} ${s.isAvailable ? '' : 'taken'} ${selectedSeat && selectedSeat.seatNumber === s.seatNumber ? 'selected' : ''}`;
              return `${aisle}<button type="button" class="${cls}" data-seat="${esc(s.seatNumber)}" ${s.isAvailable ? '' : 'disabled'}
                        title="${esc(s.seatNumber)} · ${esc(s.seatClass)} · ${esc(formatMoney(s.price, flight.currency))}">${esc(s.seatNumber)}</button>`;
            }).join('')}
          </div>`).join('')}
      </div>`;
    seatHost.querySelectorAll('button.seat:not(.taken)').forEach(b => b.addEventListener('click', () => {
      selectedSeat = (flight.seats || []).find(s => s.seatNumber === b.dataset.seat) || null;
      seatHost.querySelectorAll('button.seat').forEach(x => x.classList.toggle('selected', x.dataset.seat === b.dataset.seat));
      updateSummary();
    }));
  }

  function updateSummary() {
    if (!flight || !selectedSeat) {
      summary.className = 'summary-box muted';
      summary.textContent = flight ? 'Vælg et sæde på kortet.' : 'Intet fly valgt.';
      confirmBtn.disabled = true;
      return;
    }
    summary.className = 'summary-box';
    summary.innerHTML = `
      <dl class="kv">
        <dt>Fly</dt><dd>${esc(flight.flightNumber)} → ${esc(flight.destination)}, ${esc(formatDateTime(flight.scheduledDeparture))}</dd>
        <dt>Sæde</dt><dd><strong>${esc(selectedSeat.seatNumber)}</strong> ${badge(selectedSeat.seatClass)}</dd>
        <dt>Pris</dt><dd><strong>${esc(formatMoney(selectedSeat.price, flight.currency))}</strong></dd>
      </dl>`;
    confirmBtn.disabled = false;
  }

  confirmBtn.addEventListener('click', () => busy(confirmBtn, async () => {
    if (!form.reportValidity()) return;
    const fd = new FormData(form);
    const passenger = {
      firstName: fd.get('firstName').trim(),
      lastName: fd.get('lastName').trim(),
      email: fd.get('email').trim(),
      passportNumber: fd.get('passportNumber').trim(),
      dateOfBirth: fd.get('dateOfBirth') || null,
    };
    result.innerHTML = '';
    try {
      const booking = await bookingApi.createBooking(flight.id, selectedSeat.seatNumber, passenger);
      state.bookingRef = booking.bookingReference;
      result.innerHTML = `
        <div class="alert success">
          Booking oprettet! Reference: <span class="ref">${esc(booking.bookingReference)}</span><br>
          Sæde ${esc(booking.seatNumber)} på ${esc(booking.flightNumber)} · ${esc(formatMoney(booking.price, booking.currency))} · ${badge(booking.status)}<br>
          <span class="small">Sender dig videre til betaling…</span>
        </div>`;
      toast(`Booking ${booking.bookingReference} oprettet – betal for at bekræfte`, 'success');
      setTimeout(() => navigate('#/payment' + qs({ ref: booking.bookingReference })), 1200);
    } catch (err) {
      if (err.code === 'SEAT_TAKEN') {
        result.innerHTML = `<div class="alert error">Sædet ${esc(selectedSeat.seatNumber)} er desværre lige blevet taget. Vælg et andet sæde.</div>`;
        selectedSeat = null;
        await loadFlight(flight.id);
      } else if (err.code === 'VALIDATION_ERROR') {
        result.innerHTML = `<div class="alert error">Tjek dine oplysninger: ${esc(err.message)}</div>`;
      } else {
        result.innerHTML = `<div class="alert error">${esc(err.message)} <span class="small mono">(${esc(err.code)})</span></div>`;
      }
      showError(err);
    }
  }));
}
