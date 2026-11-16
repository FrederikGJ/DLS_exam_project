// Load test for the HPA demo (ticket 13): sends GraphQL queries to shop-service through the Ingress
// and ramps the load up, holds it and ramps it down, so you can watch the pods scale out and back in.
//
//   k6 run scripts/load-shops.js
//   k6 run -e SHOP_URL=http://localhost:8090/api/shops/graphql -e VUS=40 scripts/load-shops.js
//
// Watch the autoscaler in another terminal:  kubectl -n airport get hpa shop-service -w
// See k8s/README.md, section "Autoscaling (HPA)".
import http from 'k6/http';
import { check } from 'k6';

const SHOP_URL = __ENV.SHOP_URL || 'http://localhost:8090/api/shops/graphql';
const VUS = Number(__ENV.VUS || 30);

export const options = {
  stages: [
    { duration: '30s', target: VUS },   // ramp up
    { duration: '4m', target: VUS },    // hold: 2 -> 5 pods takes ~3.5 min (1 pod/min + JVM start), then 5 pods share the load
    { duration: '15s', target: 0 },     // stop; scale-down then happens after the HPA's stabilization window
  ],
  thresholds: {
    // GraphQL answers errors with HTTP 200, so the check below inspects the body as well.
    checks: ['rate>0.99'],
  },
};

const QUERY = JSON.stringify({
  query: '{ shops(filter: {}) { id name category terminal openingHours openNow node { id name } } }',
});
const PARAMS = { headers: { 'Content-Type': 'application/json' } };

export default function () {
  const res = http.post(SHOP_URL, QUERY, PARAMS);
  check(res, {
    'status 200': (r) => r.status === 200,
    'no GraphQL errors': (r) => {
      const body = r.json();
      return body && !body.errors && Array.isArray(body.data && body.data.shops);
    },
  });
}
