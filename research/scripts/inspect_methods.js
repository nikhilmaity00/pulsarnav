const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

const stringIdsOff = dex.readUInt32LE(0x3c);
const typeIdsOff = dex.readUInt32LE(0x44);
const protoIdsOff = dex.readUInt32LE(0x4c);
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
  return getString(nameIdx);
}

function getMethodClass(idx) {
  const classIdx = dex.readUInt16LE(methodIdsOff + idx * 8);
  return getTypeName(classIdx);
}

// Find class BikerBleService and GoogleMapsNotificationListener
for (let i = 0; i < classDefsSize; i++) {
  const cOff = classDefsOff + i * 32;
  const classIdx = dex.readUInt32LE(cOff);
  const className = getTypeName(classIdx);
  if (className.includes("BikerBleService") || className.includes("GoogleMapsNotificationListener")) {
    console.log(`\n=== Class: ${className} ===`);
    const classDataOff = dex.readUInt32LE(cOff + 24);
    if (classDataOff === 0) continue;
    
    // Parse class_data_item (ULEB128):
    // static_fields_size, instance_fields_size, direct_methods_size, virtual_methods_size
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
    const staticFieldsSize = readUleb();
    const instanceFieldsSize = readUleb();
    const directMethodsSize = readUleb();
    const virtualMethodsSize = readUleb();
    
    console.log(`Fields: static=${staticFieldsSize}, instance=${instanceFieldsSize}`);
    console.log(`Methods: direct=${directMethodsSize}, virtual=${virtualMethodsSize}`);
    
    // Skip fields
    for (let f = 0; f < staticFieldsSize + instanceFieldsSize; f++) {
      readUleb(); // field_idx_diff
      readUleb(); // access_flags
    }
    
    // Read methods
    let methodIdx = 0;
    for (let m = 0; m < directMethodsSize + virtualMethodsSize; m++) {
      const diff = readUleb();
      methodIdx += diff;
      const flags = readUleb();
      const codeOff = readUleb();
      console.log(`  Method: ${getMethodName(methodIdx)} (codeOff: ${codeOff})`);
    }
  }
}
