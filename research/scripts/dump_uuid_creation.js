const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const codeOff = 3534480;
const insnsStart = codeOff + 16;

function dumpWindow(start, end) {
  console.log(`=== Window 0x${start.toString(16)} to 0x${end.toString(16)} ===`);
  for (let i = start; i <= end; i++) {
    const op = dex.readUInt8(insnsStart + i * 2);
    const second = dex.readUInt8(insnsStart + i * 2 + 1);
    const word1 = dex.readUInt16LE(insnsStart + (i + 1) * 2);
    console.log(`0x${i.toString(16).padStart(3, '0')}: op=0x${op.toString(16).padStart(2, '0')}, second=0x${second.toString(16).padStart(2, '0')}, word1=0x${word1.toString(16).padStart(4, '0')}`);
  }
}

dumpWindow(0x015, 0x035);
dumpWindow(0x0e5, 0x105);
dumpWindow(0x110, 0x12a);
dumpWindow(0x135, 0x150);
dumpWindow(0x160, 0x178);
dumpWindow(0x185, 0x1a0);
