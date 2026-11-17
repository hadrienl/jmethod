import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
const python =
  process.env.JMETHOD_PYTHON ||
  (existsSync(".venv/bin/python") ? ".venv/bin/python" : "python3");
const children = [];
let stopping = false;
function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  for (const child of children) child.kill("SIGTERM");
  process.exitCode = code;
}
for (const [command, args] of [
  [python, ["scripts/pronote_server.py"]],
  [
    process.execPath,
    ["node_modules/vite/bin/vite.js", ...process.argv.slice(2)],
  ],
]) {
  const child = spawn(command, args, { stdio: "inherit" });
  children.push(child);
  child.on("error", () => {
    console.error(
      "Impossible de démarrer le POC. Installe les dépendances locales avec npm run setup:pronote.",
    );
    stop(1);
  });
  child.on("exit", (code) => stop(code ?? 1));
}
process.on("SIGINT", () => stop());
process.on("SIGTERM", () => stop());
