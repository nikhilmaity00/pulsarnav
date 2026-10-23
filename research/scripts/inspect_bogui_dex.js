const fs = require("fs");
const dex = fs.readFileSync("classes.dex", "binary");

function extractStrings(str) {
  const matches = str.match(/[\x20-\x7E]{5,}/g) || [];
  return matches;
}

const strings = extractStrings(dex);
console.log("Total extracted strings:", strings.length);

const bleStrings = strings.filter(s => 
  s.toLowerCase().includes("ble") || 
  s.toLowerCase().includes("bajaj") || 
  s.toLowerCase().includes("pulsar") ||
  s.toLowerCase().includes("notification") ||
  s.toLowerCase().includes("maneuver") ||
  s.toLowerCase().includes("cluster")
);

console.log("Sample BLE/Cluster/Notification strings:", bleStrings.slice(0, 50));

// Find UUIDs in Bogui dex
const uuidRegex = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
const dexUuids = [...new Set(dex.match(uuidRegex) || [])];
console.log("UUIDs in Bogui dex:", dexUuids);
