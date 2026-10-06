"""Generates the five alert sounds in app/src/main/res/raw/ from scratch (pure synthesis: sine waves, envelopes).

Nothing here is sampled or taken from anywhere, so the sounds are original work. They are dedicated to the public
domain under CC0 1.0 (see SOUNDS.md). Run: python tools/make_sounds.py   (needs numpy and ffmpeg with libvorbis)
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
LENGTH = 2.0  # seconds: the alert stops on its own


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def place(buf, sound, at):
    i = int(SR * at)
    n = min(len(sound), len(buf) - i)
    buf[i:i + n] += sound[:n]


def bell_note(freq, length, decay, partials=((1, 1.0), (2.76, 0.45), (5.4, 0.2), (8.93, 0.08))):
    x = t(length)
    s = sum(a * np.sin(2 * np.pi * freq * m * x) * np.exp(-x * decay * (1 + 0.25 * i)) for i, (m, a) in enumerate(partials))
    return s * np.minimum(x / 0.004, 1.0)  # a 4 ms attack avoids a click


def chime():
    b = np.zeros(int(SR * LENGTH))
    for k, f in enumerate((1046.5, 1318.5, 1568.0)):  # C6 E6 G6
        place(b, bell_note(f, 1.4, 3.2), 0.0 + k * 0.28)
    return b


def bell():
    b = np.zeros(int(SR * LENGTH))
    place(b, bell_note(660.0, LENGTH, 2.2), 0.0)
    return b


def pulse():
    b = np.zeros(int(SR * LENGTH))
    for k in range(3):
        x = t(0.16)
        beep = np.sin(2 * np.pi * 880 * x) * np.minimum(x / 0.01, 1) * np.minimum((0.16 - x) / 0.02, 1)
        place(b, beep, 0.1 + k * 0.4)
    return b


def marimba():
    b = np.zeros(int(SR * LENGTH))
    for k, f in enumerate((523.25, 783.99, 659.25, 1046.5)):  # C5 G5 E5 C6
        x = t(0.7)
        note = (np.sin(2 * np.pi * f * x) + 0.3 * np.sin(2 * np.pi * f * 4 * x) * np.exp(-x * 30)) * np.exp(-x * 7)
        place(b, note * np.minimum(x / 0.003, 1), 0.05 + k * 0.22)
    return b


def rise():
    x = t(LENGTH)
    freq = 420 + 520 * (x / LENGTH) ** 1.5
    phase = 2 * np.pi * np.cumsum(freq) / SR
    env = np.sin(np.pi * np.clip(x / LENGTH, 0, 1)) ** 1.5
    return (np.sin(phase) + 0.25 * np.sin(2 * phase)) * env


SOUNDS = {"alarm_chime": chime, "alarm_bell": bell, "alarm_pulse": pulse, "alarm_marimba": marimba, "alarm_rise": rise}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, make in SOUNDS.items():
        s = make()
        s = s / max(1e-9, np.max(np.abs(s))) * 0.85
        s[-int(SR * 0.05):] *= np.linspace(1, 0, int(SR * 0.05))  # fade the last 50 ms: never ends on a click
        pcm = (s * 32767).astype(np.int16)
        with tempfile.TemporaryDirectory() as d:
            wav = os.path.join(d, name + ".wav")
            with wave.open(wav, "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "3", os.path.join(OUT, name + ".ogg")], check=True)
        print("wrote", name)
