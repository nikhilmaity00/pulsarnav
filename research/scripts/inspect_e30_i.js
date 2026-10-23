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

for (let i = 0; i < classDefsSize; i++) {
  const cOff = classDefsOff + i * 32;
  const classIdx = dex.readUInt32LE(cOff);
  const className = getTypeName(classIdx);
  if (className === "Le30;") {
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
      const mName = getMethodName(methodIdx);
      console.log(`Direct method: ${mName} at codeOff=${codeOff}`);
      if (mName.endsWith("->i")) dumpMethod(codeOff);
    }
    methodIdx = 0;
    for (let m = 0; m < vm; m++) {
      methodIdx += readUleb();
      readUleb();
      const codeOff = readUleb();
      const mName = getMethodName(methodIdx);
      console.log(`Virtual method: ${mName} at codeOff=${codeOff}`);
      if (mName.endsWith("->i")) dumpMethod(codeOff);
    }
  }
}

function dumpMethod(codeOff) {
  const insnsSize = dex.readUInt32LE(codeOff + 12);
  const insnsStart = codeOff + 16;
  console.log(`Disassembling method size=${insnsSize}:`);
  for (let i = 0; i < insnsSize; i++) {
    const op = dex.readUInt8(insnsStart + i * 2);
    const second = dex.readUInt8(insnsStart + i * 2 + 1);
    let desc = `0x${i.toString(16).padStart(3, '0')}: [0x${op.toString(16).padStart(2, '0')}] v${second & 0xf}, v${(second >> 4) & 0xf}`;
    if (op === 0x12) {
      desc += ` const/4 #${(second >> 4) >= 8 ? (second >> 4) - 16 : (second >> 4)}`;
    } else if (op === 0x13) {
      desc += ` const/16 #${dex.readInt16LE(insnsStart + (i + 1) * 2)}`;
      i++;
    } else if (op === 0x14) {
      desc += ` const #${dex.readInt32LE(insnsStart + (i + 1) * 2)}`;
      i += 2;
    } else if (op === 0x1a) {
      const s = dex.readUInt16LE(insnsStart + (i + 1) * 2);
      desc += ` const-string "${getString(s)}"`;
      i++;
    } else if (op === 0x22) {
      const t = dex.readUInt16LE(insnsStart + (i + 1) * 2);
      desc += ` new-instance ${getTypeName(t)}`;
      i++;
    } else if (op === 0x23) {
      const t = dex.readUInt16LE(insnsStart + (i + 1) * 2);
      desc += ` new-array ${getTypeName(t)}`;
      i++;
    } else if (op >= 0x4b && op <= 0x51) {
      const r2 = dex.readUInt16LE(insnsStart + (i + 1) * 2);
      desc += ` aput v${second}, v${r2 & 0xff}, v${(r2 >> 8) & 0xff}`;
      i++;
    } else if (op >= 0x6e && op <= 0x72) {
      const m = dex.readUInt16LE(insnsStart + (i + 1) * 2);
      desc += ` invoke ${getMethodName(m)}`;
      i += 2;
    }
    console.log(desc);
  }
}
