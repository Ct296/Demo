from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from scipy.sparse import hstack

from .config import (
    ASPECT_KEYWORDS,
    ASPECT_WEIGHTS,
    CONTRAST_WORDS,
    MODEL_DIR,
    NEGATION_WORDS,
    NEGATIVE_THRESHOLD,
    NEGATIVE_WORDS,
    POSITIVE_THRESHOLD,
    POSITIVE_WORDS,
)
from .spell_correct import TextNormalizer


DEFAULT_POSITIVE_FALLBACK = 1.0
DEFAULT_NEGATIVE_FALLBACK = -1.0
DEFAULT_NEUTRAL_FALLBACK = 0.0

# Mac dinh: neu khong co trong tui trong so thi dung +/-1.
# Co mot so tu duoc gan do manh de giai quyet cac truong hop nhu "hoi" am hon "than_thien".
WORD_SENTIMENT_WEIGHTS: dict[str, float] = {
    **{word: 1.0 for word in POSITIVE_WORDS},
    **{word: -1.0 for word in NEGATIVE_WORDS},
    'tuyet': 1.4,
    'ly_tuong': 1.3,
    'chuyen_nghiep': 1.25,
    'chu_dao': 1.15,
    'nhiet_tinh': 1.15,
    'than_thien': 1.0,
    'hai_long': 1.0,
    'thoai_mai': 0.9,
    'rong': 0.8,
    'sach': 0.8,
    'te': -1.4,
    'that_vong': -1.5,
    'khong_hai_long': -1.35,
    'kem': -1.2,
    'ban': -1.25,
    'hoi': -2.0,
    'mui_hoi': -2.0,
    'am_moc': -1.8,
    'chat': -0.8,
    'xuong_cap': -1.3,
    'on_ao': -1.0,
    'cham': -0.9,
    'bat_tien': -0.8,
    'cu': -0.7,
    'be': -0.6,
    'lau': -0.6,
    'hoi_kho': -0.6,
}


def _safe_predict_proba(model: Any, matrix):
    if hasattr(model, 'predict_proba'):
        return model.predict_proba(matrix)
    if hasattr(model, 'decision_function'):
        decision = model.decision_function(matrix)
        if decision.ndim == 1:
            decision = decision.reshape(-1, 1)
        exp_scores = np.exp(decision - np.max(decision, axis=1, keepdims=True))
        denom = exp_scores.sum(axis=1, keepdims=True)
        denom[denom == 0] = 1.0
        return exp_scores / denom
    return None


