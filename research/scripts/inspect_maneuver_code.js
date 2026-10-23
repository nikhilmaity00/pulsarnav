const fs = require("fs");
const bundle = fs.readFileSync("bajaj_bundle.js", "utf8");

const idx = bundle.indexOf("googleManeuverToTypeModifier");
console.log("Index of googleManeuverToTypeModifier:", idx);

if (idx !== -1) {
  const start = Math.max(0, idx - 1000);
  const end = Math.min(bundle.length, idx + 2000);
  console.log("=== CODE SNIPPET ===");
  console.log(bundle.substring(start, end));
}
