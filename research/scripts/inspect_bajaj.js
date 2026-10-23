const fs = require("fs");
const bundle = fs.readFileSync("bajaj_bundle.js", "utf8");

const terms = ["mapmyindia", "mappls", "mapbox", "google", "bluetooth", "tbt", "turnbyturn", "pulsar", "cluster", "maneuver"];
const counts = {};
for (const term of terms) {
  const regex = new RegExp(term, "gi");
  const matches = bundle.match(regex);
  counts[term] = matches ? matches.length : 0;
}
console.log("Term counts:", JSON.stringify(counts, null, 2));

// Search for BLE UUIDs
const uuidRegex = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const uuids = [...new Set(bundle.match(uuidRegex) || [])];
console.log("UUIDs found in Bajaj bundle:", uuids);
