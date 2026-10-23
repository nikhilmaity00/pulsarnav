const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

// Read DEX header to locate string_ids and type_ids
const stringIdsSize = dex.readUInt32LE(0x38);
const stringIdsOff = dex.readUInt32LE(0x3c);
console.log(`string_ids: count=${stringIdsSize}, off=0x${stringIdsOff.toString(16)}`);

const strings = [];
for (let i = 0; i < stringIdsSize; i++) {
  const strDataOff = dex.readUInt32LE(stringIdsOff + i * 4);
  // Read ULEB128 utf16_size
  let offset = strDataOff;
  let len = 0;
  let shift = 0;
  while (true) {
    const b = dex.readUInt8(offset++);
    len |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) break;
    shift += 7;
  }
  // Read MUTF-8 string until null byte
  let end = offset;
  while (dex.readUInt8(end) !== 0) {
    end++;
  }
  strings.push(dex.subarray(offset, end).toString("utf8"));
}

console.log("Parsed", strings.length, "strings from DEX header.");

// Find strings related to BLE, Bluetooth, bike, Rouser, Bajaj, Pulsar, UUIDs, TBT
const bleRelated = strings.filter(s => {
  const l = s.toLowerCase();
  return l.includes("bike") || l.includes("ble") || l.includes("gatt") || l.includes("rouser") || 
         l.includes("pulsar") || l.includes("bajaj") || l.includes("cluster") || l.includes("tbt") ||
         l.includes("maneuver") || l.includes("0000") || /^[0-9a-f]{8}-[0-9a-f]{4}/i.test(s);
});

console.log("Total BLE related strings:", bleRelated.length);
console.log("Filtered strings:\n", bleRelated.join("\n"));
