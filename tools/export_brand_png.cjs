// Optional documentation exports: npm install --no-save sharp, then node tools/export_brand_png.cjs.
// Native app resources are vectors and do not depend on these PNGs or on Node.
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const directory = path.resolve(__dirname, '../docs/brand');
(async () => {
  for (const file of fs.readdirSync(directory).filter(name => name.endsWith('.svg'))) {
    await sharp(path.join(directory, file)).png().toFile(path.join(directory, file.replace('.svg', '.png')));
  }
})();
