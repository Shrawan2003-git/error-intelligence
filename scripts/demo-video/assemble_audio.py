"""Join offline narration, build slide timings, captions, and a readable transcript."""

from __future__ import annotations

import json
import re
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".local/demo-video"
PUBLIC = ROOT / "docs/demo"
STORYBOARD = json.loads((ROOT / "scripts/demo-video/storyboard.json").read_text(encoding="utf-8"))
RATE = 22050
PRE_ROLL = 0.8
POST_ROLL = 1.1


def timestamp(seconds: float) -> str:
    millis = round(seconds * 1000)
    hours, remainder = divmod(millis, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    secs, remainder = divmod(remainder, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{remainder:03d}"


def silence(seconds: float) -> bytes:
    return b"\x00\x00" * round(seconds * RATE)


timeline = []
captions = ["WEBVTT", ""]
transcript = ["# Error Intelligence — narrated product walkthrough", "", "All app data shown is synthetic. The optional AI worker is disabled in this local demo.", ""]
total_frames = 0

with wave.open(str(WORK / "narration.wav"), "wb") as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(RATE)
    for index, scene in enumerate(STORYBOARD):
        with wave.open(str(WORK / "audio" / f"{scene['id']}.wav"), "rb") as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, RATE):
                raise ValueError(f"Unexpected audio format in {scene['id']}.wav")
            audio = source.readframes(source.getnframes())
            frames = source.getnframes()

        start = total_frames / RATE
        output.writeframes(silence(PRE_ROLL))
        total_frames += round(PRE_ROLL * RATE)
        audio_start = total_frames / RATE
        output.writeframes(audio)
        total_frames += frames
        audio_end = total_frames / RATE
        output.writeframes(silence(POST_ROLL))
        total_frames += round(POST_ROLL * RATE)
        end = total_frames / RATE

        timeline.append({
            "id": scene["id"],
            "slide": f"{index:02d}-{scene['id']}.png",
            "start": round(start, 3),
            "audioStart": round(audio_start, 3),
            "audioEnd": round(audio_end, 3),
            "end": round(end, 3),
        })
        transcript += [f"## {index + 1}. {scene['title']}", "", scene["narration"], ""]

        sentences = re.split(r"(?<=[.!?])\s+", scene["narration"])
        weights = [max(1, len(sentence.split())) for sentence in sentences]
        cursor = audio_start
        for sentence_index, sentence in enumerate(sentences):
            if not sentence.strip():
                continue
            duration = (audio_end - audio_start) * weights[sentence_index] / sum(weights)
            finish = audio_end if sentence_index == len(sentences) - 1 else cursor + duration
            captions += [f"{timestamp(cursor)} --> {timestamp(finish)}", sentence.strip(), ""]
            cursor = finish

(WORK / "timeline.json").write_text(json.dumps(timeline, indent=2), encoding="utf-8")
(PUBLIC / "captions.vtt").write_text("\n".join(captions), encoding="utf-8")
(PUBLIC / "transcript.md").write_text("\n".join(transcript), encoding="utf-8")
print(f"Narration and captions cover {total_frames / RATE:.1f} seconds across {len(timeline)} scenes.")
