#!/usr/bin/env python3
"""Export the original Lume identity to SVG and Android vectors. No font dependency."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "docs/brand"
DRAWABLE = ROOT / "app/src/main/res/drawable"
MARK = "M32,20 C27.6,20 24,23.6 24,28 V74 C24,82.8 33.5,88.3 41.1,83.9 L85,58.5 C92.3,54.3 92.3,43.7 85,39.5 L63,26.8 C58.3,24.1 52.5,27.5 52.5,32.9 V45.8 L66,53.6 L42,67.4 V28 C42,23.6 38.4,20 34,20 Z"
FOLD = "M52.5,32.9 V45.8 L66,53.6 L81.5,44.6 L63,33.9 Z"
# Original outline lettering, not an embedded or redistributed font.
WORD = "M5,4 V48 Q5,58 15,58 H18 M35,23 V44 Q35,58 49,58 Q63,58 63,44 V23 M82,58 V24 M82,35 Q82,23 94,23 Q106,23 106,36 V58 M106,36 Q106,23 118,23 Q130,23 130,36 V58 M153,41 H185 Q185,23 169,23 Q152,23 152,41 Q152,59 170,59 Q179,59 185,53"

def svg(body, width, height, viewbox):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="{viewbox}" role="img" aria-label="Lume TV">{body}</svg>\n'

def vector(body, width, height, vw, vh):
    return f'<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="{width}dp" android:height="{height}dp" android:viewportWidth="{vw}" android:viewportHeight="{vh}">{body}</vector>\n'

def gradient_path(path):
    return f'''<path android:pathData="{path}"><aapt:attr name="android:fillColor"><gradient android:startX="30" android:startY="22" android:endX="80" android:endY="84" android:startColor="#FFFFFF" android:centerColor="#D6E9FF" android:endColor="#7FB8FF" android:type="linear" /></aapt:attr></path>'''

def main():
    BRAND.mkdir(parents=True, exist_ok=True)
    defs = '<defs><linearGradient id="surface" x2=".7" y2="1"><stop stop-color="#303845"/><stop offset="1" stop-color="#101217"/></linearGradient><linearGradient id="light" x1=".1" x2=".85" y2="1"><stop stop-color="#fff"/><stop offset=".5" stop-color="#d6e9ff"/><stop offset="1" stop-color="#7fb8ff"/></linearGradient></defs>'
    symbol = f'<path d="{MARK}" fill="url(#light)"/><path d="{FOLD}" fill="#84baff" opacity=".65"/>'
    icon = f'{defs}<rect width="112" height="112" rx="25" fill="url(#surface)"/><rect x=".7" y=".7" width="110.6" height="110.6" rx="24.3" fill="none" stroke="#fff" stroke-opacity=".16" stroke-width=".7"/>{symbol}'
    (BRAND / 'app-icon.svg').write_text(svg(icon, 1024, 1024, '0 0 112 112'))
    (BRAND / 'symbol.svg').write_text(svg(defs + symbol, 448, 448, '0 0 112 112'))
    for name, color in [('logo-light', '#f5f6f8'), ('logo-dark', '#12151b')]:
        word = f'<g transform="translate(113 22) scale(.94)" fill="none" stroke="{color}" stroke-width="8.5" stroke-linecap="round" stroke-linejoin="round"><path d="{WORD}"/></g><text x="309" y="78" fill="{color}" font-family="sans-serif" font-size="15" letter-spacing="2">TV</text>'
        (BRAND / f'{name}.svg').write_text(svg(defs + symbol + word, 1020, 336, '0 0 340 112'))
    mark_native = gradient_path(MARK) + f'<path android:fillColor="#A684BAFF" android:pathData="{FOLD}" />'
    bg = '<path android:pathData="M25,0 H87 Q112,0 112,25 V87 Q112,112 87,112 H25 Q0,112 0,87 V25 Q0,0 25,0 Z"><aapt:attr name="android:fillColor"><gradient android:startX="0" android:startY="0" android:endX="90" android:endY="112" android:startColor="#303845" android:endColor="#101217" android:type="linear" /></aapt:attr></path>'
    (DRAWABLE / 'ic_lume.xml').write_text(vector(bg + mark_native, 112, 112, 112, 112))
    (DRAWABLE / 'ic_lume_mark.xml').write_text(vector(mark_native, 112, 112, 112, 112))
    word_native = f'<group android:translateX="113" android:translateY="22" android:scaleX=".94" android:scaleY=".94"><path android:pathData="{WORD}" android:fillColor="@android:color/transparent" android:strokeColor="#F5F6F8" android:strokeWidth="8.5" android:strokeLineCap="round" android:strokeLineJoin="round" /></group>'
    (DRAWABLE / 'lume_wordmark.xml').write_text(vector(mark_native + word_native, 302, 112, 302, 112))
    banner = '<path android:fillColor="#10141C" android:pathData="M0,0 H320 V180 H0 Z"/><group android:translateX="33" android:translateY="43" android:scaleX=".84" android:scaleY=".84">' + mark_native + word_native + '</group>'
    (DRAWABLE / 'tv_banner.xml').write_text(vector(banner, 320, 180, 320, 180))
    try:
        import cairosvg
        for source in BRAND.glob('*.svg'):
            cairosvg.svg2png(url=str(source), write_to=str(source.with_suffix('.png')))
    except ImportError:
        print('SVG and Android vectors exported. Optional PNG export: pip install cairosvg')

if __name__ == '__main__':
    main()
