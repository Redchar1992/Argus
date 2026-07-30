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

## Chapter workflow prompts

| Node | File | Output |
| --- | --- | --- |
| Plan | `app/prompts/chapter_plan_v1.txt` | Strict `ChapterPlan` JSON with 3–6 scenes |
| Writer | `app/prompts/chapter_write_v1.txt` | Ordinary prose streamed as deltas |
| Review | `app/prompts/chapter_review_v1.txt` | Six scored dimensions; app computes total |
| Revision | `app/prompts/chapter_revision_v1.txt` | Complete revised prose |
| Summary | `app/prompts/chapter_summary_v1.txt` | Strict `ChapterSummary` JSON |
| Memory | `app/prompts/chapter_memory_v1.txt` | Strict `MemoryUpdate` JSON |
| Selection rewrite | `app/prompts/rewrite_selection_v1.txt` | Version/hash-bound proposal |

For chapter planning, the cloud-model state contains exactly two assigned beats
under `currentOutlineNodes`; `outlineNodes` is an identical compatibility alias.
`chapterGoal` and the combined scenes must cite the concrete `event` and
`protagonistGoal` anchors from both beats. The application validates this after
schema parsing, so a generic plan or one that leaks a future chapter is rejected
rather than persisted.

The Writer and Revision prompts never request JSON around正文. Only planning,
review, summary, memory, and rewrite metadata use structured schemas. Before a
memory delta is emitted, application code removes attempts to overwrite locked
facts and appends a continuity warning for every rejected key.
