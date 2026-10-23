const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
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

for (let i = 0x2b0; i < insnsSize; i++) {
  const op = dex.readUInt8(insnsStart + i * 2);
  if (op >= 0x6e && op <= 0x72) {
    const mIdx = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    console.log(`pc=0x${i.toString(16)}: ${getMethodName(mIdx)}`);
  }
}
