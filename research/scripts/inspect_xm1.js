const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
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
  const classIdx = dex.readUInt16LE(methodIdsOff + idx * 8);
  return `${getTypeName(classIdx)}->${getString(nameIdx)}`;
}
function getFieldName(idx) {
  const nameIdx = dex.readUInt32LE(fieldIdsOff + idx * 8 + 4);
  const classIdx = dex.readUInt16LE(fieldIdsOff + idx * 8);
  return `${getTypeName(classIdx)}->${getString(nameIdx)}`;
}

for (let i = 0; i < classDefsSize; i++) {
  const cOff = classDefsOff + i * 32;
  const classIdx = dex.readUInt32LE(cOff);
  const className = getTypeName(classIdx);
  if (className === "Lxm1;") {
    console.log(`\n=== Found Class: ${className} ===`);
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
    for (let j = 0; j < sf + inf; j++) { readUleb(); readUleb(); }
    let methodIdx = 0;
    for (let m = 0; m < dm; m++) {
      methodIdx += readUleb();
      readUleb();
      const codeOff = readUleb();
      console.log(`Direct method: ${getMethodName(methodIdx)} at ${codeOff}`);
      if (codeOff > 0) {
        const insnsSize = dex.readUInt32LE(codeOff + 12);
        const insnsStart = codeOff + 16;
        for (let k = 0; k < insnsSize; k++) {
          const op = dex.readUInt8(insnsStart + k * 2);
          if (op === 0x1a) {
            const s = dex.readUInt16LE(insnsStart + (k + 1) * 2);
            if (s < 57138) console.log(`  const-string "${getString(s)}"`);
          } else if (op === 0x1b) {
            const s = dex.readUInt32LE(insnsStart + (k + 1) * 2);
            if (s < 57138) console.log(`  const-string/jumbo "${getString(s)}"`);
          } else if (op >= 0x60 && op <= 0x6d) { // sput
            const f = dex.readUInt16LE(insnsStart + (k + 1) * 2);
            console.log(`  sput ${getFieldName(f)}`);
          }
        }
      }
    }
  }
}
