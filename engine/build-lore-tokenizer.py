"""Compile the pinned DeepSeek V4.1 tokenizer to the Android counting table."""

import hashlib
import json
import struct
import sys
from pathlib import Path


SOURCE_BLOB = "3abdf13e3488acc65e11e6a57f073f6673175c3a"


def byte_symbols():
    visible = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    alphabet = {byte: chr(byte) for byte in visible}
    remaining = (byte for byte in range(256) if byte not in alphabet)
    for index, byte in enumerate(remaining):
        alphabet[byte] = chr(256 + index)
    return alphabet


def compile_table(source, target):
    raw = Path(source).read_bytes()
    header = f"blob {len(raw)}\0".encode()
    if hashlib.sha1(header + raw).hexdigest() != SOURCE_BLOB:
        raise ValueError("Unexpected DeepSeek tokenizer version")
    data = json.loads(raw)
    model = data["model"]
    assert model["type"] == "BPE" and not model.get("ignore_merges")
    assert not model.get("dropout") and not model.get("continuing_subword_prefix")
    assert not model.get("end_of_word_suffix") and not model.get("byte_fallback")
    assert data["normalizer"]["normalizers"] == []
    stages = data["pre_tokenizer"]["pretokenizers"]
    assert stages[-1]["type"] == "ByteLevel" and not stages[-1]["use_regex"]
    assert not stages[-1]["add_prefix_space"]
    patterns = []
    for stage in stages[:-1]:
        assert stage["type"] == "Split" and stage["behavior"] == "Isolated" and not stage["invert"]
        # Oniguruma matches Unicode whitespace. Spell it out for the Java runtime.
        whitespace = r"[\x09-\x0D\x85\p{Z}]"
        nonspace = r"[^\x09-\x0D\x85\p{Z}]"
        pattern = stage["pattern"]["Regex"]
        patterns.append(pattern.replace(r"\s", whitespace).replace(r"\S", nonspace))
    literals = []
    for token in data["added_tokens"]:
        assert not token["single_word"] and not token["lstrip"] and not token["rstrip"]
        literals.append(token["content"])

    vocab = model["vocab"]
    alphabet = byte_symbols()
    pairs = []
    for rank, pair in enumerate(model["merges"]):
        left, right = pair.split(" ") if isinstance(pair, str) else pair
        key = vocab[left] << 32 | vocab[right]
        pairs.append((key, rank, vocab[left + right]))
    pairs.sort()
    assert len(pairs) == len({key for key, _, _ in pairs})

    with Path(target).open("wb") as out:
        def write(format_string, *values):
            out.write(struct.pack(format_string, *values))

        def strings(items):
            write(">I", len(items))
            for item in items:
                payload = item.encode()
                write(">H", len(payload))
                out.write(payload)

        write(">I", 0x44534231)
        strings(patterns)
        strings(literals)
        for byte in range(256):
            write(">I", vocab[alphabet[byte]])
        write(">I", len(pairs))
        for key, rank, merged in pairs:
            write(">QII", key, rank, merged)


if __name__ == "__main__":
    compile_table(*sys.argv[1:])
