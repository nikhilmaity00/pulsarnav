const fs = require("fs");
const dex = fs.readFileSync("bajaj_gmaps.dex");

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
  if (className === "Lcom/bajajconnect/enums/PrimaryTurns;") {
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
    let mIdx = 0;
    for (let m = 0; m < dm; m++) {
      mIdx += readUleb();
      readUleb();
      const codeOff = readUleb();
      const mName = getMethodName(mIdx);
      if (mName.includes("<clinit>")) {
        console.log(`Disassembling ${mName} at ${codeOff}:`);
        const insnsSize = dex.readUInt32LE(codeOff + 12);
        const insnsStart = codeOff + 16;
        for (let k = 0; k < insnsSize; k++) {
          const op = dex.readUInt8(insnsStart + k * 2);
          const second = dex.readUInt8(insnsStart + k * 2 + 1);
          let desc = `0x${k.toString(16).padStart(3, '0')}: [0x${op.toString(16).padStart(2, '0')}]`;
          if (op === 0x12) {
            desc += ` const/4 v${second & 0xf}, #${(second >> 4) >= 8 ? (second >> 4) - 16 : (second >> 4)}`;
          } else if (op === 0x13) {
            desc += ` const/16 v${second}, #${dex.readInt16LE(insnsStart + (k + 1) * 2)}`;
            k++;
          } else if (op === 0x1a) {
            const s = dex.readUInt16LE(insnsStart + (k + 1) * 2);
            desc += ` const-string "${getString(s)}"`;
            k++;
          } else if (op >= 0x60 && op <= 0x6d) {
            const f = dex.readUInt16LE(insnsStart + (k + 1) * 2);
            desc += ` sput ${getFieldName(f)}`;
            k++;
          } else if (op >= 0x6e && op <= 0x72) {
            const m2 = dex.readUInt16LE(insnsStart + (k + 1) * 2);
            desc += ` invoke ${getMethodName(m2)}`;
            k += 2;
          }
          console.log(desc);
        }
      }
    }
  }
}
