const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const codeOff = 3635468;
const insnsStart = codeOff + 16;
// In method at 3635468, let's look at lines around pc=0x260 to 0x290
for (let i = 0x250; i <= 0x290; i++) {
  const op = dex.readUInt8(insnsStart + i * 2);
  const second = dex.readUInt8(insnsStart + i * 2 + 1);
  console.log(`pc 0x${i.toString(16)}: op=0x${op.toString(16)} sec=0x${second.toString(16)}`);
}
