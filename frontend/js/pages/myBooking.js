// Min booking: look up by reference -> booking + payments + baggage, check-in and cancel.
// Everything on the page comes from ONE query, bookingOverview, which booking-service answers from its read model
// booking_overview (CQRS): payments and baggage are projected there from payment-service's and baggage-service's
// events, so the page no longer calls those two services. Without a reference the page lists myBookings instead.
import { bookingApi } from '../api.js';
import { esc, formatDateTime, formatDate, formatMoney, badge, toast, showError, busy, state, qs, sleep, cancellationText } from '../app.js';

export async function render(container, params) {
  const showMine = params.mine === '1';
  const initialRef = showMine ? '' : (params.ref || state.bookingRef || '').toUpperCase();
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
        <div class="auto"><a class="btn secondary" href="#/my-booking?mine=1">Alle mine bookinger</a></div>
      </form>
    </div>
    <div id="host"></div>
  `;

  const host = container.querySelector('#host');
  const refInput = container.querySelector('#ref');
  container.querySelector('#ref-form').addEventListener('submit', (e) => { e.preventDefault(); load(refInput.value.trim().toUpperCase()); });
  if (initialRef) await load(initialRef);
  else await listMine();

  async function listMine() {
    host.innerHTML = '<div class="card"><div class="empty"><span class="spinner"></span> Henter dine bookinger…</div></div>';
    let mine;
    try {
      mine = await bookingApi.myBookings();
    } catch (err) {
      host.innerHTML = `<div class="card"><div class="alert error">${esc(err.message)}</div></div>`;
      showError(err);
      return;
    }
    if (!mine.length) {
      host.innerHTML = '<div class="card"><div class="empty">Du har ingen bookinger med e-mailen fra dit login.</div></div>';
      return;
    }
    host.innerHTML = `<div class="card"><div class="card-head"><h2>Dine bookinger</h2></div><div class="table-wrap"><table>
      <thead><tr><th>Reference</th><th>Fly</th><th>Afgang</th><th>Sæde</th><th>Status</th></tr></thead>
      <tbody>${mine.map(b => `<tr>
        <td><a class="ref" href="#/my-booking${qs({ ref: b.bookingReference })}">${esc(b.bookingReference)}</a></td>
        <td><strong>${esc(b.flightNumber)}</strong></td><td>${esc(formatDateTime(b.departureTime))}</td>
        <td class="mono">${esc(b.seatNumber)}</td><td>${badge(b.status)}</td></tr>`).join('')}
      </tbody></table></div></div>`;
  }

  /** One call: the booking overview (booking, passenger, payments, baggage). Returns it, or null when not found. */
  async function load(ref) {
    if (!ref) return null;
    host.innerHTML = '<div class="card"><div class="empty"><span class="spinner"></span> Henter booking…</div></div>';
    let overview;
    try {
      overview = await bookingApi.bookingOverview(ref);
    } catch (err) {
      host.innerHTML = `<div class="card"><div class="alert error">${esc(err.message)}</div></div>`;
      showError(err);
      return null;
    }
    if (!overview) {
      host.innerHTML = `<div class="card"><div class="alert error">Ingen booking med reference <strong>${esc(ref)}</strong> (NOT_FOUND).</div></div>`;
      return null;
    }
    state.bookingRef = overview.bookingReference;
    draw(overview);
    drawPayments(overview.payments);
    drawBaggage(overview.baggage, overview);
    return overview;
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
            ${b.cancellationReason ? `<dt>Årsag</dt><dd>${esc(cancellationText(b.cancellationReason))}</dd>` : ''}
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
      <p class="muted small">Betalinger og bagage kommer fra betalings- og bagagetjenestens events og kan være op til et sekund
        bagefter. Senest opdateret ${esc(formatDateTime(b.projectedAt))}.
        <button class="btn sm secondary" id="refresh-btn" type="button">Opdatér</button></p>
    `;

    host.querySelector('#refresh-btn').addEventListener('click', () => load(b.bookingReference));

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
        const overview = await load(updated.bookingReference);
        if (overview && hasCompletedPayment(overview)) {
          // payment-service refunds when it sees booking.cancelled; the refund reaches the overview a moment later
          for (let i = 0; i < 5; i++) {
            await sleep(1000);
            const fresh = await bookingApi.bookingOverview(updated.bookingReference);
            if (!fresh || !hasCompletedPayment(fresh)) break;
          }
          await load(updated.bookingReference);
        }
      } catch (err) { showError(err); actionError(err); }
    }));
  }

  function actionError(err) {
    const el = host.querySelector('#action-result');
    if (el) el.innerHTML = `<div class="alert error">${esc(err.message)} <span class="mono small">(${esc(err.code)})</span></div>`;
  }

  function hasCompletedPayment(overview) {
    return (overview.payments || []).some(p => p.status === 'COMPLETED');
  }

  function drawPayments(payments) {
    const el = host.querySelector('#payments');
    if (!el) return;
    if (!payments.length) { el.innerHTML = '<div class="empty">Ingen betalinger registreret.</div>'; return; }
    el.innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Tidspunkt</th><th>Beløb</th><th>Kort</th><th>Status</th><th>Årsag</th></tr></thead>
      <tbody>${payments.map(p => `<tr>
        <td>${esc(formatDateTime(p.createdAt))}</td><td>${esc(formatMoney(p.amount, p.currency))}</td>
        <td class="mono">${p.cardLast4 ? `•••• ${esc(p.cardLast4)}` : '–'}</td><td>${badge(p.status)}</td><td>${esc(p.failureReason || '')}</td></tr>`).join('')}
      </tbody></table></div>`;
  }

  function drawBaggage(bags, booking) {
    const el = host.querySelector('#baggage');
    if (!el) return;
    if (!bags.length) {
      const hint = ['CONFIRMED', 'CHECKED_IN'].includes(booking.status) ? '' : ' Bagage kan først registreres når bookingen er bekræftet.';
      el.innerHTML = `<div class="empty">Ingen bagage registreret.${esc(hint)}</div>`;
      return;
    }
    el.innerHTML = `<div class="table-wrap"><table>
      <thead><tr><th>Tag</th><th>Type</th><th>Vægt</th><th>Status</th><th>Sidste lokation</th><th>Opdateret</th></tr></thead>
      <tbody>${bags.map(bg => `<tr>
        <td class="mono"><strong>${esc(bg.tagNumber)}</strong></td><td>${bg.type ? badge(bg.type) : '–'}</td><td>${bg.weightKg == null ? '–' : `${esc(Number(bg.weightKg).toLocaleString('da-DK'))} kg`}</td>
        <td>${badge(bg.status)}</td><td>${esc(bg.lastLocation || '–')}</td><td>${esc(formatDateTime(bg.updatedAt))}</td></tr>`).join('')}
      </tbody></table></div>`;
  }
}
