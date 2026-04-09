from __future__ import annotations

from pathlib import Path

from .lexicon_loader import (
    load_float_mapping_txt,
    load_key_value_txt,
    load_list_txt,
    load_multi_value_mapping_txt,
)

SRC_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SRC_DIR.parent
RESOURCE_DIR = PROJECT_ROOT / 'resources'
DATASET_PATH = PROJECT_ROOT / 'data' / 'raw' / 'dataset.txt'
PROCESSED_DATA_DIR = PROJECT_ROOT / 'data' / 'processed'
ARTIFACT_DIR = PROJECT_ROOT / 'artifacts'
MODEL_DIR = ARTIFACT_DIR / 'models'
MODEL_DIR.mkdir(parents=True, exist_ok=True)

RANDOM_STATE = 42
TEST_SIZE = 0.2

ABBREVIATION_MAP = load_key_value_txt(RESOURCE_DIR / 'lexicons' / 'abbreviation_map.txt')
ASPECT_GROUPS = load_key_value_txt(RESOURCE_DIR / 'mappings' / 'aspect_groups.txt')
ASPECT_PRIORITY = load_list_txt(RESOURCE_DIR / 'mappings' / 'aspect_priority.txt')
ASPECT_WEIGHTS = load_float_mapping_txt(RESOURCE_DIR / 'weights' / 'aspect_weights.txt')
POSITIVE_WORDS = set(load_list_txt(RESOURCE_DIR / 'lexicons' / 'positive_words.txt'))
NEGATIVE_WORDS = set(load_list_txt(RESOURCE_DIR / 'lexicons' / 'negative_words.txt'))
CONTRAST_WORDS = set(load_list_txt(RESOURCE_DIR / 'lexicons' / 'contrast_words.txt'))
NEGATION_WORDS = set(load_list_txt(RESOURCE_DIR / 'lexicons' / 'negation_words.txt'))
ASPECT_KEYWORDS = load_multi_value_mapping_txt(RESOURCE_DIR / 'lexicons' / 'aspect_keywords.txt')

SENTIMENT_SIGN = {
    'positive': 1.0,
    'neutral': 0.0,
    'negative': -1.0,
}

POSITIVE_THRESHOLD = 0.25
NEGATIVE_THRESHOLD = -0.25

DEFAULT_SENTIMENT_MODELS = ['nb', 'logreg', 'svm']
DEFAULT_ASPECT_MODELS = ['nb', 'logreg', 'svm']
