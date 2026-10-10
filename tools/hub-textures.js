// Builds the procedural VTL Hub textures (glow). Run: node tools/hub-textures.js
const fs = require('fs');
const path = require('path');
const { encode } = require('./png');

const out = path.join(__dirname, '..', 'src/main/resources/assets/vtl/textures/gui');
fs.mkdirSync(out, { recursive: true });

// Soft round glow: white with alpha falling off smoothly; tinted when drawn.
const S = 128;
const glow = new Uint8Array(S * S * 4);
for (let y = 0; y < S; y++) {
  for (let x = 0; x < S; x++) {
    const dx = (x + 0.5) / S * 2 - 1, dy = (y + 0.5) / S * 2 - 1;
    const d = Math.min(1, Math.sqrt(dx * dx + dy * dy));
    const a = Math.pow(1 - d, 2.2);
    const o = (y * S + x) * 4;
    glow[o] = glow[o + 1] = glow[o + 2] = 255;
    glow[o + 3] = Math.round(a * 255);
  }
}
fs.writeFileSync(path.join(out, 'glow.png'), encode(S, S, glow));
console.log('wrote glow.png');
