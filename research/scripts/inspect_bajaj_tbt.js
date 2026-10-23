const fs = require("fs");
const bundle = fs.readFileSync("bajaj_bundle.js", "utf8");

// Search for snippets around "cluster" and "tbt" in bajaj_bundle.js
const regex = /[^\n]{0,200}(?:cluster|tbt|maneuver|otcEngineering|turnByTurn)[^\n]{0,200}/gi;
let match;
let count = 0;
while ((match = regex.exec(bundle)) !== null && count < 30) {
  count++;
  console.log(`[Bajaj Bundle Match ${count}]:\n`, match[0].trim());
}
