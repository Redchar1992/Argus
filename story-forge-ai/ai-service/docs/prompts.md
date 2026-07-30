# Topic Agent prompt

The production prompt is version-controlled in `app/agents/prompts.py`.

## Responsibility split

- **Topic Agent** proposes exactly 10 short-drama concepts. It returns only
  `title`, `hook`, `summary`, and `tags` so the provider cannot invent a score.
- **Score Agent** evaluates each candidate after provider validation. It scores
  conflict, reversal, emotional value, and short-drama fit independently from
  0–100, then uses their equally weighted mean as the visible score.

## Output contract sent to the LLM

The model must return a plain JSON object (no Markdown fences or prose):

```json
{
  "topics": [
    {
      "title": "离婚当天，我继承百亿集团",
      "hook": "签字现场遭羞辱，继承人身份随后公开。",
      "summary": "故事方案……",
      "tags": ["都市情感", "逆袭", "身份反转"]
    }
  ]
}
```

`topics` must contain exactly 10 entries. Extra top-level or topic fields are
rejected. Invalid JSON, HTTP failures, and schema violations all follow the same
explicit fallback policy described in the README.

## Stateful story workflow prompts

Second-week prompts are immutable versioned text files:

| Node | File | Responsibility |
| --- | --- | --- |
| Character | `app/prompts/character_v1.txt` | Create 3–6 non-duplicated dramatic functions |
| Outline | `app/prompts/outline_v1.txt` | Create exactly 20 causal beats and at least four twists |
| Score | `app/prompts/score_v1.txt` | Return five 0–20 dimensions without doing the sum |
| Revise | `app/prompts/revise_v1.txt` | Return a complete new outline without changing core identities |

The application attaches `character_v1`, `outline_v1`, `score_v1`, or
`revise_v1` to every artifact and model-call record. Mechanical constraints
(cast size/roles, node count/order, twist count, opening conflict, ending
release, score bounds, and total arithmetic) are enforced in Python even when
the model prompt contains the same requirement.

Creative and review prompts deliberately use separate model configurations.
This keeps scoring conservative and reproducible while allowing characters and
outline revisions some creative variance.
