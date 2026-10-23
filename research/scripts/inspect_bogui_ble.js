const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

// Search for any Bluetooth/BLE service UUIDs or characteristic UUIDs, or strings in com/bikerhud/app/ble
// In Android DEX, strings are UTF-8 in the string table.
const strRegex = /com\/bikerhud\/app\/[a-zA-Z0-9_\/$]+/g;
const strMatches = [...new Set(dex.toString("latin1").match(strRegex) || [])];
console.log("Com.bikerhud matches:", strMatches.slice(0, 50));

// Find any strings related to bluetooth scanning, device name filters, etc.
const allAscii = dex.toString("latin1").match(/[\x20-\x7E]{4,}/g) || [];
const scanFilters = allAscii.filter(s => 
  s.includes("ROUSER") || 
  s.includes("PULSAR") || 
  s.includes("BAJAJ") || 
  s.includes("DOMINAR") ||
  s.includes("KTM") ||
  s.includes("BluetoothGatt") ||
  s.includes("0000") || // 16-bit UUID in standard 128-bit format
  s.includes("writeCharacteristic") ||
  s.includes("notify")
);
console.log("Device names / BLE operations:", scanFilters.slice(0, 40));
