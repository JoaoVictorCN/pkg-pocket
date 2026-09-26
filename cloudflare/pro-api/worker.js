const MP_API = "https://api.mercadopago.com";
const STRIPE_API = "https://api.stripe.com/v1";

const STRIPE_PRICE_ID =
  "price_1UJmHnA9MXYST9EgDPCaThcg";

const STRIPE_AMOUNT = 999;
const STRIPE_CURRENCY = "brl";


const SANDBOX = true;
const PRICE = 9.99;
const CURRENCY = "BRL";
const PRODUCT_NAME = "PKG Pocket Pro";

const API_BASE =
  "https://pkg-pocket-api.wbjoaovictor.workers.dev";

const SANDBOX_BUYER_EMAIL =
  "test_user_1095593974963489628@testuser.com";


/* =========================================================
   RESPONSE / HELPERS
   ========================================================= */

function json(data, status = 200) {
  return new Response(
    JSON.stringify(data, null, 2),
    {
      status,
      headers: {
        "content-type":
          "application/json; charset=utf-8",

        "cache-control":
          "no-store",
      },
    }
  );
}


function html(body, status = 200) {
  return new Response(
    body,
    {
      status,

      headers: {
        "content-type":
          "text/html; charset=utf-8",

        "cache-control":
          "no-store",

        "x-content-type-options":
          "nosniff",

        "referrer-policy":
          "no-referrer",
      },
    }
  );
}


function normalizeEmail(email) {
  return String(email || "")
    .trim()
    .toLowerCase();
}


function validEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/
    .test(email);
}


function randomPurchaseId() {
  return (
    "pp_" +
    crypto
      .randomUUID()
      .replaceAll("-", "")
  );
}


async function sha256(value) {
  const data =
    new TextEncoder()
      .encode(value);

  const digest =
    await crypto.subtle.digest(
      "SHA-256",
      data
    );

  return [
    ...new Uint8Array(digest),
  ]
    .map(
      b =>
        b
          .toString(16)
          .padStart(2, "0")
    )
    .join("");
}


function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}



/* =========================================================
   STRIPE
   ========================================================= */

class StripeError extends Error {
  constructor(status, body) {
    super(
      body?.error?.message ||
      `Stripe HTTP ${status}`
    );

    this.name = "StripeError";
    this.status = status;
    this.body = body || {};
  }
}


async function stripeRequest(
  env,
  path,
  options = {}
) {
  if (!env.STRIPE_SECRET_KEY) {
    throw new Error(
      "STRIPE_SECRET_KEY não configurada"
    );
  }

  const headers =
    new Headers(options.headers || {});

  headers.set(
    "Authorization",
    `Bearer ${env.STRIPE_SECRET_KEY}`
  );

  headers.set(
    "Accept",
    "application/json"
  );

  const response =
    await fetch(
      `${STRIPE_API}${path}`,
      {
        ...options,
        headers,
      }
    );

  const raw = await response.text();

  let body = {};

  try {
    body = raw ? JSON.parse(raw) : {};
  } catch {
    body = { raw };
  }

  if (!response.ok) {
    throw new StripeError(
      response.status,
      body
    );
  }

  return body;
}


function stripeForm(data) {
  const form = new URLSearchParams();

  for (const [key, value] of
    Object.entries(data)) {

    if (
      value !== undefined &&
      value !== null
    ) {
      form.set(key, String(value));
    }
  }

  return form;
}


async function createStripeCheckout(
  env,
  {
    purchaseId,
    email,
    emailHash,
    country,
  }
) {
  const now =
    new Date().toISOString();

  const normalizedCountry =
    String(country || "")
      .trim()
      .toUpperCase();

  const euroCountries =
    new Set([
      "AT", "BE", "HR", "CY", "EE",
      "FI", "FR", "DE", "GR", "IE",
      "IT", "LV", "LT", "LU", "MT",
      "NL", "PT", "SK", "SI", "ES",
    ]);

  const checkoutCurrency =
    normalizedCountry === "US"
      ? "usd"
      : normalizedCountry === "GB"
        ? "gbp"
        : euroCountries.has(normalizedCountry)
          ? "eur"
          : "brl";

  const checkoutAmount =
    checkoutCurrency === "usd"
      ? 199
      : checkoutCurrency === "gbp"
        ? 149
        : checkoutCurrency === "eur"
          ? 179
          : 999;

  const purchase = {
    version: 3,
    provider: "stripe",
    flow: "checkout_session",
    external_reference: purchaseId,
    email_hash: emailHash,
    country: normalizedCountry,
    status: "creating",
    amount_minor: checkoutAmount,
    currency: checkoutCurrency.toUpperCase(),
    sandbox: true,
    created_at: now,
    updated_at: now,
  };

  const key =
    `purchase:${purchaseId}`;

  await env.LICENSES.put(
    key,
    JSON.stringify(purchase),
    {
      expirationTtl:
        60 * 60 * 24 * 7,
    }
  );

  const form =
    stripeForm({
      "mode":
        "payment",

      "line_items[0][price]":
        STRIPE_PRICE_ID,

      "line_items[0][quantity]":
        "1",

      "currency":
        checkoutCurrency,

      "customer_email":
        email,

      "client_reference_id":
        purchaseId,

      "metadata[purchase_id]":
        purchaseId,

      "metadata[email_hash]":
        emailHash,

      "payment_intent_data[metadata][purchase_id]":
        purchaseId,

      "success_url":
        `${API_BASE}/v1/pro/return/success?provider=stripe&purchase_id=${encodeURIComponent(purchaseId)}&session_id={CHECKOUT_SESSION_ID}`,

      "cancel_url":
        `${API_BASE}/v1/pro/return/failure?provider=stripe&purchase_id=${encodeURIComponent(purchaseId)}`,
    });

  let session;

  try {
    session =
      await stripeRequest(
        env,
        "/checkout/sessions",
        {
          method: "POST",

          headers: {
            "Content-Type":
              "application/x-www-form-urlencoded",
          },

          body: form,
        }
      );
  } catch (error) {
    await env.LICENSES.put(
      key,
      JSON.stringify({
        ...purchase,
        status: "checkout_error",
        updated_at:
          new Date().toISOString(),
      }),
      {
        expirationTtl:
          60 * 60 * 24 * 7,
      }
    );

    throw error;
  }

  if (!session?.id || !session?.url) {
    throw new Error(
      "Resposta inválida da Stripe"
    );
  }

  await env.LICENSES.put(
    key,
    JSON.stringify({
      ...purchase,

      status:
        "checkout_created",

      stripe_session_id:
        session.id,

      updated_at:
        new Date().toISOString(),
    }),
    {
      expirationTtl:
        60 * 60 * 24 * 7,
    }
  );

  return json({
    ok: true,
    sandbox: true,
    provider: "stripe",
    country: normalizedCountry,
    currency: checkoutCurrency.toUpperCase(),
    amount_minor: checkoutAmount,
    flow: "checkout_session",
    purchase_id: purchaseId,
    session_id: session.id,
    checkout_url: session.url,
  });
}


async function validateStripeCheckoutProduct(
  env,
  session
) {
  if (!session?.id) {
    throw new Error(
      "Checkout Session Stripe inválida"
    );
  }

  const lineItems =
    await stripeRequest(
      env,
      `/checkout/sessions/${encodeURIComponent(
        session.id
      )}/line_items?limit=10`,
      {
        method: "GET",
      }
    );

  const items =
    Array.isArray(lineItems?.data)
      ? lineItems.data
      : [];

  if (items.length !== 1) {
    throw new Error(
      "Quantidade de itens Stripe inválida"
    );
  }

  const item = items[0];

  const priceId =
    typeof item?.price === "string"
      ? item.price
      : String(item?.price?.id || "");

  if (priceId !== STRIPE_PRICE_ID) {
    throw new Error(
      "Price Stripe inválido"
    );
  }

  if (Number(item?.quantity || 0) !== 1) {
    throw new Error(
      "Quantidade Stripe inválida"
    );
  }

  return {
    priceId,
    amountTotal:
      Number(session.amount_total || 0),

    currency:
      String(session.currency || "")
        .toLowerCase(),
  };
}


