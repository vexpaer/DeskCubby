import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const assetsDirectory = path.resolve(scriptDirectory, "../app/src/main/assets");
const juniorRevision = "b11547e302eb510bfe2118cc86df21d3e1cbc36d";
const seniorRevision = "cfca00fdb602634ac4507374975f0edb1dfed549";
const juniorUrl =
  `https://raw.githubusercontent.com/tangyuan0821/Junior-Middle-School-poetry/${juniorRevision}/poetry.json`;
const seniorUrl =
  `https://raw.githubusercontent.com/zyhqwq/Highschool-poetry/${seniorRevision}/src/common/poetryData.js`;

async function fetchText(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Could not fetch source (${response.status})`);
  return response.text();
}

function normalizeEntry(entry) {
  const content = String(entry.content ?? "")
    .replace(/\r/g, "")
    .split("\n")
    .map((line) => line.trim())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  const title = String(entry.title ?? "").trim();
  const author = String(entry.author ?? "").trim();
  if (!title || !content) throw new Error("Preset entry is missing title or content");
  return { title, author, content };
}

function takeRanges(items, ranges) {
  return ranges.flatMap(([startInclusive, endExclusive]) =>
    items.slice(startInclusive, endExclusive)
  );
}

function isModernWork(entry) {
  return (
    (entry.title === "沁园春·雪" && entry.author === "毛泽东") ||
    (entry.title === "沁园春·长沙" && entry.author === "毛泽东") ||
    (entry.title === "梅岭三章" && entry.author === "陈毅")
  );
}

const [juniorText, seniorText] = await Promise.all([
  fetchText(juniorUrl),
  fetchText(seniorUrl),
]);
const junior = JSON.parse(juniorText).map(normalizeEntry);
if (junior.length !== 118) throw new Error(`Expected 118 junior entries, got ${junior.length}`);

const sandbox = { module: { exports: {} } };
vm.runInNewContext(seniorText, sandbox, { timeout: 1_000 });
const senior = sandbox.module.exports.poems;
if (!senior || typeof senior !== "object") throw new Error("Senior preset source is invalid");

const categoryDefinitions = [
  {
    id: "junior-7-1",
    nameZh: "初中·七年级上册",
    nameEn: "Junior · Grade 7 Vol. 1",
    colorArgb: 0xffc76b5c | 0,
    items: takeRanges(junior, [[0, 13], [84, 90]]),
  },
  {
    id: "junior-7-2",
    nameZh: "初中·七年级下册",
    nameEn: "Junior · Grade 7 Vol. 2",
    colorArgb: 0xffca8b45 | 0,
    items: takeRanges(junior, [[13, 25], [90, 95]]),
  },
  {
    id: "junior-8-1",
    nameZh: "初中·八年级上册",
    nameEn: "Junior · Grade 8 Vol. 1",
    colorArgb: 0xff9d8a45 | 0,
    items: takeRanges(junior, [[25, 43], [95, 102]]),
  },
  {
    id: "junior-8-2",
    nameZh: "初中·八年级下册",
    nameEn: "Junior · Grade 8 Vol. 2",
    colorArgb: 0xff5d9168 | 0,
    items: takeRanges(junior, [[43, 55], [102, 108]]),
  },
  {
    id: "junior-9-1",
    nameZh: "初中·九年级上册",
    nameEn: "Junior · Grade 9 Vol. 1",
    colorArgb: 0xff4f8f8a | 0,
    items: takeRanges(junior, [[55, 66], [108, 111]]).filter((entry) => !isModernWork(entry)),
  },
  {
    id: "junior-9-2",
    nameZh: "初中·九年级下册",
    nameEn: "Junior · Grade 9 Vol. 2",
    colorArgb: 0xff4f76a1 | 0,
    items: takeRanges(junior, [[66, 84], [111, 118]]).filter((entry) => !isModernWork(entry)),
  },
];

const seniorDefinitions = [
  ["senior-required-1", "高中·必修上册", "Senior · Required Vol. 1", "compulsory1", 0xff7166a4],
  ["senior-required-2", "高中·必修下册", "Senior · Required Vol. 2", "compulsory2", 0xff98639a],
  ["senior-selective-1", "高中·选择性必修上册", "Senior · Selective Vol. 1", "elective1", 0xffad627e],
  ["senior-selective-2", "高中·选择性必修中册", "Senior · Selective Vol. 2", "elective2", 0xff8f6f62],
  ["senior-selective-3", "高中·选择性必修下册", "Senior · Selective Vol. 3", "elective3", 0xff68727c],
];
for (const [id, nameZh, nameEn, sourceKey, color] of seniorDefinitions) {
  const seen = new Set();
  const items = senior[sourceKey]
    .map(normalizeEntry)
    .filter((entry) => !isModernWork(entry))
    .filter((entry) => {
      const key = `${entry.title}\u0000${entry.author}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  categoryDefinitions.push({
    id,
    nameZh,
    nameEn,
    colorArgb: color | 0,
    items,
  });
}

