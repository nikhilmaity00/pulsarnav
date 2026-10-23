// Comprehensive protocol test and verification script

const Maneuver = {
  TURN_LEFT: 73,           // 'I'
  TURN_RIGHT: 74,          // 'J'
  TURN_SLIGHT_LEFT: 67,    // 'C'
  TURN_SLIGHT_RIGHT: 68,   // 'D'
  TURN_SHARP_LEFT: 69,     // 'E'
  TURN_SHARP_RIGHT: 70,    // 'F'
  STRAIGHT: 71,            // 'G'
  DESTINATION_REACHED: 72, // 'H'
  RAMP_LEFT: 75,           // 'K'
  RAMP_RIGHT: 76,          // 'L'
  ROUNDABOUT_RIGHT: 78,    // 'N'
  U_TURN_LEFT: 79,         // 'O'
  U_TURN_RIGHT: 80,        // 'P'
  FORK_LEFT: 81,           // 'Q'
  FORK_RIGHT: 82,          // 'R'
  ROUNDABOUT_LEFT: 85,     // 'U'
  MERGE: 86,               // 'V'
  KEEP_RIGHT: 88,          // 'X'
  KEEP_LEFT: 90,           // 'Z'
  WRONG_WAY: 66,           // 'B'
};

function normalizeStreetName(str) {
  if (!str) return "";
  let clean = str
    .replace(/[áÁ]/g, "a")
    .replace(/[éÉ]/g, "e")
    .replace(/[íÍ]/g, "i")
    .replace(/[óÓ]/g, "o")
    .replace(/[úÚ]/g, "u")
    .replace(/[ñÑ]/g, "n")
    .replace(/[^0-9a-zA-Z. ]/g, " ")
    .trim()
    .replace(/\s+/g, " ");
  if (clean.length > 31) {
    clean = clean.substring(0, 31);
  }
  return clean;
}

function buildOtcTbtPacket({ maneuverOpcode, distanceMeters, streetName, etaMinutes = 0 }) {
  const packet = Buffer.alloc(48, 0);

  // Byte 0: Sequence/Packet indicator (0x01)
  packet[0] = 0x01;
  packet[1] = 0x00;
  packet[2] = 0x00;
  packet[3] = 0x00;
  packet[4] = 0x00;
  packet[5] = 0x00;
  packet[6] = 0x00;

  // Byte 7: Maneuver Opcode
  packet[7] = maneuverOpcode & 0xff;

  // Bytes 8-11: Distance in meters (Big Endian)
  const dist = Math.max(0, Math.floor(distanceMeters));
  packet[8] = (dist >> 24) & 0xff;
  packet[9] = (dist >> 16) & 0xff;
  packet[10] = (dist >> 8) & 0xff;
  packet[11] = dist & 0xff;

  // Bytes 12-13: Status / ETA minutes
  packet[12] = (etaMinutes >> 8) & 0xff;
  packet[13] = etaMinutes & 0xff;

  // Byte 14: Street name length
  const cleanStreet = normalizeStreetName(streetName);
  const streetBytes = Buffer.from(cleanStreet, "ascii");
  const streetLen = Math.min(streetBytes.length, 31);
  packet[14] = streetLen;

  // Bytes 15 to 15 + streetLen: Street ASCII
  streetBytes.copy(packet, 15, 0, streetLen);

  // Byte 47: Checksum of bytes 0..46
  let checksum = 0;
  for (let i = 0; i < 47; i++) {
    checksum = (checksum + packet[i]) & 0xff;
  }
  packet[47] = checksum;

  return packet;
}