async function processStripeSession(
  env,
  session
) {
  const purchaseId =
    String(
      session?.metadata?.purchase_id ||
      session?.client_reference_id ||
      ""
    );

  if (!purchaseId.startsWith("pp_")) {
    throw new Error(
      "purchase_id Stripe inválido"
    );
  }

  const purchaseKey =
    `purchase:${purchaseId}`;

  const purchase =
    await env.LICENSES.get(
      purchaseKey,
      "json"
    );

  if (!purchase) {
    throw new Error(
      "Compra Stripe não encontrada"
    );
  }

  if (purchase.provider !== "stripe") {
    throw new Error(
      "Provider da compra inválido"
    );
  }

  if (
    purchase.stripe_session_id &&
    String(purchase.stripe_session_id) !==
      String(session.id)
  ) {
    throw new Error(
      "Checkout Session inválida"
    );
  }

  const checkoutProduct =
    await validateStripeCheckoutProduct(
      env,
      session
    );

  if (
    !checkoutProduct.amountTotal ||
    !checkoutProduct.currency
  ) {
    throw new Error(
      "Valor/moeda Stripe inválidos"
    );
  }

  const paid =
    String(
      session.payment_status || ""
    ).toLowerCase() === "paid";

  const now =
    new Date().toISOString();

  if (!paid) {
    await env.LICENSES.put(
      purchaseKey,
      JSON.stringify({
        ...purchase,
        status:
          session.payment_status ||
          "pending",
        payment_status:
          session.payment_status ||
          null,
        updated_at: now,
      })
    );

    return {
      activated: false,
      revoked: false,
      purchaseId,
    };
  }

  const paymentId =
    String(
      session.payment_intent ||
      session.id
    );

  const licenseKey =
    `license:${purchase.email_hash}`;

  const oldLicense =
    await env.LICENSES.get(
      licenseKey,
      "json"
    );

  const license = {
    version: 1,
    product: "pkg-pocket-pro",
    entitlement: "pro",
    status: "active",

    email_hash:
      purchase.email_hash,

    active_device_hash:
      oldLicense?.active_device_hash ||
      null,

    purchased_at:
      oldLicense?.purchased_at ||
      now,

    activated_at: now,
    last_verified_at: now,

    payment_id:
      paymentId,

    preference_id: null,

    stripe_session_id:
      session.id,

    amount_minor:
      checkoutProduct.amountTotal,

    currency:
      checkoutProduct.currency
        .toUpperCase(),

    stripe_price_id:
      checkoutProduct.priceId,

    provider:
      "stripe",

    external_reference:
      purchaseId,

    transfer_available_at:
      oldLicense
        ?.transfer_available_at ||
      null,

    updated_at: now,
  };

  await env.LICENSES.put(
    licenseKey,
    JSON.stringify(license)
  );

  await env.LICENSES.put(
    purchaseKey,
    JSON.stringify({
      ...purchase,
      status: "approved",
      payment_id: paymentId,
      payment_status: "paid",
      stripe_session_id:
        session.id,

      amount_minor:
        checkoutProduct.amountTotal,

      currency:
        checkoutProduct.currency
          .toUpperCase(),

      stripe_price_id:
        checkoutProduct.priceId,

      approved_at:
        purchase.approved_at ||
        now,
      updated_at: now,
    })
  );

  await env.LICENSES.put(
    `payment:${paymentId}`,
    JSON.stringify({
      purchase_id: purchaseId,
      email_hash:
        purchase.email_hash,
      provider: "stripe",
      status: "approved",
      updated_at: now,
    })
  );

  return {
    activated: true,
    revoked: false,
    purchaseId,
    license,
  };
}



function hexToBytes(hex) {
  const clean =
    String(hex || "").toLowerCase();

  if (
    !/^[0-9a-f]+$/.test(clean) ||
    clean.length % 2 !== 0
  ) {
    return null;
  }

  const out =
    new Uint8Array(clean.length / 2);

  for (
    let i = 0;
    i < out.length;
    i++
  ) {
    out[i] =
      parseInt(
        clean.slice(
          i * 2,
          i * 2 + 2
        ),
        16
      );
  }

  return out;
}


function timingSafeBytesEqual(a, b) {
  if (!a || !b) return false;

  if (a.length !== b.length) {
    return false;
  }

  let diff = 0;

  for (
    let i = 0;
    i < a.length;
    i++
  ) {
    diff |= a[i] ^ b[i];
  }

  return diff === 0;
}


async function verifyStripeSignature(
  rawBody,
  signatureHeader,
  secret
) {
  if (!secret) {
    throw new Error(
      "STRIPE_WEBHOOK_SECRET não configurada"
    );
  }

  const parts =
    String(signatureHeader || "")
      .split(",");

  let timestamp = null;
  const signatures = [];

  for (const part of parts) {
    const index =
      part.indexOf("=");

    if (index === -1) continue;

    const key =
      part.slice(0, index).trim();

    const value =
      part.slice(index + 1).trim();

    if (key === "t") {
      timestamp = value;
    }

    if (key === "v1") {
      signatures.push(value);
    }
  }

  if (
    !timestamp ||
    !signatures.length
  ) {
    return false;
  }

  const ts =
    Number(timestamp);

  if (!Number.isFinite(ts)) {
    return false;
  }

  /*
   * Evita replay de webhook antigo.
   * Stripe recomenda tolerância curta.
   */
  const now =
    Math.floor(Date.now() / 1000);

  if (
    Math.abs(now - ts) > 300
  ) {
    return false;
  }

  const encoder =
    new TextEncoder();

  const key =
    await crypto.subtle.importKey(
      "raw",
      encoder.encode(secret),
      {
        name: "HMAC",
        hash: "SHA-256",
      },
      false,
      ["sign"]
    );

  const signedPayload =
    `${timestamp}.${rawBody}`;

  const digest =
    new Uint8Array(
      await crypto.subtle.sign(
        "HMAC",
        key,
        encoder.encode(
          signedPayload
        )
      )
    );

  for (
    const signature of signatures
  ) {
    const candidate =
      hexToBytes(signature);

    if (
      timingSafeBytesEqual(
        digest,
        candidate
      )
    ) {
      return true;
    }
  }

  return false;
}


async function stripeWebhook(
  request,
  env
) {
  /*
   * IMPORTANTE:
   * a assinatura é calculada sobre o
   * corpo EXATO recebido da Stripe.
   */
  const rawBody =
    await request.text();

  const signature =
    request.headers.get(
      "Stripe-Signature"
    );

  const valid =
    await verifyStripeSignature(
      rawBody,
      signature,
      env.STRIPE_WEBHOOK_SECRET
    );

  if (!valid) {
    return json(
      {
        ok: false,
        code:
          "INVALID_STRIPE_SIGNATURE",
      },
      400
    );
  }

  let event;

  try {
    event =
      JSON.parse(rawBody);
  } catch {
    return json(
      {
        ok: false,
        code:
          "INVALID_STRIPE_JSON",
      },
      400
    );
  }

  const eventId =
    String(event?.id || "");

  const eventType =
    String(event?.type || "");

  /*
   * Idempotência simples.
   * Se a Stripe reenviar o mesmo evento,
   * não precisamos ativar novamente.
   */
  if (eventId) {
    const eventKey =
      `stripe_event:${eventId}`;

    const alreadyProcessed =
      await env.LICENSES.get(
        eventKey
      );

    if (alreadyProcessed) {
      return json({
        ok: true,
        duplicate: true,
        event_id: eventId,
      });
    }
  }

  const supported =
    eventType ===
      "checkout.session.completed" ||

    eventType ===
      "checkout.session.async_payment_succeeded";

  if (!supported) {
    return json({
      ok: true,
      ignored: true,
      type: eventType,
    });
  }

  const session =
    event?.data?.object;

  if (
    !session ||
    session.object !==
      "checkout.session"
  ) {
    return json(
      {
        ok: false,
        code:
          "INVALID_STRIPE_OBJECT",
      },
      400
    );
  }

  let processed;

  try {
    /*
     * Não confiamos apenas no objeto
     * recebido pelo webhook.
     *
     * Recuperamos a Session diretamente
     * da Stripe usando nossa secret key.
     */
    const freshSession =
      await stripeRequest(
        env,
        `/checkout/sessions/${encodeURIComponent(
          session.id
        )}`,
        {
          method: "GET",
        }
      );

    processed =
      await processStripeSession(
        env,
        freshSession
      );

  } catch (error) {

    console.error(
      "Stripe webhook processing error",
      error
    );

    /*
     * 500 faz a Stripe tentar novamente.
     */
    return json(
      {
        ok: false,
        code:
          "STRIPE_WEBHOOK_PROCESSING_FAILED",

        ...(SANDBOX
          ? {
              message:
                String(
                  error?.message ||
                  error
                ),
            }
          : {}),
      },
      500
    );
  }

  /*
   * Só marcamos como processado
   * depois do processamento terminar.
   */
  if (eventId) {
    await env.LICENSES.put(
      `stripe_event:${eventId}`,
      JSON.stringify({
        type: eventType,
        processed_at:
          new Date().toISOString(),
      }),
      {
        expirationTtl:
          60 * 60 * 24 * 30,
      }
    );
  }

  return json({
    ok: true,
    type: eventType,

    activated:
      Boolean(
        processed?.activated
      ),

    revoked:
      Boolean(
        processed?.revoked
      ),
  });
}


async function reconcileStripe(
  env,
  purchase
) {
  if (!purchase.stripe_session_id) {
    return {
      ok: true,
      activated: false,
      status: "waiting_payment",
    };
  }

  const session =
    await stripeRequest(
      env,
      `/checkout/sessions/${encodeURIComponent(
        purchase.stripe_session_id
      )}`,
      {
        method: "GET",
      }
    );

  const processed =
    await processStripeSession(
      env,
      session
    );

  return {
    ok: true,

    payment_id:
      session.payment_intent
        ? String(
            session.payment_intent
          )
        : String(session.id),

    status:
      session.payment_status === "paid"
        ? "paid"
        : session.status === "expired"
          ? "expired"
          : session.payment_status === "unpaid"
            ? "unpaid"
            : session.payment_status ||
              session.status ||
              "unknown",

    status_detail: null,

    activated:
      Boolean(
        processed.activated
      ),

    revoked:
      Boolean(
        processed.revoked
      ),
  };
}


