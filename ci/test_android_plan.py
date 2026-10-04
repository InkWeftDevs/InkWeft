import unittest
from android_plan import plan, inventory

class PlanTest(unittest.TestCase):
    def test_explicit_methods_keep_source_order_and_reject_missing_or_repeated(self):
        from android_plan import select_methods
        self.assertEqual(["a", "b"], select_methods(["a", "b"]))
        self.assertEqual(["a", "c"], select_methods(["a", "b", "c"], ["c", "a"]))
        for values in ([], ["missing"], ["a", "a"]):
            with self.assertRaises(ValueError):
                select_methods(["a", "b"], values)

    def test_tls_has_explicit_gate_and_full_run(self):
        for path in ['backup-server/tls_fixture.py','android/app/src/main/java/org/inkweft/app/BackupTransport.kt','android/app/src/tlsProbe/res/xml/network_security_config.xml','android/app/build.gradle.kts','ci/run_tls_probe.py']:
            self.assertTrue(plan([path])['tls'],path)
        self.assertTrue(plan([],True)['tls'])
        self.assertFalse(plan(['android/CHANGELOG.md'])['tls'])
        self.assertFalse(plan(['android/app/src/main/java/org/inkweft/app/PaperPickerDialog.kt'])['tls'])
        self.assertFalse(plan(['ci/run_tls_probe.py'])['device'])
    def test_backup_protocol_and_dependency_changes_run_real_roundtrip(self):
        for path in ['backup-server/server.py','backup-server/requirements.txt']:
            p=plan([path]);self.assertTrue(p['backup']);self.assertEqual(['org.inkweft.app.EncryptedBackupIntegrationTest','org.inkweft.app.ShadowNativeTest','org.inkweft.app.ShadowSyncTest'],p['app']);self.assertEqual([],p['room'])
    def test_synthetic_protocol_only_does_not_build_android(self):
        p=plan(['sync-lab/model.py','resource-packs/pack.py','resource-packs/paper/planner.png'])
        self.assertTrue(p['experiments']);self.assertFalse(p['build']);self.assertFalse(p['device'])
        self.assertTrue(plan(['resource-packs/paper/planner.png'])['experiments'])
        self.assertFalse(plan(['resource-packs/README.md'])['experiments'])
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
