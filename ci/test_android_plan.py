import unittest
from android_plan import plan, inventory

class PlanTest(unittest.TestCase):
    def test_docs_do_not_boot_emulator(self):
        self.assertFalse(plan(["android/CHANGELOG.md", "android/DEVICE-TEST-CHECKLIST.md"])["build"])
    def test_one_ui_test_only_runs_that_class(self):
        p=plan(["android/app/src/androidTest/java/org/inkweft/app/WorkspaceUiTest.kt"])
        self.assertEqual(["org.inkweft.app.WorkspaceUiTest"],p["app"])
        self.assertFalse(p["core"]);self.assertFalse(p["lint"]);self.assertEqual([],p["room"])
    def test_app_ui_does_not_repeat_database_suite(self):
        p=plan(["android/app/src/main/java/org/inkweft/app/PaperPickerDialog.kt"])
        self.assertTrue(p["app"]);self.assertTrue(p["lint"]);self.assertEqual([],p["room"])
    def test_core_test_only_is_jvm(self):
        p=plan(["android/core-domain/src/test/kotlin/org/inkweft/core/PaperTemplatesTest.kt"])
        self.assertTrue(p["core"]);self.assertFalse(p["device"])
    def test_storage_and_unknown_changes_fall_back_to_full(self):
        for path in ["android/data-local/src/main/kotlin/db.kt", ".github/workflows/android-a0.yml", "android/gradle/libs.versions.toml"]:
            p=plan([path]);self.assertTrue(p["core"] and p["lint"] and p["room"] and p["app"])
    def test_manual_full_and_deleted_tests(self):
        self.assertTrue(plan([],True)["device"])
        self.assertEqual(sorted(inventory("app")),plan(["android/app/src/androidTest/DeletedTest.kt"])["app"])

if __name__ == "__main__": unittest.main()