/* =========================================================
   MERCADO PAGO HTTP
   ========================================================= */

class MercadoPagoError
  extends Error {

  constructor(
    status,
    body
  ) {

    super(
      body?.message ||
      `Mercado Pago HTTP ${status}`
    );

    this.name =
      "MercadoPagoError";

    this.status =
      status;

    this.body =
      body || {};
  }
}


async function mpRequest(
  env,
  path,
  options = {}
) {

  const headers =
    new Headers(
      options.headers || {}
    );


  headers.set(
    "Authorization",
    `Bearer ${env.MERCADOPAGO_ACCESS_TOKEN}`
  );


  headers.set(
    "Accept",
    "application/json"
  );


  if (options.body) {

    headers.set(
      "Content-Type",
      "application/json"
    );
  }


  const response =
    await fetch(
      `${MP_API}${path}`,
      {
        ...options,
        headers,
      }
    );


  const raw =
    await response.text();


  let body = {};


  try {

    body =
      raw
        ? JSON.parse(raw)
        : {};

  } catch {

    body = {
      raw,
    };
  }


  if (!response.ok) {

    console.error(
      "Mercado Pago API error",
      {
        path,

        status:
          response.status,

        body,
      }
    );


    throw new MercadoPagoError(
      response.status,
      body
    );
  }


  return body;
}


/* =========================================================
   WEBHOOK SIGNATURE
   ========================================================= */

function parseSignature(value) {

  const result = {};


  for (
    const piece
    of String(value || "")
      .split(",")
  ) {

    const [
      key,
      ...rest
    ] =
      piece.split("=");


    if (
      key &&
      rest.length
    ) {

      result[
        key.trim()
      ] =
        rest
          .join("=")
          .trim();
    }
  }


  return result;
}


async function hmacHex(
  secret,
  message
) {

  const encoder =
    new TextEncoder();


  const key =
    await crypto.subtle.importKey(
      "raw",

      encoder.encode(
        secret
      ),

      {
        name: "HMAC",
        hash: "SHA-256",
      },

      false,

      ["sign"]
    );


  const signature =
    await crypto.subtle.sign(
      "HMAC",

      key,

      encoder.encode(
        message
      )
    );


  return [
    ...new Uint8Array(
      signature
    ),
  ]
    .map(
      b =>
        b
          .toString(16)
          .padStart(2, "0")
    )
    .join("");
}


function safeEqual(
  a,
  b
) {

  if (
    !a ||
    !b ||
    a.length !== b.length
  ) {

    return false;
  }


  let result = 0;


  for (
    let i = 0;
    i < a.length;
    i++
  ) {

    result |=
      a.charCodeAt(i) ^
      b.charCodeAt(i);
  }


  return result === 0;
}


async function verifyWebhook(
  request,
  env,
  dataId
) {

  const xSignature =
    request.headers.get(
      "x-signature"
    );


  const xRequestId =
    request.headers.get(
      "x-request-id"
    );


  if (
    !xSignature ||
    !xRequestId ||
    !dataId
  ) {

    return false;
  }


  const parsed =
    parseSignature(
      xSignature
    );


  if (
    !parsed.ts ||
    !parsed.v1
  ) {

    return false;
  }


  /*
   * Para Payment é numérico.
   * Para IDs alfanuméricos,
   * lowercase mantém compatibilidade
   * com a orientação do Mercado Pago.
   */
  const normalizedId =
    String(dataId)
      .toLowerCase();


  const manifest =
    `id:${normalizedId};` +
    `request-id:${xRequestId};` +
    `ts:${parsed.ts};`;


  const calculated =
    await hmacHex(
      env.MERCADOPAGO_WEBHOOK_SECRET,
      manifest
    );


  return safeEqual(
    calculated,
    parsed.v1
  );
}


/* =========================================================
   PAYMENT -> LICENSE
   ========================================================= */

async function processPayment(
  env,
  payment
) {

  const purchaseId =
    String(
      payment
        ?.external_reference ||
      ""
    );


  if (
    !purchaseId.startsWith(
      "pp_"
    )
  ) {

    throw new Error(
      "external_reference inválida"
    );
  }


  const purchaseKey =
    `purchase:${purchaseId}`;


  const purchase =
    await env.LICENSES.get(
      purchaseKey,
      "json"
    );


  if (!purchase) {

    throw new Error(
      "Compra não encontrada"
    );
  }


  const paidAmount =
    Number(
      payment
        .transaction_amount
    );


  if (
    !Number.isFinite(
      paidAmount
    ) ||

    Math.abs(
      paidAmount -
      PRICE
    ) >
      0.0001
  ) {

    throw new Error(
      "Valor do pagamento inválido"
    );
  }


  if (
    String(
      payment
        .currency_id ||
      ""
    )
      .toUpperCase() !==
      CURRENCY
  ) {

    throw new Error(
      "Moeda inválida"
    );
  }


  if (
    purchase.collector_id &&

    payment.collector_id &&

    String(
      purchase.collector_id
    ) !==
      String(
        payment.collector_id
      )
  ) {

    throw new Error(
      "Collector inválido"
    );
  }


  const status =
    String(
      payment.status ||
      ""
    )
      .toLowerCase();


  const now =
    new Date()
      .toISOString();


  /* ---------- CANCELADO / REEMBOLSADO ---------- */

  if (
    status === "refunded" ||

    status ===
      "charged_back" ||

    status ===
      "cancelled" ||

    status ===
      "canceled"
  ) {

    const licenseKey =
      `license:${purchase.email_hash}`;


    const license =
      await env.LICENSES.get(
        licenseKey,
        "json"
      );


    if (license) {

      await env.LICENSES.put(
        licenseKey,

        JSON.stringify({
          ...license,

          status:
            "revoked",

          revoked_at:
            now,

          revoke_reason:
            status,

          last_payment_id:
            String(
              payment.id
            ),

          updated_at:
            now,
        })
      );
    }


    await env.LICENSES.put(
      purchaseKey,

      JSON.stringify({
        ...purchase,

        status:
          "revoked",

        payment_id:
          String(
            payment.id
          ),

        payment_status:
          status,

        updated_at:
          now,
      })
    );


    return {

      activated:
        false,

      revoked:
        true,

      purchaseId,
    };
  }


  /* ---------- AINDA NÃO APROVADO ---------- */

  if (
    status !== "approved"
  ) {

    await env.LICENSES.put(
      purchaseKey,

      JSON.stringify({
        ...purchase,

        status:
          status ||
          "pending",

        payment_id:
          payment.id
            ? String(
                payment.id
              )
            : null,

        payment_status:
          status ||
          null,

        payment_status_detail:
          payment
            .status_detail ||
          null,

        updated_at:
          now,
      })
    );


    return {

      activated:
        false,

      revoked:
        false,

      purchaseId,
    };
  }


  /* ---------- PRO ATIVO ---------- */

  const licenseKey =
    `license:${purchase.email_hash}`;


  const oldLicense =
    await env.LICENSES.get(
      licenseKey,
      "json"
    );


  const license = {

    version:
      1,

    product:
      "pkg-pocket-pro",

    entitlement:
      "pro",

    status:
      "active",

    email_hash:
      purchase.email_hash,

    /*
     * Vinculação ao aparelho
     * entra na próxima etapa.
     */
    active_device_hash:
      oldLicense
        ?.active_device_hash ||
      null,

    purchased_at:
      oldLicense
        ?.purchased_at ||
      now,

    activated_at:
      now,

    last_verified_at:
      now,

    payment_id:
      String(
        payment.id
      ),

    preference_id:
      purchase
        .preference_id ||
      null,

    external_reference:
      purchaseId,

    transfer_available_at:
      oldLicense
        ?.transfer_available_at ||
      null,

    updated_at:
      now,
  };


  await env.LICENSES.put(
    licenseKey,
    JSON.stringify(
      license
    )
  );


  await env.LICENSES.put(
    purchaseKey,

    JSON.stringify({
      ...purchase,

      status:
        "approved",

      payment_id:
        String(
          payment.id
        ),

      payment_status:
        payment.status,

      payment_status_detail:
        payment
          .status_detail ||
        null,

      approved_at:
        purchase
          .approved_at ||
        now,

      updated_at:
        now,
    })
  );


  await env.LICENSES.put(
    `payment:${payment.id}`,

    JSON.stringify({

      purchase_id:
        purchaseId,

      email_hash:
        purchase.email_hash,

      status:
        "approved",

      updated_at:
        now,
    })
  );


  return {

    activated:
      true,

    revoked:
      false,

    purchaseId,

    license,
  };
}


/* =========================================================
   CHECKOUT PRO - PREFERENCES
   ========================================================= */

