const fs = require("fs");
const buffer = fs.readFileSync("bogui_resources.arsc");

// Quick check for UTF-8 and UTF-16 strings in arsc
const text16 = buffer.toString("utf16le");
const text8 = buffer.toString("utf8");

const matches16 = text16.match(/[\u0020-\u007E\u00A0-\u00FF]{4,}/g) || [];
const matches8 = text8.match(/[\x20-\x7E\xA0-\xFF]{4,}/g) || [];

console.log("Found utf16 strings:", matches16.length);
console.log("Found utf8 strings:", matches8.length);

// Let's search for language indicators or known Spanish phrases
const samplePhrases = ["moto", "tablero", "conectar", "velocidad", "radar", "cluster", "bajaj", "pulsar", "bluetooth", "mapa", "idioma", "language", "english", "ingles"];
for (const p of samplePhrases) {
  const found16 = matches16.filter(s => s.toLowerCase().includes(p));
  const found8 = matches8.filter(s => s.toLowerCase().includes(p));
  console.log(`Phrase "${p}": ${found16.length} (utf16), ${found8.length} (utf8)`);
  if (found16.length > 0) console.log(`   sample 16:`, found16.slice(0, 3));
  if (found8.length > 0) console.log(`   sample 8:`, found8.slice(0, 3));
}
