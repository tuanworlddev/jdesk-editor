#!/usr/bin/env python3
"""Writes a minimal FAIL gate-results.json when the editor could not be driven at all."""
import json
import os
import sys

out_dir, test_id, reason = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(out_dir, exist_ok=True)
with open(os.path.join(out_dir, "gate-results.json"), "w") as f:
    json.dump({"tests": [{"id": test_id, "outcome": "FAIL", "detail": reason,
                          "evidence": ["gate-results.json"]}]}, f, indent=2)