function proQuote(request) {
  const detectedCountry =
    String(
      request.cf?.country || ""
    )
      .trim()
      .toUpperCase();

  const country =
    /^[A-Z]{2}$/.test(detectedCountry)
      ? detectedCountry
      : "XX";

  if (country === "BR") {
    return json({
      ok: true,
      provider: "mercadopago",
      country,
      currency: "BRL",
      amount_minor: 999,
    });
  }

  if (country === "US") {
    return json({
      ok: true,
      provider: "stripe",
      country,
      currency: "USD",
      amount_minor: 199,
    });
  }

  if (country === "GB") {
    return json({
      ok: true,
      provider: "stripe",
      country,
      currency: "GBP",
      amount_minor: 149,
    });
  }

  const euroCountries =
    new Set([
      "AT", "BE", "HR", "CY", "EE",
      "FI", "FR", "DE", "GR", "IE",
      "IT", "LV", "LT", "LU", "MT",
      "NL", "PT", "SK", "SI", "ES",
    ]);

  if (euroCountries.has(country)) {
    return json({
      ok: true,
      provider: "stripe",
      country,
      currency: "EUR",
      amount_minor: 179,
    });
  }

  /*
   * Fallback internacional atual.
   * Mantém coerência com o Price Stripe.
   */
  return json({
    ok: true,
    provider: "stripe",
    country,
    currency: "BRL",
    amount_minor: 999,
  });
}


async function createCheckout(
  request,
  env
) {

  let body;


  try {

    body =
      await request.json();

  } catch {

    return json(
      {
        ok: false,

        code:
          "INVALID_JSON",
      },
      400
    );
  }


  /*
   * E-mail usado para a LICENÇA
   * PKG Pocket.
   */
  const email =
    normalizeEmail(
      body.email
    );


  if (
    !validEmail(
      email
    )
  ) {

    return json(
      {
        ok:
          false,

        code:
          "INVALID_EMAIL",

        message:
          "Informe um e-mail válido.",
      },
      400
    );
  }


  const emailHash =
    await sha256(
      email
    );


  const currentLicense =
    await env.LICENSES.get(
      `license:${emailHash}`,
      "json"
    );


  if (
    currentLicense
      ?.status ===
      "active"
  ) {

    return json(
      {
        ok:
          false,

        code:
          "ALREADY_PRO",

        message:
          "Esse e-mail já possui PKG Pocket Pro.",
      },
      409
    );
  }


  const purchaseId =
    randomPurchaseId();


  /*
   * O servidor é a autoridade para região de pagamento.
   *
   * request.cf.country é determinado pela Cloudflare
   * a partir da conexão que chegou ao Worker.
   *
   * provider/country enviados pelo APK NÃO decidem
   * mais provedor ou moeda.
   */
  const detectedCountry =
    String(
      request.cf?.country || ""
    )
      .trim()
      .toUpperCase();

  /*
   * Se a Cloudflare não fornecer país, usamos Stripe
   * como fallback internacional em vez de assumir BR.
   */
  const paymentCountry =
    /^[A-Z]{2}$/.test(detectedCountry)
      ? detectedCountry
      : "XX";

  const paymentProvider =
    paymentCountry === "BR"
      ? "mercadopago"
      : "stripe";


  if (paymentProvider === "stripe") {

    try {

      return await createStripeCheckout(
        env,
        {
          purchaseId,
          email,
          emailHash,
          country: paymentCountry,
        }
      );

    } catch (error) {

      console.error(
        "Stripe checkout error",
        error
      );

      return json(
        {
          ok: false,
          code:
            "STRIPE_CHECKOUT_CREATE_FAILED",

          ...(SANDBOX
            ? {
                message:
                  String(
                    error?.message ||
                    error
                  ),
              }
            : {}),
        },
        502
      );
    }
  }


  const now =
    new Date()
      .toISOString();


  const purchase = {

    version:
      2,

    provider:
      "mercadopago",

    flow:
      "preferences",

    external_reference:
      purchaseId,

    email_hash:
      emailHash,

    country:
      paymentCountry,

    status:
      "creating",

    amount:
      PRICE,

    currency:
      CURRENCY,

    sandbox:
      SANDBOX,

    created_at:
      now,

    updated_at:
      now,
  };


  await env.LICENSES.put(
    `purchase:${purchaseId}`,

    JSON.stringify(
      purchase
    ),

    {
      expirationTtl:
        60 *
        60 *
        24 *
        7,
    }
  );


  const preferencePayload = {

    items: [
      {

        id:
          "pkg-pocket-pro",

        title:
          PRODUCT_NAME,

        description:
          "Licença permanente PKG Pocket Pro",

        currency_id:
          CURRENCY,

        quantity:
          1,

        unit_price:
          PRICE,
      },
    ],


    payer: {

      email:
        SANDBOX
          ? SANDBOX_BUYER_EMAIL
          : email,
    },


    external_reference:
      purchaseId,


    back_urls: {

      success:
        `${API_BASE}/v1/pro/return/success`,

      pending:
        `${API_BASE}/v1/pro/return/pending`,

      failure:
        `${API_BASE}/v1/pro/return/failure`,
    },


    auto_return:
      "approved",


    /*
     * Sem notification_url.
     *
     * O Webhook configurado no painel
     * do Mercado Pago continua ativo.
     */
    statement_descriptor:
      "PKGPOCKET",
  };


  let preference;


  try {

    preference =
      await mpRequest(
        env,

        "/checkout/preferences",

        {
          method:
            "POST",

          body:
            JSON.stringify(
              preferencePayload
            ),
        }
      );

  } catch (error) {


    await env.LICENSES.put(
      `purchase:${purchaseId}`,

      JSON.stringify({
        ...purchase,

        status:
          "checkout_error",

        updated_at:
          new Date()
            .toISOString(),
      }),

      {
        expirationTtl:
          60 *
          60 *
          24 *
          7,
      }
    );


    if (
      SANDBOX &&

      error instanceof
        MercadoPagoError
    ) {

      return json(
        {
          ok:
            false,

          code:
            "CHECKOUT_CREATE_FAILED",

          mercado_pago: {

            http_status:
              error.status,

            body:
              error.body,
          },
        },
        502
      );
    }


    return json(
      {
        ok:
          false,

        code:
          "CHECKOUT_CREATE_FAILED",
      },
      502
    );
  }


  /*
   * Sandbox usa sandbox_init_point.
   * Produção usa init_point.
   */
  const checkoutUrl =
    SANDBOX

      ? preference
          ?.sandbox_init_point

      : preference
          ?.init_point;


  if (
    !preference?.id ||
    !checkoutUrl
  ) {

    return json(
      {
        ok:
          false,

        code:
          "INVALID_MP_RESPONSE",

        mercado_pago:
          SANDBOX
            ? preference
            : undefined,
      },
      502
    );
  }


  await env.LICENSES.put(
    `purchase:${purchaseId}`,

    JSON.stringify({
      ...purchase,

      status:
        "checkout_created",

      preference_id:
        preference.id,

      collector_id:
        preference
          .collector_id ||
        null,

      updated_at:
        new Date()
          .toISOString(),
    }),

    {
      expirationTtl:
        60 *
        60 *
        24 *
        7,
    }
  );


  return json({

    ok:
      true,

    sandbox:
      SANDBOX,

    provider:
      "mercadopago",

    country:
      paymentCountry,

    currency:
      CURRENCY,

    amount_minor:
      Math.round(PRICE * 100),

    flow:
      "preferences",

    purchase_id:
      purchaseId,

    preference_id:
      preference.id,

    checkout_url:
      checkoutUrl,
  });
}


/* =========================================================
   WEBHOOK
   ========================================================= */

async function webhook(
  request,
  env
) {

  const url =
    new URL(
      request.url
    );


  let body = {};


  try {

    body =
      await request
        .clone()
        .json();

  } catch {

    body = {};
  }


  const dataId =

    url.searchParams.get(
      "data.id"
    ) ||

    body?.data?.id;


  const type =
    String(

      url.searchParams.get(
        "type"
      ) ||

      body?.type ||

      ""
    )
      .toLowerCase();


  if (!dataId) {

    return json(
      {
        ok:
          false,

        code:
          "MISSING_DATA_ID",
      },
      400
    );
  }


  const valid =
    await verifyWebhook(
      request,
      env,
      String(
        dataId
      )
    );


  if (!valid) {

    console.warn(
      "Webhook Mercado Pago inválido"
    );


    return json(
      {
        ok:
          false,

        code:
          "INVALID_SIGNATURE",
      },
      401
    );
  }


  /*
   * Checkout Pro Preferences
   * usa Payment como autoridade.
   */
  if (
    type !== "payment"
  ) {

    return json({

      ok:
        true,

      ignored:
        true,

      type,
    });
  }


  try {

    const payment =
      await mpRequest(
        env,

        `/v1/payments/${encodeURIComponent(
          dataId
        )}`,

        {
          method:
            "GET",
        }
      );


    const result =
      await processPayment(
        env,
        payment
      );


    return json({

      ok:
        true,

      payment_id:
        String(
          payment.id
        ),

      status:
        payment.status,

      status_detail:
        payment
          .status_detail ||
        null,

      activated:
        Boolean(
          result.activated
        ),

      revoked:
        Boolean(
          result.revoked
        ),
    });


  } catch (error) {

    console.error(
      "Webhook payment error",
      error
    );


    return json(
      {
        ok:
          false,

        code:
          "PAYMENT_PROCESSING_FAILED",
      },
      500
    );
  }
}


