const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const classDefsSize = dex.readUInt32LE(0x60);
const classDefsOff = dex.readUInt32LE(0x64);

function getString(idx) {
  const strDataOff = dex.readUInt32LE(stringIdsOff + idx * 4);
  let offset = strDataOff;
  while ((dex.readUInt8(offset) & 0x80) !== 0) offset++;
  offset++;
  let end = offset;
  while (dex.readUInt8(end) !== 0) end++;
  return dex.subarray(offset, end).toString("utf8");
}

function getTypeName(idx) {
  const strIdx = dex.readUInt32LE(typeIdsOff + idx * 4);
  return getString(strIdx);
}

for (let i = 0; i < classDefsSize; i++) {
  const cOff = classDefsOff + i * 32;
  const classIdx = dex.readUInt32LE(cOff);
  const className = getTypeName(classIdx);
  if (className.includes("GoogleMapsNotificationListener") || className.includes("GoogleMapsActivity") || className.includes("DashboardGuideActivity")) {
    console.log(`\n================ Class: ${className} ================`);
    const classDataOff = dex.readUInt32LE(cOff + 24);
    if (classDataOff === 0) continue;
    let offset = classDataOff;
    function readUleb() {
      let result = 0, shift = 0;
      while (true) {
        const b = dex.readUInt8(offset++);
        result |= (b & 0x7f) << shift;
        if ((b & 0x80) === 0) break;
        shift += 7;
      }
      return result;
    }
    const sf = readUleb(), inf = readUleb(), dm = readUleb(), vm = readUleb();
    for (let j = 0; j < sf + inf; j++) { readUleb(); readUleb(); }
    
    const codeOffsets = [];
    for (let m = 0; m < dm + vm; m++) {
      readUleb(); // diff
      readUleb(); // flags
      const codeOff = readUleb();
      if (codeOff > 0) codeOffsets.push(codeOff);
    }
    
    // In code_item:
    // registers_size (uint16), ins_size (uint16), outs_size (uint16), tries_size (uint16), debug_info_off (uint32), insns_size (uint32), insns (ushort[insns_size])
    const stringsInClass = new Set();
    for (const codeOff of codeOffsets) {
      const insnsSize = dex.readUInt32LE(codeOff + 12);
      const insnsStart = codeOff + 16;
      for (let k = 0; k < insnsSize; k++) {
        const op = dex.readUInt8(insnsStart + k * 2);
        if (op === 0x1a) { // const-string
          const strIdx = dex.readUInt16LE(insnsStart + k * 2 + 2);
          if (strIdx < 57138) stringsInClass.add(getString(strIdx));
        } else if (op === 0x1b) { // const-string/jumbo
          const strIdx = dex.readUInt32LE(insnsStart + k * 2 + 2);
          if (strIdx < 57138) stringsInClass.add(getString(strIdx));
        }
      }
    }
    console.log("Strings referenced in bytecode:");
    for (const s of stringsInClass) {
      if (s.trim().length > 0) console.log("   -", s);
    }
  }
}
