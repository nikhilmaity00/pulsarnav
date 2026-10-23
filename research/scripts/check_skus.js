const fs = require("fs");
const bundle = fs.readFileSync("bajaj_bundle.js", "utf8");
const skus = ["00JR73", "00JR90", "00JR54", "00JRGF", "00JRGG", "00JR66", "00JF63", "00JL31", "00DT23", "00JL34", "00JF68", "00JF69", "00JF64", "00JFNK", "00JR68", "00JR91", "00JR88", "00JR72", "00JR71"];

for (const sku of skus) {
  let idx = 0;
  while ((idx = bundle.indexOf(sku, idx)) !== -1) {
    console.log(`SKU ${sku}: ` + bundle.substring(Math.max(0, idx - 80), Math.min(bundle.length, idx + 120)).replace(/\n/g, ' '));
    idx += sku.length;
  }
}