/* =========================================================
   MANUAL RECONCILE / APP FALLBACK
   ========================================================= */

async function reconcile(
  request,
  env
) {

  let body;


  try {

    body =
      await request.json();

  } catch {

    return json(
      {
        ok:
          false,

        code:
          "INVALID_JSON",
      },
      400
    );
  }


  const purchaseId =
    String(
      body.purchase_id ||
      ""
    );


  if (
    !purchaseId.startsWith(
      "pp_"
    )
  ) {

    return json(
      {
        ok:
          false,

        code:
          "INVALID_PURCHASE_ID",
      },
      400
    );
  }


  const purchase =
    await env.LICENSES.get(
      `purchase:${purchaseId}`,
      "json"
    );


  if (!purchase) {

    return json(
      {
        ok:
          false,

        code:
          "PURCHASE_NOT_FOUND",
      },
      404
    );
  }


  /*
   * Stripe possui reconciliação própria.
   * Mercado Pago continua exatamente no
   * caminho original abaixo.
   */
  if (purchase.provider === "stripe") {

    try {

      const stripeResult =
        await reconcileStripe(
          env,
          purchase
        );

      return json(
        stripeResult
      );

    } catch (error) {

      console.error(
        "Stripe reconcile error",
        error
      );

      return json(
        {
          ok: false,
          code:
            "PAYMENT_LOOKUP_FAILED",

          ...(SANDBOX
            ? {
                message:
                  String(
                    error?.message ||
                    error
                  ),
              }
            : {}),
        },
        502
      );
    }
  }


  let result;


  try {

    result =
      await mpRequest(
        env,

        `/v1/payments/search?external_reference=${encodeURIComponent(
          purchaseId
        )}`,

        {
          method:
            "GET",
        }
      );

  } catch {

    return json(
      {
        ok:
          false,

        code:
          "PAYMENT_LOOKUP_FAILED",
      },
      502
    );
  }


  const payments =
    Array.isArray(
      result?.results
    )

      ? result.results

      : [];


  if (
    !payments.length
  ) {

    return json({

      ok:
        true,

      activated:
        false,

      status:
        "not_found",
    });
  }


  const payment =

    payments.find(
      payment =>
        payment.status ===
        "approved"
    )

    ||

    payments[0];


  const processed =
    await processPayment(
      env,
      payment
    );


  return json({

    ok:
      true,

    payment_id:
      String(
        payment.id
      ),

    status:
      payment.status,

    status_detail:
      payment
        .status_detail ||
      null,

    activated:
      Boolean(
        processed
          .activated
      ),

    revoked:
      Boolean(
        processed
          .revoked
      ),
  });
}


/* =========================================================
   LICENSE STATUS
   ========================================================= */


async function recoverSandboxLicenseByEmail(
  env,
  emailHash
) {
  if (!SANDBOX) return null;

  let cursor = undefined;

  do {
    const page = await env.LICENSES.list({
      prefix: "purchase:",
      limit: 1000,
      ...(cursor ? { cursor } : {}),
    });

    for (const entry of page.keys || []) {
      const purchase = await env.LICENSES.get(
        entry.name,
        "json"
      );

      if (
        !purchase ||
        purchase.email_hash !== emailHash
      ) {
        continue;
      }

      const purchaseId = String(
        purchase.external_reference ||
        entry.name.slice("purchase:".length)
      );

      if (!purchaseId.startsWith("pp_")) {
        continue;
      }

      let search;

      try {
        search = await mpRequest(
          env,
          `/v1/payments/search?external_reference=${encodeURIComponent(
            purchaseId
          )}`,
          { method: "GET" }
        );
      } catch {
        continue;
      }

      const payments = Array.isArray(search?.results)
        ? search.results
        : [];

      const approved = payments.find(
        payment => payment.status === "approved"
      );

      if (!approved) {
        continue;
      }

      try {
        const processed = await processPayment(
          env,
          approved
        );

        if (processed?.activated) {
          return (
            processed.license ||
            await env.LICENSES.get(
              `license:${emailHash}`,
              "json"
            )
          );
        }
      } catch {
        continue;
      }
    }

    if (page.list_complete) {
      break;
    }

    cursor = page.cursor;
  } while (cursor);

  return null;
}


async function proStatus(
  request,
  env
) {

  const url =
    new URL(
      request.url
    );


  const email =
    normalizeEmail(

      url.searchParams.get(
        "email"
      )
    );


  if (
    !validEmail(
      email
    )
  ) {

    return json(
      {
        ok:
          false,

        code:
          "INVALID_EMAIL",
      },
      400
    );
  }


  const emailHash =
    await sha256(
      email
    );


  let license =
    await env.LICENSES.get(
      `license:${emailHash}`,
      "json"
    );


  /*
   * Sandbox: se o app perdeu o purchase_id local,
   * o próprio status tenta localizar uma compra
   * aprovada vinculada ao mesmo e-mail de licença.
   *
   * Em produção isso fica desativado; restauração
   * deverá exigir confirmação por e-mail/dispositivo.
   */
  if (
    !license &&
    SANDBOX
  ) {

    license =
      await recoverSandboxLicenseByEmail(
        env,
        emailHash
      );
  }


  if (!license) {

    return json({

      ok:
        true,

      pro:
        false,

      status:
        "free",
    });
  }


  return json({

    ok:
      true,

    pro:
      license.status ===
      "active",

    status:
      license.status,

    purchased_at:
      license
        .purchased_at ||
      null,

    activated_at:
      license
        .activated_at ||
      null,
  });
}


/* =========================================================
   PKG POCKET LOGO
   ========================================================= */

function logoSvg() {

  return `
<svg
  class="brand-logo"
  viewBox="0 0 64 64"
  aria-hidden="true"
>
  <defs>

    <linearGradient
      id="pkgLogoGradient"
      x1="8"
      y1="8"
      x2="56"
      y2="58"
      gradientUnits="userSpaceOnUse"
    >

      <stop
        stop-color="#D1C4FF"
      />

      <stop
        offset="1"
        stop-color="#9575EA"
      />

    </linearGradient>

  </defs>


  <path
    d="
      M32 4
      55 17
      v30
      L32 60
      9 47
      V17
      L32 4Z
    "
    fill="url(#pkgLogoGradient)"
  />


  <path
    d="
      M32 12
      47 20.5
      32 29
      17 20.5
      32 12Z
    "
    fill="#111520"
  />


  <path
    d="
      M17 25.5
      28 31.8
      v15.7
      L17 41
      V25.5Z
    "
    fill="#111520"
  />


  <path
    d="
      M36 31.8
      47 25.5
      V41
      l-11 6.5
      V31.8Z
    "
    fill="#111520"
  />


  <path
    d="
      M32 18.2
      39.8 22.6
      32 27
      24.2 22.6
      32 18.2Z
    "
    fill="url(#pkgLogoGradient)"
  />


  <path
    d="
      M24.2 26.5
      29.2 29.4
      V36
      l-5-2.9
      v-6.6Z
    "
    fill="url(#pkgLogoGradient)"
  />


  <path
    d="
      M34.8 29.4
      39.8 26.5
      v6.6
      l-5 2.9
      v-6.6Z
    "
    fill="url(#pkgLogoGradient)"
  />

</svg>
`;
}


/* =========================================================
   RETURN PAGE UI
   ========================================================= */

function returnLanguage(country) {
  return String(country || "")
    .trim()
    .toUpperCase() === "BR"
      ? "pt-BR"
      : "en";
}


