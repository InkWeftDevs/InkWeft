import subprocess
import unittest
from android_device import prepare_case


class DeviceSetupTest(unittest.TestCase):
    def test_never_clears_a_physical_device(self):
        with self.assertRaises(ValueError):
            prepare_case("tablet", "org.inkweft.app.a0.insertion", print,
                         lambda *a, **k: self.fail("must not execute"))

    def test_recovers_only_idempotent_setup_after_offline(self):
        calls = []
        records = []
        def run(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 1 if len(calls) == 1 else 0,
                                               "", "error: device offline" if len(calls) == 1 else "")
        prepare_case("emulator-5554", "org.inkweft.app.a0.insertion", records.append, run)
        self.assertEqual(calls[1][-1], "wait-for-device")
        self.assertEqual(calls[0], calls[2])
        self.assertEqual(len(calls), 6)
        self.assertEqual(len(records), 1)

    def test_other_failure_is_not_retried(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 1, "", "permission denied")
        with self.assertRaises(RuntimeError):
            prepare_case("emulator-5554", "org.inkweft.app.a0.insertion", print, run)
        self.assertEqual(len(calls), 1)
