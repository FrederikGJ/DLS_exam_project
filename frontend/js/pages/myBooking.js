// Min booking: look up by reference -> booking + payments + baggage, check-in and cancel.
import { bookingApi, paymentApi, baggageApi } from '../api.js';
import { esc, formatDateTime, formatDate, formatMoney, badge, toast, showError, busy, state, qs } from '../app.js';

export async function render(container, params) {
  const initialRef = (params.ref || state.bookingRef || '').toUpperCase();
  container.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Min booking</h1>
        <p class="lead">Slå din booking op med referencen fra bekræftelsen.</p>
      </div>
    </div>
    <div class="card">
      <form id="ref-form" class="row">
        <div><label for="ref">Bookingreference</label>
          <input id="ref" maxlength="6" placeholder="fx K7Q2ZP" value="${esc(initialRef)}" style="text-transform:uppercase" required></div>
        <div class="auto"><button class="btn" type="submit">Slå op</button></div>
      </form>
    </div>
    <div id="host"></div>
  `;

  const host = container.querySelector('#host');
  const refInput = container.querySelector('#ref');
  container.querySelector('#ref-form').addEventListener('submit', (e) => { e.preventDefault(); load(refInput.value.trim().toUpperCase()); });
  if (initialRef) await load(initialRef);

  async function load(ref) {
    if (!ref) return;
    host.innerHTML = '<div class="card"><div class="empty"><span class="spinner"></span> Henter booking…</div></div>';
    let booking;
    try {
      booking = await bookingApi.bookingByReference(ref);
    } catch (err) {
      host.innerHTML = `<div class="card"><div class="alert error">${esc(err.message)}</div></div>`;
      showError(err);
      return;
    }
    if (!booking) {
      host.innerHTML = `<div class="card"><div class="alert error">Ingen booking med reference <strong>${esc(ref)}</strong> (NOT_FOUND).</div></div>`;
      return;
    }
    state.bookingRef = booking.bookingReference;
    draw(booking);

    // payments and baggage live in other services - load independently so one failure doesn't hide the rest
    const [payments, baggage] = await Promise.allSettled([
      paymentApi.paymentsByBooking(booking.bookingReference),
      baggageApi.baggageByBooking(booking.bookingReference),
    ]);
    drawPayments(payments);
    drawBaggage(baggage, booking);
  }

  function draw(b) {
    const canCheckIn = b.status === 'CONFIRMED';
    const canCancel = b.status !== 'CANCELLED';
    host.innerHTML = `
      <div class="grid-2">
        <div class="card">
          <div class="card-head"><h2>Booking</h2>${badge(b.status)}</div>
          <dl class="kv">
            <dt>Reference</dt><dd><span class="ref">${esc(b.bookingReference)}</span></dd>
            <dt>Fly</dt><dd><strong>${esc(b.flightNumber)}</strong> ${b.flightStatus ? badge(b.flightStatus) : ''}</dd>
            <dt>Afgang</dt><dd>${esc(formatDateTime(b.departureTime))}</dd>
            <dt>Gate</dt><dd class="mono">${esc(b.gate || '–')}</dd>
            <dt>Sæde</dt><dd class="mono">${esc(b.seatNumber)}</dd>
            <dt>Pris</dt><dd>${esc(formatMoney(b.price, b.currency))}</dd>
            <dt>Oprettet</dt><dd>${esc(formatDateTime(b.createdAt))}</dd>
            <dt>Opdateret</dt><dd>${esc(formatDateTime(b.updatedAt))}</dd>
          </dl>
          <div class="row" style="margin-top:14px">
            <button class="btn auto" id="checkin-btn" ${canCheckIn ? '' : 'disabled'}>Check ind</button>
            <button class="btn danger auto" id="cancel-btn" ${canCancel ? '' : 'disabled'}>Annuller booking</button>
            ${b.status === 'PENDING_PAYMENT' ? `<a class="btn secondary auto" href="#/payment${qs({ ref: b.bookingReference })}">Gå til betaling</a>` : ''}
            <a class="btn secondary auto" href="#/baggage${qs({ ref: b.bookingReference })}">Bagage</a>
          </div>
          <div id="action-result" style="margin-top:10px"></div>
        </div>
        <div class="card">
          <h2>Passager</h2>
          <dl class="kv">
            <dt>Navn</dt><dd>${esc(b.passenger?.firstName)} ${esc(b.passenger?.lastName)}</dd>
            <dt>E-mail</dt><dd>${esc(b.passenger?.email)}</dd>
            <dt>Pasnummer</dt><dd class="mono">${esc(b.passenger?.passportNumber)}</dd>
            <dt>Fødselsdato</dt><dd>${esc(b.passenger?.dateOfBirth ? formatDate(b.passenger.dateOfBirth) : '–')}</dd>
          </dl>
        </div>
      </div>
      <div class="card"><div class="card-head"><h2>Betalinger</h2></div><div id="payments"><span class="spinner"></span></div></div>
      <div class="card"><div class="card-head"><h2>Bagage</h2><a class="btn sm" href="#/baggage${qs({ ref: b.bookingReference })}">Registrér bagage</a></div><div id="baggage"><span class="spinner"></span></div></div>
    `;

    host.querySelector('#checkin-btn').addEventListener('click', (e) => busy(e.currentTarget, async () => {
      try {
        const updated = await bookingApi.checkIn(b.bookingReference);
        toast(`Du er nu checket ind på ${updated.flightNumber}`, 'success');
        await load(updated.bookingReference);
      } catch (err) { showError(err); actionError(err); }
    }));
    host.querySelector('#cancel-btn').addEventListener('click', (e) => busy(e.currentTarget, async () => {
      if (!confirm(`Annuller booking ${b.bookingReference}? Eventuel betaling refunderes automatisk.`)) return;
      try {
        const updated = await bookingApi.cancelBooking(b.bookingReference);
        toast(`Booking ${updated.bookingReference} er annulleret`, 'success');
        await load(updated.bookingReference);
      } catch (err) { showError(err); actionError(err); }
    }));
  }

  function actionError(err) {
    const el = host.querySelector('#action-result');
    if (el) el.innerHTML = `<div class="alert error">${esc(err.message)} <span class="mono small">(${esc(err.code)})</span></div>`;
  }

  function drawPayments(res) {
    const el = host.querySelector('#payments');
    if (!el) return;
    if (res.status === 'rejected') { el.innerHTML = `<div class="alert warn small">${esc(res.reason?.message || 'Kunne ikke hente betalinger')}</div>`; return; }
    const payments = res.value || [];
    if (!payments.length) { el.innerHTML = '<div class="empty">Ingen betalinger registreret.</div>'; return; }
    el.innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Tidspunkt</th><th>Beløb</th><th>Kort</th><th>Status</th><th>Årsag</th></tr></thead>
      <tbody>${payments.map(p => `<tr>
        <td>${esc(formatDateTime(p.createdAt))}</td><td>${esc(formatMoney(p.amount, p.currency))}</td>
        <td class="mono">•••• ${esc(p.cardLast4)}</td><td>${badge(p.status)}</td><td>${esc(p.failureReason || '')}</td></tr>`).join('')}
      </tbody></table></div>`;
  }

  function drawBaggage(res, booking) {
    const el = host.querySelector('#baggage');
    if (!el) return;
    if (res.status === 'rejected') { el.innerHTML = `<div class="alert warn small">${esc(res.reason?.message || 'Kunne ikke hente bagage')}</div>`; return; }
    const bags = res.value || [];
    if (!bags.length) {
      const hint = ['CONFIRMED', 'CHECKED_IN'].includes(booking.status) ? '' : ' Bagage kan først registreres når bookingen er bekræftet.';
      el.innerHTML = `<div class="empty">Ingen bagage registreret.${esc(hint)}</div>`;
      return;
    }
    el.innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Tag</th><th>Type</th><th>Vægt</th><th>Status</th><th>Sidste lokation</th><th>Opdateret</th></tr></thead>
      <tbody>${bags.map(bg => `<tr>
        <td class="mono"><strong>${esc(bg.tagNumber)}</strong></td><td>${badge(bg.type)}</td><td>${esc(Number(bg.weightKg).toLocaleString('da-DK'))} kg</td>
        <td>${badge(bg.status)}</td><td>${esc(bg.lastLocation || '–')}</td><td>${esc(formatDateTime(bg.updatedAt))}</td></tr>`).join('')}
      </tbody></table></div>`;
  }
}
