// Cuts the kit card images out of the dev client's render (./gradlew runClient -Pdevshots):
// run/vtl-shots/screenshots/kit-art.png + run/vtl-shots/kit-art.json
//   -> src/main/resources/assets/vtl/textures/gui/kit/<kit>.png and <kit>_gray.png (128 x 176)
// The #FF00FF background becomes transparent; the grey version is for closed kits.
// Run: node tools/kit-art.js
const fs = require('fs');
const path = require('path');
const { decode, encode } = require('./png');

const root = path.join(__dirname, '..');
const shots = path.join(root, 'run/vtl-shots');
const outDir = path.join(root, 'src/main/resources/assets/vtl/textures/gui/kit');
const OUT_W = 128, OUT_H = 176;

const meta = JSON.parse(fs.readFileSync(path.join(shots, 'kit-art.json'), 'utf8'));
const shot = decode(fs.readFileSync(path.join(shots, 'screenshots/kit-art.png')));
const scale = meta.guiScale;
fs.mkdirSync(outDir, { recursive: true });

const isKey = (r, g, b) => r > 200 && g < 70 && b > 200;

for (const cell of meta.cells) {
  const x0 = Math.round(cell.x * scale), y0 = Math.round(cell.y * scale);
  const cw = Math.round(cell.w * scale), ch = Math.round(cell.h * scale);
  const color = new Uint8Array(OUT_W * OUT_H * 4);
  const gray = new Uint8Array(OUT_W * OUT_H * 4);
  for (let oy = 0; oy < OUT_H; oy++) {
    for (let ox = 0; ox < OUT_W; ox++) {
      // area average over the source pixels this output pixel covers (premultiplied)
      const sx0 = x0 + Math.floor(ox * cw / OUT_W), sx1 = x0 + Math.max(Math.floor((ox + 1) * cw / OUT_W), Math.floor(ox * cw / OUT_W) + 1);
      const sy0 = y0 + Math.floor(oy * ch / OUT_H), sy1 = y0 + Math.max(Math.floor((oy + 1) * ch / OUT_H), Math.floor(oy * ch / OUT_H) + 1);
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      for (let sy = sy0; sy < sy1; sy++) {
        for (let sx = sx0; sx < sx1; sx++) {
          n++;
          if (sx < 0 || sy < 0 || sx >= shot.width || sy >= shot.height) continue;
          const i = (sy * shot.width + sx) * 4;
          const pr = shot.rgba[i], pg = shot.rgba[i + 1], pb = shot.rgba[i + 2];
          if (isKey(pr, pg, pb)) continue;
          r += pr; g += pg; b += pb; a += 255;
        }
      }
      const o = (oy * OUT_W + ox) * 4;
      if (a === 0) continue;
      const alpha = a / n;
      const cr = r * 255 / a, cg = g * 255 / a, cb = b * 255 / a;
      color[o] = cr; color[o + 1] = cg; color[o + 2] = cb; color[o + 3] = Math.round(alpha);
      const l = (0.299 * cr + 0.587 * cg + 0.114 * cb) * 0.8;
      gray[o] = gray[o + 1] = gray[o + 2] = Math.round(l); gray[o + 3] = Math.round(alpha);
    }
  }
  fs.writeFileSync(path.join(outDir, `${cell.kit}.png`), encode(OUT_W, OUT_H, color));
  fs.writeFileSync(path.join(outDir, `${cell.kit}_gray.png`), encode(OUT_W, OUT_H, gray));
  const opaque = color.filter((v, i) => i % 4 === 3 && v > 0).length;
  console.log(`${cell.kit}: ${opaque} visible pixels`);
}
