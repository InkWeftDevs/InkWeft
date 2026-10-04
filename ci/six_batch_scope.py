"""Explicit, source-checked regression scope for the six-batch development branch."""
import json
from pathlib import Path
from android_plan import inventory, select_methods
from run_six_batch_fixture import PHASE_METHODS, RUNNER
import re

APP = ["ViewportRasterTest", "PdfViewportRasterTest", "ZoomRasterTest", "RenderResourcesTest",
       "ImageRenderingTest", "CardPresentationUiTest", "RecallMaskUiTest", "StarNoteInteractionsUiTest", "StudyOrganizationUiTest", "CardTransformUiTest", "StudyTransformNavigationUiTest", "StudyRelationPaintTest", "StudyRelationsUiTest", "KnowledgeRelationEditorUiTest", "CardReuseStoreTest",
       "LayerAuthoringNativeTest", "LayerSnapshotPreviewTest", "VisiblePageExportTest",
       "RecallDurableRecoveryTest", "RecallMaskNativeTest", "RecallQuestionEditorUiTest", "StudyNavigationStateTest", "CardSourceNavigationUiTest", "LibrarySourceNavigationUiTest", "MapPortalUiTest", "LearningWorkspacePolishUiTest", "MapInteractionUiTest", "SelectionStudyUiTest"]
ROOM = ["PageObjectRepositoryTest", "CardPresentationRepositoryTest", "LibraryBackupRepositoryTest",
        "LibraryBackupGuardTest", "LibraryContentRepositoryTest", "StudyAndSelectionRepositoryTest", "CardTransformRepositoryTest", "CardReuseRepositoryTest", "KnowledgeRepositoryTest", "KnowledgeTextRepositoryTest",
        "PageAuthoringRepositoryTest", "AuthoringPendingStoreTest", "BranchReviewRepositoryTest", "BranchReviewRoundRepositoryTest", "RecallStudyRepositoryTest"]
METHODS = {
    "org.inkweft.app.StarNoteInteractionsUiTest": ["excerptKeepsPageImageAddsCommentAndDeletesWithoutDeletingSource",
        "inlineCommentAndEightHandleRecropCancelSaveAndReopenKeepOriginalInk"],
}
CORE = ["PageObjectTest", "ContentTransferTest", "LibraryArchiveTest", "CardPresentationTest",
        "KnowledgeTest", "StudyTextTest", "MapAddendumTest", "StudyOrganizationTest", "StudyOutlineTest", "CardTransformTest", "KnowledgeTextLinksTest",
        "PageAuthoringTest", "SearchRequestVersionTest", "RecallStudyTest", "SuperMemo2PortTest", "BranchReviewTest", "BranchReviewRoundTest"]


def build_plan():
    app=["org.inkweft.app."+name for name in APP]
    room=["org.inkweft.data."+name for name in ROOM]
    root=Path(__file__).resolve().parents[1]
    assert all((root/"android/core-domain/src/test/kotlin/org/inkweft/core"/(name+".kt")).exists() for name in CORE)
    fixture_classes={"org.inkweft.app."+name for name,_ in PHASE_METHODS.values()}
    # Stateful fixture methods run in order, outside per-case clearing.
    for name,method in PHASE_METHODS.values():
        source=next((root/"android/app/src/androidTest").rglob(name+".kt"))
        select_methods(re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)",source.read_text(encoding="utf-8")),[method])
    for name,requested in METHODS.items():
        source=next((root/"android/app/src/androidTest").rglob(name.rsplit(".",1)[1]+".kt"))
        select_methods(re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)",source.read_text(encoding="utf-8")),requested)
    assert (root/RUNNER).is_file()
    assert not set(app)&fixture_classes
    assert set(app)<=inventory("app").keys() and set(room)<=inventory("data-local").keys()
    return {"app":app,"room":room,"app_methods":METHODS,
            "expected_app":sum(len(METHODS[c]) if c in METHODS else inventory("app")[c] for c in app),
            "expected_room":sum(inventory("data-local")[c] for c in room),
            "room_class_counts":{c:inventory("data-local")[c] for c in room},
            "core_classes":["org.inkweft.core."+c for c in CORE],
            "fixture":{"runner":RUNNER,"phases":list(PHASE_METHODS),"expected_methods":len(PHASE_METHODS),
                       "expected_screenshots":38,"native_workspace_screenshots":24,"native_recall_screenshots":6},
            "scope":"six-batch B1-B5 targeted regression and B6 full-sized synthetic prepare/cold-reopen/workspace/native-recall evidence; physical device and human acceptance remain NOT_RUN"}


if __name__ == "__main__":
    plan=build_plan()
    Path("android-plan.json").write_text(json.dumps(plan,indent=2),encoding="utf-8")
    print(json.dumps(plan,indent=2))
