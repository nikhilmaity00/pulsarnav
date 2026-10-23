const fs = require("fs");
const dex = fs.readFileSync("bajaj_gmaps.dex");
const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const fieldIdsOff = dex.readUInt32LE(0x54);
const methodIdsOff = dex.readUInt32LE(0x5c);
const classDefsSize = dex.readUInt32LE(0x60);
const classDefsOff = dex.readUInt32LE(0x64);

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

function dumpAllMethods(targetName) {
  for (let i = 0; i < classDefsSize; i++) {
    const cOff = classDefsOff + i * 32;
    const classIdx = dex.readUInt32LE(cOff);
    const className = getTypeName(classIdx);
    if (className === targetName) {
      console.log(`\n=================== ${className} ===================`);
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
        const mName = getMethodName(mIdx);
        console.log(`Direct: ${mName} (codeOff=${codeOff})`);
        dumpMethod(codeOff);
      }
      mIdx = 0;
      for (let m = 0; m < vm; m++) {
        mIdx += readUleb();
        readUleb();
        const codeOff = readUleb();
        const mName = getMethodName(mIdx);
        console.log(`Virtual: ${mName} (codeOff=${codeOff})`);
        dumpMethod(codeOff);
      }
    }
  }
}

function dumpMethod(codeOff) {
  if (codeOff === 0) return;
  const insnsSize = dex.readUInt32LE(codeOff + 12);
  const insnsStart = codeOff + 16;
  for (let k = 0; k < Math.min(insnsSize, 120); k++) {
    const op = dex.readUInt8(insnsStart + k * 2);
    const second = dex.readUInt8(insnsStart + k * 2 + 1);
    let desc = `  0x${k.toString(16).padStart(3, '0')}: [0x${op.toString(16).padStart(2, '0')}]`;
    if (op === 0x12) {
      desc += ` const/4 v${second & 0xf}, #${(second >> 4) >= 8 ? (second >> 4) - 16 : (second >> 4)}`;
    } else if (op === 0x13) {
      desc += ` const/16 v${second}, #${dex.readInt16LE(insnsStart + (k + 1) * 2)}`;
      k++;
    } else if (op === 0x1a) {
      const s = dex.readUInt16LE(insnsStart + (k + 1) * 2);
      desc += ` const-string "${getString(s)}"`;
      k++;
    } else if (op === 0x70 || op === 0x71 || op === 0x6e) {
      const mIdx = dex.readUInt16LE(insnsStart + (k + 1) * 2);
      desc += ` invoke ${getMethodName(mIdx)}`;
      k += 2;
    } else if (op === 0x52 || op === 0x54) {
      const fIdx = dex.readUInt16LE(insnsStart + (k + 1) * 2);
      desc += ` iget/iput ${getFieldName(fIdx)}`;
      k++;
    } else if (op === 0x60 || op === 0x62) {
      const fIdx = dex.readUInt16LE(insnsStart + (k + 1) * 2);
      desc += ` sget/sput ${getFieldName(fIdx)}`;
      k++;
    }
    console.log(desc);
  }
}

dumpAllMethods("Lcom/bajajconnect/utils/CallFrame;");
