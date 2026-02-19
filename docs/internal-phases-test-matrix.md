# InternalPhases Test Matrix

Purpose: map invariants to concrete tests and current verification status.

Last updated: 2026-02-19

## Invariant Coverage

| Invariant ID | Invariant | Test(s) | Status | Notes |
|---|---|---|---|---|
| INV-01 | Non-empty initialize schedules parse request | `InternalPhasesMachineSpec` `initialize schedules parse request for non-empty text` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:19`) | pass | Pure reducer-level |
| INV-02 | Stale parse completion does not change machine state | `InternalPhasesMachineSpec` `stale parse completion is ignored` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:34`) | pass | Pure reducer-level |
| INV-03 | Stale parse completion does not overwrite latest orchestrator snapshot/error | `InternalPhasesPhaseSpec` `stale parse failure does not overwrite state or editorError` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:52`) | pass | Fixed in orchestrator parse completion path (`InternalPhases.scala`) |
| INV-04 | Current parse failure applies fallback graph + editor error | `InternalPhasesMachineSpec` `current parse failure emits editor error and fallback graph` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:68`), `InternalPhasesPhaseSpec` `latest parse failure sets editorError and fallback graph` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:112`) | pass | Verified in latest three-suite run |
| INV-05 | Graph edit serializes text and clears in-flight parse at machine level | `InternalPhasesMachineSpec` `graph edit updates text via serializer and clears in-flight parse` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:92`) | pass | Pure reducer-level |
| INV-06 | Graph edit uses injected serializer at orchestrator boundary | `InternalPhasesPhaseSpec` `graph updates use injected serializer for current format` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:163`) | pass | Verified in latest three-suite run |
| INV-07 | Format switch reparses current text using selected backend | `InternalPhasesPhaseSpec` `format switch reparses current text using selected backend` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:137`) | pass | Verified in latest three-suite run |
| INV-08 | Baseline end-to-end internal phases behavior remains stable | `InternalPhasesSpec` sanity and DOT roundtrip checks (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesSpec.scala:23`) | pass | Re-verified after fold-only UI dispatch, explicit ports, direct-write callsite migration, and compatibility write-path migration |
| INV-09 | `step` delegates UI machine input semantics to existing UI reducer | `InternalPhasesMachineSpec` `step delegates ui events to reduce` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:68`) | pass | Added in PH1-T1 |
| INV-10 | `step` ignores parse input when machine is idle | `InternalPhasesMachineSpec` `step ignores parse events while idle` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesMachineSpec.scala:90`) | pass | Added in PH1-T1 |
| INV-11 | Single source edit emits one parse request through folded orchestrator path | `InternalPhasesPhaseSpec` `single source edit schedules one parse request through fold` (`viewer/src/test/scala/org/jpablo/graphexplorer/viewer/state/InternalPhasesPhaseSpec.scala:86`) | pass | Added in PH2-T4 to guard against double-reduce regressions |

## Last Verification Commands

```bash
sbt test
npm run build
```

Result summary:

- `sbt test`: passed
- `npm run build`: passed
- Re-validation executed after PH2-T7 migration commit (`126ef9eb`)
