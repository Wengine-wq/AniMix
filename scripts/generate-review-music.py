"""Original procedural instrumental for AniMix year review. No samples or third-party recordings.
Run from the repository root with Python, NumPy and FFmpeg. Output: shared Compose resource.
"""
import math, pathlib, subprocess, wave
import numpy as np

rate, bpm, bars = 24000, 86, 16
beat = 60 / bpm
duration = bars * 4 * beat
audio = np.zeros((int((duration + 4) * rate), 2), dtype=np.float64)
rng = np.random.default_rng(30102026)

def note(freq, start, length, volume, pan=0.0, bell=False):
    t = np.arange(int(length * rate)) / rate
    envelope = np.minimum(t / .06, 1) * np.exp(-t / (1.0 if bell else 4)) * np.minimum((length - t) / .35, 1)
    wave_ = np.sin(2*np.pi*freq*t) + .22*np.sin(2*np.pi*freq*2*t) + .06*np.sin(2*np.pi*freq*3*t)
    wave_ *= envelope * volume
    at = int(start*rate)
    audio[at:at+len(t), 0] += wave_ * math.sqrt((1-pan)/2)
    audio[at:at+len(t), 1] += wave_ * math.sqrt((1+pan)/2)

def hz(midi): return 440 * 2**((midi-69)/12)
progression = [(57,60,64,67),(53,57,60,64),(60,64,67,71),(55,59,62,67)]  # Am7 Fmaj7 Cmaj7 G
for bar in range(bars):
    chord = progression[bar % 4]
    start = bar * 4 * beat
    for j, pitch in enumerate(chord): note(hz(pitch), start, 4*beat+1.2, .035, (j-1.5)/3)
    note(hz(chord[0]-12), start, 3.8*beat, .065)
    for step in range(8):
        pitch = chord[[0,2,1,3,2,1,3,2][step]]+12
        note(hz(pitch), start+step*beat/2, 1.6, .035 if step%2 else .05, .35*math.sin(step), True)
    for b in range(4):
        t=np.arange(int(.22*rate))/rate
        kick=np.sin(2*np.pi*(48*t+18*(1-np.exp(-t*28))/28))*np.exp(-t*24)*.06
        at=int((start+b*beat)*rate);audio[at:at+len(t)] += kick[:,None]
    for b in (1,3):
        noise=rng.normal(0,1,int(.13*rate))*np.exp(-np.arange(int(.13*rate))/rate*40)*.012
        at=int((start+b*beat)*rate);audio[at:at+len(noise)] += noise[:,None]

# Soft cross-channel delays; no harsh transients. Smooth fade into and out of the loop.
for seconds, gain in ((.27,.15),(.53,.07)):
    delay=int(seconds*rate);audio[delay:] += audio[:-delay,::-1]*gain
audio=audio[:int(duration*rate)]
fade=int(2*rate);audio[:fade] *= np.linspace(0,1,fade)[:,None];audio[-fade:] *= np.linspace(1,0,fade)[:,None]
audio=np.tanh(audio*1.2)
root=pathlib.Path(__file__).resolve().parents[1]
wav=root/'build/review-music.wav';wav.parent.mkdir(exist_ok=True)
with wave.open(str(wav),'wb') as output:
    output.setnchannels(2);output.setsampwidth(2);output.setframerate(rate);output.writeframes((audio*32767).astype('<i2').tobytes())
target=root/'shared/src/commonMain/composeResources/files/review_horizon.ogg';target.parent.mkdir(exist_ok=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(wav),'-c:a','libvorbis','-q:a','3',str(target)],check=True)
print(f'Original AniMix instrumental: {duration:.1f}s, {target.stat().st_size} bytes')
