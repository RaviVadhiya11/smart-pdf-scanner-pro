// Deploy only to the DocNexa Supabase project. Keep service account credentials server-side.
const PACKAGE = "com.pixelfire.smartpdfscanner";
const PRODUCT = "docnexa_premium";
const google = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE;
const respond = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const encode = (value: Uint8Array) => btoa(String.fromCharCode(...value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const enc = new TextEncoder();
async function googleToken() {
  const account = JSON.parse(Deno.env.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON") || "{}");
  if (!account.client_email || !account.private_key) throw new Error("Missing service account");
  const now = Math.floor(Date.now() / 1000);
  const header = encode(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = encode(enc.encode(JSON.stringify({ iss: account.client_email, scope: "https://www.googleapis.com/auth/androidpublisher", aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 300 })));
  const keyData = Uint8Array.from(atob(account.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, "")), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", keyData, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const payload = header + "." + claims;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(payload));
  const res = await fetch("https://oauth2.googleapis.com/token", { method: "POST", body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: payload + "." + encode(new Uint8Array(signature)) }) });
  if (!res.ok) throw new Error("Google authentication failed");
  const token = (await res.json()).access_token;
  if (!token) throw new Error("Google authentication unavailable");
  return token;
}
Deno.serve(async req => {
  if (req.method !== "POST") return respond({ error: "Method not allowed" }, 405);
  try {
    if (Deno.env.get("PLAY_BILLING_ENABLED") !== "true") return respond({ ready: false, error: "Subscriptions are not activated" }, 503);
    const auth = req.headers.get("authorization") || "";
    if (!auth.startsWith("Bearer ")) return respond({ error: "Sign in required" }, 401);
    const userRes = await fetch(Deno.env.get("SUPABASE_URL") + "/auth/v1/user", { headers: { authorization: auth, apikey: Deno.env.get("SUPABASE_ANON_KEY") || "" } });
    if (!userRes.ok) return respond({ error: "Sign in again" }, 401);
    const user = await userRes.json();
    if (!user.id) return respond({ error: "Sign in required" }, 401);
    const body = await req.json();
    const token = await googleToken();
    const headers = { authorization: "Bearer " + token, "content-type": "application/json" };
    if (body.action === "ready") {
      const product = await fetch(google + "/subscriptions/" + PRODUCT, { headers });
      if (!product.ok) return respond({ ready: false, error: "Subscription configuration unavailable" }, 503);
      const config = await product.json();
      const active = (config.basePlans || []).filter((p: any) => p.state === "ACTIVE" && p.autoRenewingBasePlan && ["premium-monthly", "premium-yearly"].includes(p.basePlanId));
      if (!active.length) return respond({ ready: false, error: "No active auto-renewing Premium plans" }, 503);
      return respond({ ready: true, activePlans: active.map((p: any) => p.basePlanId) });
    }
    if (body.action !== "verify" || typeof body.purchaseToken !== "string" || body.purchaseToken.length > 4096 || !body.purchaseToken) return respond({ error: "Invalid request" }, 400);
    const purchaseToken = encodeURIComponent(body.purchaseToken);
    const res = await fetch(google + "/purchases/subscriptionsv2/tokens/" + purchaseToken, { headers });
    if (!res.ok) return respond({ error: "Google purchase verification unavailable" }, 503);
    const purchase = await res.json();
    const digest = await crypto.subtle.digest("SHA-256", enc.encode(user.id));
    const accountId = Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, "0")).join("");
    if (purchase.externalAccountIdentifiers?.obfuscatedExternalAccountId !== accountId) return respond({ error: "Purchase belongs to another account" }, 403);
    const states = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"];
    const expiries = (purchase.lineItems || []).filter((item: any) => item.productId === PRODUCT && ["premium-monthly", "premium-yearly"].includes(item.offerDetails?.basePlanId)).map((item: any) => Date.parse(item.expiryTime)).filter(Number.isFinite);
    const expiresAt = Math.max(0, ...expiries);
    if (!states.includes(purchase.subscriptionState) || expiresAt <= Date.now()) return respond({ active: false, expiresAt: 0 });
    if (purchase.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING") {
      const ack = await fetch(google + "/purchases/subscriptions/" + PRODUCT + "/tokens/" + purchaseToken + ":acknowledge", { method: "POST", headers, body: "{}" });
      if (!ack.ok) return respond({ error: "Purchase acknowledgement unavailable; restore later" }, 503);
    } else if (purchase.acknowledgementState !== "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED") {
      return respond({ error: "Unrecognized purchase state" }, 503);
    }
    return respond({ active: true, expiresAt });
  } catch {
    return respond({ error: "Subscription service unavailable" }, 503);
  }
});
