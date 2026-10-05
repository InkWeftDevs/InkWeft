"""Explicit, source-checked regression scope for the six-batch development branch."""
import argparse
import json
from pathlib import Path
from android_plan import inventory, select_methods
from run_six_batch_fixture import FOCUSED_PHASES, PHASE_METHODS, RUNNER
from room_evidence import room_evidence
import re

APP = ["ViewportRasterTest", "PdfViewportRasterTest", "ZoomRasterTest", "RenderResourcesTest",
       "ImageRenderingTest", "CardPresentationUiTest", "StudyCapacityUiTest", "StudyCapacityPanelUiTest", "RecallMaskUiTest", "StarNoteInteractionsUiTest", "StudyOrganizationUiTest", "CardTransformUiTest", "StudyTransformNavigationUiTest", "StudyRelationPaintTest", "StudyRelationsUiTest", "KnowledgeRelationEditorUiTest", "CardReuseStoreTest", "CardReuseDialogUiTest", "CardTrashRestorationTest", "CardTrashUiTest", "DocumentShortcutPreferencesUiTest", "RecallOriginalSourceUiTest",
       "BoundAnnotationFlowUiTest", "LayerAuthoringNativeTest", "LayerSnapshotPreviewTest", "VisiblePageExportTest",
       "RecallDurableRecoveryTest", "RecallMaskNativeTest", "RecallAnswerPadUiTest", "RecallQuestionEditorUiTest", "StudyNavigationStateTest", "CardSourceNavigationUiTest", "LibrarySourceNavigationUiTest", "MapPortalUiTest", "LearningWorkspacePolishUiTest", "MapInteractionUiTest", "SelectionStudyUiTest", "CaptureJobRegistrationTest", "AuthoringReadinessTest", "ContinuousWritingUiTest", "ContinuousPageGestureUiTest", "StudyMapReadinessTest", "LearningWorkbenchTest"]
ROOM = ["PageObjectRepositoryTest", "StudyCapacityRepositoryTest", "CardTrashRepositoryTest", "StudyOrganizationRepositoryTest", "CardPresentationRepositoryTest", "LibraryBackupRepositoryTest",
        "LibraryBackupGuardTest", "LibraryContentRepositoryTest", "StudyAndSelectionRepositoryTest", "CardTransformRepositoryTest", "CardReuseRepositoryTest", "KnowledgeRepositoryTest", "KnowledgeTextRepositoryTest",
        "PageAuthoringRepositoryTest", "AuthoringPendingStoreTest", "BranchReviewRepositoryTest", "BranchReviewRoundRepositoryTest", "RecallStudyRepositoryTest"]
CONTINUOUS_METHODS = {
    "org.inkweft.app.ContinuousWritingUiTest": [
        "cancellingLongGestureDoesNotResurrectItsCheckpointOnReopen",
        "continuousSeamHasNoGapAndOneGesturePersistsOnBothSides"],
    "org.inkweft.app.ContinuousPageGestureUiTest": [
        "longFingerCancellationBlocksAppendUntilItsJournalSettles",
        "failedLongFingerCancellationRetriesCancelWithoutRevivingInk"],
}
METHODS = {
    "org.inkweft.app.StarNoteInteractionsUiTest": ["excerptKeepsPageImageAddsCommentAndDeletesWithoutDeletingSource",
        "inlineCommentAndEightHandleRecropCancelSaveAndReopenKeepOriginalInk"],
    **CONTINUOUS_METHODS,
    "org.inkweft.app.LearningWorkbenchTest": ["learningOpensMapWithoutOpeningNoteAndKeepsHostConfiguration"],
}

# Three full-run failures, each observed in at most three independently reset rounds.
# No larger timeout, retries, or substitute fixture; B6 is not rerun here.
FOCUSED_METHODS = {
    "org.inkweft.app.RecallOriginalSourceUiTest": [
        "recycledCurrentSourceKeepsFixedSnapshotAndSameAttempt",
        "foreignSourceIsRejectedWithoutRenderingAnotherNotebook"],
    "org.inkweft.app.SelectionStudyUiTest": [
        "outlineAndMapReuseSingleEditableCard"],
}

