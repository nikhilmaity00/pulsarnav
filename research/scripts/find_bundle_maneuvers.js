const fs = require("fs");
const bundle = fs.readFileSync("bajaj_bundle.js", "utf8");

// Search for maneuverID mapping in React Native bundle
const regex = /[^\n]{0,200}maneuverID[^\n]{0,200}/gi;
let match;
while ((match = regex.exec(bundle)) !== null) {
  console.log("Match:\n", match[0].trim());
}
