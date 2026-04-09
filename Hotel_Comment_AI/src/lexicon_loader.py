from __future__ import annotations

from pathlib import Path


def load_key_value_txt(file_path: str | Path, separator: str = '\t') -> dict[str, str]:
    mapping: dict[str, str] = {}
    path = Path(file_path)
    for raw_line in path.read_text(encoding='utf-8').splitlines():
        line = raw_line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split(separator)
        if len(parts) < 2:
            continue
        key = parts[0].strip()
        value = separator.join(parts[1:]).strip()
        if key:
            mapping[key] = value
    return mapping


def load_float_mapping_txt(file_path: str | Path, separator: str = '\t') -> dict[str, float]:
    raw_mapping = load_key_value_txt(file_path, separator=separator)
    return {key: float(value) for key, value in raw_mapping.items()}


def load_list_txt(file_path: str | Path) -> list[str]:
    items: list[str] = []
    path = Path(file_path)
    for raw_line in path.read_text(encoding='utf-8').splitlines():
        line = raw_line.strip()
        if not line or line.startswith('#'):
            continue
        items.append(line)
    return items


def load_multi_value_mapping_txt(
    file_path: str | Path,
    separator: str = '\t',
    value_separator: str = '|',
) -> dict[str, list[str]]:
    raw_mapping = load_key_value_txt(file_path, separator=separator)
    parsed: dict[str, list[str]] = {}
    for key, value in raw_mapping.items():
        parsed[key] = [item.strip() for item in value.split(value_separator) if item.strip()]
    return parsed
