const fs = require("fs");
const buffer = fs.readFileSync("manual_moto_digital.pdf");

// Search for plain text or metadata in PDF
const text = buffer.toString("latin1");
const titleMatch = text.match(/\/Title\s*\(([^)]+)\)/i);
console.log("PDF Title:", titleMatch ? titleMatch[1] : "None");

// Find any readable text chunks in PDF streams
const streams = text.match(/stream[\r\n]+([\s\S]*?)[\r\n]+endstream/g) || [];
console.log("Total stream objects:", streams.length);

// Search for motorcycle brands or instructions
const matches = text.match(/[\x20-\x7E]{6,}(?:pulsar|bajaj|bluetooth|pantalla|tablero|tbt|dominar|ktm|ns200|n250|f250)[\x20-\x7E]{6,}/gi) || [];
console.log("Matches count:", matches.length);
console.log("Sample matches:", matches.slice(0, 30));
