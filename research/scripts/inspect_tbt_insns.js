const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const protoIdsOff = dex.readUInt32LE(0x4c);
const fieldIdsOff = dex.readUInt32LE(0x54);
const methodIdsOff = dex.readUInt32LE(0x5c);

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

const codeOff = 3635468;
const insnsSize = dex.readUInt32LE(codeOff + 12);
const insnsStart = codeOff + 16;

console.log(`Insns size: ${insnsSize}`);

// Scan for all const-string, const-string/jumbo, and byte array creation in this method
const strings = [];
const methodsCalled = [];
const constants = [];

for (let i = 0; i < insnsSize; i++) {
  const op = dex.readUInt8(insnsStart + i * 2);
  const second = dex.readUInt8(insnsStart + i * 2 + 1);
  if (op === 0x1a) { // const-string
    const sIdx = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    if (sIdx < 57138) strings.push({ pc: i, str: getString(sIdx) });
  } else if (op === 0x1b) { // const-string/jumbo
    const sIdx = dex.readUInt32LE(insnsStart + (i + 1) * 2);
    if (sIdx < 57138) strings.push({ pc: i, str: getString(sIdx) });
  } else if (op >= 0x6e && op <= 0x72) { // invoke-kind
    const mIdx = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    methodsCalled.push({ pc: i, method: getMethodName(mIdx) });
  } else if (op === 0x12) { // const/4
    const v = (second >> 4);
    constants.push({ pc: i, val: v >= 8 ? v - 16 : v });
  } else if (op === 0x13) { // const/16
    const v = dex.readInt16LE(insnsStart + (i + 1) * 2);
    constants.push({ pc: i, val: v });
  }
}

console.log("=== STRINGS IN METHOD ===");
strings.forEach(s => console.log(`  pc=0x${s.pc.toString(16)}: "${s.str}"`));

console.log("\n=== KEY METHODS CALLED ===");
methodsCalled.filter(m => !m.method.includes("java/lang/StringBuilder") && !m.method.includes("kotlin/jvm")).forEach(m => console.log(`  pc=0x${m.pc.toString(16)}: ${m.method}`));
