const PRO_STATUS_BASE =
  "https://pkg-pocket-api.wbjoaovictor.workers.dev";

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });

const normalizeEmail = (value) =>
  String(value || "").trim().toLowerCase();

const validEmail = (value) =>
  /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value);

const sha256 = async (text) => {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
};

const cleanText = (value, max = 300) =>
  String(value || "").slice(0, max);

const sanitizeRecords = (records) => {
  if (!Array.isArray(records)) return [];

  const out = [];
  const seen = new Set();

  for (const raw of records.slice(0, 250)) {
    const key = cleanText(raw?.key, 500).trim();
    if (!key || seen.has(key)) continue;
    seen.add(key);

    out.push({
      key,
      title: cleanText(raw?.title, 300),
      titleId: cleanText(raw?.titleId, 80),
      contentId: cleanText(raw?.contentId, 220),
      version: cleanText(raw?.version, 80),
      kind: cleanText(raw?.kind, 40),
      fileName: cleanText(raw?.fileName, 500),
      installedAt: Math.max(
        0,
        Number(raw?.installedAt) || 0
      ),
    });
  }

  return out;
};

const verifyPro = async (env, email) => {
  const query = encodeURIComponent(email);

  let response;

  if (env.PRO_API && typeof env.PRO_API.fetch === "function") {
    response = await env.PRO_API.fetch(
      new Request(
        `https://pkg-pocket-api.internal/v1/pro/status?email=${query}`,
        {
          method: "GET",
          headers: {
            accept: "application/json",
            "user-agent": "pkg-pocket-library-sync/2",
          },
        }
      )
    );
  } else {
    response = await fetch(
      `${PRO_STATUS_BASE}/v1/pro/status?email=${query}`,
      {
        headers: {
          accept: "application/json",
          "user-agent": "pkg-pocket-library-sync/2",
        },
      }
    );
  }

  if (!response.ok) return false;

  const data = await response.json().catch(() => ({}));
  return data?.pro === true;
};

const readBody = async (request) => {
  try {
    return await request.json();
  } catch {
    return null;
  }
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({
        ok: true,
        service: "pkg-pocket-library",
        version: 2,
      });
    }

    if (
      url.pathname !== "/v1/library/sync" &&
      url.pathname !== "/v1/library/restore"
    ) {
      return json({ error: "not_found" }, 404);
    }

    if (request.method !== "POST") {
      return json({ error: "method_not_allowed" }, 405);
    }

    const body = await readBody(request);
    if (!body) {
      return json({ error: "invalid_json" }, 400);
    }

    const email = normalizeEmail(body.email);
    if (!validEmail(email)) {
      return json({ error: "invalid_email" }, 400);
    }

    const active = await verifyPro(env, email);
    if (!active) {
      return json(
        {
          error: "pro_required",
          message: "Active Pro license required",
        },
        403
      );
    }

    const emailHash = await sha256(email);
    const key = `library:${emailHash}`;

    if (url.pathname === "/v1/library/restore") {
      const saved =
        (await env.LIBRARY.get(key, "json")) || {
          revision: 0,
          updated_at: 0,
          records: [],
        };

      return json({
        ok: true,
        revision: Number(saved.revision) || 0,
        updated_at: Number(saved.updated_at) || 0,
        records: sanitizeRecords(saved.records),
      });
    }

    const current =
      (await env.LIBRARY.get(key, "json")) || {
        revision: 0,
      };

    const records = sanitizeRecords(body.records);

    const next = {
      revision: (Number(current.revision) || 0) + 1,
      updated_at: Date.now(),
      records,
    };

    await env.LIBRARY.put(
      key,
      JSON.stringify(next)
    );

    return json({
      ok: true,
      revision: next.revision,
      updated_at: next.updated_at,
      count: records.length,
    });
  },
};
