"""Explicit, source-checked regression scope for the six-batch development branch."""
import json
from pathlib import Path
from android_plan import inventory, select_methods
import re

APP = ["ViewportRasterTest", "PdfViewportRasterTest", "ZoomRasterTest", "RenderResourcesTest",
       "ImageRenderingTest", "CardPresentationUiTest", "RecallMaskUiTest", "StarNoteInteractionsUiTest"]
ROOM = ["PageObjectRepositoryTest", "CardPresentationRepositoryTest", "LibraryBackupRepositoryTest",
        "LibraryBackupGuardTest", "LibraryContentRepositoryTest", "StudyAndSelectionRepositoryTest"]
METHODS = {
    "org.inkweft.app.RecallMaskUiTest": ["sharedCardAnnotationIsShieldedDuringRecallAndRestoredWithoutWrites"],
    "org.inkweft.app.StarNoteInteractionsUiTest": ["excerptKeepsPageImageAddsCommentAndDeletesWithoutDeletingSource",
        "inlineCommentAndEightHandleRecropCancelSaveAndReopenKeepOriginalInk"],
}
CORE = ["PageObjectTest", "ContentTransferTest", "LibraryArchiveTest", "CardPresentationTest",
        "KnowledgeTest", "StudyTextTest", "MapAddendumTest", "StudyOrganizationTest"]
app=["org.inkweft.app."+name for name in APP]
room=["org.inkweft.data."+name for name in ROOM]
root=Path(__file__).resolve().parents[1]
assert all((root/"android/core-domain/src/test/kotlin/org/inkweft/core"/(name+".kt")).exists() for name in CORE)
for name, requested in METHODS.items():
    source=next((root/"android/app/src/androidTest").rglob(name.rsplit(".",1)[1]+".kt"))
    select_methods(re.findall(r"@Test(?:\([^)]*\))?\s+fun\s+(\w+)",source.read_text(encoding="utf-8")),requested)
assert set(app)<=inventory("app").keys() and set(room)<=inventory("data-local").keys()
plan={"app":app,"room":room,"app_methods":METHODS,
      "expected_app":sum(len(METHODS[c]) if c in METHODS else inventory("app")[c] for c in app),
      "expected_room":sum(inventory("data-local")[c] for c in room),
      "core_classes":["org.inkweft.core."+c for c in CORE],
      "scope":"six-batch B1 and shared rendering/data contracts; not the full historical suite"}
Path("android-plan.json").write_text(json.dumps(plan,indent=2),encoding="utf-8")
print(json.dumps(plan,indent=2))
