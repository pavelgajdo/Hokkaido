import tempfile
import unittest
from pathlib import Path

from verify_fusion_apk import check_badging, VerificationError

class BadgingTests(unittest.TestCase):
    def check(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "badging.txt"
            path.write_text(text, encoding="utf-8")
            check_badging(path)

    def test_actual_build_tools_36_output(self):
        self.check("package: name='dev.lichii.fusion' versionCode='34390012' versionName='0.7.1-fusion.0.3.52-preview' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'\nminSdkVersion:'33'\ntargetSdkVersion:'36'\napplication-label:'Hokkaido Fusion'\nnative-code: 'arm64-v8a'\n")

    def test_legacy_output(self):
        self.check("package: name='dev.lichii.fusion'\nsdkVersion:'33'\nnative-code: 'arm64-v8a'\n")

    def test_invalid_metadata(self):
        for text in (
            "package: name='dev.lichii.fusion'\nminSdkVersion:'32'\nnative-code: 'arm64-v8a'\n",
            "package: name='dev.lichii.fusion'\ntargetSdkVersion:'33'\nnative-code: 'arm64-v8a'\n",
            "package: name='wrong'\nminSdkVersion:'33'\nnative-code: 'arm64-v8a'\n",
            "package: name='dev.lichii.fusion'\nminSdkVersion:'33'\nnative-code: 'x86_64'\n",
        ):
            with self.subTest(text=text), self.assertRaises(VerificationError):
                self.check(text)

if __name__ == "__main__":
    unittest.main()
