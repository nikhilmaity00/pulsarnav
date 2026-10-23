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

function getFieldName(idx) {
  const nameIdx = dex.readUInt32LE(fieldIdsOff + idx * 8 + 4);
  const classIdx = dex.readUInt16LE(fieldIdsOff + idx * 8);
  return `${getTypeName(classIdx)}->${getString(nameIdx)}`;
}

const codeOff = 3635468;
const registersSize = dex.readUInt16LE(codeOff);
const insSize = dex.readUInt16LE(codeOff + 2);
const outsSize = dex.readUInt16LE(codeOff + 4);
const insnsSize = dex.readUInt32LE(codeOff + 12);
const insnsStart = codeOff + 16;

console.log(`Disassembling method at ${codeOff}: reg=${registersSize}, in=${insSize}, out=${outsSize}, insnsSize=${insnsSize}`);

// Disassemble instruction stream
let pc = 0;
while (pc < insnsSize) {
  const addr = pc;
  const op = dex.readUInt8(insnsStart + pc * 2);
  const second = dex.readUInt8(insnsStart + pc * 2 + 1);
  let line = `${addr.toString(16).padStart(4, '0')}: [0x${op.toString(16).padStart(2, '0')}] `;
  
  if (op === 0x00) { // nop
    line += "nop";
    pc += 1;
  } else if (op === 0x01) { // move
    line += `move v${second & 0xf}, v${(second >> 4) & 0xf}`;
    pc += 1;
  } else if (op === 0x02) { // move/from16
    const reg2 = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `move/from16 v${second}, v${reg2}`;
    pc += 2;
  } else if (op === 0x04) { // move-wide
    line += `move-wide v${second & 0xf}, v${(second >> 4) & 0xf}`;
    pc += 1;
  } else if (op === 0x07 || op === 0x08) { // move-object
    line += `move-object v${second & 0xf}, v${(second >> 4) & 0xf}`;
    pc += 1;
  } else if (op === 0x0e) { // return-void
    line += "return-void";
    pc += 1;
  } else if (op === 0x0f || op === 0x10 || op === 0x11) { // return
    line += `return v${second}`;
    pc += 1;
  } else if (op === 0x12) { // const/4
    const val = (second >> 4);
    line += `const/4 v${second & 0xf}, #int ${val >= 8 ? val - 16 : val}`;
    pc += 1;
  } else if (op === 0x13) { // const/16
    const val = dex.readInt16LE(insnsStart + (pc + 1) * 2);
    line += `const/16 v${second}, #${val}`;
    pc += 2;
  } else if (op === 0x14) { // const
    const val = dex.readInt32LE(insnsStart + (pc + 1) * 2);
    line += `const v${second}, #${val}`;
    pc += 3;
  } else if (op === 0x1a) { // const-string
    const sIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `const-string v${second}, "${getString(sIdx).replace(/\n/g, '\\n')}"`;
    pc += 2;
  } else if (op === 0x1b) { // const-string/jumbo
    const sIdx = dex.readUInt32LE(insnsStart + (pc + 1) * 2);
    line += `const-string/jumbo v${second}, "${getString(sIdx).replace(/\n/g, '\\n')}"`;
    pc += 3;
  } else if (op === 0x1c) { // const-class
    const tIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `const-class v${second}, ${getTypeName(tIdx)}`;
    pc += 2;
  } else if (op === 0x22) { // new-instance
    const tIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `new-instance v${second}, ${getTypeName(tIdx)}`;
    pc += 2;
  } else if (op === 0x23) { // new-array
    const tIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `new-array v${second & 0xf}, v${(second >> 4) & 0xf}, ${getTypeName(tIdx)}`;
    pc += 2;
  } else if (op === 0x28) { // goto
    const offset = dex.readInt8(insnsStart + pc * 2 + 1);
    line += `goto ${ (pc + offset).toString(16) }`;
    pc += 1;
  } else if (op >= 0x32 && op <= 0x37) { // if-test
    const offset = dex.readInt16LE(insnsStart + (pc + 1) * 2);
    line += `if-cmp v${second & 0xf}, v${(second >> 4) & 0xf}, -> ${(pc + offset).toString(16)}`;
    pc += 2;
  } else if (op >= 0x38 && op <= 0x3d) { // if-testz
    const offset = dex.readInt16LE(insnsStart + (pc + 1) * 2);
    line += `if-testz v${second}, -> ${(pc + offset).toString(16)}`;
    pc += 2;
  } else if (op >= 0x44 && op <= 0x4a) { // aget
    const r2 = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `aget v${second}, v${r2 & 0xff}, v${(r2 >> 8) & 0xff}`;
    pc += 2;
  } else if (op >= 0x4b && op <= 0x51) { // aput
    const r2 = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `aput v${second}, v${r2 & 0xff}, v${(r2 >> 8) & 0xff}`;
    pc += 2;
  } else if (op >= 0x52 && op <= 0x5f) { // iget/iput
    const fIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `instance-field v${second & 0xf}, v${(second >> 4) & 0xf}, ${getFieldName(fIdx)}`;
    pc += 2;
  } else if (op >= 0x60 && op <= 0x6d) { // sget/sput
    const fIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `static-field v${second}, ${getFieldName(fIdx)}`;
    pc += 2;
  } else if (op >= 0x6e && op <= 0x72) { // invoke-kind
    const mIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    const regs = dex.readUInt16LE(insnsStart + (pc + 2) * 2);
    line += `invoke (count=${(second >> 4)}) ${getMethodName(mIdx)}`;
    pc += 3;
  } else if (op >= 0x74 && op <= 0x78) { // invoke-kind/range
    const mIdx = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    const startReg = dex.readUInt16LE(insnsStart + (pc + 2) * 2);
    line += `invoke/range (count=${second}, start=v${startReg}) ${getMethodName(mIdx)}`;
    pc += 3;
  } else if (op === 0x7b || op === 0x7c || op === 0x7d || op === 0x7e || op === 0x7f) {
    line += `unary-op`;
    pc += 1;
  } else if (op >= 0x90 && op <= 0xaf) { // binop-2addr
    line += `binop-2addr op=0x${op.toString(16)} v${second & 0xf}, v${(second >> 4) & 0xf}`;
    pc += 1;
  } else if (op >= 0xd0 && op <= 0xd7) { // binop/2addr-lit16
    const lit = dex.readInt16LE(insnsStart + (pc + 1) * 2);
    line += `binop/lit16 op=0x${op.toString(16)} v${second & 0xf}, v${(second >> 4) & 0xf}, #${lit}`;
    pc += 2;
  } else if (op >= 0xd8 && op <= 0xe2) { // binop/lit8
    const r2 = dex.readUInt16LE(insnsStart + (pc + 1) * 2);
    line += `binop/lit8 op=0x${op.toString(16)} v${second}, v${r2 & 0xff}, #${(r2 >> 8) >= 128 ? (r2 >> 8) - 256 : (r2 >> 8)}`;
    pc += 2;
  } else {
    line += `instruction 0x${op.toString(16)}`;
    // Skip based on common instruction lengths if unknown
    pc += 1;
  }
  console.log(line);
}
