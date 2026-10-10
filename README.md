# elurea-payment-service

Online payments for Elurea orders, through **PayPal**. A **sandbox** provider simulates payments during development. It only ever charges the amount the order service gives, and only PayPal's own answers mark a payment as paid. It also refunds automatically when an order is cancelled.

Spring Boot 4.1, Java 25, MySQL (`elurea_payment_db`), port **8084**. In normal use it is reached through the gateway (`http://localhost:8000`).

## Run

```bash
./mvnw spring-boot:run                          # sandbox: no PayPal account needed
PAYMENT_PROVIDER=paypal PAYPAL_CLIENT_ID=… PAYPAL_CLIENT_SECRET=… \
  PAYPAL_WEBHOOK_ID=… PAYPAL_EXCHANGE_RATE=0.092 ./mvnw spring-boot:run   # PayPal
```

The order service must be running: payments read the order from it and report back to it.

## Currency: MAD prices, paid in EUR

PayPal does not support the Moroccan dirham. Prices and orders stay in **MAD**. PayPal charges in **EUR**, or another currency set with `PAYPAL_CURRENCY`, at the rate set in `PAYPAL_EXCHANGE_RATE`.

For example, 169.20 MAD at 0.092 is charged as 15.57 EUR, rounded to the cent. Each payment records both amounts (`amount`/`currency` and `chargedAmount`/`chargedCurrency`).

The rate is fixed configuration: update it when needed. The service refuses to start in PayPal mode without a rate. Also confirm with PayPal that the business's Moroccan account can receive payments.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8084` | |
| `DB_URL` | `jdbc:mysql://localhost:3306/elurea_payment_db?createDatabaseIfNotExist=true` | |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / empty | |
| `DDL_AUTO` | `update` | |
| `JWT_SECRET` | dev-only value | Must equal the user service's secret |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | |
| `ORDER_SERVICE_URL` | `http://localhost:8083` | |
| `INTERNAL_API_KEY` | `local-dev-internal-key` | Same value in every service |
| `PAYMENT_PROVIDER` | `sandbox` | `sandbox` or `paypal` |
| `PAYMENT_CURRENCY` | `MAD` | Currency of the shop's prices |
| `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` | empty | Credentials of a REST app created at developer.paypal.com |
| `PAYPAL_WEBHOOK_ID` | empty | Id of the webhook created in the same dashboard (see below) |
| `PAYPAL_API_URL` | `https://api-m.sandbox.paypal.com` | Use `https://api-m.paypal.com` for real payments |
| `PAYPAL_CURRENCY` | `EUR` | Currency PayPal charges in |
| `PAYPAL_EXCHANGE_RATE` | empty | Value of 1 MAD in `PAYPAL_CURRENCY`, e.g. `0.092`. Required when the two currencies differ |
| `PAYPAL_RETURN_URL` / `PAYPAL_CANCEL_URL` | `http://localhost:3000/checkout/paypal/return` / `…/cancel` | Frontend pages the buyer returns to, for the redirect flow |

### Setting up PayPal

1. At developer.paypal.com, create a REST app, sandbox first, and copy its client id and secret.
2. In that app, add a webhook pointing to `https://<public gateway address>/api/payments/webhooks/paypal`, subscribed to `PAYMENT.CAPTURE.COMPLETED` and `PAYMENT.CAPTURE.DENIED`. Copy its id into `PAYPAL_WEBHOOK_ID`.
3. Make one complete test payment with a PayPal sandbox buyer account before switching `PAYPAL_API_URL` to live.

## API

| Method | Path | Access | Description |
|---|---|---|---|
| POST | `/api/payments` | owner of the order | `{ "orderId": "…" }`: start paying an online order. 201 for a new payment; 200 when an open payment for that order is returned instead |
| POST | `/api/payments/{id}/capture` | owner | After the buyer approved on PayPal: the server takes the money with PayPal |
| GET | `/api/payments` | logged in | Customers get their own payments. Admins get all, with filters `orderId` and `status`. Sortable by `createdAt`, `updatedAt`, `amount`, `status` |
| GET | `/api/payments/{id}` | owner or admin | |
| POST | `/api/payments/{id}/refund` | admin | Refund a successful payment, for example returned goods. Calling it again re-sends the notification to the order service |
| POST | `/api/payments/{id}/sandbox-result` | owner, sandbox only | `{ "outcome": "SUCCEEDED" }` or `"DECLINED"`: simulates the buyer at PayPal. 404 in PayPal mode |
| POST | `/api/payments/webhooks/paypal` | PayPal only | Webhook. Each call is verified with PayPal before use |
| POST | `/internal/payments/orders/{orderId}/refund` | order service only | Internal: refund an order being cancelled |

