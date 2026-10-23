const fs = require("fs");
const dex = fs.readFileSync("bajaj_gmaps.dex");

function dumpDetailed(codeOff, name) {
  console.log(`\n=== Detailed ${name} ===`);
  const insnsSize = dex.readUInt32LE(codeOff + 12);
  const insnsStart = codeOff + 16;
  for (let k = 0; k < insnsSize; k++) {
    const b0 = dex.readUInt8(insnsStart + k * 2);
    const b1 = dex.readUInt8(insnsStart + k * 2 + 1);
    const w1 = dex.readUInt16LE(insnsStart + k * 2);
    console.log(`0x${k.toString(16).padStart(2, '0')}: raw=0x${w1.toString(16).padStart(4, '0')} (b0=0x${b0.toString(16)}, b1=0x${b1.toString(16)})`);
  }
}

dumpDetailed(2797968, "getStatusByteOne");
dumpDetailed(2798044, "getStatusByteZero");
