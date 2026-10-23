const fs = require("fs");
const buffer = fs.readFileSync("bajaj_manifest.xml");

const stringCount = buffer.readUInt32LE(16);
const flags = buffer.readUInt32LE(24);
const stringsStart = buffer.readUInt32LE(28) + 8;
const isUtf8 = (flags & (1 << 8)) !== 0;

const strings = [];
for (let i = 0; i < stringCount; i++) {
  const off = stringsStart + buffer.readUInt32LE(36 + i * 4);
  if (isUtf8) {
    let p = off;
    if ((buffer.readUInt8(p) & 0x80) !== 0) p += 2; else p += 1;
    if ((buffer.readUInt8(p) & 0x80) !== 0) p += 2; else p += 1;
    let end = p;
    while (buffer.readUInt8(end) !== 0) end++;
    strings.push(buffer.subarray(p, end).toString("utf8"));
  } else {
    const len = buffer.readUInt16LE(off);
    strings.push(buffer.subarray(off + 2, off + 2 + len * 2).toString("utf16le"));
  }
}
console.log("Sample strings:", strings.filter(s => s.includes("com.") || s.includes("bajaj") || s.includes("ride")).slice(0, 30));