class HotelCommentPredictor:
    def __init__(self, model_dir: str | Path = MODEL_DIR, use_bkai: bool = False):
        self.model_dir = Path(model_dir)
        self.normalizer = TextNormalizer(use_bkai=use_bkai)
        self.sentiment_model = joblib.load(self.model_dir / 'sentiment_model.joblib')
        self.sentiment_word_vectorizer = joblib.load(self.model_dir / 'sentiment_word_vectorizer.joblib')
        self.sentiment_char_vectorizer = joblib.load(self.model_dir / 'sentiment_char_vectorizer.joblib')
        self.aspect_model = joblib.load(self.model_dir / 'aspect_model.joblib')
        self.aspect_word_vectorizer = joblib.load(self.model_dir / 'aspect_word_vectorizer.joblib')
        self.aspect_char_vectorizer = joblib.load(self.model_dir / 'aspect_char_vectorizer.joblib')
        self.aspect_mlb = joblib.load(self.model_dir / 'aspect_mlb.joblib')
        self.word_weights = WORD_SENTIMENT_WEIGHTS

    def _vectorize(self, text: str, word_vectorizer, char_vectorizer):
        return hstack([word_vectorizer.transform([text]), char_vectorizer.transform([text])]).tocsr()

    def _split_clauses(self, normalized_text: str) -> list[str]:
        text = normalized_text
        for marker in CONTRAST_WORDS:
            text = re.sub(rf'\b{re.escape(marker)}\b', ' <CUT> ', text)
        clauses = [chunk.strip(' ,;:.!-') for chunk in text.split('<CUT>') if chunk.strip(' ,;:.!-')]
        return clauses or [normalized_text]

    def _clause_weight(self, idx: int, clause_count: int) -> float:
        clause_weight = 1.0 if idx == 0 else 1.35
        if idx == clause_count - 1 and clause_count > 1:
            clause_weight += 0.15
        return clause_weight

    def _count_sentiment_words(self, clause: str) -> tuple[float, float, list[str]]:
        tokens = clause.split()
        positive_score = 0.0
        negative_score = 0.0
        evidence: list[str] = []
        token_text = f' {clause.replace("_", " ")} '
        matched_phrases: set[str] = set()

        for phrase in sorted(POSITIVE_WORDS | NEGATIVE_WORDS, key=len, reverse=True):
            phrase_with_spaces = phrase.replace('_', ' ')
            if f' {phrase_with_spaces} ' in token_text:
                matched_phrases.add(phrase)

        for idx, token in enumerate(tokens):
            window_prev = tokens[max(0, idx - 2):idx]
            is_negated = any(item in NEGATION_WORDS for item in window_prev)
            if token in POSITIVE_WORDS:
                if is_negated:
                    negative_score += 1.0
                    evidence.append(f'negated:{token}')
                else:
                    positive_score += 1.0
                    evidence.append(f'positive:{token}')
            elif token in NEGATIVE_WORDS:
                if is_negated:
                    positive_score += 0.6
                    evidence.append(f'negated_negative:{token}')
                else:
                    negative_score += 1.0
                    evidence.append(f'negative:{token}')

        for phrase in matched_phrases:
            if '_' in phrase:
                if phrase in POSITIVE_WORDS:
                    positive_score += 1.2
                    evidence.append(f'positive_phrase:{phrase}')
                elif phrase in NEGATIVE_WORDS:
                    negative_score += 1.2
                    evidence.append(f'negative_phrase:{phrase}')

        return positive_score, negative_score, evidence

    def _collect_weighted_hits(self, clause: str) -> list[dict[str, Any]]:
        tokens = clause.split()
        token_text = f' {clause.replace("_", " ")} '
        hits: list[dict[str, Any]] = []
        seen_phrases: set[str] = set()

        for phrase in sorted(self.word_weights, key=len, reverse=True):
            if '_' not in phrase:
                continue
            phrase_spaces = phrase.replace('_', ' ')
            if f' {phrase_spaces} ' not in token_text:
                continue
            approx_index = max(token_text.find(f' {phrase_spaces} '), 0)
            is_negated = any(
                f' {neg} {phrase_spaces} ' in token_text
                for neg in NEGATION_WORDS
            )
            raw_weight = self.word_weights[phrase]
            signed_weight = -raw_weight if is_negated else raw_weight
            hits.append({
                'term': phrase,
                'raw_weight': round(float(raw_weight), 3),
                'signed_weight': round(float(signed_weight), 3),
                'is_negated': is_negated,
                'kind': 'phrase',
                'approx_index': approx_index,
            })
            seen_phrases.add(phrase)

        for idx, token in enumerate(tokens):
            if token not in self.word_weights:
                continue
            if any(token in phrase.split('_') for phrase in seen_phrases):
                continue
            window_prev = tokens[max(0, idx - 2):idx]
            is_negated = any(item in NEGATION_WORDS for item in window_prev)
            raw_weight = self.word_weights[token]
            signed_weight = -raw_weight if is_negated else raw_weight
            hits.append({
                'term': token,
                'raw_weight': round(float(raw_weight), 3),
                'signed_weight': round(float(signed_weight), 3),
                'is_negated': is_negated,
                'kind': 'token',
                'approx_index': idx,
            })

        return sorted(hits, key=lambda item: (item['approx_index'], item['term']))

    def _clauses_with_aspect(self, clauses: list[str], aspect: str) -> list[tuple[int, str, float]]:
        matched: list[tuple[int, str, float]] = []
        keywords = ASPECT_KEYWORDS.get(aspect, [])
        if not keywords:
            return matched
        for idx, clause in enumerate(clauses):
            clause_plain = clause.replace('_', ' ')
            if any(keyword.replace('_', ' ') in clause_plain for keyword in keywords):
                matched.append((idx, clause, self._clause_weight(idx, len(clauses))))
        return matched

    def _label_clause_score(
            self,
            aspect: str,
            sentiment: str,
            clauses: list[str],
    ) -> tuple[float, list[dict[str, Any]]]:
        relevant_clauses = self._clauses_with_aspect(clauses, aspect)
        matched_evidence: list[dict[str, Any]] = []
        total_score = 0.0

        for idx, clause, clause_weight in relevant_clauses:
            hits = self._collect_weighted_hits(clause)
            if sentiment == 'positive':
                sentiment_hits = [hit for hit in hits if hit['signed_weight'] > 0]
            elif sentiment == 'negative':
                sentiment_hits = [hit for hit in hits if hit['signed_weight'] < 0]
            else:
                sentiment_hits = []

            if not sentiment_hits:
                continue

            clause_score = sum(hit['signed_weight'] for hit in sentiment_hits) * clause_weight
            total_score += clause_score
            matched_evidence.append({
                'clause': clause,
                'clause_weight': round(clause_weight, 3),
                'matched_terms': [hit['term'] for hit in sentiment_hits],
                'matched_score': round(float(clause_score), 3),
            })

        if total_score == 0.0:
            if sentiment == 'positive':
                return DEFAULT_POSITIVE_FALLBACK, matched_evidence
            if sentiment == 'negative':
                return DEFAULT_NEGATIVE_FALLBACK, matched_evidence
            return DEFAULT_NEUTRAL_FALLBACK, matched_evidence

        return float(total_score), matched_evidence

    def _lexicon_aspect_labels(self, normalized_text: str) -> tuple[list[str], list[dict[str, Any]], float]:
        clauses = self._split_clauses(normalized_text)
        labels: set[str] = set()
        details: list[dict[str, Any]] = []
        total_score = 0.0
        for idx, clause in enumerate(clauses):
            weighted_hits = self._collect_weighted_hits(clause)
            pos_score = sum(hit['signed_weight'] for hit in weighted_hits if hit['signed_weight'] > 0)
            neg_score = -sum(hit['signed_weight'] for hit in weighted_hits if hit['signed_weight'] < 0)
            evidence = [
                f"{'negated:' if hit['is_negated'] else ''}{hit['term']}={hit['signed_weight']}"
                for hit in weighted_hits
            ]
            clause_weight = self._clause_weight(idx, len(clauses))
            clause_plain = clause.replace('_', ' ')
            for aspect, keywords in ASPECT_KEYWORDS.items():
                if any(keyword.replace('_', ' ') in clause_plain for keyword in keywords):
                    clause_sentiment = None
                    if neg_score > pos_score and neg_score > 0:
                        clause_sentiment = 'negative'
                    elif pos_score > neg_score and pos_score > 0:
                        clause_sentiment = 'positive'
                    if clause_sentiment:
                        label = f'{aspect}__{clause_sentiment}'
                        labels.add(label)
                        signed = pos_score if clause_sentiment == 'positive' else -neg_score
                        if signed == 0.0:
                            signed = DEFAULT_POSITIVE_FALLBACK if clause_sentiment == 'positive' else DEFAULT_NEGATIVE_FALLBACK
                        total_score += signed * clause_weight
                        details.append({
                            'clause': clause,
                            'aspect': aspect,
                            'sentiment': clause_sentiment,
                            'clause_weight': round(clause_weight, 3),
                            'evidence': evidence,
                        })
        return sorted(labels), details, total_score

    def _aggregate_aspect_score(self, labels: list[str], normalized_text: str) -> tuple[float, list[dict[str, Any]]]:
        score = 0.0
        contributions: list[dict[str, Any]] = []
        clauses = self._split_clauses(normalized_text)
        for label in labels:
            if '__' not in label:
                continue
            aspect, sentiment = label.split('__', 1)
            signed_weight, evidence = self._label_clause_score(aspect, sentiment, clauses)
            base_weight = abs(float(signed_weight))
            score += signed_weight
            contributions.append({
                'label': label,
                'weight': round(base_weight, 3),
                'signed_weight': round(float(signed_weight), 3),
                'source': 'ml_model',
                'evidence': evidence,
            })
        return score, contributions

    def predict(self, raw_text: str) -> dict[str, Any]:
        normalized_text = self.normalizer.normalize(raw_text)
        sentiment_matrix = self._vectorize(normalized_text, self.sentiment_word_vectorizer, self.sentiment_char_vectorizer)
        aspect_matrix = self._vectorize(normalized_text, self.aspect_word_vectorizer, self.aspect_char_vectorizer)

        sentiment_label = self.sentiment_model.predict(sentiment_matrix)[0]
        sentiment_proba = _safe_predict_proba(self.sentiment_model, sentiment_matrix)
        aspect_binary = self.aspect_model.predict(aspect_matrix)
        ml_aspect_labels = list(self.aspect_mlb.inverse_transform(aspect_binary)[0])
        ml_aspect_score, ml_contributions = self._aggregate_aspect_score(sorted(ml_aspect_labels), normalized_text)
        lexicon_labels, lexicon_details, lexicon_aspect_score = self._lexicon_aspect_labels(normalized_text)

        merged_labels = sorted(set(ml_aspect_labels) | set(lexicon_labels))
        merged_score, merged_contributions = self._aggregate_aspect_score(merged_labels, normalized_text)
        merged_contributions.extend({
                                        'label': item['aspect'] + '__' + item['sentiment'],
                                        'weight': round(abs(sum(
                                            hit['signed_weight'] for hit in self._collect_weighted_hits(item['clause'])
                                            if (hit['signed_weight'] > 0 and item['sentiment'] == 'positive')
                                            or (hit['signed_weight'] < 0 and item['sentiment'] == 'negative')
                                        )) or abs(DEFAULT_POSITIVE_FALLBACK if item['sentiment'] == 'positive' else DEFAULT_NEGATIVE_FALLBACK), 3),
                                        'signed_weight': round(float(
                                            (sum(
                                                hit['signed_weight'] for hit in self._collect_weighted_hits(item['clause'])
                                                if (hit['signed_weight'] > 0 and item['sentiment'] == 'positive')
                                                or (hit['signed_weight'] < 0 and item['sentiment'] == 'negative')
                                            ) or (DEFAULT_POSITIVE_FALLBACK if item['sentiment'] == 'positive' else DEFAULT_NEGATIVE_FALLBACK))
                                            * item['clause_weight']
                                        ), 3),
                                        'source': 'lexicon_rule',
                                        'clause': item['clause'],
                                        'clause_weight': item['clause_weight'],
                                        'evidence': item['evidence'],
                                    } for item in lexicon_details)

        direct_sentiment_score = 0.0
        probability_map: dict[str, float] = {}
        if sentiment_proba is not None and hasattr(self.sentiment_model, 'classes_'):
            classes = list(self.sentiment_model.classes_)
            row = sentiment_proba[0]
            probability_map = {cls: round(float(prob), 4) for cls, prob in zip(classes, row)}
            direct_sentiment_score = probability_map.get('positive', 0.0) - probability_map.get('negative', 0.0)

        clauses = self._split_clauses(normalized_text)
        clause_analysis = []
        lexicon_clause_score = 0.0
        for idx, clause in enumerate(clauses):
            weighted_hits = self._collect_weighted_hits(clause)
            pos_score = sum(hit['signed_weight'] for hit in weighted_hits if hit['signed_weight'] > 0)
            neg_score = -sum(hit['signed_weight'] for hit in weighted_hits if hit['signed_weight'] < 0)
            clause_weight = self._clause_weight(idx, len(clauses))
            clause_value = (pos_score - neg_score) * clause_weight
            lexicon_clause_score += clause_value
            clause_analysis.append({
                'clause': clause,
                'positive_hits': round(float(pos_score), 3),
                'negative_hits': round(float(neg_score), 3),
                'clause_weight': round(clause_weight, 3),
                'clause_score': round(float(clause_value), 3),
                'evidence': [
                    {
                        'term': hit['term'],
                        'signed_weight': hit['signed_weight'],
                        'is_negated': hit['is_negated'],
                    }
                    for hit in weighted_hits
                ],
            })

        # Uu tien output aspect-sentiment tu model + bo aggregate. Direct sentiment chi la tin hieu phu.
        final_score = 0.15 * float(direct_sentiment_score) + 0.70 * float(merged_score) + 0.15 * float(lexicon_clause_score)
        if final_score >= POSITIVE_THRESHOLD:
            final_label = 'positive'
        elif final_score <= NEGATIVE_THRESHOLD:
            final_label = 'negative'
        else:
            if any(label.endswith('__positive') for label in merged_labels) and any(label.endswith('__negative') for label in merged_labels):
                final_label = 'neutral'
            else:
                final_label = 'neutral'

        return {
            'raw_text': raw_text,
            'normalized_text': normalized_text,
            'direct_sentiment_label': sentiment_label,
            'direct_sentiment_score': round(float(direct_sentiment_score), 4),
            'direct_sentiment_probabilities': probability_map,
            'ml_aspect_labels': sorted(ml_aspect_labels),
            'lexicon_aspect_labels': sorted(lexicon_labels),
            'aspect_labels': merged_labels,
            'ml_aspect_score': round(float(ml_aspect_score), 4),
            'lexicon_aspect_score': round(float(lexicon_aspect_score), 4),
            'aspect_score': round(float(merged_score), 4),
            'clause_analysis': clause_analysis,
            'aspect_contributions': ml_contributions + merged_contributions,
            'lexicon_clause_score': round(float(lexicon_clause_score), 4),
            'final_score': round(float(final_score), 4),
            'final_label': final_label,
        }
