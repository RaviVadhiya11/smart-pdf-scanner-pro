import { withSupabase } from "npm:@supabase/server@1";

export default {
  fetch: withSupabase({ auth: "user" }, async (req, ctx) => {
    try {
      if (req.method !== "POST") {
        return Response.json(
          { success: false, error: "Method not allowed." },
          { status: 405 },
        );
      }

      const payload = await req.json().catch(() => ({}));
      if (payload?.confirm !== true) {
        return Response.json(
          { success: false, error: "Explicit account deletion confirmation is required." },
          { status: 400 },
        );
      }

      // The wrapper verifies the user's JWT. Never accept a user ID from the request.
      const userId = ctx.userClaims?.sub;
      if (!userId) {
        return Response.json(
          { success: false, error: "Please sign in again before deleting your account." },
          { status: 401 },
        );
      }

      // This client uses the server-side secret key and bypasses RLS.
      const admin = ctx.supabaseAdmin;
      const bucket = admin.storage.from("docnexa-pdfs");

      // Collect only objects in this authenticated user's UUID folder.
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

          if (error) {
            console.error("Storage listing failed:", error);
            throw new Error("Could not list your cloud documents. Your account was not deleted.");
          }

          if (!data || data.length === 0) break;

          for (const item of data) {
            const path = `${folder}/${item.name}`;
            if (item.id === null) {
              paths.push(...await collectPaths(path));
            } else {
              paths.push(path);
            }
          }

          if (data.length < pageSize) break;
          offset += data.length;
        }

        return paths;
      }

      const objectPaths = await collectPaths(userId);

      for (let i = 0; i < objectPaths.length; i += 100) {
        const { error } = await bucket.remove(objectPaths.slice(i, i + 100));
        if (error) {
          console.error("Storage removal failed:", error);
          throw new Error("Could not remove all cloud documents. Your account was not deleted.");
        }
      }

      // Delete only the account identified by the verified JWT.
      const { error: deleteError } = await admin.auth.admin.deleteUser(userId);
      if (deleteError) {
        console.error("Supabase Auth account deletion failed:", deleteError);
        return Response.json(
          {
            success: false,
            error: "Cloud files were removed, but account deletion could not be completed. Please contact support.",
          },
          { status: 500 },
        );
      }

      return Response.json({
        success: true,
        message: "Your DocNexa cloud account and synced documents have been deleted.",
      });
    } catch (error) {
      console.error("DocNexa account deletion failed:", error);
      return Response.json(
        {
          success: false,
          error: error instanceof Error
            ? error.message
            : "Account deletion could not be completed.",
        },
        { status: 500 },
      );
    }
  }),
};