function returnPage({

  state,

  activated = false,

  purchaseId = "",

  paymentStatus = "",

  retryUrl = "",

  provider = "mercadopago",

  amountMinor = null,

  currency = null,

  country = "",

}) {

  const language =
    returnLanguage(country);

  const isPtBr =
    language === "pt-BR";

  const paymentProvider =
    provider === "stripe"
      ? "stripe"
      : "mercadopago";

  const providerName =
    paymentProvider === "stripe"
      ? "Stripe"
      : "Mercado Pago";

  const displayCurrency =
    String(
      currency ||
      (
        paymentProvider === "stripe"
          ? STRIPE_CURRENCY
          : CURRENCY
      )
    ).toUpperCase();

  const fallbackMinor =
    paymentProvider === "stripe"
      ? STRIPE_AMOUNT
      : Math.round(PRICE * 100);

  const hasAmountMinor =
    amountMinor !== null &&
    amountMinor !== undefined &&
    amountMinor !== "";

  const parsedMinor =
    hasAmountMinor
      ? Number(amountMinor)
      : NaN;

  const displayAmountMinor =
    Number.isFinite(parsedMinor) &&
    parsedMinor > 0
      ? parsedMinor
      : fallbackMinor;

  let displayPrice;

  try {
    displayPrice =
      new Intl.NumberFormat(
        isPtBr
          ? "pt-BR"
          : "en-US",
        {
          style: "currency",
          currency: displayCurrency,
        }
      ).format(
        displayAmountMinor / 100
      );
  } catch {
    displayPrice =
      `${displayCurrency} ${(
        displayAmountMinor / 100
      ).toFixed(2)}`;
  }


  const isSuccess =
    state === "success" &&
    activated;


  const isPending =
    state === "pending" ||

    (
      state === "success" &&
      !activated
    );


  const title =
    isPtBr
      ? (
          isSuccess
            ? "Pagamento concluído"
            : isPending
              ? "Confirmando pagamento"
              : "Pagamento não concluído"
        )
      : (
          isSuccess
            ? "Payment complete"
            : isPending
              ? "Confirming payment"
              : "Payment not completed"
        );


  const subtitle =
    isPtBr
      ? (
          isSuccess
            ? `Sua licença Pro foi confirmada diretamente com o ${providerName}.`
            : isPending
              ? "O pagamento foi recebido, mas a confirmação da licença ainda está em processamento."
              : "O pagamento não foi concluído. Nenhuma licença foi ativada."
        )
      : (
          isSuccess
            ? `Your Pro license was confirmed directly with ${providerName}.`
            : isPending
              ? "Your payment was received, but license confirmation is still being processed."
              : "The payment was not completed. No license was activated."
        );


  const statusTitle =
    isPtBr
      ? (
          isSuccess
            ? "Licença Pro ativada"
            : isPending
              ? "Confirmação em andamento"
              : "Licença não ativada"
        )
      : (
          isSuccess
            ? "Pro license activated"
            : isPending
              ? "Confirmation in progress"
              : "License not activated"
        );


  const statusText =
    isPtBr
      ? (
          isSuccess
            ? `Pagamento confirmado via ${providerName}.`
            : isPending
              ? "Você pode atualizar esta página em alguns segundos."
              : "Você pode voltar ao aplicativo e tentar novamente."
        )
      : (
          isSuccess
            ? `Payment confirmed via ${providerName}.`
            : isPending
              ? "You can refresh this page in a few seconds."
              : "You can return to the app and try again."
        );


  const icon =
    isSuccess

      ? `
<svg viewBox="0 0 24 24">
  <path
    d="m5 12.5 4.2 4.2L19 7"
  />
</svg>
`

      : isPending

        ? `
<svg viewBox="0 0 24 24">
  <path
    d="M12 7v5l3 2"
  />
  <circle
    cx="12"
    cy="12"
    r="8"
  />
</svg>
`

        : `
<svg viewBox="0 0 24 24">
  <path
    d="m8 8 8 8M16 8l-8 8"
  />
  <circle
    cx="12"
    cy="12"
    r="8"
  />
</svg>
`;


  const statusClass =
    isSuccess

      ? "good"

      : isPending

        ? "waiting"

        : "bad";


  const deepState =
    isSuccess

      ? "success"

      : isPending

        ? "pending"

        : "failure";


  const deepLink =
    `pkgpocket://checkout/${deepState}` +

    (
      purchaseId

        ? `?purchase_id=${encodeURIComponent(
            purchaseId
          )}`

        : ""
    );


  const retryButton =

    isPending &&
    retryUrl

      ? `
<a
  class="secondary"
  href="${escapeHtml(
    retryUrl
  )}"
>

  <svg viewBox="0 0 24 24">

    <path
      d="
        M20 11
        a8 8 0 1 0
        -2.3 5.7
      "
    />

    <path
      d="
        M20 5
        v6
        h-6
      "
    />

  </svg>

  ${isPtBr
    ? "Atualizar confirmação"
    : "Refresh confirmation"}

</a>
`

      : "";


  const debugStatus =

    paymentStatus

      ? `
<span class="tiny">
  ${isPtBr ? "Status" : "Status"}
  ${escapeHtml(providerName)}:
  ${escapeHtml(
    paymentStatus
  )}
</span>
`

      : "";


  return html(`
<!doctype html>

<html lang="${escapeHtml(language)}">

<head>

<meta charset="utf-8">

<meta
  name="viewport"
  content="
    width=device-width,
    initial-scale=1,
    viewport-fit=cover
  "
>

<meta
  name="theme-color"
  content="#090B11"
>

<title>
PKG Pocket
</title>


<style>

:root {

  color-scheme:
    dark;

  --bg:
    #090b11;

  --panel:
    #111520;

  --panel-2:
    #151a27;

  --border:
    rgba(
      184,
      167,
      232,
      .22
    );

  --text:
    #f4f2fa;

  --muted:
    #aeb5c7;

  --accent:
    #b8a7e8;

  --accent-strong:
    #9b82ed;

  --green:
    #57dda0;

  --amber:
    #e6bd70;

  --red:
    #ff747e;
}


* {

  box-sizing:
    border-box;
}


html,
body {

  margin:
    0;

  min-height:
    100%;

  background:
    var(--bg);

  font-family:
    Inter,
    ui-sans-serif,
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    "Segoe UI",
    sans-serif;

  color:
    var(--text);
}


body {

  min-height:
    100vh;

  overflow-x:
    hidden;

  background:

    radial-gradient(
      circle at 50% -20%,
      rgba(
        118,
        88,
        201,
        .22
      ),
      transparent 42%
    ),

    radial-gradient(
      circle at 100% 90%,
      rgba(
        89,
        68,
        154,
        .17
      ),
      transparent 40%
    ),

    var(--bg);
}


body::before,
body::after {

  content:
    "";

  position:
    fixed;

  width:
    220px;

  height:
    220px;

  border:
    1px solid
    rgba(
      159,
      132,
      238,
      .18
    );

  transform:
    rotate(30deg);

  border-radius:
    28px;

  pointer-events:
    none;

  filter:
    blur(.2px);
}


body::before {

  left:
    -150px;

  bottom:
    8vh;
}


body::after {

  right:
    -165px;

  top:
    12vh;
}


.shell {

  width:
    min(
      100% - 28px,
      760px
    );

  margin:
    0 auto;

  padding:
    44px 0 48px;
}


.brand {

  display:
    flex;

  align-items:
    center;

  justify-content:
    center;

  gap:
    14px;

  margin-bottom:
    28px;
}


.brand-logo {

  width:
    44px;

  height:
    44px;

  filter:
    drop-shadow(
      0 10px 24px
      rgba(
        155,
        130,
        237,
        .20
      )
    );
}


.brand-name {

  font-size:
    clamp(
      24px,
      4vw,
      32px
    );

  font-weight:
    800;

  letter-spacing:
    -.03em;

  color:
    var(--accent);
}


.card {

  position:
    relative;

  overflow:
    hidden;

  background:
    linear-gradient(
      180deg,
      rgba(
        19,
        23,
        35,
        .96
      ),
      rgba(
        13,
        16,
        25,
        .98
      )
    );

  border:
    1px solid
    var(--border);

  border-radius:
    30px;

  padding:
    clamp(
      24px,
      5vw,
      44px
    );

  box-shadow:
    0 28px 80px
    rgba(
      0,
      0,
      0,
      .38
    );
}


.card::before {

  content:
    "";

  position:
    absolute;

  inset:
    0;

  background:
    linear-gradient(
      135deg,
      rgba(
        184,
        167,
        232,
        .06
      ),
      transparent 42%
    );

  pointer-events:
    none;
}


.hero {

  text-align:
    center;

  position:
    relative;
}


.state-icon {

  width:
    86px;

  height:
    86px;

  margin:
    0 auto 20px;

  border-radius:
    50%;

  display:
    grid;

  place-items:
    center;

  border:
    2px solid
    var(--accent);

  background:
    rgba(
      184,
      167,
      232,
      .10
    );

  box-shadow:
    0 0 45px
    rgba(
      155,
      130,
      237,
      .16
    );
}


.state-icon.good {

  border-color:
    var(--accent);

  background:
    rgba(
      184,
      167,
      232,
      .11
    );
}


.state-icon.waiting {

  border-color:
    var(--amber);

  background:
    rgba(
      230,
      189,
      112,
      .08
    );
}


.state-icon.bad {

  border-color:
    var(--red);

  background:
    rgba(
      255,
      116,
      126,
      .08
    );
}


.state-icon svg {

  width:
    42px;

  height:
    42px;

  fill:
    none;

  stroke:
    currentColor;

  stroke-width:
    2.2;

  stroke-linecap:
    round;

  stroke-linejoin:
    round;
}


.state-icon.good {

  color:
    var(--accent);
}


.state-icon.waiting {

  color:
    var(--amber);
}


.state-icon.bad {

  color:
    var(--red);
}


h1 {

  font-size:
    clamp(
      29px,
      5vw,
      42px
    );

  letter-spacing:
    -.04em;

  margin:
    0 0 12px;
}


.lead {

  max-width:
    560px;

  margin:
    0 auto;

  color:
    var(--muted);

  font-size:
    clamp(
      15px,
      2.4vw,
      18px
    );

  line-height:
    1.6;
}


.product {

  display:
    flex;

  align-items:
    center;

  gap:
    16px;

  margin-top:
    30px;

  padding:
    16px;

  border:
    1px solid
    rgba(
      255,
      255,
      255,
      .07
    );

  background:
    rgba(
      255,
      255,
      255,
      .025
    );

  border-radius:
    20px;

  text-align:
    left;
}


.product-icon {

  width:
    54px;

  height:
    54px;

  display:
    grid;

  place-items:
    center;

  border-radius:
    14px;

  background:
    linear-gradient(
      145deg,
      rgba(
        184,
        167,
        232,
        .20
      ),
      rgba(
        103,
        80,
        175,
        .16
      )
    );

  border:
    1px solid
    rgba(
      184,
      167,
      232,
      .15
    );

  flex:
    none;
}


.product-icon
.brand-logo {

  width:
    36px;

  height:
    36px;
}


.product-copy {

  min-width:
    0;

  flex:
    1;
}


.product-title {

  font-weight:
    800;

  font-size:
    18px;
}


.product-subtitle {

  margin-top:
    3px;

  color:
    var(--muted);

  font-size:
    14px;
}


.price {

  font-weight:
    800;

  font-size:
    20px;

  white-space:
    nowrap;
}


.status {

  display:
    flex;

  align-items:
    center;

  gap:
    14px;

  margin-top:
    12px;

  padding:
    16px;

  border-radius:
    20px;

  text-align:
    left;

  border:
    1px solid
    rgba(
      255,
      255,
      255,
      .06
    );
}


.status.good {

  background:
    rgba(
      48,
      167,
      108,
      .08
    );
}


.status.waiting {

  background:
    rgba(
      230,
      189,
      112,
      .07
    );
}


.status.bad {

  background:
    rgba(
      255,
      116,
      126,
      .07
    );
}


.status-dot {

  width:
    42px;

  height:
    42px;

  border-radius:
    50%;

  display:
    grid;

  place-items:
    center;

  flex:
    none;
}


.status.good
.status-dot {

  color:
    var(--green);

  background:
    rgba(
      87,
      221,
      160,
      .12
    );
}


.status.waiting
.status-dot {

  color:
    var(--amber);

  background:
    rgba(
      230,
      189,
      112,
      .12
    );
}


.status.bad
.status-dot {

  color:
    var(--red);

  background:
    rgba(
      255,
      116,
      126,
      .12
    );
}


.status-dot svg {

  width:
    23px;

  height:
    23px;

  fill:
    none;

  stroke:
    currentColor;

  stroke-width:
    2.4;

  stroke-linecap:
    round;

  stroke-linejoin:
    round;
}


.status-copy {

  flex:
    1;
}


.status-title {

  font-weight:
    750;
}


.status-text {

  margin-top:
    3px;

  color:
    var(--muted);

  font-size:
    14px;

  line-height:
    1.4;
}


.tiny {

  display:
    block;

  margin-top:
    5px;

  color:
    #7f879b;

  font-size:
    12px;
}


.actions {

  display:
    grid;

  gap:
    11px;

  margin-top:
    22px;
}


.actions a {

  text-decoration:
    none;

  min-height:
    56px;

  border-radius:
    18px;

  display:
    flex;

  align-items:
    center;

  justify-content:
    center;

  gap:
    10px;

  font-weight:
    800;

  font-size:
    16px;

  transition:
    transform .12s ease,
    filter .12s ease;
}


.actions a:active {

  transform:
    scale(.985);
}


.actions svg {

  width:
    21px;

  height:
    21px;

  fill:
    none;

  stroke:
    currentColor;

  stroke-width:
    2;

  stroke-linecap:
    round;

  stroke-linejoin:
    round;
}


.primary {

  color:
    #11121a;

  background:
    linear-gradient(
      135deg,
      #c2b4f4,
      #9f85ef
    );

  box-shadow:
    0 14px 30px
    rgba(
      133,
      105,
      216,
      .18
    );
}


.primary:hover {

  filter:
    brightness(
      1.04
    );
}


.secondary {

  color:
    var(--text);

  border:
    1px solid
    rgba(
      184,
      167,
      232,
      .38
    );

  background:
    rgba(
      184,
      167,
      232,
      .03
    );
}


.footer {

  text-align:
    center;

  margin-top:
    18px;

  color:
    #747c8f;

  font-size:
    12px;
}


.footer strong {

  color:
    #939bad;

  font-weight:
    650;
}


@media (
  max-width:
    520px
) {

  .shell {

    width:
      min(
        100% - 20px,
        760px
      );

    padding-top:
      24px;
  }


  .card {

    border-radius:
      24px;

    padding:
      24px 18px;
  }


  .brand {

    margin-bottom:
      20px;
  }


  .product {

    gap:
      12px;
  }


  .price {

    font-size:
      18px;
  }
}

</style>

</head>


<body>


<main class="shell">


  <div class="brand">

    ${logoSvg()}

    <div class="brand-name">
      PKG Pocket
    </div>

  </div>


  <section class="card">


    <div class="hero">

      <div
        class="
          state-icon
          ${statusClass}
        "
      >
        ${icon}
      </div>


      <h1>
        ${title}
      </h1>


      <p class="lead">
        ${subtitle}
      </p>

    </div>


    <div class="product">


      <div class="product-icon">

        ${logoSvg()}

      </div>


      <div class="product-copy">

        <div class="product-title">
          PKG Pocket Pro
        </div>

        <div class="product-subtitle">
          Licença permanente
          • compra única
        </div>

      </div>


      <div class="price">
        ${escapeHtml(displayPrice)}
      </div>


    </div>


    <div
      class="
        status
        ${statusClass}
      "
    >


      <div class="status-dot">

        ${icon}

      </div>


      <div class="status-copy">

        <div class="status-title">
          ${statusTitle}
        </div>

        <div class="status-text">
          ${statusText}
        </div>

        ${debugStatus}

      </div>


    </div>


    <div class="actions">


      <a
        class="primary"
        href="${escapeHtml(
          deepLink
        )}"
      >

        <svg viewBox="0 0 24 24">

          <path
            d="
              M10 7H6
              a2 2 0 0 0
              -2 2
              v8
              a2 2 0 0 0
              2 2
              h8
              a2 2 0 0 0
              2-2
              v-4
            "
          />

          <path
            d="
              m13 5
              6 0
              0 6
            "
          />

          <path
            d="
              m19 5
              -9 9
            "
          />

        </svg>


        ${isPtBr
    ? "Voltar ao aplicativo"
    : "Return to app"}

      </a>


      ${retryButton}


    </div>


  </section>


  <div class="footer">

    ${isPtBr
      ? "Pagamento processado com segurança pelo"
      : "Payment securely processed by"}

    <strong>
      ${escapeHtml(providerName)}
    </strong>.

  </div>


</main>


</body>

</html>
`);
}


