const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const protoIdsOff = dex.readUInt32LE(0x4c);
const fieldIdsOff = dex.readUInt32LE(0x54);
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
  return getString(nameIdx);
}

// Search for the exact string index of "BLE_TBT -> Opcode: "
let bleTbtStrIdx = -1;
for (let i = 0; i < dex.readUInt32LE(0x38); i++) {
  if (getString(i).includes("BLE_TBT -> Opcode")) {
    bleTbtStrIdx = i;
    console.log(`Found "BLE_TBT -> Opcode" at string index ${i}`);
    break;
  }
}

// Find all code_items that reference bleTbtStrIdx
for (let i = 0; i < classDefsSize; i++) {
  const cOff = classDefsOff + i * 32;
  const classIdx = dex.readUInt32LE(cOff);
  const className = getTypeName(classIdx);
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
  
  let methodIdx = 0;
  for (let m = 0; m < dm + vm; m++) {
    const diff = readUleb();
    methodIdx += diff;
    const flags = readUleb();
    const codeOff = readUleb();
    if (codeOff === 0) continue;
    
    const insnsSize = dex.readUInt32LE(codeOff + 12);
    const insnsStart = codeOff + 16;
    for (let k = 0; k < insnsSize; k++) {
      const op = dex.readUInt8(insnsStart + k * 2);
      let sIdx = -1;
      if (op === 0x1a) {
        sIdx = dex.readUInt16LE(insnsStart + k * 2 + 2);
      } else if (op === 0x1b) {
        sIdx = dex.readUInt32LE(insnsStart + k * 2 + 2);
      }
      if (sIdx === bleTbtStrIdx) {
        console.log(`Method referencing BLE_TBT: ${className} -> ${getMethodName(methodIdx)} (codeOff: ${codeOff}, insnsSize: ${insnsSize})`);
      }
    }
  }
}
