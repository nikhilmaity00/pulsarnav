const fs = require("fs");
const dex = fs.readFileSync("classes.dex", "binary");

// Let's find snippets around BikeBleManager and BLE_TBT
const idx = dex.indexOf("BLE_TBT");
console.log("BLE_TBT index:", idx);

function printContext(buffer, index, length = 1000) {
  const start = Math.max(0, index - 200);
  const end = Math.min(buffer.length, index + length);
  const slice = buffer.slice(start, end);
  return slice.replace(/[\x00-\x1F\x7F-\xFF]/g, ".");
}

if (idx !== -1) {
  console.log("Context around BLE_TBT:\n", printContext(dex, idx, 1500));
}

// Let's also search for "Enviando alerta a cluster"
const idx2 = dex.indexOf("cluster de la moto");
if (idx2 !== -1) {
  console.log("Context around cluster de la moto:\n", printContext(dex, idx2, 1000));
}
