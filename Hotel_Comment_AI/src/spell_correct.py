from __future__ import annotations

import argparse
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path

import pandas as pd

from .config import ABBREVIATION_MAP, CONTRAST_WORDS


@dataclass
class NormalizedText:
    original: str
    normalized: str


class BKAIOptionalCorrector:
    def __init__(self, enabled: bool = False):
        self.enabled = enabled
        self._pipeline = None
        if enabled:
            self._try_load()

    def _try_load(self) -> None:
        try:
            from transformers import pipeline  # type: ignore
            self._pipeline = pipeline('text2text-generation', model='bmd1905/vietnamese-correction-v2')
        except Exception:
            self._pipeline = None
            self.enabled = False

    def correct(self, text: str) -> str:
        if not self.enabled or self._pipeline is None:
            return text
        try:
            result = self._pipeline(text, max_new_tokens=max(32, len(text.split()) * 2))[0]
            return result.get('generated_text', text).strip()
        except Exception:
            return text


class TextNormalizer:
    def __init__(self, use_bkai: bool = False):
        self.bkai = BKAIOptionalCorrector(enabled=use_bkai)
        self.token_replacements = dict(sorted(ABBREVIATION_MAP.items(), key=lambda item: len(item[0]), reverse=True))
        self.phrase_patterns = self._build_phrase_patterns()

    def _build_phrase_patterns(self) -> list[tuple[re.Pattern[str], str]]:
        phrase_map = {
            'khong  hai long': 'khong_hai_long',
            'rat tot': 'rat_tot',
            'tam duoc': 'tam_duoc',
            'tạm được': 'tam_duoc',
            'khong tot': 'khong_tot',
            'khong sach': 'khong_sach',
            'that vong': 'that_vong',
            'xuong cap': 'xuong_cap',
            'am moc': 'am_moc',
            'thoai mai': 'thoai_mai',
            'nhiet tinh': 'nhiet_tinh',
            'than thien': 'than_thien',
            'hai long': 'hai_long',
            'gia hop ly': 'gia_hop_ly',
            'gia ca hop ly': 'gia_hop_ly',
            'canh quan': 'canh_quan',
            'mui hoi': 'mui_hoi',
            'phong tam': 'phong_tam',
            'an sang': 'an_sang',
            'trung tam': 'trung_tam',
            'gan bien': 'gan_bien',
            'khong duoc': 'khong_duoc',
            'may lanh': 'may_lanh',
            'nuoc nong': 'nuoc_nong',
            'le tan': 'le_tan',
            'ho boi': 'ho_boi',
            'be boi': 'be_boi',
            'bai do xe': 'bai_do_xe',
            'pho co': 'pho_co',
        }
        for word in CONTRAST_WORDS:
            phrase_map[word.replace('_', ' ')] = word
        return [
            (re.compile(rf'\b{re.escape(source)}\b'), target)
            for source, target in sorted(phrase_map.items(), key=lambda item: len(item[0]), reverse=True)
        ]

    def normalize(self, text: str) -> str:
        text = text or ''
        text = unicodedata.normalize('NFC', text)
        text = text.lower().strip()
        text = self._replace_html_noise(text)
        text = self._remove_url_like_noise(text)
        text = self._normalize_repeated_chars(text)
        text = self._separate_punctuation(text)
        text = self._replace_abbreviations(text)
        text = self._normalize_whitespace(text)
        text = self._normalize_phrases(text)
        text = self.bkai.correct(text)
        text = self._normalize_whitespace(text)
        return text

    def _replace_html_noise(self, text: str) -> str:
        text = text.replace('&nbsp', ' ')
        return re.sub(r'&[a-zA-Z0-9#]+;', ' ', text)

    def _remove_url_like_noise(self, text: str) -> str:
        return re.sub(r'https?://\S+|www\.\S+', ' ', text)

    def _normalize_repeated_chars(self, text: str) -> str:
        return re.sub(r'(\w)\1{2,}', r'\1\1', text)

    def _separate_punctuation(self, text: str) -> str:
        return re.sub(r'([,;:.!?()\-/])', r' \1 ', text)

    def _replace_abbreviations(self, text: str) -> str:
        tokens = text.split()
        replaced = [self.token_replacements.get(token, token) for token in tokens]
        return ' '.join(replaced)

    def _normalize_phrases(self, text: str) -> str:
        updated = text
        for pattern, replacement in self.phrase_patterns:
            updated = pattern.sub(replacement, updated)
        return updated

    def _normalize_whitespace(self, text: str) -> str:
        return re.sub(r'\s+', ' ', text).strip()


def normalize_dataframe(df: pd.DataFrame, text_col: str = 'text', use_bkai: bool = False) -> pd.DataFrame:
    normalizer = TextNormalizer(use_bkai=use_bkai)
    out = df.copy()
    out['text_original'] = out[text_col]
    out[text_col] = out[text_col].fillna('').apply(normalizer.normalize)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description='Chuan hoa / sua chinh ta comment.')
    parser.add_argument('--input', required=True, help='CSV dau vao')
    parser.add_argument('--output', required=True, help='CSV dau ra')
    parser.add_argument('--text-col', default='text', help='Ten cot chua comment')
    parser.add_argument('--use-bkai', action='store_true', help='Thu goi BKAI/transformers neu co san')
    args = parser.parse_args()

    df = pd.read_csv(args.input)
    normalized = normalize_dataframe(df, text_col=args.text_col, use_bkai=args.use_bkai)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    normalized.to_csv(args.output, index=False, encoding='utf-8-sig')
    print(f'Da luu file chuan hoa: {args.output}')


if __name__ == '__main__':
    main()
