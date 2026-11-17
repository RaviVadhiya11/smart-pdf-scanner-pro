import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function jsonResponse(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return jsonResponse(405, { success: false, error: "Method not allowed." });

  try {
    const authorization = req.headers.get("Authorization") ?? "";
    const match = authorization.match(/^Bearer\s+(.+)$/i);
    if (!match) {
      return jsonResponse(401, { success: false, error: "Please sign in again before deleting your account." });
    }

    const projectUrl = Deno.env.get("SUPABASE_URL");
    const publishableKeys = JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}");
    const secretKeys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    const publishableKey = publishableKeys["default"];
    const secretKey = secretKeys["default"];
    if (!projectUrl || !publishableKey || !secretKey) {
      console.error("Required Supabase environment variables are missing.");
      return jsonResponse(500, { success: false, error: "Account deletion is not configured yet. Please contact support." });
    }

    const payload = await req.json().catch(() => ({}));
    if (payload?.confirm !== true) {
      return jsonResponse(400, { success: false, error: "Explicit account deletion confirmation is required." });
    }

    // Verify the caller's JWT before using the server-side admin client.
    const userClient = createClient(projectUrl, publishableKey, {
      auth: { autoRefreshToken: false, persistSession: false },
      global: { headers: { Authorization: `Bearer ${match[1]}` } },
    });
    const { data: userData, error: userError } = await userClient.auth.getUser(match[1]);
    if (userError || !userData.user?.id) {
      return jsonResponse(401, { success: false, error: "Your sign-in session is invalid or expired. Please sign in again." });
    }

    const userId = userData.user.id;
    const adminClient = createClient(projectUrl, secretKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });
    const bucket = adminClient.storage.from("docnexa-pdfs");

    // Recursively collect only objects inside this authenticated user's UUID folder.
    async function collectPaths(folder: string): Promise<string[]> {
      const paths: string[] = [];
      let offset = 0;
      const pageSize = 1000;
      while (true) {
        const { data, error } = await bucket.list(folder, {
          limit: pageSize,
          offset,
          sortBy: { column: "name", order: "asc" },
        });
        if (error) throw new Error("Could not list cloud documents.");
        if (!data || data.length === 0) break;

        for (const item of data) {
          const path = `${folder}/${item.name}`;
          if (item.id === null) paths.push(...await collectPaths(path));
          else paths.push(path);
        }
        if (data.length < pageSize) break;
        offset += data.length;
      }
      return paths;
    }

    const objectPaths = await collectPaths(userId);
    for (let i = 0; i < objectPaths.length; i += 100) {
      const { error } = await bucket.remove(objectPaths.slice(i, i + 100));
      if (error) throw new Error("Could not remove all cloud documents. The account was not deleted.");
    }

    // Delete only the account whose access token was verified above.
    const { error: deleteError } = await adminClient.auth.admin.deleteUser(userId);
    if (deleteError) {
      console.error("Supabase Auth account deletion failed:", deleteError);
      return jsonResponse(500, {
        success: false,
        error: "Cloud files were removed, but account deletion could not be completed. Please contact support.",
      });
    }

    return jsonResponse(200, {
      success: true,
      message: "Your DocNexa cloud account and synced documents have been deleted.",
    });
  } catch (error) {
    console.error("DocNexa account deletion failed:", error);
    return jsonResponse(500, {
      success: false,
      error: error instanceof Error ? error.message : "Account deletion could not be completed.",
    });
  }
});
