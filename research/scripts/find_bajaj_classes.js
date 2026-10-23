const fs = require("fs");
const dex = fs.readFileSync("bajaj_classes.dex");

const strRegex = /Lcom\/bajaj\/[a-zA-Z0-9_\/$]+/g;
const strMatches = [...new Set(dex.toString("latin1").match(strRegex) || [])];
console.log("Com.bajaj classes:", strMatches);
