// Entry point: Keycloak login, hash router + shared UI helpers used by all pages.
import { initAuth, isLoggedIn, login, logout, username, roles, onAuthChange } from './auth.js';
import * as departures from './pages/departures.js';
import * as book from './pages/book.js';
import * as payment from './pages/payment.js';
import * as myBooking from './pages/myBooking.js';
import * as baggage from './pages/baggage.js';
import * as shops from './pages/shops.js';

const routes = {
  'departures': departures,
  'book': book,
  'payment': payment,
  'my-booking': myBooking,
  'baggage': baggage,
  'shops': shops,
};
const DEFAULT_ROUTE = 'departures';
/**
 * Pages that need a logged-in user: their mutations require PASSENGER or OPERATIONS, and "Min booking" reads the
 * booking overview, which contains payment and baggage data. Departures and shops stay public.
 */
const LOGIN_REQUIRED = new Set(['book', 'payment', 'baggage', 'my-booking']);

// ------------------------------------------------------------------ helpers
export function esc(v) {
  return String(v ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

const dtf = new Intl.DateTimeFormat('da-DK', { dateStyle: 'short', timeStyle: 'short' });
const df = new Intl.DateTimeFormat('da-DK', { dateStyle: 'medium' });
const tf = new Intl.DateTimeFormat('da-DK', { timeStyle: 'short' });

export function formatDateTime(iso) {
  if (!iso) return '–';
  const d = new Date(iso);
  return isNaN(d) ? String(iso) : dtf.format(d);
}
export function formatDate(iso) {
  if (!iso) return '–';
  const d = new Date(iso);
  return isNaN(d) ? String(iso) : df.format(d);
}
export function formatTime(iso) {
  if (!iso) return '–';
  const d = new Date(iso);
  return isNaN(d) ? String(iso) : tf.format(d);
}
export function formatMoney(amount, currency = 'DKK') {
  const n = Number(amount);
  if (!isFinite(n)) return '–';
  return new Intl.NumberFormat('da-DK', { style: 'currency', currency, minimumFractionDigits: 2 }).format(n);
}

const STATUS = {
  // flights
  SCHEDULED: ['Planlagt', 'blue'], BOARDING: ['Boarding', 'green'], DEPARTED: ['Afgået', 'gray'],
  DELAYED: ['Forsinket', 'orange'], CANCELLED: ['Aflyst', 'red'],
  // bookings
  PENDING_PAYMENT: ['Afventer betaling', 'orange'], CONFIRMED: ['Bekræftet', 'green'], CHECKED_IN: ['Checket ind', 'teal'],
  // payments
  PENDING: ['Afventer', 'orange'], COMPLETED: ['Gennemført', 'green'], FAILED: ['Afvist', 'red'], REFUNDED: ['Refunderet', 'purple'],
  // baggage
  REGISTERED: ['Registreret', 'blue'], SECURITY: ['Sikkerhedskontrol', 'orange'], LOADED: ['Lastet', 'green'],
  IN_TRANSIT: ['Undervejs', 'teal'], ARRIVED: ['Ankommet', 'green'], LOST: ['Bortkommet', 'red'],
  // baggage type / shop category
  CHECKED: ['Indchecket', 'blue'], CABIN: ['Håndbagage', 'gray'], SPECIAL: ['Special', 'purple'],
  FOOD: ['Mad & drikke', 'orange'], RETAIL: ['Butik', 'blue'], DUTY_FREE: ['Tax free', 'purple'],
  SERVICE: ['Service', 'gray'], LOUNGE: ['Lounge', 'teal'],
};
export function statusLabel(code) { return (STATUS[code] || [code])[0]; }

/** booking-service's cancellation reasons (English, part of the event contract) in Danish for the pages. */
export function cancellationText(reason) {
  if (!reason) return '';
  const timeout = /^Payment not received within (\d+) (minute|second)s?$/.exec(reason);
  if (timeout) {
    const one = timeout[1] === '1';
    const unit = timeout[2] === 'minute' ? (one ? 'minut' : 'minutter') : (one ? 'sekund' : 'sekunder');
    return `Ikke betalt inden for ${timeout[1]} ${unit}`;
  }
  if (reason === 'Cancelled by passenger') return 'Annulleret af passageren';
  if (reason === 'Flight cancelled') return 'Flyet er aflyst';
  if (reason.startsWith('Payment failed')) return 'Betalingen blev afvist' + reason.slice('Payment failed'.length);
  return reason;
}
export function badge(code) {
  const [label, color] = STATUS[code] || [code, 'gray'];
  return `<span class="badge ${color}" title="${esc(code)}">${esc(label)}</span>`;
}

export function toast(message, type = 'info', code = null, ms = 5000) {
  const host = document.getElementById('toasts');
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.innerHTML = esc(message) + (code ? `<span class="code">${esc(code)}</span>` : '');
  host.appendChild(t);
  setTimeout(() => t.remove(), ms);
}
/** Show any thrown error as a toast. */
export function showError(err) {
  const msg = err && err.message ? err.message : 'Ukendt fejl';
  const code = err && err.code ? err.code : null;
  toast(msg, 'error', code, 7000);
  console.error(err);
}

/** Small shared state between pages (survives reload within the tab). */
export const state = {
  get bookingRef() { try { return sessionStorage.getItem('bookingRef') || ''; } catch (_) { return ''; } },
  set bookingRef(v) { try { v ? sessionStorage.setItem('bookingRef', v) : sessionStorage.removeItem('bookingRef'); } catch (_) { /* ignore */ } },
};

export function navigate(hash) { location.hash = hash; }

export function qs(params) {
  const p = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '');
  return p.length ? '?' + p.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&') : '';
}

/** Runs an async action while disabling a button and showing a spinner. */
export async function busy(button, fn) {
  const old = button.innerHTML;
  button.disabled = true;
  button.innerHTML = `<span class="spinner"></span> ${old}`;
  try { return await fn(); }
  finally { button.disabled = false; button.innerHTML = old; }
}

export const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// ------------------------------------------------------------------- router
function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?');
  const params = Object.fromEntries(new URLSearchParams(query).entries());
  return { path: path || DEFAULT_ROUTE, params };
}

