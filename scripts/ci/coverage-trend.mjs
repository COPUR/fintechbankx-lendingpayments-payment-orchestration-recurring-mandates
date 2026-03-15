#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

const rootDir = process.cwd();
const baselinePath = path.join(rootDir, ".ci", "coverage-baseline.json");
const outputDir = path.join(rootDir, "build", "reports", "coverage");
const outputJson = path.join(outputDir, "coverage-summary.json");
const outputMd = path.join(outputDir, "coverage-summary.md");

function walk(dir, results = []) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.name === ".git" || entry.name === "node_modules" || entry.name === ".gradle") {
      continue;
    }
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, results);
      continue;
    }
    if (
      entry.isFile() &&
      (entry.name === "jacocoTestReport.csv" || entry.name === "jacocoTestReport.xml") &&
      full.includes(`${path.sep}build${path.sep}reports${path.sep}jacoco${path.sep}`)
    ) {
      results.push(full);
    }
  }
  return results;
}

function parseCsvLine(line) {
  const out = [];
  let cur = "";
  let inQuotes = false;

  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        cur += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
      continue;
    }
    if (ch === "," && !inQuotes) {
      out.push(cur);
      cur = "";
      continue;
    }
    cur += ch;
  }
  out.push(cur);
  return out;
}

function toPct(covered, missed) {
  const total = covered + missed;
  if (total <= 0) return 0;
  return Number(((covered / total) * 100).toFixed(2));
}

function readBaseline() {
  if (!fs.existsSync(baselinePath)) return { modules: {} };
  try {
    const raw = JSON.parse(fs.readFileSync(baselinePath, "utf8"));
    if (raw && typeof raw === "object" && raw.modules && typeof raw.modules === "object") {
      return raw;
    }
    return { modules: {} };
  } catch {
    return { modules: {} };
  }
}

function moduleFromCoverageFile(filePath) {
  const rel = path.relative(rootDir, filePath);
  const marker = `${path.sep}build${path.sep}reports${path.sep}jacoco${path.sep}`;
  const idx = rel.indexOf(marker);
  if (idx <= 0) return "root";
  return rel.slice(0, idx);
}

function parseXmlLineStats(xmlPath) {
  const xml = fs.readFileSync(xmlPath, "utf8");
  const lineCounter = xml.match(/<counter\s+type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/);
  if (!lineCounter) {
    console.error(`Missing LINE counter in ${xmlPath}`);
    process.exit(1);
  }
  return {
    missed: Number(lineCounter[1]),
    covered: Number(lineCounter[2]),
  };
}

function parseCsvLineStats(csvPath) {
  const lines = fs.readFileSync(csvPath, "utf8").split(/\r?\n/).filter(Boolean);
  if (lines.length < 2) {
    return { missed: 0, covered: 0 };
  }
  const header = parseCsvLine(lines[0]);
  const lineMissedIdx = header.indexOf("LINE_MISSED");
  const lineCoveredIdx = header.indexOf("LINE_COVERED");
  if (lineMissedIdx < 0 || lineCoveredIdx < 0) {
    console.error(`Missing LINE_MISSED/LINE_COVERED in ${csvPath}`);
    process.exit(1);
  }

  let missed = 0;
  let covered = 0;
  for (let i = 1; i < lines.length; i += 1) {
    const cols = parseCsvLine(lines[i]);
    missed += Number(cols[lineMissedIdx] || 0);
    covered += Number(cols[lineCoveredIdx] || 0);
  }
  return { missed, covered };
}

const coverageFiles = walk(rootDir);
if (coverageFiles.length === 0) {
  console.error("No JaCoCo XML/CSV files found. Ensure jacocoTestReport runs in CI.");
  process.exit(1);
}

const perModule = new Map();
for (const file of coverageFiles.sort()) {
  const moduleName = moduleFromCoverageFile(file);
  const stats = file.endsWith(".csv") ? parseCsvLineStats(file) : parseXmlLineStats(file);

  const current = perModule.get(moduleName) || { lineMissed: 0, lineCovered: 0 };
  current.lineMissed += stats.missed;
  current.lineCovered += stats.covered;
  perModule.set(moduleName, current);
}

const baseline = readBaseline();
const modules = [];
let totalMissed = 0;
let totalCovered = 0;

for (const [moduleName, stat] of [...perModule.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
  totalMissed += stat.lineMissed;
  totalCovered += stat.lineCovered;
  const coverage = toPct(stat.lineCovered, stat.lineMissed);
  const baselineValue = baseline.modules[moduleName];
  const delta = typeof baselineValue === "number" ? Number((coverage - baselineValue).toFixed(2)) : null;
  modules.push({
    module: moduleName,
    lineMissed: stat.lineMissed,
    lineCovered: stat.lineCovered,
    coverage,
    baseline: typeof baselineValue === "number" ? baselineValue : null,
    delta,
  });
}

const overall = {
  lineMissed: totalMissed,
  lineCovered: totalCovered,
  coverage: toPct(totalCovered, totalMissed),
};

fs.mkdirSync(outputDir, { recursive: true });
const payload = {
  generatedAtUtc: new Date().toISOString(),
  overall,
  modules,
};
fs.writeFileSync(outputJson, JSON.stringify(payload, null, 2));

const mdLines = [];
mdLines.push("# Coverage Trend Summary");
mdLines.push("");
mdLines.push(`Generated at: ${payload.generatedAtUtc}`);
mdLines.push("");
mdLines.push(`Overall line coverage: **${overall.coverage}%** (covered: ${overall.lineCovered}, missed: ${overall.lineMissed})`);
mdLines.push("");
mdLines.push("| Module | Coverage % | Baseline % | Delta | Covered | Missed |");
mdLines.push("| --- | ---: | ---: | ---: | ---: | ---: |");
for (const row of modules) {
  const baselineCell = row.baseline === null ? "n/a" : row.baseline.toFixed(2);
  const deltaCell = row.delta === null ? "n/a" : (row.delta > 0 ? `+${row.delta.toFixed(2)}` : row.delta.toFixed(2));
  mdLines.push(`| ${row.module} | ${row.coverage.toFixed(2)} | ${baselineCell} | ${deltaCell} | ${row.lineCovered} | ${row.lineMissed} |`);
}
mdLines.push("");
mdLines.push("Note: Baseline values are loaded from `.ci/coverage-baseline.json`. Commit updated baseline values after approved releases if delta tracking should reflect new target coverage.");

const markdown = mdLines.join("\n");
fs.writeFileSync(outputMd, markdown + "\n");

if (process.env.GITHUB_STEP_SUMMARY) {
  fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
}

console.log(`Coverage summary written to ${outputJson}`);
console.log(`Coverage markdown written to ${outputMd}`);
