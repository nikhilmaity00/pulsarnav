const fs = require("fs");
const dex = fs.readFileSync("bajaj_gmaps.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const methodIdsOff = dex.readUInt32LE(0x5c);
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
function getMethodName(idx) {
  const nameIdx = dex.readUInt32LE(methodIdsOff + idx * 8 + 4);
  const classIdx = dex.readUInt16LE(methodIdsOff + idx * 8);
  return `${getTypeName(classIdx)}->${getString(nameIdx)}`;
}

for (let i = 0; i < classDefsSize; i++) {
  const cOff = classDefsOff + i * 32;
  const classIdx = dex.readUInt32LE(cOff);
  const className = getTypeName(classIdx);
  if (className.includes("com/bajajconnect/ble")) {
    console.log(`\n=== Found Class: ${className} ===`);
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
    let mIdx = 0;
    for (let m = 0; m < dm; m++) {
      mIdx += readUleb();
      readUleb();
      const codeOff = readUleb();
      console.log(`Direct: ${getMethodName(mIdx)} (codeOff=${codeOff})`);
      dumpStrings(codeOff);
    }
    mIdx = 0;
    for (let m = 0; m < vm; m++) {
      mIdx += readUleb();
      readUleb();
      const codeOff = readUleb();
      console.log(`Virtual: ${getMethodName(mIdx)} (codeOff=${codeOff})`);
      dumpStrings(codeOff);
    }
  }
}

function dumpStrings(codeOff) {
  if (codeOff === 0) return;
  const insnsSize = dex.readUInt32LE(codeOff + 12);
  const insnsStart = codeOff + 16;
  const strings = [];
  for (let k = 0; k < insnsSize; k++) {
    const op = dex.readUInt8(insnsStart + k * 2);
    if (op === 0x1a) {
      const s = dex.readUInt16LE(insnsStart + (k + 1) * 2);
      if (s < dex.readUInt32LE(0x38)) strings.push(getString(s));
    }
  }
  if (strings.length > 0) console.log("   Strings:", strings);
}
