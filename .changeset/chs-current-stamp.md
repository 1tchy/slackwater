---
"@slackwater/engine": minor
---

Add `chsCurrentFit.stamp`, which identifies the CHS current gate recipe. It changes whenever the recipe or the fitting and prediction code would move a predicted slack, so a fitted model and a validation result can record which recipe they came from. SlackwaterKit and the Kotlin engine export the same value as `ChsCurrentFit.stamp` and `ChsCurrentFit.STAMP`.
