# Confidence Threshold Experiment

**Date:** 2026-08-25
**Requested by:** Team tech adviser
**Run by:** Mark Joseph Garcia

## Objective

Determine how `BOX_CONFIDENCE_THRESHOLD` (the per-box confidence cutoff in the YOLO
post-processing step) affects detection behavior, to justify the value used in the app
rather than just keeping Ultralytics' generic default (`0.25`) unexamined.

## What This Threshold Actually Controls

Two different things in the pipeline get called "confidence," and this experiment is
about only one of them:

- **Overall reported confidence** (the "68.4%" shown to the user) — the model's raw class
  score, taken as the max across all 8400 candidate boxes for the winning class. This is
  computed *before* any thresholding and is unaffected by `BOX_CONFIDENCE_THRESHOLD`.
- **`BOX_CONFIDENCE_THRESHOLD`** (this experiment) — controls only which of the 8400
  raw candidate boxes are kept for *drawing bounding boxes* on the result image. It has
  no effect on the classification result or the reported confidence percentage.

Source: `app/src/main/java/com/dermalens/app/ml/YoloDetector.kt`, `bestClass()`.

## Methodology

`BOX_CONFIDENCE_THRESHOLD` only affects post-processing, not the model inference itself —
the same 8400 raw per-box scores come out of the model regardless of the threshold. So
rather than re-running inference once per threshold value (six separate on-device runs,
introducing run-to-run noise from JIT warmup, thermal throttling, etc.), this experiment
runs inference **once** and sweeps six threshold values against that identical raw output.
Every threshold in the table below is therefore evaluated against exactly the same 8400
raw box scores — a controlled comparison, not six independent trials.

For each threshold value, the same candidate-filter → sort-by-score → NMS pipeline used in
production (`bestClass()`) was replicated in a temporary logging block, reporting:
- **candidateBoxes** — how many of the 8400 raw boxes score at or above that threshold
- **keptAfterNMS** — how many boxes remain after non-max suppression (IoU > 0.45 collapsed)
- **topScores** — the 5 highest raw scores among the candidates at that threshold

## Test Setup

| | |
|---|---|
| Device | Android emulator (Pixel-class AVD, `DermaLensTest`) |
| Model | `best.tflite` — single-class test model (Melasma only), input `[1,3,640,640]` (channels-first), output `[1,5,8400]` |
| Test image | A clear, well-lit stock photo showing visible bilateral cheek melasma (natural, non-clinical photo — chosen specifically because it's a different image than the clinical reference photo used in earlier end-to-end verification, to sanity-check the pipeline isn't overfit to one specific test image) |
| App flow | Real production path: Login → Scan → gallery picker → real image → real `runYoloInference()` call (not a synthetic/unit-test harness) |
| NMS IoU threshold | 0.45 (unchanged, held constant across all six runs) |

## Raw Results

| Threshold | Candidate boxes (pre-NMS) | Boxes kept (post-NMS) | Top 5 raw scores |
|---|---|---|---|
| 0.10 | 34 | 3 | 0.684, 0.672, 0.670, 0.643, 0.628 |
| 0.25 *(production default)* | 17 | 3 | 0.684, 0.672, 0.670, 0.643, 0.628 |
| 0.40 | 8 | 1 | 0.684, 0.672, 0.670, 0.643, 0.628 |
| 0.50 | 8 | 1 | 0.684, 0.672, 0.670, 0.643, 0.628 |
| 0.60 | 6 | 1 | 0.684, 0.672, 0.670, 0.643, 0.628 |
| 0.75 | 0 | 0 | *(none — highest raw score is 0.684)* |

Overall reported confidence for this image: **68.4%** ("Mild" severity), classified
correctly as Melasma — unaffected by the threshold sweep, as expected (see above).

At the production threshold (0.25), the app correctly drew **3 separate bounding boxes**
on-screen: forehead, and both left/right cheek regions — matching what's visible in the
photo (bilateral + forehead involvement).

## Interpretation

1. **0.10 and 0.25 produce an identical final result** (3 boxes kept after NMS), despite
   0.10 admitting exactly 2x more raw candidates into the NMS step (34 vs. 17). NMS is
   already absorbing the extra low-confidence noise at 0.10 — so lowering the threshold
   below 0.25 buys nothing for this image, it just makes NMS do more work for the same
   outcome.
2. **Between 0.25 and 0.40, a real detection is lost.** Boxes kept drops from 3 to 1 —
   this is the threshold crossing where one of the three genuine regions (raw score
   somewhere between 0.25 and 0.40) gets filtered out before NMS ever sees it. This is
   the real risk zone for false *negatives*: raise the threshold too far and you start
   discarding regions a human would actually flag as affected skin.
3. **0.40 through 0.60 stay flat at 1 kept box** — the remaining detection's score
   (0.684) is comfortably above all these thresholds, so nothing changes until threshold
   exceeds the highest score.
4. **0.75 exceeds every candidate's score** (max is 0.684) — the model reports nothing
   detected at all, even though the classification itself is clearly correct (68.4%
   confidence) and the photo obviously shows melasma. This is the failure mode of setting
   the threshold too high: a technically-correct classification with zero visual evidence
   shown to the user.

## Recommendation

**Keep `BOX_CONFIDENCE_THRESHOLD = 0.25`.** It sits safely below the 0.25–0.40 cliff where
a real region starts getting dropped, while not being so low that it does unnecessary
extra filtering work for an identical outcome (0.10 and 0.25 tie on this image). Ultralytics'
generic default happens to land in a reasonable spot for this model, but that's now an
empirical finding for this specific model rather than an unexamined assumption.

## Limitations

- **Single test image, single-class model.** This is a pilot/sanity experiment, not a full
  precision/recall sweep across a labeled test set. The real methodology-worthy version of
  this experiment (with proper precision/recall curves per threshold) needs the merged
  multi-class model and a labeled test set, per `HANDOFF.md`.
- **One image can't establish a false-positive rate.** Everything reported here is about
  false *negatives* (losing real detections) on an image known to contain melasma. A
  meaningful precision measurement needs images *without* the condition too, to see how
  many boxes a low threshold spuriously draws on clean skin.
- This experiment used a natural, non-clinical stock photo, not the clinical reference
  photo used in the original end-to-end verification (see `README.md`) — chosen
  deliberately to confirm the pipeline generalizes beyond one specific test image, not
  to replace that earlier verification.

## Implementation Note

The temporary threshold-sweep logging block added to `YoloDetector.kt`'s `bestClass()` for
this experiment was reverted after this data was captured — it added per-inference logging
overhead not appropriate for production. The `BOX_CONFIDENCE_THRESHOLD` and `NMS_IOU_THRESHOLD`
constants and their existing single-pass logic are unchanged from before this experiment.
