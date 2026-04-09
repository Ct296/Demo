from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

from .config import (
    ASPECT_GROUPS,
    ASPECT_PRIORITY,
    ASPECT_WEIGHTS,
    DATASET_PATH,
    NEGATIVE_THRESHOLD,
    POSITIVE_THRESHOLD,
    SENTIMENT_SIGN,
)

LABEL_PATTERN = re.compile(r'\{([^,]+),\s*(positive|negative|neutral)\}')
ENTRY_PATTERN = re.compile(r'#(\d+)\s*(.*?)\s*(?=(?:#\d+)|\Z)', re.DOTALL)


@dataclass
class ParsedEntry:
    entry_id: int
    text: str
    raw_labels: list[tuple[str, str]]
    positive_aspects: list[str]
    negative_aspects: list[str]
    neutral_aspects: list[str]
    overall_sentiment: str
    primary_aspect: str
    multilabel_targets: list[str]
    weighted_score: float


def _clean_html_entities(text: str) -> str:
    text = (
        text.replace('&nbsp', ' ')
        .replace('&nbsp;', ' ')
        .replace('&amp;', '&')
        .replace('&quot;', '"')
        .replace('&apos;', "'")
        .replace('&atildey', 'hay')
        .replace('&atildem', 'cam')
        .replace('&aacutey', 'ay')
        .replace('&nbsps', ' ')
        .replace('&nbspv', ' ')
    )
    text = re.sub(r'&[a-zA-Z0-9#]+;', ' ', text)
    return text


def map_aspect(raw_aspect: str) -> str:
    raw_aspect = raw_aspect.strip()
    if '#PRICES' in raw_aspect:
        return 'gia_ca'
    if '#CLEANLINESS' in raw_aspect:
        return 've_sinh'
    prefix = raw_aspect.split('#', 1)[0].strip()
    return ASPECT_GROUPS.get(prefix, 'khac')


def _primary_aspect(pos: list[str], neg: list[str], neu: list[str]) -> str:
    counts = Counter(pos + neg + neu)
    if not counts:
        return 'khac'
    max_count = max(counts.values())
    candidates = {aspect for aspect, count in counts.items() if count == max_count}
    for aspect in ASPECT_PRIORITY:
        if aspect in candidates:
            return aspect
    return sorted(candidates)[0]


def compute_weighted_score(labels: list[tuple[str, str]]) -> float:
    total_score = 0.0
    for raw_aspect, sentiment in labels:
        mapped_aspect = map_aspect(raw_aspect)
        weight = ASPECT_WEIGHTS.get(mapped_aspect, ASPECT_WEIGHTS['khac'])
        total_score += weight * SENTIMENT_SIGN[sentiment]
    return total_score


def weighted_overall_sentiment(labels: list[tuple[str, str]]) -> str:
    if not labels:
        return 'neutral'
    score = compute_weighted_score(labels)
    if score >= POSITIVE_THRESHOLD:
        return 'positive'
    if score <= NEGATIVE_THRESHOLD:
        return 'negative'
    return 'neutral'


def parse_dataset_text(raw_text: str) -> list[ParsedEntry]:
    raw_text = raw_text.lstrip('\ufeff')
    entries: list[ParsedEntry] = []
    for match in ENTRY_PATTERN.finditer(raw_text):
        entry_id = int(match.group(1))
        block = match.group(2).strip()
        label_matches = LABEL_PATTERN.findall(block)
        if not label_matches:
            continue

        text_only = LABEL_PATTERN.sub('', block)
        text_only = _clean_html_entities(text_only)
        text_only = re.sub(r'\s+', ' ', text_only).strip(' ,;.-')

        positive_aspects: list[str] = []
        negative_aspects: list[str] = []
        neutral_aspects: list[str] = []
        multilabel_targets: list[str] = []

        for raw_aspect, sentiment in label_matches:
            aspect = map_aspect(raw_aspect)
            multilabel_targets.append(f'{aspect}__{sentiment}')
            if sentiment == 'positive':
                positive_aspects.append(aspect)
            elif sentiment == 'negative':
                negative_aspects.append(aspect)
            else:
                neutral_aspects.append(aspect)

        positive_aspects = sorted(set(positive_aspects))
        negative_aspects = sorted(set(negative_aspects))
        neutral_aspects = sorted(set(neutral_aspects))
        weighted_score = compute_weighted_score(label_matches)

        entries.append(
            ParsedEntry(
                entry_id=entry_id,
                text=text_only,
                raw_labels=label_matches,
                positive_aspects=positive_aspects,
                negative_aspects=negative_aspects,
                neutral_aspects=neutral_aspects,
                overall_sentiment=weighted_overall_sentiment(label_matches),
                primary_aspect=_primary_aspect(positive_aspects, negative_aspects, neutral_aspects),
                multilabel_targets=sorted(set(multilabel_targets)),
                weighted_score=weighted_score,
            )
        )
    return entries


def load_dataset(dataset_path: Path | None = None) -> pd.DataFrame:
    dataset_path = dataset_path or DATASET_PATH
    raw_text = Path(dataset_path).read_text(encoding='utf-8')
    entries = parse_dataset_text(raw_text)
    rows = []
    for item in entries:
        rows.append(
            {
                'entry_id': item.entry_id,
                'text': item.text,
                'overall_sentiment': item.overall_sentiment,
                'primary_aspect': item.primary_aspect,
                'positive_aspects': item.positive_aspects,
                'negative_aspects': item.negative_aspects,
                'neutral_aspects': item.neutral_aspects,
                'multilabel_targets': item.multilabel_targets,
                'label_count': len(item.multilabel_targets),
                'weighted_score': item.weighted_score,
            }
        )
    return pd.DataFrame(rows)


def save_prepared_csv(output_path: str | Path) -> Path:
    output_path = Path(output_path)
    df = load_dataset()
    export_df = df.copy()
    for col in ['positive_aspects', 'negative_aspects', 'neutral_aspects', 'multilabel_targets']:
        export_df[col] = export_df[col].apply(lambda values: '|'.join(values))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    export_df.to_csv(output_path, index=False, encoding='utf-8-sig')
    return output_path


if __name__ == '__main__':
    df = load_dataset()
    print(f'So dong hop le: {len(df)}')
    print(df.head(5).to_string(index=False))
