const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const fieldIdsOff = dex.readUInt32LE(0x54);

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
function getFieldName(idx) {
  const nameIdx = dex.readUInt32LE(fieldIdsOff + idx * 8 + 4);
  const classIdx = dex.readUInt16LE(fieldIdsOff + idx * 8);
  return `${getTypeName(classIdx)}->${getString(nameIdx)}`;
}

const codeOff = 5962272;
const insnsSize = dex.readUInt32LE(codeOff + 12);
const insnsStart = codeOff + 16;
console.log(`Lxm1;<clinit> size: ${insnsSize}`);

for (let i = 0; i < Math.min(insnsSize, 150); i++) {
  const op = dex.readUInt8(insnsStart + i * 2);
  const second = dex.readUInt8(insnsStart + i * 2 + 1);
  let desc = `0x${i.toString(16).padStart(3, '0')}: op=0x${op.toString(16).padStart(2, '0')} (sec=0x${second.toString(16)})`;
  if (op === 0x1a) {
    const s = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    desc += ` const-string "${getString(s)}"`;
    i++;
  } else if (op === 0x1b) {
    const s = dex.readUInt32LE(insnsStart + (i + 1) * 2);
    desc += ` const-string/jumbo "${getString(s)}"`;
    i += 2;
  } else if (op >= 0x60 && op <= 0x6d) {
    const f = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    desc += ` sput ${getFieldName(f)}`;
    i++;
  } else if (op === 0x23) {
    const t = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    desc += ` new-array ${getTypeName(t)}`;
    i++;
  } else if (op === 0x26) {
    desc += ` fill-array-data`;
    i += 2;
  }
  console.log(desc);
}
