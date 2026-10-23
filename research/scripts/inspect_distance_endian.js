const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const codeOff = 2135684;
const insnsStart = codeOff + 16;

console.log("=== Lines 0x145 to 0x175 of Le30;->h ===");
for (let i = 0x145; i <= 0x175; i++) {
  const op = dex.readUInt8(insnsStart + i * 2);
  const sec = dex.readUInt8(insnsStart + i * 2 + 1);
  const w1 = dex.readUInt16LE(insnsStart + (i + 1) * 2);
  console.log(`0x${i.toString(16)}: op=0x${op.toString(16)} sec=0x${sec.toString(16)} w1=0x${w1.toString(16)}`);
}
