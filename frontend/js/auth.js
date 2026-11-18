// Login via Keycloak (OpenID Connect, Authorization Code + PKCE) - dev plan DP-03.
//
// One Keycloak adapter for the whole SPA. app.js awaits initAuth() before the first render; pages only use
// the small API below (isLoggedIn/hasRole/login/logout) and api.js attaches the token to every request.
// Keycloak URL/realm/client come from js/config.js (window.AIRPORT_CONFIG) - in Kubernetes the ConfigMap
// version points at the Ingress path /auth.
import Keycloak from './vendor/keycloak.js';

const cfg = window.AIRPORT_CONFIG || {};
const listeners = new Set();
let kc = null;

/** Restores an existing session silently (page refresh keeps the login). Never forces a login. */
export async function initAuth() {
  if (!cfg.KEYCLOAK_URL) {
    console.warn('KEYCLOAK_URL is not configured (js/config.js) - login is disabled');
    return;
  }
  kc = new Keycloak({
    url: cfg.KEYCLOAK_URL,
    realm: cfg.KEYCLOAK_REALM || 'airport',
    clientId: cfg.KEYCLOAK_CLIENT_ID || 'airport-frontend',
  });
  kc.onAuthLogout = () => notify();
  kc.onTokenExpired = () => kc.updateToken(30).catch(() => { /* reported by the next getToken() */ });
  try {
    await kc.init({
      onLoad: 'check-sso',                                               // silent SSO check, no redirect
      silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html`,
      pkceMethod: 'S256',
      responseMode: 'query',      // callback params arrive as ?code=..., so the #/route hash survives the round trip
      checkLoginIframe: false,    // depends on third-party cookies; token refresh covers session expiry instead
    });
  } catch (err) {
    console.warn('Keycloak init failed - continuing logged out', err);
    kc = null;
  }
  notify();
}

export function isLoggedIn() { return !!(kc && kc.authenticated); }
export function username() { return kc?.tokenParsed?.preferred_username || null; }
export function email() { return kc?.tokenParsed?.email || null; }
export function roles() { return kc?.tokenParsed?.realm_access?.roles || []; }
export function hasRole(role) { return !!(kc && kc.hasRealmRole(role)); }

/** Redirects to the Keycloak login page; Keycloak sends the browser back to `redirectUri` (default: this page). */
export function login(redirectUri = location.href) {
  return kc ? kc.login({ redirectUri }) : Promise.resolve();
}

/** Ends the Keycloak session and drops the tokens; lands on the start page afterwards. */
export function logout() {
  return kc ? kc.logout({ redirectUri: `${location.origin}/` }) : Promise.resolve();
}

/** A valid access token (refreshed when it expires within 30 s), or null when logged out. */
export async function getToken() {
  if (!isLoggedIn()) return null;
  try {
    await kc.updateToken(30);
  } catch (err) {
    console.warn('Token refresh failed - treating as logged out', err);
    notify();
    return null;
  }
  return kc.token;
}

/** Called after init, logout and failed refresh; returns an unsubscribe function. */
export function onAuthChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function notify() {
  listeners.forEach(fn => { try { fn(); } catch (err) { console.error(err); } });
}