/* =========================================================
   AUTOMATIC RETURN RECONCILE
   ========================================================= */

async function handleStripePaymentReturn(
  request,
  env,
  routeState
) {

  const url =
    new URL(
      request.url
    );

  const purchaseId =
    String(
      url.searchParams.get(
        "purchase_id"
      ) || ""
    );

  const sessionId =
    String(
      url.searchParams.get(
        "session_id"
      ) || ""
    );


  const render = async ({
    state,
    activated = false,
    paymentStatus = "",
    amountMinor = null,
    currency = null,
  }) => {
    const purchase =
      purchaseId
        ? await env.LICENSES.get(
            `purchase:${purchaseId}`,
            "json"
          )
        : null;

    return returnPage({
      state,
      activated,
      purchaseId,
      paymentStatus,
      retryUrl:
        request.url,
      provider:
        "stripe",
      amountMinor,
      currency,
      country:
        purchase?.country || "",
    });
  };


  if (
    routeState ===
      "failure"
  ) {

    return await render({
      state:
        "failure",

      paymentStatus:
        "checkout cancelado",
    });
  }


  if (
    !purchaseId.startsWith(
      "pp_"
    ) ||

    !sessionId.startsWith(
      "cs_"
    )
  ) {

    return await render({
      state:
        "pending",

      paymentStatus:
        "sessão de pagamento ausente",
    });
  }


  try {

    const session =
      await stripeRequest(
        env,

        `/checkout/sessions/${encodeURIComponent(
          sessionId
        )}`,

        {
          method:
            "GET",
        }
      );


    const sessionPurchaseId =
      String(
        session?.metadata
          ?.purchase_id ||

        session
          ?.client_reference_id ||

        ""
      );


    if (
      sessionPurchaseId !==
        purchaseId
    ) {

      throw new Error(
        "Checkout Session não pertence à compra"
      );
    }


    const result =
      await processStripeSession(
        env,
        session
      );


    const paymentStatus =
      String(
        session
          ?.payment_status ||
        "unknown"
      )
        .toLowerCase();


    const presentment =
      session?.presentment_details ||
      null;


    const amountMinor =
      Number(
        presentment?.presentment_amount ??
        session?.amount_total ??
        0
      );


    const currency =
      String(
        presentment?.presentment_currency ||
        session?.currency ||
        STRIPE_CURRENCY
      )
        .toUpperCase();


    if (
      paymentStatus ===
        "paid" &&

      result.activated
    ) {

      return await render({
        state:
          "success",

        activated:
          true,

        paymentStatus,

        amountMinor,

        currency,
      });
    }


    return await render({
      state:
        "pending",

      activated:
        Boolean(
          result.activated
        ),

      paymentStatus,

      amountMinor,

      currency,
    });


  } catch (error) {

    console.error(
      "Stripe return confirmation error",
      error
    );


    return await render({
      state:
        "pending",

      paymentStatus:
        "confirmação temporariamente indisponível",
    });
  }
}


