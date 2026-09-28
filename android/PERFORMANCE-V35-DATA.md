# v35性能原始汇总

以下JSON与交付目录中的同名原始JSON一致；它是验证数据，不是运行配置。

```json
{
  "productionCommit": "21921e44f4bb585ffbce0cdb7a68a84af7a11d6e",
  "probeCommit": "d2805c85f5d86dd0fb3448859ba32ba7f93c49f8",
  "baselineApkSha256": "dc9c52906b908fe5ee9bf7d098ddae425edb85bcab6e09c5c30c2477d0e96f90",
  "targetApkSha256": "c7d4c9866ac8309a4ef63cedc4ba145bc99ff1d9eed26c5448f8d44940c42a3f",
  "repeatCountPerSide": 3,
  "summary": {
    "before": {
      "component": {
        "ordinary": {
          "sha256": [
            "c1ecc12ee2cd7ad5ac891eb174462de3f12dc8d5efe0bb73393db4290519d425"
          ],
          "metrics": {
            "decodeMs": 2.14,
            "firstCompletePreviewMs": 29.071,
            "finalMs": 31.99,
            "warmDrawP50Ms": 0.483,
            "warmDrawP95Ms": 0.725,
            "javaHeapBytes": 42200472,
            "nativeHeapBytes": 21302624,
            "pssKb": 256838
          }
        },
        "pencil": {
          "sha256": [
            "ed4e0ff766c99196b19f619cf9a9705fdfb4ca3d643c12d8071e6231088d05da"
          ],
          "metrics": {
            "decodeMs": 23.244,
            "firstCompletePreviewMs": 277.564,
            "finalMs": 1104.869,
            "warmDrawP50Ms": 1.273,
            "warmDrawP95Ms": 1.589,
            "javaHeapBytes": 85187568,
            "nativeHeapBytes": 56466480,
            "pssKb": 220531
          }
        },
        "erased": {
          "sha256": [
            "0f85fe162555ccf01fd9cc76d51f1a64d6c9805cc4477e4caa975b0c59fbfca1"
          ],
          "metrics": {
            "decodeMs": 4.742,
            "firstCompletePreviewMs": 63.08,
            "finalMs": 328.182,
            "warmDrawP50Ms": 0.679,
            "warmDrawP95Ms": 1.117,
            "javaHeapBytes": 52929960,
            "nativeHeapBytes": 29000704,
            "pssKb": 174130
          }
        },
        "mixed": {
          "sha256": [
            "a20cfb756986a3ee0b53bfed5dc9a66bb4e7b4842f229ba9cb84df8ec5fa5a47"
          ],
          "metrics": {
            "decodeMs": 2.039,
            "firstCompletePreviewMs": 51.963,
            "finalMs": 61.737,
            "warmDrawP50Ms": 0.556,
            "warmDrawP95Ms": 0.806,
            "javaHeapBytes": 35858440,
            "nativeHeapBytes": 33756480,
            "pssKb": 160299
          }
        },
        "map": {
          "sha256": [
            "75d7d60c8f272ba391e8cd47ee84f2dd47cc9949f4bfc07d0bd1920b51c5ed49"
          ],
          "metrics": {
            "firstDrawMs": 9.463
          }
        },
        "pdf-annotation": {
          "sha256": [
            "2b988b48258cf7a11eda148e824dfb10b5dd72a1353c46b5a107d62c98667125"
          ],
          "metrics": {
            "renderMs": 53.844
          }
        }
      },
      "workflow": {
        "firstOpen": {
          "dataReadyMs": 357.673,
          "completePreviewMs": 357.673,
          "finalMs": 775.785
        },
        "panelOpenMs": 173.484,
        "panelCloseMs": 100.04,
        "hotReopen": {
          "dataReadyMs": 183.414,
          "completePreviewMs": 183.414,
          "finalMs": 183.414
        },
        "scrollGestureMs": 69.071,
        "scrollSettledMs": 750.122,
        "pinchGestureMs": 133.289,
        "pinchSettledMs": 566.652,
        "eraseCommandCommittedMs": 86.7,
        "eraseVisibleMs": 667.77,
        "uiCpuMs": 1530,
        "renderCpuMs": 70,
        "pssKb": 217198,
        "javaHeapBytes": 59572216,
        "nativeHeapBytes": 68022032,
        "frameP50Ms": 3.96,
        "frameP95Ms": 95.052,
        "overRefreshFrames": [
          9,
          8,
          8
        ],
        "totalFrames": [
          33,
          33,
          32
        ],
        "sha256": [
          "e22305093602b8f293376dc5cf433bc6789f43aa96706ea0bf8a3a0c12149e5d"
        ],
        "gpuMemoryBytes": null,
        "gpuDurationMs": null
      },
      "coldStartTotalTimeMs": 1057
    },
    "after": {
      "component": {
        "ordinary": {
          "sha256": [
            "c1ecc12ee2cd7ad5ac891eb174462de3f12dc8d5efe0bb73393db4290519d425"
          ],
          "metrics": {
            "decodeMs": 2.112,
            "firstCompletePreviewMs": 31.554,
            "finalMs": 42.024,
            "warmDrawP50Ms": 0.452,
            "warmDrawP95Ms": 0.573,
            "javaHeapBytes": 42029144,
            "nativeHeapBytes": 21272784,
            "pssKb": 199057
          }
        },
        "pencil": {
          "sha256": [
            "ed4e0ff766c99196b19f619cf9a9705fdfb4ca3d643c12d8071e6231088d05da"
          ],
          "metrics": {
            "decodeMs": 22.58,
            "firstCompletePreviewMs": 288.711,
            "finalMs": 1153.904,
            "warmDrawP50Ms": 0.607,
            "warmDrawP95Ms": 0.758,
            "javaHeapBytes": 81100792,
            "nativeHeapBytes": 56425680,
            "pssKb": 216789
          }
        },
        "erased": {
          "sha256": [
            "0f85fe162555ccf01fd9cc76d51f1a64d6c9805cc4477e4caa975b0c59fbfca1"
          ],
          "metrics": {
            "decodeMs": 5.043,
            "firstCompletePreviewMs": 74.192,
            "finalMs": 319.437,
            "warmDrawP50Ms": 0.497,
            "warmDrawP95Ms": 0.617,
            "javaHeapBytes": 51816744,
            "nativeHeapBytes": 28964208,
            "pssKb": 173705
          }
        },
        "mixed": {
          "sha256": [
            "a20cfb756986a3ee0b53bfed5dc9a66bb4e7b4842f229ba9cb84df8ec5fa5a47"
          ],
          "metrics": {
            "decodeMs": 1.768,
            "firstCompletePreviewMs": 55.483,
            "finalMs": 61.932,
            "warmDrawP50Ms": 0.437,
            "warmDrawP95Ms": 0.567,
            "javaHeapBytes": 54286632,
            "nativeHeapBytes": 41751232,
            "pssKb": 183069
          }
        },
        "map": {
          "sha256": [
            "75d7d60c8f272ba391e8cd47ee84f2dd47cc9949f4bfc07d0bd1920b51c5ed49"
          ],
          "metrics": {
            "firstDrawMs": 10.029
          }
        },
        "pdf-annotation": {
          "sha256": [
            "2b988b48258cf7a11eda148e824dfb10b5dd72a1353c46b5a107d62c98667125"
          ],
          "metrics": {
            "renderMs": 20.001
          }
        }
      },
      "workflow": {
        "firstOpen": {
          "dataReadyMs": 388.921,
          "completePreviewMs": 388.921,
          "finalMs": 1610.245
        },
        "panelOpenMs": 188.24,
        "panelCloseMs": 100.176,
        "hotReopen": {
          "dataReadyMs": 183.469,
          "completePreviewMs": 183.469,
          "finalMs": 183.469
        },
        "scrollGestureMs": 70.016,
        "scrollSettledMs": 1915.984,
        "pinchGestureMs": 149.836,
        "pinchSettledMs": 1316.744,
        "eraseCommandCommittedMs": 85.451,
        "eraseVisibleMs": 1651.554,
        "uiCpuMs": 2500,
        "renderCpuMs": 90,
        "pssKb": 224586,
        "javaHeapBytes": 66118704,
        "nativeHeapBytes": 67324064,
        "frameP50Ms": 4.441,
        "frameP95Ms": 89.168,
        "overRefreshFrames": [
          9,
          10,
          7
        ],
        "totalFrames": [
          35,
          34,
          32
        ],
        "sha256": [
          "e22305093602b8f293376dc5cf433bc6789f43aa96706ea0bf8a3a0c12149e5d"
        ],
        "gpuMemoryBytes": null,
        "gpuDurationMs": null
      },
      "coldStartTotalTimeMs": 1030,
      "trackedPeakBytes": [
        73677040,
        73677040,
        73677040
      ]
    }
  },
  "traces": {
    "after-0.atrace": {
      "workflowPidCount": 1,
      "groups": {
        "uiDoFrame": {
          "n": 410,
          "totalMs": 548.378,
          "p50Ms": 0.033,
          "p95Ms": 1.929,
          "over16_67ms": 2
        },
        "renderDrawFrame": {
          "n": 38,
          "totalMs": 140.387,
          "p50Ms": 1.636,
          "p95Ms": 19.184,
          "over16_67ms": 3
        },
        "uncachedInkComparison": {
          "n": 820,
          "totalMs": 6.183,
          "p50Ms": 0.007,
          "p95Ms": 0.01,
          "over16_67ms": 0
        }
      }
    },
    "after-1.atrace": {
      "workflowPidCount": 1,
      "groups": {
        "uiDoFrame": {
          "n": 423,
          "totalMs": 585.424,
          "p50Ms": 0.033,
          "p95Ms": 2.01,
          "over16_67ms": 3
        },
        "renderDrawFrame": {
          "n": 38,
          "totalMs": 199.629,
          "p50Ms": 1.853,
          "p95Ms": 26.48,
          "over16_67ms": 4
        },
        "uncachedInkComparison": {
          "n": 820,
          "totalMs": 5.61,
          "p50Ms": 0.004,
          "p95Ms": 0.015,
          "over16_67ms": 0
        }
      }
    },
    "after-2.atrace": {
      "workflowPidCount": 1,
      "groups": {
        "uiDoFrame": {
          "n": 207,
          "totalMs": 631.625,
          "p50Ms": 0.037,
          "p95Ms": 4.028,
          "over16_67ms": 2
        },
        "renderDrawFrame": {
          "n": 37,
          "totalMs": 123.092,
          "p50Ms": 1.806,
          "p95Ms": 19.74,
          "over16_67ms": 2
        },
        "uncachedInkComparison": {
          "n": 820,
          "totalMs": 4.338,
          "p50Ms": 0.003,
          "p95Ms": 0.011,
          "over16_67ms": 0
        }
      }
    },
    "before-0.atrace": {
      "workflowPidCount": 1,
      "groups": {
        "uiDoFrame": {
          "n": 190,
          "totalMs": 546.379,
          "p50Ms": 0.02,
          "p95Ms": 3.991,
          "over16_67ms": 3
        },
        "renderDrawFrame": {
          "n": 38,
          "totalMs": 163.453,
          "p50Ms": 1.654,
          "p95Ms": 25.156,
          "over16_67ms": 3
        },
        "uncachedInkComparison": {
          "n": 0,
          "totalMs": null,
          "p50Ms": null,
          "p95Ms": null,
          "over16_67ms": null
        }
      }
    },
    "before-1.atrace": {
      "workflowPidCount": 1,
      "groups": {
        "uiDoFrame": {
          "n": 190,
          "totalMs": 550.215,
          "p50Ms": 0.022,
          "p95Ms": 4.401,
          "over16_67ms": 3
        },
        "renderDrawFrame": {
          "n": 37,
          "totalMs": 115.962,
          "p50Ms": 1.645,
          "p95Ms": 19.13,
          "over16_67ms": 2
        },
        "uncachedInkComparison": {
          "n": 0,
          "totalMs": null,
          "p50Ms": null,
          "p95Ms": null,
          "over16_67ms": null
        }
      }
    },
    "before-2.atrace": {
      "workflowPidCount": 1,
      "groups": {
        "uiDoFrame": {
          "n": 189,
          "totalMs": 510.437,
          "p50Ms": 0.024,
          "p95Ms": 3.598,
          "over16_67ms": 2
        },
        "renderDrawFrame": {
          "n": 36,
          "totalMs": 135.133,
          "p50Ms": 1.704,
          "p95Ms": 19.696,
          "over16_67ms": 2
        },
        "uncachedInkComparison": {
          "n": 0,
          "totalMs": null,
          "p50Ms": null,
          "p95Ms": null,
          "over16_67ms": null
        }
      }
    }
  }
}
```