Starting a payment checks that the order belongs to the caller, is paid `ONLINE`, is still `UNPAID` and is `PENDING`. Otherwise the answer is 409 with the reason.

Response, shortened:

```json
{
  "id": "…", "orderId": "…", "orderReference": "EL261010-3ZR49P",
  "amount": 169.20, "currency": "MAD", "chargedAmount": 15.57, "chargedCurrency": "EUR",
  "provider": "PAYPAL", "status": "PENDING",
  "providerPaymentId": "5O190127TN364715T",
  "approvalUrl": "https://www.paypal.com/checkoutnow?token=5O190127TN364715T"
}
```

`providerPaymentId` (the PayPal order id) and `approvalUrl` are only returned to the payment's owner, and only while the payment is `PENDING`.

### Frontend integration (PayPal JavaScript buttons)

```html
<script src="https://www.paypal.com/sdk/js?client-id=PAYPAL_CLIENT_ID&currency=EUR"></script>
```

```js
let paymentId;
paypal.Buttons({
  // 1. Our backend creates the PayPal order with the right amount.
  createOrder: async () => {
    const payment = await api.post('/api/payments', { orderId });
    paymentId = payment.id;
    return payment.providerPaymentId;
  },
  // 2. The buyer approved in the PayPal popup: ask our backend to capture.
  onApprove: async () => {
    const payment = await api.post(`/api/payments/${paymentId}/capture`);
    if (payment.status === 'SUCCEEDED') showOrderConfirmed();
    else showPaymentFailed(payment.failureReason); // e.g. card declined: let the buyer try again
  },
}).render('#paypal-buttons');
```

Without the buttons, redirect the buyer to `approvalUrl`. On the return page (`PAYPAL_RETURN_URL`), call `POST /api/payments/{id}/capture`.

In sandbox mode, replace PayPal with a "Pay" button that calls `POST /api/payments/{id}/sandbox-result`.

## Payment statuses

| Status | Meaning |
|---|---|
| `PENDING` | Created at PayPal, waiting for the buyer |
| `SUCCEEDED` | Money captured; the order is now `PAID` and `CONFIRMED` |
| `FAILED` | Declined or interrupted. The buyer can start a new payment |
| `REFUNDED` | Money returned |

## Rules that protect the money

- **Trusted amounts only.** The amount comes from the order service, never from the browser.
- **Trusted outcomes only.** Only PayPal can mark a payment successful, either through the server-side capture or a webhook verified with PayPal's verification API. Webhooks with missing headers, a failed verification, or older than 10 minutes are rejected.
- **At most one successful payment per order.** If a second payment for the same order succeeds, for example in two browser tabs, it is refunded automatically.
- **Late payments.** A payment that completes after its order was cancelled (customer, admin or 30-minute expiry) is refunded automatically.
- **Cancelling a paid order** refunds it, through the order service calling the internal refund endpoint.
- **Safe to repeat.** Every PayPal call uses the payment id as idempotency key (`PayPal-Request-Id`). Capturing, refunding or receiving the same webhook twice never charges or refunds twice.
- **Outages.** If PayPal or the order service is down, the request gets a 503. The attempt is recorded as `FAILED` when PayPal could not even be reached. PayPal redelivers webhooks that were not acknowledged.

All decisions for one order are taken under a database lock on that order's payments. Calls to the order service happen outside that lock, so the two services can never wait on each other.

## Code map

| Package | Content |
|---|---|
| `gateway` | `PaymentGateway` (provider interface), `PayPalPaymentGateway`, `SandboxPaymentGateway`. Adding another provider, such as CMI, means writing one more implementation |
| `service` | `PaymentService`: creation, capture, success and failure handling, refunds |
| `controller` | Public API, PayPal webhook, internal refund endpoint |
| `client` | Calls to the order service |

## Tests

```bash
./mvnw test     # 34 tests; the full-application tests need MySQL running
```

`PayPalPaymentGatewayTest` replays PayPal's API with a mock server and covers:

- the token handshake and currency conversion;
- capture: completed, declined, already captured and not yet approved;
- refunds and webhook verification.

No PayPal account is needed for the tests.
#   e l u r e a - p a y m e n t - s e r v i c e 
 
 
