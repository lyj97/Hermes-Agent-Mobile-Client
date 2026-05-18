#!/usr/bin/env node
const { spawnSync } = require("node:child_process");
const { join } = require("node:path");

const script = join(__dirname, "../scripts/hermes-mobile.sh");
const result = spawnSync("bash", [script, ...process.argv.slice(2)], {
  stdio: "inherit",
});

process.exit(result.status ?? 1);
