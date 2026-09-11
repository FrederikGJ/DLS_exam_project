// Betaling: look up a PENDING_PAYMENT booking and pay with simulated card data.
import { bookingApi, paymentApi } from '../api.js';
import { esc, formatDateTime, formatMoney, badge, toast, showError, busy, state, sleep, qs } from '../app.js';

export async function render(container, params) {
  const initialRef = (params.ref || state.bookingRef || '').toUpperCase();
  container.innerHTML = `
    <div class="page-head">
      <div>
        <h1>Betaling</h1>
        <p class="lead">Betal for din booking. Når betalingen er gennemført, bekræftes bookingen automatisk.</p>
      </div>
    </div>

    <div class="card">
      <form id="ref-form" class="row">
        <div><label for="ref">Bookingreference</label>
          <input id="ref" maxlength="6" placeholder="fx K7Q2ZP" value="${esc(initialRef)}" style="text-transform:uppercase" required></div>
        <div class="auto"><button class="btn" type="submit">Hent booking</button></div>
      </form>
    </div>

    <div id="booking-host"></div>
  `;

  const host = container.querySelector('#booking-host');
  const refForm = container.querySelector('#ref-form');
  const refInput = container.querySelector('#ref');

  refForm.addEventListener('submit', (e) => { e.preventDefault(); load(refInput.value.trim().toUpperCase()); });
  if (initialRef) await load(initialRef);

  async function load(ref) {
    if (!ref) return;
    host.innerHTML = '<div class="card"><div class="empty"><span class="spinner"></span> Henter booking…</div></div>';
    try {
      const booking = await bookingApi.bookingByReference(ref);
      if (!booking) {
        host.innerHTML = `<div class="card"><div class="alert error">Ingen booking med reference <strong>${esc(ref)}</strong>.</div></div>`;
        return;
      }
      state.bookingRef = booking.bookingReference;
      draw(booking);
    } catch (err) {
      host.innerHTML = `<div class="card"><div class="alert error">${esc(err.message)}</div></div>`;
      showError(err);
    }
  }

  function bookingCard(b) {
    return `
      <dl class="kv">
        <dt>Reference</dt><dd><span class="ref">${esc(b.bookingReference)}</span></dd>
        <dt>Passager</dt><dd>${esc(b.passenger?.firstName)} ${esc(b.passenger?.lastName)} · ${esc(b.passenger?.email)}</dd>
        <dt>Fly</dt><dd>${esc(b.flightNumber)} · ${esc(formatDateTime(b.departureTime))} · Gate ${esc(b.gate || '–')}</dd>
        <dt>Sæde</dt><dd>${esc(b.seatNumber)}</dd>
        <dt>Pris</dt><dd><strong>${esc(formatMoney(b.price, b.currency))}</strong></dd>
        <dt>Status</dt><dd>${badge(b.status)}</dd>
      </dl>`;
  }

  function draw(booking) {
    const pending = booking.status === 'PENDING_PAYMENT';
    host.innerHTML = `
      <div class="grid-2">
        <div class="card">
          <h2>Booking</h2>
          ${bookingCard(booking)}
          <p style="margin-top:12px">
            <a class="btn secondary sm" href="#/my-booking${qs({ ref: booking.bookingReference })}">Se Min booking</a>
          </p>
        </div>
        <div class="card">
          <h2>Kortbetaling <span class="muted small">(simuleret)</span></h2>
          ${pending ? `
          <form id="pay-form" autocomplete="off">
            <div class="field"><label for="card">Kortnummer</label>
              <input id="card" inputmode="numeric" placeholder="4242 4242 4242 4242" required minlength="12" maxlength="23" value="4242 4242 4242 4242"></div>
            <div class="grid-2">
              <div class="field"><label for="expiry">Udløb (MM/YY)</label><input id="expiry" placeholder="12/29" required pattern="^(0[1-9]|1[0-2])\\/\\d{2}$" value="12/29"></div>
              <div class="field"><label for="cvv">CVV</label><input id="cvv" inputmode="numeric" placeholder="123" required pattern="^\\d{3,4}$" maxlength="4" value="123"></div>
            </div>
            <div class="alert info small">Kort der slutter på <strong>0000</strong> afvises (Insufficient funds). Udløbet kort afvises (Card expired). Alle andre kort godkendes. Kortnummeret gemmes ikke – kun de sidste 4 cifre.</div>
            <div style="margin-top:12px">
              <button class="btn" type="submit" id="pay-btn">Betal ${esc(formatMoney(booking.price, booking.currency))}</button>
            </div>
          </form>` : `
          <div class="alert ${booking.status === 'CANCELLED' ? 'error' : 'success'}">
            Bookingen er ${esc(badgeText(booking.status))} og kan ikke betales${booking.status === 'CANCELLED' ? '' : ' igen'}.
          </div>`}
          <div id="pay-result" style="margin-top:12px"></div>
          <div id="pay-history" style="margin-top:12px"></div>
        </div>
      </div>`;

    loadHistory(booking.bookingReference);

    const payForm = host.querySelector('#pay-form');
    if (!payForm) return;
    payForm.addEventListener('submit', (e) => {
      e.preventDefault();
      if (!payForm.reportValidity()) return;
      busy(host.querySelector('#pay-btn'), () => pay(booking));
    });
  }

  async function pay(booking) {
    const resultEl = host.querySelector('#pay-result');
    const cardNumber = host.querySelector('#card').value.replace(/\s+/g, '');
    const expiry = host.querySelector('#expiry').value.trim();
    const cvv = host.querySelector('#cvv').value.trim();
    resultEl.innerHTML = '';
    let payment;
    try {
      payment = await paymentApi.pay(booking.bookingReference, booking.price, cardNumber, expiry, cvv);
    } catch (err) {
      resultEl.innerHTML = `<div class="alert error">${esc(err.message)} <span class="mono small">(${esc(err.code)})</span></div>`;
      showError(err);
      return;
    }

    if (payment.status === 'COMPLETED') {
      resultEl.innerHTML = `<div class="alert success">Betaling gennemført (kort •••• ${esc(payment.cardLast4)}). Venter på bekræftelse af bookingen… <span class="spinner"></span></div>`;
      toast('Betaling gennemført', 'success');
    } else {
      resultEl.innerHTML = `<div class="alert error">Betaling afvist: <strong>${esc(payment.failureReason || 'ukendt årsag')}</strong>. Bookingen annulleres… <span class="spinner"></span></div>`;
      toast(`Betaling afvist: ${payment.failureReason || ''}`, 'error', 'PAYMENT_FAILED');
    }

    // Booking status is updated asynchronously via payment.completed / payment.failed events.
    const updated = await waitForStatusChange(booking.bookingReference, 'PENDING_PAYMENT', 12);
    if (updated && updated.status !== 'PENDING_PAYMENT') {
      state.bookingRef = updated.bookingReference;
      if (updated.status === 'CONFIRMED') {
        resultEl.innerHTML = `<div class="alert success">
          Bookingen <span class="ref">${esc(updated.bookingReference)}</span> er nu ${badge(updated.status)}.<br>
          <a class="btn sm" style="margin-top:8px" href="#/my-booking${qs({ ref: updated.bookingReference })}">Gå til Min booking</a>
          <a class="btn sm secondary" style="margin-top:8px" href="#/baggage${qs({ ref: updated.bookingReference })}">Registrér bagage</a>
        </div>`;
      } else {
        resultEl.innerHTML = `<div class="alert error">Bookingen er nu ${badge(updated.status)}. ${payment.failureReason ? 'Årsag: ' + esc(payment.failureReason) : ''}</div>`;
      }
      draw(updated);
      host.querySelector('#pay-result').innerHTML = resultEl.innerHTML;
    } else {
      resultEl.innerHTML += `<div class="alert warn" style="margin-top:8px">Bookingen er endnu ikke opdateret. Prøv "Hent booking" om et øjeblik.</div>`;
    }
  }

  async function waitForStatusChange(ref, fromStatus, attempts) {
    let last = null;
    for (let i = 0; i < attempts; i++) {
      await sleep(1000);
      try {
        last = await bookingApi.bookingByReference(ref);
        if (last && last.status !== fromStatus) return last;
      } catch (_) { /* retry */ }
    }
    return last;
  }

  async function loadHistory(ref) {
    const el = host.querySelector('#pay-history');
    if (!el) return;
    try {
      const payments = await paymentApi.paymentsByBooking(ref);
      if (!payments.length) { el.innerHTML = ''; return; }
      el.innerHTML = `<h3>Tidligere betalinger</h3>
        <div class="table-wrap"><table>
          <thead><tr><th>Tidspunkt</th><th>Beløb</th><th>Kort</th><th>Status</th><th>Årsag</th></tr></thead>
          <tbody>${payments.map(p => `<tr>
            <td>${esc(formatDateTime(p.createdAt))}</td><td>${esc(formatMoney(p.amount, p.currency))}</td>
            <td class="mono">•••• ${esc(p.cardLast4)}</td><td>${badge(p.status)}</td><td>${esc(p.failureReason || '')}</td></tr>`).join('')}
          </tbody></table></div>`;
    } catch (err) {
      el.innerHTML = `<div class="alert warn small">Betalingshistorik utilgængelig: ${esc(err.message)}</div>`;
    }
  }
}

function badgeText(status) {
  return { CONFIRMED: 'bekræftet', CANCELLED: 'annulleret', CHECKED_IN: 'checket ind' }[status] || status;
}
