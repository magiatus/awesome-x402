// Daily enhancer for awesome-x402.
// Calls the Anthropic Messages API to generate ONE new, self-contained README
// section, then inserts it (and a Table-of-Contents entry) deterministically.
//
// Env:
//   ANTHROPIC_API_KEY (required)
//   ANTHROPIC_MODEL   (optional, default below)
//
// Exit codes: 0 = section added (changes written), 78 = nothing to do (skip),
//             1 = hard error.

import { readFileSync, writeFileSync } from "node:fs";

const README = "README.md";
const MODEL = process.env.ANTHROPIC_MODEL || "claude-sonnet-4-6";
const API_KEY = process.env.ANTHROPIC_API_KEY;

// Body section that new content is inserted *before*, and the matching TOC line.
const BODY_ANCHOR = "## 🤝 Contributing";
const TOC_ANCHOR = "- [🤝 Contributing](#-contributing)";

if (!API_KEY) {
  console.error("ANTHROPIC_API_KEY is not set.");
  process.exit(1);
}

const readme = readFileSync(README, "utf8");

if (!readme.includes(BODY_ANCHOR) || !readme.includes(TOC_ANCHOR)) {
  console.error("Could not find insertion anchors in README — aborting safely.");
  process.exit(1);
}

// Collect existing section titles (## ... lines) so the model avoids duplicates.
const existingSections = [...readme.matchAll(/^##\s+(.+)$/gm)].map((m) => m[1].trim());

const systemPrompt = `Du bist ein Maintainer der "Awesome X402" Liste (kuratierte Markdown-Liste rund um das x402-Bezahlprotokoll: HTTP 402, USDC auf Base, ~2s Settlement, EIP-3009 TransferWithAuthorization, Facilitators, MCP).

Aufgabe: Schlage GENAU EINE neue, eigenständige README-Sektion vor, die echten Mehrwert bietet und noch nicht existiert.

Harte Regeln:
- Inhalt muss korrekt und an die genannten Fakten gebunden sein. KEINE erfundenen externen Links oder URLs. Interne Anker-Links (z.B. (#-faq)) sind erlaubt.
- Stil wie der Rest der Liste: Sektion beginnt mit "## <emoji> <Titel>". Knappe, nützliche Markdown-Inhalte (Tabellen, Bullet-Listen, Code-Blöcke ok).
- Keine Doppelung zu existierenden Sektionen.
- Antworte AUSSCHLIESSLICH mit einem JSON-Objekt, kein weiterer Text, kein Markdown-Codefence.

JSON-Form:
{
  "title": "❓ Beispieltitel",            // ohne führendes "## "
  "anchor": "#-beispieltitel",            // GitHub-Slug: lowercase, Emojis/Sonderzeichen entfernt, Spaces -> '-'
  "tocEntry": "- [❓ Beispieltitel](#-beispieltitel)",
  "markdown": "## ❓ Beispieltitel\\n\\n<voller Sektionsinhalt>\\n"
}`;

const userPrompt = `Existierende Sektionen (nicht duplizieren):\n${existingSections
  .map((s) => `- ${s}`)
  .join("\n")}\n\nGib EINE neue Sektion als JSON zurück.`;

async function callClaude() {
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 2000,
      system: systemPrompt,
      messages: [{ role: "user", content: userPrompt }],
    }),
  });
  if (!res.ok) {
    throw new Error(`Anthropic API ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  const text = (data.content || [])
    .filter((b) => b.type === "text")
    .map((b) => b.text)
    .join("")
    .trim();
  return text;
}

function parseSection(text) {
  // Tolerate an accidental ```json fence.
  const cleaned = text.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "").trim();
  const obj = JSON.parse(cleaned);
  for (const k of ["title", "anchor", "tocEntry", "markdown"]) {
    if (!obj[k] || typeof obj[k] !== "string") throw new Error(`Missing field: ${k}`);
  }
  if (!obj.markdown.startsWith("## ")) throw new Error("markdown must start with '## '");
  return obj;
}

let section;
try {
  let text = await callClaude();
  try {
    section = parseSection(text);
  } catch {
    // one retry
    text = await callClaude();
    section = parseSection(text);
  }
} catch (e) {
  console.error("Failed to obtain a valid section:", e.message);
  process.exit(1);
}

// Duplicate guard.
const newTitle = section.title.replace(/^##\s+/, "").trim();
if (existingSections.some((s) => s.toLowerCase() === newTitle.toLowerCase())) {
  console.log(`Section "${newTitle}" already exists — skipping.`);
  process.exit(78);
}

const block = section.markdown.trimEnd() + "\n\n";
let out = readme.replace(BODY_ANCHOR, block + BODY_ANCHOR);
out = out.replace(TOC_ANCHOR, `${section.tocEntry}\n${TOC_ANCHOR}`);

if (out === readme) {
  console.error("No changes produced — aborting.");
  process.exit(1);
}

writeFileSync(README, out, "utf8");
// Expose the title for the commit message.
writeFileSync(process.env.GITHUB_OUTPUT || "/dev/null", `title=${newTitle}\n`, { flag: "a" });
console.log(`Added new section: ${newTitle}`);