async function handlePaymentReturn(
  request,
  env,
  routeState
) {

  const url =
    new URL(
      request.url
    );


  const provider =
    String(
      url.searchParams.get(
        "provider"
      ) || ""
    )
      .toLowerCase();


  if (
    provider ===
      "stripe"
  ) {

    return handleStripePaymentReturn(
      request,
      env,
      routeState
    );
  }



  /*
   * Mercado Pago envia os dois
   * normalmente com o mesmo ID.
   */
  const paymentId =

    url.searchParams.get(
      "payment_id"
    )

    ||

    url.searchParams.get(
      "collection_id"
    );


  const fallbackPurchaseId =
    String(

      url.searchParams.get(
        "external_reference"
      )

      ||

      ""
    );


  /*
   * Não confiamos em:
   *
   * status=approved
   * collection_status=approved
   *
   * vindos da URL.
   *
   * Precisamos sempre consultar
   * /v1/payments/{id}.
   */
  if (!paymentId) {

    return returnPage({

      state:
        routeState ===
          "failure"

          ? "failure"

          : "pending",

      activated:
        false,

      purchaseId:
        fallbackPurchaseId,

      paymentStatus:
        "payment_id ausente",

      retryUrl:
        request.url,
      country:
        "BR",
    });
  }


  try {

    /*
     * Aqui está o fallback
     * automático.
     */
    const payment =
      await mpRequest(
        env,

        `/v1/payments/${encodeURIComponent(
          paymentId
        )}`,

        {
          method:
            "GET",
        }
      );


    /*
     * Mesma rotina usada pelo
     * Webhook e pelo reconcile.
     *
     * Portanto:
     *
     * valida valor
     * valida moeda
     * valida collector
     * valida external_reference
     * e só então ativa Pro.
     */
    const result =
      await processPayment(
        env,
        payment
      );


    const paymentStatus =
      String(
        payment.status ||
        ""
      )
        .toLowerCase();


    const purchaseId =
      result.purchaseId
      ||
      fallbackPurchaseId;


    /*
     * Dados confirmados diretamente pelo Mercado Pago.
     * Nunca dependemos do valor vindo da URL de retorno.
     */
    const amountMinor =
      Number.isFinite(
        Number(payment.transaction_amount)
      )
        ? Math.round(
            Number(payment.transaction_amount) * 100
          )
        : null;

    const currency =
      String(
        payment.currency_id ||
        CURRENCY
      )
        .trim()
        .toUpperCase();


    /* ---------- APROVADO ---------- */

    if (
      paymentStatus ===
        "approved" &&

      result.activated
    ) {

      return returnPage({

        state:
          "success",

        activated:
          true,

        purchaseId,

        paymentStatus:
          `${payment.status}${
            payment.status_detail
              ? ` / ${payment.status_detail}`
              : ""
          }`,

        retryUrl:
          request.url,

        provider:
          "mercadopago",

        amountMinor,

        currency,

        country:
          "BR",
      });
    }


    /* ---------- PENDENTE ---------- */

    if (
      paymentStatus ===
        "pending"

      ||

      paymentStatus ===
        "in_process"

      ||

      paymentStatus ===
        "in_mediation"
    ) {

      return returnPage({

        state:
          "pending",

        activated:
          false,

        purchaseId,

        paymentStatus:
          `${payment.status}${
            payment.status_detail
              ? ` / ${payment.status_detail}`
              : ""
          }`,

        retryUrl:
          request.url,

        provider:
          "mercadopago",

        amountMinor,

        currency,

        country:
          "BR",
      });
    }


    /* ---------- RECUSADO / CANCELADO ---------- */

    return returnPage({

      state:
        "failure",

      activated:
        false,

      purchaseId,

      paymentStatus:
        `${payment.status}${
          payment.status_detail
            ? ` / ${payment.status_detail}`
            : ""
        }`,

      retryUrl:
        request.url,

      provider:
        "mercadopago",

      amountMinor,

      currency,

      country:
        "BR",
    });


  } catch (error) {

    console.error(
      "Return payment confirmation error",
      error
    );


    /*
     * Se o Mercado Pago estiver
     * temporariamente indisponível,
     * NÃO mostramos falso sucesso.
     */
    return returnPage({

      state:
        routeState ===
          "failure"

          ? "failure"

          : "pending",

      activated:
        false,

      purchaseId:
        fallbackPurchaseId,

      paymentStatus:
        "confirmação temporariamente indisponível",

      retryUrl:
        request.url,
      country:
        "BR",
    });
  }
}


/* =========================================================
   WORKER
   ========================================================= */

export default {

  async fetch(
    request,
    env
  ) {

    const url =
      new URL(
        request.url
      );


    const method =
      request.method
        .toUpperCase();


    try {


      /* ---------- HEALTH ---------- */

      if (
        method === "GET" &&

        (
          url.pathname ===
            "/"

          ||

          url.pathname ===
            "/health"
        )
      ) {

        return json({

          ok:
            true,

          service:
            "pkg-pocket-api",

          version:
            6,

          payment_flow:
            "checkout-pro-preferences",

          return_reconcile:
            "enabled",

          environment:
            SANDBOX
              ? "sandbox"
              : "production",

          price:
            PRICE,

          currency:
            CURRENCY,

          kv:
            env.LICENSES
              ? "connected"
              : "missing",

          mercado_pago:
            env.MERCADOPAGO_ACCESS_TOKEN
              ? "configured"
              : "missing",

          stripe:
            env.STRIPE_SECRET_KEY
              ? "configured"
              : "missing",

          stripe_price:
            STRIPE_PRICE_ID,

          stripe_webhook:
            env.STRIPE_WEBHOOK_SECRET
              ? "configured"
              : "missing",

          webhook_secret:
            env.MERCADOPAGO_WEBHOOK_SECRET
              ? "configured"
              : "missing",

          license_secret:
            env.LICENSE_TOKEN_SECRET
              ? "configured"
              : "missing",
        });
      }


      /* ---------- PRO QUOTE ---------- */

      if (
        method === "GET" &&

        url.pathname ===
          "/v1/pro/quote"
      ) {

        return proQuote(
          request
        );
      }


      /* ---------- CHECKOUT ---------- */

      if (
        method === "POST" &&

        url.pathname ===
          "/v1/pro/checkout"
      ) {

        return createCheckout(
          request,
          env
        );
      }


      /* ---------- WEBHOOK ---------- */

      if (
        method === "POST" &&

        url.pathname ===
          "/v1/mercadopago/webhook"
      ) {

        return webhook(
          request,
          env
        );
      }


      /* ---------- STRIPE WEBHOOK ---------- */

      if (
        method === "POST" &&

        url.pathname ===
          "/v1/stripe/webhook"
      ) {

        return stripeWebhook(
          request,
          env
        );
      }


      /* ---------- FALLBACK APP ---------- */

      if (
        method === "POST" &&

        url.pathname ===
          "/v1/pro/reconcile"
      ) {

        return reconcile(
          request,
          env
        );
      }


      /* ---------- PRO STATUS ---------- */

      if (
        method === "GET" &&

        url.pathname ===
          "/v1/pro/status"
      ) {

        return proStatus(
          request,
          env
        );
      }


      /* ---------- RETURN SUCCESS ---------- */

      if (
        method === "GET" &&

        url.pathname ===
          "/v1/pro/return/success"
      ) {

        return handlePaymentReturn(
          request,
          env,
          "success"
        );
      }


      /* ---------- RETURN PENDING ---------- */

      if (
        method === "GET" &&

        url.pathname ===
          "/v1/pro/return/pending"
      ) {

        return handlePaymentReturn(
          request,
          env,
          "pending"
        );
      }


      /* ---------- RETURN FAILURE ---------- */

      if (
        method === "GET" &&

        url.pathname ===
          "/v1/pro/return/failure"
      ) {

        return handlePaymentReturn(
          request,
          env,
          "failure"
        );
      }


      /* ---------- FUTURO ---------- */

      if (
        method === "POST" &&

        (
          url.pathname ===
            "/v1/pro/restore/request"

          ||

          url.pathname ===
            "/v1/pro/restore/verify"

          ||

          url.pathname ===
            "/v1/pro/transfer"
        )
      ) {

        return json(
          {
            ok:
              false,

            code:
              "NOT_CONFIGURED",
          },
          503
        );
      }


      return json(
        {
          ok:
            false,

          code:
            "NOT_FOUND",
        },
        404
      );


    } catch (error) {

      console.error(
        "Unhandled Worker error",
        error
      );


      return json(
        {
          ok:
            false,

          code:
            "INTERNAL_ERROR",

          ...(SANDBOX
            ? {
                message:
                  String(
                    error?.message ||
                    error
                  ),
              }
            : {}),
        },
        500
      );
    }
  },
};
