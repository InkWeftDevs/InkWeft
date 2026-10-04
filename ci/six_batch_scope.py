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
       "RecallDurableRecoveryTest", "RecallMaskNativeTest", "RecallAnswerPadUiTest", "RecallQuestionEditorUiTest", "StudyNavigationStateTest", "CardSourceNavigationUiTest", "LibrarySourceNavigationUiTest", "MapPortalUiTest", "LearningWorkspacePolishUiTest", "MapInteractionUiTest", "SelectionStudyUiTest"]
ROOM = ["PageObjectRepositoryTest", "StudyCapacityRepositoryTest", "CardTrashRepositoryTest", "StudyOrganizationRepositoryTest", "CardPresentationRepositoryTest", "LibraryBackupRepositoryTest",
        "LibraryBackupGuardTest", "LibraryContentRepositoryTest", "StudyAndSelectionRepositoryTest", "CardTransformRepositoryTest", "CardReuseRepositoryTest", "KnowledgeRepositoryTest", "KnowledgeTextRepositoryTest",
        "PageAuthoringRepositoryTest", "AuthoringPendingStoreTest", "BranchReviewRepositoryTest", "BranchReviewRoundRepositoryTest", "RecallStudyRepositoryTest"]
METHODS = {
    "org.inkweft.app.StarNoteInteractionsUiTest": ["excerptKeepsPageImageAddsCommentAndDeletesWithoutDeletingSource",
        "inlineCommentAndEightHandleRecropCancelSaveAndReopenKeepOriginalInk"],
}
# Target the concentrated four scope repairs and previously unverified production flows.
# Full mode retains all prior regressions; focused omissions are not claimed as new passes.
FOCUSED_METHODS = {
 'org.inkweft.app.BoundAnnotationFlowUiTest': ['bindMoveReorderZoomDetachAndReopenKeepOneTransformAndProtectedLayerOwnership'],'org.inkweft.app.CardTrashRestorationTest': ['unknownFrozenImpactSurvivesRestorationAndRejectsLaterReferences',
                                              'oldPendingIsNotReplayedAndReadOnlyCanOnlyResolveACommittedReceipt'],
 'org.inkweft.app.CardTrashUiTest': ['cardManagementCancelsRejectsChangedQuestionThenRestoresFromRecycleArea'],
 'org.inkweft.app.DocumentShortcutPreferencesUiTest': ['documentOrderAndVisibilityApplyImmediatelyAcrossFullScreenModesAndRecreation',
                                                       'legacyToolOrderHiddenIdsAndPenSettingsSurviveNewDestinationPreferences',
                                                       'hiddenDestinationsRemainReachableFromDocumentWritingAndReadingMenus'],
 'org.inkweft.app.RecallOriginalSourceUiTest': ['durableCurrentSourceWaitsForOriginalReceiptAndReturnsToSameAnswerAndWindow',
                                                'recycledCurrentSourceKeepsFixedSnapshotAndSameAttempt',
                                                'sealedComparisonKeepsSixScoresReachableWithinBoundedReadingColumn',
                                                'foreignSourceIsRejectedWithoutRenderingAnotherNotebook'],
 'org.inkweft.app.CardReuseDialogUiTest': ['realReferenceAndCopyActionsKeepSharedIdentityAndIndependentContentAfterReopen'],
 'org.inkweft.app.StudyCapacityPanelUiTest': ['warningsStartAtEightyPercentForEachBudgetAndStayAbsentBelowIt',
                                              'narrowLargeTypePanelScrollsToFullTouchTargetsAndRetriesSnapshotFailure'],
 'org.inkweft.app.StudyOrganizationUiTest': ['outlineEdgeScrollReachesOffscreenParentAndCancelKeepsWholeAuthorGraph'],
 'org.inkweft.app.RecallDurableRecoveryTest': ['repeatedOriginalSavesNewAnswerAndCancellationNeverRunsStaleContinuation',
                                               'cancellingUnknownAnswerBeforeOriginalRetryDoesNotOpenOrRecordOriginal'],
 'org.inkweft.app.StarNoteInteractionsUiTest': ['excerptKeepsPageImageAddsCommentAndDeletesWithoutDeletingSource',
                                                'inlineCommentAndEightHandleRecropCancelSaveAndReopenKeepOriginalInk'],
 'org.inkweft.app.StudyCapacityUiTest': ['cardBudgetRejectionKeepsTheRealEditorDraftAndAuthorRecords']}

CORE = ["PageObjectTest", "CardTrashCommandTest", "ContentTransferTest", "LibraryArchiveTest", "CardPresentationTest",
        "KnowledgeTest", "StudyTextTest", "MapAddendumTest", "StudyOrganizationTest", "StudyOutlineTest", "CardTransformTest", "KnowledgeTextLinksTest",
        "PageAuthoringTest", "SearchRequestVersionTest", "RecallStudyTest", "SuperMemo2PortTest", "BranchReviewTest", "BranchReviewRoundTest"]


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
            "lint":"NOT_RUN_FOCUSED" if mode == "focused" else "RUN_REQUIRED",
            "room_evidence":room_evidence(root, mode, expected_room, room),
            "room_class_counts":{c:inventory("data-local")[c] for c in room},
            "core_classes":["org.inkweft.core."+c for c in CORE],
            "fixture":{"runner":RUNNER,"phases":list(PHASE_METHODS),
                       "selected_phases":list(FOCUSED_PHASES) if mode == "focused" else list(PHASE_METHODS),
                       "selected_screenshots":14 if mode == "focused" else 39,
                       "expected_methods":len(PHASE_METHODS),
                       "expected_screenshots":39,"native_workspace_screenshots":24,"native_recall_screenshots":7},
            "scope":("focused: explicit UI methods and same-library B6 prepare/native_recall; omitted phases NOT_RUN_FOCUSED; not full acceptance"
                     if mode == "focused" else "six-batch B1-B5 complete scoped regression and B6 full-sized synthetic five-phase evidence")
                    + "; physical device and human acceptance remain NOT_RUN"}


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode",choices=("focused","full"),default="full")
    plan=build_plan(parser.parse_args().mode)
    Path("android-plan.json").write_text(json.dumps(plan,indent=2),encoding="utf-8")
    print(json.dumps(plan,indent=2))
