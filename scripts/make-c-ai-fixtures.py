#!/usr/bin/env python3
"""Generate synthetic Chinese OCR/ASR fixtures; no employee records or real recordings."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

import pymupdf
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
TEXT = '员工每年享有五天年假。请假申请须由人事审批。'


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, default=ROOT / 'artifacts/c-ai-fixtures')
    p.add_argument('--font', type=Path, help='A font with Chinese glyphs, e.g. NotoSansCJK-Regular.ttc')
    args = p.parse_args()
    candidates = [args.font] if args.font else [Path('C:/Windows/Fonts/msyh.ttc'),
                 Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')]
    font = next((f for f in candidates if f.is_file()), None)
    if font is None:
        raise RuntimeError('Provide --font with an installed Chinese font')
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    names = ['real-ocr-fixture.png', 'real-ocr-fixture.pdf', 'real-asr-fixture.wav', 'fixture-manifest.json']
    if any((folder / n).exists() for n in names):
        raise RuntimeError('Fixture files already exist; choose a new output directory to preserve them')
    image = Image.new('RGB', (1200, 260), 'white')
    draw = ImageDraw.Draw(image)
    typeface = ImageFont.truetype(str(font), 48)
    draw.text((50, 45), '员工每年享有五天年假', font=typeface, fill='black')
    draw.text((50, 135), '请假申请须由人事审批', font=typeface, fill='black')
    image.save(folder / names[0])
    with pymupdf.open() as pdf:
        page = pdf.new_page(width=600, height=130)
        page.insert_image(page.rect, filename=str(folder / names[0]))
        pdf.save(folder / names[1])
    if os.name == 'nt':
        env = os.environ.copy()
        env['LIANZHI_SYNTHETIC_WAV'] = str(folder / names[2])
        script = """Add-Type -AssemblyName System.Speech
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
  $speaker.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet,[System.Speech.Synthesis.VoiceAge]::NotSet,0,[System.Globalization.CultureInfo]::GetCultureInfo('zh-CN'))
  $speaker.SetOutputToWaveFile($env:LIANZHI_SYNTHETIC_WAV)
  $speaker.Speak('员工每年享有五天年假。请假申请须由人事审批。')
} finally { $speaker.Dispose() }
"""
        import base64
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-EncodedCommand',
                        base64.b64encode(script.encode('utf-16le')).decode('ascii')], env=env, check=True, timeout=30,
                       creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        speech = shutil.which('espeak-ng')
        if speech is None:
            raise RuntimeError('Install espeak-ng for synthetic Mandarin speech; OCR fixtures were generated')
        subprocess.run([speech, '-v', 'cmn', '-s', '150', '-w', str(folder / names[2]), TEXT], check=True, timeout=30)
    (folder / names[3]).write_text(json.dumps({'synthetic': True, 'text': TEXT, 'speech': 'system TTS',
                                             'scannedPdfHasTextLayer': False}, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Synthetic OCR/PDF/speech fixtures: ' + str(folder))


if __name__ == '__main__':
    main()
