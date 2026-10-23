const fs = require("fs");
const dex = fs.readFileSync("classes.dex");

// Let's inspect where Maneuver opcodes are mapped
// In GoogleMapsNotificationListener and BikerBleService, let's search for opcode assignments
const strOpcode = "BLE_TBT -> Opcode: ";
const tbtIdx = dex.indexOf(strOpcode);
console.log("Index:", tbtIdx);

// Search for opcode mappings in the dex file
const strList = [];
for (let i = 0; i < dex.length - 20; i++) {
  if (dex[i] === 0x1a) { // const-string
    // check if it's near maneuver names
  }
}
