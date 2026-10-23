const fs = require("fs");
const dex = fs.readFileSync("bajaj_gmaps.dex");
const fieldIdsOff = dex.readUInt32LE(0x54);
const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);

function getString(idx) {
  let off = dex.readUInt32LE(stringIdsOff + idx * 4);
  while ((dex.readUInt8(off) & 0x80) !== 0) off++;
  off++;
  let end = off;
  while (dex.readUInt8(end) !== 0) end++;
  return dex.subarray(off, end).toString("utf8");
}
function getTypeName(idx) {
  return getString(dex.readUInt32LE(typeIdsOff + idx * 4));
}
function getFieldName(idx) {
  const nameIdx = dex.readUInt32LE(fieldIdsOff + idx * 8 + 4);
  const classIdx = dex.readUInt16LE(fieldIdsOff + idx * 8);
  return `${getTypeName(classIdx)}->${getString(nameIdx)}`;
}

const codeOff = 2797040;
const insnsStart = codeOff + 16;
for (let k = 0; k < 12; k++) {
  const op = dex.readUInt8(insnsStart + k * 2);
  const second = dex.readUInt8(insnsStart + k * 2 + 1);
  if (op === 0x62 || op === 0x54 || op === 0x60 || op === 0x52) {
    const fIdx = dex.readUInt16LE(insnsStart + (k + 1) * 2);
    console.log(`op=0x${op.toString(16)} field=${getFieldName(fIdx)}`);
  }
}
