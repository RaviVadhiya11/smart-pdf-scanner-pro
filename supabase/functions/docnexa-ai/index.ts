import { withSupabase } from "npm:@supabase/server@1";

const OPENAI_API_URL = "https://api.openai.com/v1/responses";

function instructionFor(task: string, prompt: string): string {
  const extra = prompt.trim();
  switch (task) {
    case "summary":
      return "Summarize the document clearly. Preserve important names, dates, amounts, decisions and action items. Do not invent facts.";
    case "ask":
      return "Answer the user's question using only the supplied document text. If the answer is not in the document, say that clearly. Question: " + extra;
    case "translate":
      return "Translate the supplied document text according to this request: " + (extra || "Translate it into clear English.") + " Preserve meaning, names, dates and numbers.";
    case "ocr":
      return "Clean up OCR text from the document. Fix obvious OCR spacing and character errors while preserving the original meaning and structure. Do not add new facts.";
    default:
      return "Help with the supplied document using only its contents. " + extra;
  }
}

function responseText(json: any): string {
  if (typeof json?.output_text === "string" && json.output_text.trim()) {
    return json.output_text.trim();
  }
  const chunks: string[] = [];
  for (const item of Array.isArray(json?.output) ? json.output : []) {
    for (const part of Array.isArray(item?.content) ? item.content : []) {
      if ((part?.type === "output_text" || part?.type === "text") && typeof part?.text === "string") {
        chunks.push(part.text);
      }
    }
  }
  return chunks.join("\n").trim();
}

export default {
  fetch: withSupabase({ auth: "user" }, async (req, ctx) => {
    try {
      if (req.method !== "POST") {
        return Response.json({ success: false, error: "Method not allowed." }, { status: 405 });
      }
      if (!ctx.userClaims?.id) {
        return Response.json({ success: false, error: "Please sign in again." }, { status: 401 });
      }

      const apiKey = Deno.env.get("OPENAI_API_KEY")?.trim();
      if (!apiKey) {
        console.error("OPENAI_API_KEY is not configured.");
        return Response.json({ success: false, error: "DocNexa AI is not configured on the server." }, { status: 503 });
      }
      const model = Deno.env.get("OPENAI_MODEL")?.trim() || "gpt-6.2-astra";
      const body = await req.json().catch(() => ({}));
      const task = String(body?.task || "").trim().toLowerCase();
      const prompt = String(body?.prompt || "").trim().slice(0, 2000);
      const documentText = String(body?.documentText || "").trim();

      if (!["summary", "ask", "translate", "ocr"].includes(task)) {
        return Response.json({ success: false, error: "Unsupported AI task." }, { status: 400 });
      }
      if (!documentText) {
        return Response.json({ success: false, error: "No document text was supplied." }, { status: 400 });
      }
      if (documentText.length > 30000) {
        return Response.json({ success: false, error: "Document text is too large. Split the PDF and try again." }, { status: 413 });
      }

      const instruction = instructionFor(task, prompt);
      const input =
        instruction +
        "\n\nDOCUMENT TEXT\n---\n" +
        documentText +
        "\n---\nReturn only the useful answer for the user.";

      const response = await fetch(OPENAI_API_URL, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model,
          input,
        }),
      });

      const raw = await response.text();
      let json: any = {};
      try { json = JSON.parse(raw); } catch { json = {}; }

      if (!response.ok) {
        console.error("OpenAI Responses API error", response.status, raw.slice(0, 1000));
        const message =
          json?.error?.message ||
          (response.status === 429 ? "AI service is busy. Please try again shortly." : "AI processing failed.");
        return Response.json({ success: false, error: message }, { status: response.status === 429 ? 429 : 502 });
      }

      const answer = responseText(json);
      if (!answer) {
        console.error("OpenAI response did not contain output text.", raw.slice(0, 1000));
        return Response.json({ success: false, error: "The AI service returned an empty answer." }, { status: 502 });
      }

      return Response.json({ success: true, answer });
    } catch (error) {
      console.error("DocNexa AI function failed:", error);
      return Response.json(
        { success: false, error: error instanceof Error ? error.message : "AI request failed." },
        { status: 500 },
      );
    }
  }),
};