CORE = ["PageObjectTest", "CardTrashCommandTest", "ContentTransferTest", "LibraryArchiveTest", "CardPresentationTest",
        "KnowledgeTest", "StudyTextTest", "MapAddendumTest", "StudyOrganizationTest", "StudyOutlineTest", "CardTransformTest", "KnowledgeTextLinksTest",
        "PageAuthoringTest", "SearchRequestVersionTest", "RecallStudyTest", "SuperMemo2PortTest", "BranchReviewTest", "BranchReviewRoundTest"]



def lint_requirement(mode, shard):
    """One full-source lint owner off the Room/B6 critical path; no ignored failures."""
    if mode not in ("focused", "full") or shard not in ([0] if mode == "focused" else [0, 1, 2]):
        raise ValueError("Invalid mode/shard for lint")
    return ("RUN_REQUIRED" if shard == 1 else "NOT_ASSIGNED") if mode == "full" else "NOT_RUN_FOCUSED"


def build_plan(mode="full"):
    if mode not in ("focused", "full"):
        raise ValueError("Unknown six-batch mode: " + mode)
    methods = FOCUSED_METHODS if mode == "focused" else METHODS
    app=list(methods) if mode == "focused" else ["org.inkweft.app."+name for name in APP]
    room=["org.inkweft.data."+name for name in ROOM]
    root=Path(__file__).resolve().parents[1]
    assert all((root/"android/core-domain/src/test/kotlin/org/inkweft/core"/(name+".kt")).exists() for name in CORE)
    fixture_classes={"org.inkweft.app."+name for name,_ in PHASE_METHODS.values()}
    # Stateful fixture methods run in order, outside per-case clearing.
    for name,method in PHASE_METHODS.values():
        source=next((root/"android/app/src/androidTest").rglob(name+".kt"))
        select_methods(re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)",source.read_text(encoding="utf-8")),[method])
    for name,requested in methods.items():
        source=next((root/"android/app/src/androidTest").rglob(name.rsplit(".",1)[1]+".kt"))
        select_methods(re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)",source.read_text(encoding="utf-8")),requested)
    assert (root/RUNNER).is_file()
    assert not set(app)&fixture_classes
    assert set(app)<=inventory("app").keys() and set(room)<=inventory("data-local").keys()
    expected_room=sum(inventory("data-local")[c] for c in room)
    return {"mode":mode,"app":app,"room":room,"app_methods":methods,
            "expected_app":sum(len(methods[c]) if c in methods else inventory("app")[c] for c in app),
            "expected_room":expected_room,
            "diagnostic_rounds":3 if mode == "focused" else 1,
            "lint":"NOT_RUN_FOCUSED" if mode == "focused" else "RUN_REQUIRED",
            "room_evidence":room_evidence(root, mode, expected_room, room),
            "room_class_counts":{c:inventory("data-local")[c] for c in room},
            "core_classes":["org.inkweft.core."+c for c in CORE],
            "fixture":{"runner":RUNNER,"phases":list(PHASE_METHODS),
                       "selected_phases":list(FOCUSED_PHASES) if mode == "focused" else list(PHASE_METHODS),
                       "selected_screenshots":0 if mode == "focused" else 40,
                       "expected_methods":len(PHASE_METHODS),
                       "expected_screenshots":40,"native_workspace_screenshots":24,"native_recall_screenshots":7,
                       "native_recall_reopen_screenshots":1},
            "scope":("focused: source-reader completion and shared-card save diagnosis; B6 all NOT_RUN_FOCUSED with zero new screenshots; not full acceptance"
                     if mode == "focused" else "six-batch B1-B5 complete scoped regression and B6 full-sized synthetic five-phase evidence")
                    + "; physical device and human acceptance remain NOT_RUN"}


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode",choices=("focused","full"),default="full")
    plan=build_plan(parser.parse_args().mode)
    Path("android-plan.json").write_text(json.dumps(plan,indent=2),encoding="utf-8")
    print(json.dumps(plan,indent=2))
