const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

// In classes.dex, method Lxm1;-><clinit> at 5962272
// Let's decode the fill-array-data payloads for fields d, e, f, g, h, i, j, k, l, m, n
const clinitOff = 5962272;
const insnsStart = clinitOff + 16;

function readFillArrayData(pc) {
  // pc points to instruction op=0x26 (fill-array-data vAA, +offset)
  const offset = dex.readInt32LE(insnsStart + (pc + 1) * 2);
  const target = (insnsStart + pc * 2) + offset * 2;
  const ident = dex.readUInt16LE(target);
  if (ident !== 0x0300) throw new Error(`Invalid fill-array-data ident: 0x${ident.toString(16)} at ${target}`);
  const elementWidth = dex.readUInt16LE(target + 2);
  const size = dex.readUInt32LE(target + 4);
  const data = [];
  for (let i = 0; i < size; i++) {
    data.push(dex.readUInt8(target + 8 + i * elementWidth));
  }
  return Buffer.from(data);
}

// In clinit:
// i: pc=0x3b
// j: pc=0x42
// k: pc=0x49
// l: pc=0x50
// m: pc=0x57
// n: pc=0x5e
const arrays = {
  i: readFillArrayData(0x3b),
  j: readFillArrayData(0x42),
  k: readFillArrayData(0x49),
  l: readFillArrayData(0x50),
  m: readFillArrayData(0x57),
  n: readFillArrayData(0x5e),
};

console.log("Raw arrays:");
for (const [k, v] of Object.entries(arrays)) {
  console.log(`  ${k} (len=${v.length}): ${v.toString("hex")}`);
}

// Now let's find the XOR key from onServicesDiscovered (0x021)
// In 0x021: op=0xdf (xor-int/2addr or xor-int/lit8)
// Let's inspect bytes around 0x01b-0x027 in onServicesDiscovered (codeOff=3534480)
const osdStart = 3534480 + 16;
for (let p = 0x1b; p <= 0x27; p++) {
  const b0 = dex.readUInt8(osdStart + p * 2);
  const b1 = dex.readUInt8(osdStart + p * 2 + 1);
  console.log(`osd 0x${p.toString(16)}: 0x${b0.toString(16)} 0x${b1.toString(16)}`);
}

// Let's test all XOR keys from 1 to 255 on array 'i'
console.log("\nTesting XOR keys on array 'i' to find UUID-like strings (containing '-' and hex):");
for (let key = 0; key < 256; key++) {
  const dec = Buffer.from(arrays.i.map(b => b ^ key)).toString("utf8");
  if (dec.includes("-") && /[0-9a-f]{4}/i.test(dec)) {
    console.log(`Key 0x${key.toString(16)}: "${dec}"`);
    for (const [k, arr] of Object.entries(arrays)) {
      const d = Buffer.from(arr.map(b => b ^ key)).toString("utf8");
      console.log(`   ${k} -> "${d}"`);
    }
    break;
  }
}
