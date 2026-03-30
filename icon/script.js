const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const INPUT_FILE = 'icon-original.svg';
const TARGET_DIR = path.resolve(__dirname, '../app/src/main/res');
const ANYDPI_DIR = path.join(TARGET_DIR, 'mipmap-anydpi-v26');
const DRAWABLE_DIR = path.join(TARGET_DIR, 'drawable');

const densities = { 'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192 };

async function generate() {
    if (!fs.existsSync(INPUT_FILE)) return console.error("Missing icon-original.svg");

    [ANYDPI_DIR, DRAWABLE_DIR].forEach(d => { if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true }); });

    // GENERATE VECTOR FOREGROUND (No pixelation, perfect alignment)
    const vectorForeground = `<?xml version="1.0" encoding="utf-8"?>
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="108dp"
        android:height="108dp"
        android:viewportWidth="108"
        android:viewportHeight="108">
        <group android:translateX="54" android:translateY="54">
            <path android:strokeColor="#FFFFFF" android:strokeWidth="4" android:strokeLineCap="round"
                android:pathData="M -14.93,14.93 A 21.12,21.12 0 1 1 14.93,14.93" />
            <path android:strokeColor="#FFFFFF" android:strokeWidth="4" android:strokeLineCap="round"
                android:pathData="M -8.40,8.40 A 11.88,11.88 0 1 1 8.40,8.40" />
            <path android:strokeColor="#FFFFFF" android:strokeWidth="4" android:strokeLineCap="round"
                android:pathData="M 0,0 L 0,26.4" />
            <path android:fillColor="#FFFFFF"
                android:pathData="M 0,0m -4.62,0a 4.62,4.62 0 1 1 9.24,0a 4.62,4.62 0 1 1 -9.24,0" />
        </group>
    </vector>`;
    fs.writeFileSync(path.join(DRAWABLE_DIR, 'ic_launcher_foreground.xml'), vectorForeground);

    // GENERATE ANYDPI XML
    const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>`;
    fs.writeFileSync(path.join(ANYDPI_DIR, 'ic_launcher.xml'), adaptiveXml);
    fs.writeFileSync(path.join(ANYDPI_DIR, 'ic_launcher_round.xml'), adaptiveXml);

    // GENERATE LEGACY BITMAPS
    const svgBuffer = fs.readFileSync(INPUT_FILE);
    for (const [name, size] of Object.entries(densities)) {
        const dir = path.join(TARGET_DIR, `mipmap-${name}`);
        if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });

        // Build legacy icon (Background + Icon)
        await sharp({
            create: { width: size, height: size, channels: 4, background: '#66BB6A' }
        })
        .composite([{ input: await sharp(svgBuffer).resize(size, size).toBuffer() }])
        .webp({ lossless: true })
        .toFile(path.join(dir, 'ic_launcher.webp'));

        // Rounded legacy icon (Circle Mask)
        const mask = Buffer.from(`<svg><circle cx="${size/2}" cy="${size/2}" r="${size/2}" fill="white"/></svg>`);
        await sharp({
            create: { width: size, height: size, channels: 4, background: '#66BB6A' }
        })
        .composite([
            { input: await sharp(svgBuffer).resize(size, size).toBuffer() },
            { input: mask, blend: 'dest-in' }
        ])
        .webp({ lossless: true })
        .toFile(path.join(dir, 'ic_launcher_round.webp'));

        console.log(`Generated assets for ${name}`);
    }
}

generate();