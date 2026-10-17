"""Reject inconsistent coordinates that patch(1) can accept for zero-context hunks."""
import re

HEADER = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*$", re.M)

def validate_patch_offsets(patch: str) -> None:
    hunks = list(HEADER.finditer(patch))
    if not hunks:
        raise ValueError("sdk_patch_has_no_hunks")
    delta = 0
    for index, hunk in enumerate(hunks):
        old, old_count, new, new_count = (int(hunk[1]), int(hunk[2] or 1), int(hunk[3]), int(hunk[4] or 1))
        expected = old + delta + (1 if old_count == 0 else -1 if new_count == 0 else 0)
        if new != expected:
            raise ValueError("sdk_patch_inconsistent_offset")
        end = hunks[index + 1].start() if index + 1 < len(hunks) else len(patch)
        body = patch[hunk.end():end].splitlines()
        if any(line and not line.startswith(("+", "-", " ", "\\ No newline")) for line in body):
            raise ValueError("sdk_patch_invalid_hunk_body")
        actual_old = sum(line.startswith(("-", " ")) for line in body)
        actual_new = sum(line.startswith(("+", " ")) for line in body)
        if (actual_old, actual_new) != (old_count, new_count):
            raise ValueError("sdk_patch_inconsistent_line_count")
        delta += new_count - old_count

def validate_external_layout(source: str) -> None:
    start = source.index("private int initPlayout(")
    end = source.index("private boolean startPlayout(", start)
    init = source[start:end]
    external = init.find("if (externalPlayout)")
    hardware = init.find("AudioTrack.getMinBufferSize")
    if external < 0 or hardware < 0 or external > hardware:
        raise ValueError("sdk_external_playout_must_bypass_hardware_init")
    branch = init[external:init.index("}", external)]
    if not all(value in branch for value in ("externalSampleRate = sampleRate", "externalChannels = channels", "return byteBuffer.capacity()")):
        raise ValueError("sdk_external_playout_format_not_initialized")
    getter = source[source.index("private int getBufferSizeInFrames("):]
    if getter.find("return initialBufferSizeInFrames") < 0 or getter.find("return initialBufferSizeInFrames") > getter.find("return audioTrack.getBufferSizeInFrames"):
        raise ValueError("sdk_external_playout_buffer_getter_not_guarded")