function parseGoogleMapsNotification(title, text, subtext = "") {
  const fullText = `${title || ""} ${text || ""} ${subtext || ""}`.toLowerCase();

  // 1. Parse Distance
  let distanceMeters = 0;
  const kmMatch = fullText.match(/([0-9]+[.,]?[0-9]*)\s*(?:km|kilometers)/i);
  const mMatch = fullText.match(/([0-9]+)\s*(?:m|meters)(?!in)/i);
  const miMatch = fullText.match(/([0-9]+[.,]?[0-9]*)\s*(?:mi|miles)/i);
  const ftMatch = fullText.match(/([0-9]+)\s*(?:ft|feet)/i);

  if (kmMatch) {
    distanceMeters = parseFloat(kmMatch[1].replace(",", ".")) * 1000;
  } else if (mMatch) {
    distanceMeters = parseInt(mMatch[1], 10);
  } else if (miMatch) {
    distanceMeters = parseFloat(miMatch[1].replace(",", ".")) * 1609.34;
  } else if (ftMatch) {
    distanceMeters = parseInt(ftMatch[1], 10) * 0.3048;
  }

  // 2. Parse Maneuver Opcode
  let opcode = Maneuver.STRAIGHT;
  if (/destination|arrived|you have arrived/i.test(fullText)) {
    opcode = Maneuver.DESTINATION_REACHED;
  } else if (/u-turn|uturn/i.test(fullText)) {
    opcode = /left/i.test(fullText) ? Maneuver.U_TURN_LEFT : Maneuver.U_TURN_RIGHT;
  } else if (/roundabout|rotary/i.test(fullText)) {
    opcode = /left/i.test(fullText) ? Maneuver.ROUNDABOUT_LEFT : Maneuver.ROUNDABOUT_RIGHT;
  } else if (/sharp left/i.test(fullText)) {
    opcode = Maneuver.TURN_SHARP_LEFT;
  } else if (/sharp right/i.test(fullText)) {
    opcode = Maneuver.TURN_SHARP_RIGHT;
  } else if (/slight left|bear left/i.test(fullText)) {
    opcode = Maneuver.TURN_SLIGHT_LEFT;
  } else if (/slight right|bear right/i.test(fullText)) {
    opcode = Maneuver.TURN_SLIGHT_RIGHT;
  } else if (/keep left/i.test(fullText)) {
    opcode = Maneuver.KEEP_LEFT;
  } else if (/keep right/i.test(fullText)) {
    opcode = Maneuver.KEEP_RIGHT;
  } else if (/fork.*left/i.test(fullText)) {
    opcode = Maneuver.FORK_LEFT;
  } else if (/fork.*right/i.test(fullText)) {
    opcode = Maneuver.FORK_RIGHT;
  } else if (/ramp.*left/i.test(fullText)) {
    opcode = Maneuver.RAMP_LEFT;
  } else if (/ramp.*right/i.test(fullText)) {
    opcode = Maneuver.RAMP_RIGHT;
  } else if (/turn left|take a left|left onto/i.test(fullText)) {
    opcode = Maneuver.TURN_LEFT;
  } else if (/turn right|take a right|right onto/i.test(fullText)) {
    opcode = Maneuver.TURN_RIGHT;
  } else if (/merge/i.test(fullText)) {
    opcode = Maneuver.MERGE;
  } else if (/continue|head|straight|drive/i.test(fullText)) {
    opcode = Maneuver.STRAIGHT;
  }

  // 3. Extract Street / Road Name
  let streetName = "";
  if (text) {
    streetName = text.trim();
  }

  return { opcode, distanceMeters: Math.round(distanceMeters), streetName };
}

// Test cases
console.log("=== RUNNING PROTOCOL & PARSER VERIFICATION ===");

const testNotifications = [
  { title: "In 350 m", text: "Turn left onto Outer Ring Rd" },
  { title: "In 1.2 km", text: "Keep right toward Airport Rd" },
  { title: "In 500 m", text: "Take the 2nd exit at the roundabout" },
  { title: "In 100 m", text: "Make a U-turn on MG Road" },
  { title: "You have arrived", text: "Your destination is on the right" },
];

for (const test of testNotifications) {
  const parsed = parseGoogleMapsNotification(test.title, test.text);
  const packet = buildOtcTbtPacket({
    maneuverOpcode: parsed.opcode,
    distanceMeters: parsed.distanceMeters,
    streetName: parsed.streetName,
    etaMinutes: 18
  });
  console.log(`\nInput: "${test.title}" - "${test.text}"`);
  console.log(`Parsed Maneuver Opcode: ${parsed.opcode} (0x${parsed.opcode.toString(16).toUpperCase()}), Distance: ${parsed.distanceMeters}m`);
  console.log(`Packet Hex (${packet.length} bytes):`);
  console.log(packet.toString("hex").match(/.{1,32}/g).join("\n"));
  console.log(`Verified Checksum at byte 47: 0x${packet[47].toString(16).toUpperCase()}`);
}
