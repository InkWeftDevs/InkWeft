# v35机器回执

以下JSON与交付目录中的同名原始JSON一致；它是验证数据，不是运行配置。

```json
{
  "sourceCommit": "21921e44f4bb585ffbce0cdb7a68a84af7a11d6e",
  "buildCommit": "21921e44f4bb585ffbce0cdb7a68a84af7a11d6e",
  "sourceDirty": false,
  "versionCode": 35,
  "versionName": "0.0.35-integrated-mindmap",
  "applicationId": "org.inkweft.app.a0.workspace",
  "apkSha256": "c7d4c9866ac8309a4ef63cedc4ba145bc99ff1d9eed26c5448f8d44940c42a3f",
  "apkBytes": 145663085,
  "testApkSha256": "6d3d646c826995fa0665b38690beee85092cd7bc7ce11ecb816de00de16cea59",
  "signingCertificateSha256": "18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969",
  "ciSourceSnapshot": 36373941351,
  "ciAndroid": 36373941327,
  "verification": {
    "core": {
      "passed": 239,
      "failed": 0
    },
    "room": {
      "passed": 144,
      "failed": 0
    },
    "app": {
      "passed": 169,
      "failed": 0
    },
    "ciPython": {
      "passed": 9,
      "failed": 0
    },
    "lint": {
      "errors": 0,
      "warnings": 101
    },
    "tablet": {
      "install": "PASS",
      "originalNotesRetained": 23,
      "newSyntheticNotes": 1,
      "physicalStylus15Minutes": "NOT_RUN",
      "instrumentationStartup": "INCOMPLETE_ABORTED",
      "manualUsbFlow": "PASS_WITH_LIMITS"
    },
    "performance": {
      "probeCommit": "d2805c85f5d86dd0fb3448859ba32ba7f93c49f8",
      "testApkSha256": "b5160d26925f1f96179733872c3c49fc1ff4e8f999f11f0a3f5b7eaa098e0cb6",
      "ciRun": 36375731298,
      "ciConclusion": "success",
      "samplesMatch": true,
      "repeatsPerSide": 3,
      "allPerformanceTargetsMet": false
    }
  },
  "ciArtifact": {
    "runId": 36373941327,
    "conclusion": "success",
    "sourceCommit": "21921e44f4bb585ffbce0cdb7a68a84af7a11d6e",
    "buildCommit": "9fd1371eee5243ddd2ad2638ae7985beb05d93cc",
    "applicationId": "org.inkweft.app.a0.insertion",
    "apkSha256": "76a1b37dc46a1264ec2c874a619cb011b7fcb3f71a20d42bf93b616d86f1307a",
    "signingCertificateSha256": "8c84299859ba25127610b341f9e3decdeb73278d5213cc16f816aa92f188a6ce",
    "counts": {
      "core": 239,
      "room": 144,
      "app": 169
    }
  }
}
```
