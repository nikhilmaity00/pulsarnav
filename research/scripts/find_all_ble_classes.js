const fs = require("fs");
const dex = fs.readFileSync("classes.dex");
const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
function getString(idx) {
  const strDataOff = dex.readUInt32LE(stringIdsOff + idx * 4);
  let offset = strDataOff;
  while ((dex.readUInt8(offset) & 0x80) !== 0) offset++;
  offset++;
  let end = offset;
  while (dex.readUInt8(end) !== 0) end++;
  return dex.subarray(offset, end).toString("utf8");
}
for (let i = 1820; i <= 1840; i++) {
  const descriptorIdx = dex.readUInt32LE(typeIdsOff + i * 4);
  console.log(`Type #${i}: ${getString(descriptorIdx)}`);
}
