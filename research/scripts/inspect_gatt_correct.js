const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const methodIdsOff = dex.readUInt32LE(0x5c);
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

// Class index #1297
const cOff = classDefsOff + 1297 * 32;
const classDataOff = dex.readUInt32LE(cOff + 24);
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
console.log(`Fields: sf=${sf}, inf=${inf}, Direct methods: ${dm}, Virtual methods: ${vm}`);
for (let j = 0; j < sf + inf; j++) { readUleb(); readUleb(); }

let mIdx = 0;
console.log("=== DIRECT METHODS ===");
for (let m = 0; m < dm; m++) {
  mIdx += readUleb();
  const flags = readUleb();
  const codeOff = readUleb();
  console.log(`Direct #${m}: ${getMethodName(mIdx)} (codeOff=${codeOff})`);
}

mIdx = 0; // In DEX, virtual method index diff resets to 0!
console.log("=== VIRTUAL METHODS ===");
for (let m = 0; m < vm; m++) {
  mIdx += readUleb();
  const flags = readUleb();
  const codeOff = readUleb();
  console.log(`Virtual #${m}: ${getMethodName(mIdx)} (codeOff=${codeOff})`);
}
