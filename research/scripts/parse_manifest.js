const fs = require("fs");
const buffer = fs.readFileSync("bogui_manifest.xml");

// Parse string pool of binary XML:
// Byte 0-3: 0x00080003 (RES_XML_TYPE)
// Next chunk: RES_STRING_POOL_TYPE (0x001c0001)
const chunkType = buffer.readUInt16LE(8);
console.log("Chunk type:", chunkType.toString(16));
const stringCount = buffer.readUInt32LE(16);
const styleCount = buffer.readUInt32LE(20);
const flags = buffer.readUInt32LE(24);
const stringsStart = buffer.readUInt32LE(28) + 8;
const isUtf8 = (flags & (1 << 8)) !== 0;
console.log(`stringCount: ${stringCount}, isUtf8: ${isUtf8}`);

const strings = [];
for (let i = 0; i < stringCount; i++) {
  const off = stringsStart + buffer.readUInt32LE(36 + i * 4);
  if (isUtf8) {
    // 2 bytes length (or uleb)
    let p = off;
    // skip char len
    if ((buffer.readUInt8(p) & 0x80) !== 0) p += 2; else p += 1;
    // skip byte len
    if ((buffer.readUInt8(p) & 0x80) !== 0) p += 2; else p += 1;
    let end = p;
    while (buffer.readUInt8(end) !== 0) end++;
    strings.push(buffer.subarray(p, end).toString("utf8"));
  } else {
    const len = buffer.readUInt16LE(off);
    strings.push(buffer.subarray(off + 2, off + 2 + len * 2).toString("utf16le"));
  }
}

console.log("Sample manifest strings:\n", strings.filter(s => s.includes("android.") || s.includes("bikerhud") || s.includes("Activity") || s.includes("Service")).join("\n"));