const result = {
  version: 1,
  generatedFrom: [
    {
      project: "tangyuan0821/Junior-Middle-School-poetry",
      revision: juniorRevision,
      license: "CC-BY-SA-4.0",
    },
    {
      project: "zyhqwq/Highschool-poetry",
      revision: seniorRevision,
      license: "MIT",
    },
  ],
  categories: categoryDefinitions,
};
const total = result.categories.reduce((sum, category) => sum + category.items.length, 0);
if (total !== 182) throw new Error(`Expected 182 preset entries, got ${total}`);

fs.mkdirSync(assetsDirectory, { recursive: true });
fs.writeFileSync(
  path.join(assetsDirectory, "poetry_presets.json"),
  `${JSON.stringify(result, null, 2)}\n`,
  "utf8",
);
fs.writeFileSync(
  path.join(assetsDirectory, "poetry_presets_NOTICES.txt"),
  [
    "DeskCubby built-in poetry preset notices",
    "",
    "Junior-middle-school selection and source data:",
    "  tangyuan0821/Junior-Middle-School-poetry",
    `  Revision: ${juniorRevision}`,
    "  License: Creative Commons Attribution-ShareAlike 4.0 International",
    "  Source: https://github.com/tangyuan0821/Junior-Middle-School-poetry",
    "  https://creativecommons.org/licenses/by-sa/4.0/",
    "",
    "The junior selection, its normalized arrangement in poetry_presets.json, and DeskCubby's",
    "changes to that selection are distributed under CC BY-SA 4.0. Changes: whitespace was",
    "normalized, entries were grouped by textbook volume, and modern works were omitted.",
    "",
    "Senior-high-school selection and source data:",
    "  zyhqwq/Highschool-poetry",
    `  Revision: ${seniorRevision}`,
    "  Source: https://github.com/zyhqwq/Highschool-poetry",
    "  License: MIT License",
    "  Copyright (c) 2026 zyhqwq",
    "",
    "Permission is hereby granted, free of charge, to any person obtaining a copy",
    "of this software and associated documentation files (the \"Software\"), to deal",
    "in the Software without restriction, including without limitation the rights",
    "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell",
    "copies of the Software, and to permit persons to whom the Software is",
    "furnished to do so, subject to the following conditions:",
    "",
    "The above copyright notice and this permission notice shall be included in all",
    "copies or substantial portions of the Software.",
    "",
    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR",
    "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,",
    "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE",
    "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER",
    "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,",
    "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE",
    "SOFTWARE.",
    "",
    "The underlying classical Chinese works are in the public domain. DeskCubby normalizes",
    "whitespace, groups entries by textbook volume, omits three modern works, and removes one",
    "duplicate entry. Textbook selections can change between editions.",
    "",
  ].join("\n"),
  "utf8",
);
console.log(`Generated ${result.categories.length} categories with ${total} entries.`);
