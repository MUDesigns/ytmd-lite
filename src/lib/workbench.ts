import type { Panel } from "./types";

export const viewLabels: Record<Panel, string> = {
  home: "Home", explore: "Discover", library: "Library", queue: "Queue",
  settings: "Settings", lastfm: "Last.fm", search: "Search", detail: "Collection",
};
export const workspaceViews: Panel[] = ["home", "explore", "library", "queue", "settings"];
export const codeFileTypes = [
  { extension: "cs", symbol: "C#" }, { extension: "tsx", symbol: "TSX" },
  { extension: "ts", symbol: "TS" }, { extension: "js", symbol: "JS" },
  { extension: "jsx", symbol: "JSX" }, { extension: "py", symbol: "PY" },
  { extension: "rs", symbol: "RS" }, { extension: "go", symbol: "GO" },
  { extension: "cpp", symbol: "C++" }, { extension: "java", symbol: "J" },
  { extension: "rb", symbol: "RB" }, { extension: "sh", symbol: "$" },
  { extension: "lua", symbol: "LUA" }, { extension: "swift", symbol: "SW" },
  { extension: "kt", symbol: "KT" }, { extension: "sql", symbol: "SQL" },
  { extension: "json", symbol: "{ }" }, { extension: "yml", symbol: "YML" },
  { extension: "html", symbol: "<>" }, { extension: "css", symbol: "#" },
];
export type WorkbenchFiles = Record<Panel, (typeof codeFileTypes)[number]>;
const panels = Object.keys(viewLabels) as Panel[];
export function defaultWorkbenchFiles(): WorkbenchFiles {
  return Object.fromEntries(panels.map((panel, i) => [panel, codeFileTypes[i]])) as WorkbenchFiles;
}

/** Pick a varied set once, then retain names across tab changes and app restarts. */
export function loadWorkbenchFiles(): WorkbenchFiles {
  let saved: Record<string, unknown> = {};
  try {
    const parsed = JSON.parse(localStorage.getItem("ytmd.workbenchFiles.v1") || "{}");
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) saved = parsed;
  } catch { /* Pick fresh names if storage is unavailable or invalid. */ }
  const result = defaultWorkbenchFiles();
  const used = new Set(Object.values(saved).filter(v => typeof v === "string"));
  for (const panel of panels) {
    const existing = codeFileTypes.find(type => type.extension === saved[panel]);
    const available = codeFileTypes.filter(type => !used.has(type.extension));
    const pool = available.length ? available : codeFileTypes;
    result[panel] = existing || pool[Math.floor(Math.random() * pool.length)];
    used.add(result[panel].extension);
  }
  try {
    localStorage.setItem("ytmd.workbenchFiles.v1", JSON.stringify(Object.fromEntries(panels.map(panel => [panel, result[panel].extension]))));
  } catch { /* The names remain stable for the current session. */ }
  return result;
}
export function workbenchFilename(files: WorkbenchFiles, panel: Panel, collectionTitle?: string): string {
  const name = panel === "detail" && collectionTitle ? collectionTitle : viewLabels[panel].toLowerCase();
  return `${name}.${files[panel].extension}`;
}
