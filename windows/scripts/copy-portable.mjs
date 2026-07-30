import { access, copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const windowsRoot = path.resolve(scriptDirectory, "..");
const packageJson = JSON.parse(
  await readFile(path.join(windowsRoot, "package.json"), "utf8"),
);
const executableName = "deskcubby-windows.exe";
const releaseDirectories = [
  path.join(
    windowsRoot,
    "src-tauri",
    "target",
    "x86_64-pc-windows-msvc",
    "release",
  ),
  path.join(windowsRoot, "src-tauri", "target", "release"),
];

let source;
for (const releaseDirectory of releaseDirectories) {
  const candidate = path.join(releaseDirectory, executableName);
  try {
    await access(candidate);
    source = candidate;
    break;
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw error;
    }
  }
}

if (!source) {
  throw new Error(
    `Release executable not found. Run "pnpm package:windows" first.`,
  );
}

const executable = await readFile(source);
if (executable.length < 64 || executable.toString("ascii", 0, 2) !== "MZ") {
  throw new Error("Release output is not a valid Windows executable.");
}
const peOffset = executable.readUInt32LE(0x3c);
if (executable.toString("ascii", peOffset, peOffset + 4) !== "PE\u0000\u0000") {
  throw new Error("Release output has an invalid PE header.");
}
const machine = executable.readUInt16LE(peOffset + 4);
if (machine !== 0x8664) {
  throw new Error(
    `Expected an x64 executable (0x8664), found 0x${machine.toString(16)}.`,
  );
}

const artifactsDirectory = path.join(windowsRoot, "artifacts");
await mkdir(artifactsDirectory, { recursive: true });
const artifactName = `DeskCubby-${packageJson.version}-windows-x64-portable.exe`;
const destination = path.join(artifactsDirectory, artifactName);
await copyFile(source, destination);

const digest = createHash("sha256").update(executable).digest("hex");
await writeFile(
  `${destination}.sha256`,
  `${digest}  ${artifactName}\n`,
  "utf8",
);

console.log(`Portable x64 executable: ${destination}`);
console.log(`SHA-256: ${digest}`);