let currentRender = 0;
async function render() {
  const { path, params } = parseHash();
  const page = routes[path];
  if (!page) { navigate('#/' + DEFAULT_ROUTE); return; }

  document.querySelectorAll('#main-nav a').forEach(a =>
    a.classList.toggle('active', a.dataset.route === path));

  const container = document.getElementById('app');
  const id = ++currentRender;
  if (LOGIN_REQUIRED.has(path) && !isLoggedIn()) {
    // Off to Keycloak; the login lands on exactly this URL (hash included) again.
    container.innerHTML = '<div class="empty"><span class="spinner"></span> Denne side kræver login – sender dig til login…</div>';
    login();
    return;
  }
  container.innerHTML = '<div class="empty"><span class="spinner"></span> Indlæser…</div>';
  try {
    await page.render(container, params);
  } catch (err) {
    if (id !== currentRender) return;
    container.innerHTML = `<div class="alert error">Siden kunne ikke indlæses: ${esc(err.message || err)}</div>`;
    showError(err);
  }
}

// ---------------------------------------------------------------- login UI
function renderAuthNav() {
  const host = document.getElementById('auth-nav');
  if (!host) return;
  if (isLoggedIn()) {
    const roleBadges = roles().filter(r => r === 'PASSENGER' || r === 'OPERATIONS')
      .map(r => `<span class="badge ${r === 'OPERATIONS' ? 'purple' : 'blue'}">${esc(r)}</span>`).join(' ');
    host.innerHTML = `<span class="user" id="auth-user">${esc(username())} ${roleBadges}</span>
      <button class="btn sm secondary" id="logout-btn" type="button">Log ud</button>`;
    host.querySelector('#logout-btn').addEventListener('click', () => logout());
  } else {
    host.innerHTML = '<button class="btn sm" id="login-btn" type="button">Log ind</button>';
    host.querySelector('#login-btn').addEventListener('click', () => login());
  }
}

// Module scripts run after the document is parsed, so the DOM is ready here. The silent SSO check must finish
// before the first render, otherwise a refreshed page would briefly look logged out.
async function main() {
  await initAuth();
  renderAuthNav();
  onAuthChange(renderAuthNav);
  window.addEventListener('hashchange', render);
  await render();
}
main();
