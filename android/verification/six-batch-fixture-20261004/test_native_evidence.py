"""Host contract checks only; these do not simulate native rendering or an Android pass."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("run-fixture.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class NativeEvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.name = "05-layered-1000-stroke-pressure.png"
        self.pixels = b"\x89PNG\r\n\x1a\nsynthetic-host-contract-only"
        self.commit = "a" * 40
        self.proof = dict(runId="same-run", sourceCommit=self.commit, pageId="page-12", sourcePage=12,
                          sha256=hashlib.sha256(self.pixels).hexdigest(), layers=3, documentSha256="b" * 64,
                          nativeInkSha256="e" * 64, pdfTilePresent=True, sourceFrameDrawn=True,
                          pendingRaster=False, pendingImages=False, nativeStoredStrokes=1000,
                          nativeStoredPoints=100000, nativeVisibleStrokes=1000, authoringFingerprint="c" * 64,
                          continuous=False, baseLayerBluePixels=20, lockedLayerBluePixels=20)
        self.manifest = dict(runId="same-run", documentPages=[f"page-{n}" for n in range(1, 13)],
                             documentSha256="b" * 64, stressAuthoringFingerprint="c" * 64,
                             nativePageCaptures={self.name: self.proof})

    def check(self):
        return runner.checked_native_capture(self.manifest, self.name, self.pixels, self.commit)

    def test_matching_full_native_proof_is_accepted(self):
        self.assertEqual(self.proof, self.check())

    def test_png_name_alone_is_rejected(self):
        self.manifest["nativePageCaptures"] = {}
        with self.assertRaises(ValueError):
            self.check()

    def test_wrong_source_or_stale_bytes_are_rejected(self):
        for key, value in (("pageId", "page-1"), ("sourcePage", 1), ("sourceCommit", "d" * 40),
                           ("sha256", "d" * 64), ("runId", "other-run")):
            with self.subTest(key=key):
                old = self.proof[key]
                self.proof[key] = value
                with self.assertRaises(ValueError):
                    self.check()
                self.proof[key] = old

    def test_loading_or_incomplete_ink_is_rejected(self):
        for key, value in (("pdfTilePresent", False), ("sourceFrameDrawn", False), ("pendingRaster", True),
                           ("pendingImages", True), ("nativeStoredStrokes", 999), ("nativeStoredPoints", 99999),
                           ("nativeVisibleStrokes", 900), ("baseLayerBluePixels", 0), ("lockedLayerBluePixels", 0)):
            with self.subTest(key=key):
                old = self.proof[key]
                self.proof[key] = value
                with self.assertRaises(ValueError):
                    self.check()
                self.proof[key] = old

    def test_first_page_requires_completed_continuous_source_frame(self):
        self.name = "01-twelve-page-material.png"
        first = copy.deepcopy(self.proof)
        first.update(pageId="page-1", sourcePage=1, continuous=True)
        self.manifest["nativePageCaptures"][self.name] = first
        self.assertEqual(first, self.check())
        first["sourceFrameDrawn"] = False
        with self.assertRaises(ValueError):
            self.check()

    def test_panel_sections_require_correct_page_and_actual_assertions(self):
        for name, section in (("06-pressure-page-layers.png", "current"), ("07-pressure-hidden-layer.png", "hidden"),
                              ("08-pressure-locked-layer.png", "locked")):
            with self.subTest(section=section):
                self.name = name
                proof = copy.deepcopy(self.proof)
                proof.update(hiddenLayers=1, lockedLayers=1, currentLayer="00000000-0000-0000-0000-000000000001",
                             panelAssertionsPassed=True, panelSection=section, pressurePixelsFile="05-layered-1000-stroke-pressure.png")
                self.manifest["nativePageCaptures"][name] = proof
                self.assertEqual(proof, self.check())
                proof["panelAssertionsPassed"] = False
                with self.assertRaises(ValueError):
                    self.check()


if __name__ == "__main__":
    unittest.main()
