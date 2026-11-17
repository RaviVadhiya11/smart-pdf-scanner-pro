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

async function collectObjectPaths(
  storage: ReturnType<ReturnType<typeof createClient>["storage"]["from"]>,
  folder: string,
): Promise<string[]> {
  const paths: string[] = [];
  let offset = 0;
  const pageSize = 1000;

  while (true) {
    const { data, error } = await storage.list(folder, {
      limit: pageSize,
      offset,
      sortBy: { column: "name", order: "asc" },
    });
    if (error) throw new Error(`Could not list account files: ${error.message}`);
    if (!data || data.length === 0) break;

    for (const item of data) {
      const path = folder ? `${folder}/${item.name}` : item.name;
      // Storage folders have a null id; files have an id.
      if (item.id === null) {
        paths.push(...await collectObjectPaths(storage, path));
      } else {
        paths.push(path);
      }
    }

    if (data.length < pageSize) break;
    offset += data.length;
  }
  return paths;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return jsonResponse(405, { success: false, error: "Method not allowed." });
  }

  try {
    const authorization = req.headers.get("Authorization") ?? "";
    const match = authorization.match(/^Bearer\s+(.+)$/i);
    if (!match) {
      return jsonResponse(401, { success: false, error: "Please sign in again before deleting your account." });
    }

    const projectUrl = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!projectUrl || !anonKey || !serviceRoleKey) {
      console.error("Required Supabase environment variables are missing.");
      return jsonResponse(500, { success: false, error: "Account deletion is not configured yet. Please contact support." });
    }

    const payload = await req.json().catch(() => ({}));
    if (payload?.confirm !== true) {
      return jsonResponse(400, { success: false, error: "Explicit account deletion confirmation is required." });
    }

    // Validate the caller's access token with Supabase Auth before using admin privileges.
    const userClient = createClient(projectUrl, anonKey, {
      auth: { autoRefreshToken: false, persistSession: false },
      global: { headers: { Authorization: `Bearer ${match[1]}` } },
    });
    const { data: userData, error: userError } = await userClient.auth.getUser(match[1]);
    if (userError || !userData.user?.id) {
      return jsonResponse(401, { success: false, error: "Your sign-in session is invalid or expired. Please sign in again." });
    }

    const userId = userData.user.id;
    const adminClient = createClient(projectUrl, serviceRoleKey, {
      auth: { autoRefreshToken: false, persistSession: false },
    });

    // The app stores each user's cloud PDFs under a folder named with that user's UUID.
    const bucket = adminClient.storage.from("docnexa-pdfs");
    const objectPaths = await collectObjectPaths(bucket, userId);

    // Remove files in manageable batches. If storage cleanup fails, keep the Auth account intact.
    for (let i = 0; i < objectPaths.length; i += 100) {
      const batch = objectPaths.slice(i, i + 100);
      const { error } = await bucket.remove(batch);
      if (error) {
        throw new Error(`Could not remove cloud documents: ${error.message}`);
      }
    }

    // Delete only the authenticated caller's account.
    const { error: deleteError } = await adminClient.auth.admin.deleteUser(userId);
    if (deleteError) {
      throw new Error(`Cloud files were removed, but account deletion failed: ${deleteError.message}`);
    }

    return jsonResponse(200, {
      success: true,
      message: "Your DocNexa cloud account and synced documents have been deleted.",
    });
  } catch (error) {
    console.error("DocNexa account deletion failed:", error);
    const message = error instanceof Error ? error.message : "Account deletion could not be completed.";
    return jsonResponse(500, { success: false, error: message });
  }
});
