import unittest
from pathlib import Path
from validate_sdk_patch import validate_patch_offsets

class SdkPatchLayoutTest(unittest.TestCase):
    VALID = """--- a/File.java
+++ b/File.java
@@ -1,0 +2,2 @@
+first
+second
@@ -5,1 +7,1 @@
-before
+after
@@ -9,2 +10,0 @@
-removed1
-removed2
@@ -12,0 +13,1 @@
+last
"""

    def test_insert_replace_and_delete_coordinates(self):
        validate_patch_offsets(self.VALID)

    def test_stale_following_offset_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "inconsistent_offset"):
            validate_patch_offsets(self.VALID.replace("+7,1", "+14,1"))

    def test_wrong_hunk_size_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "inconsistent_line_count"):
            validate_patch_offsets(self.VALID.replace("+second\n", ""))

    def test_actual_pinned_sdk_patch_is_consistent(self):
        validate_patch_offsets((Path(__file__).resolve().parents[1] / "sdk/external-playout.patch").read_text())

    def test_trailing_old_hunk_block_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "invalid_hunk_body"):
            validate_patch_offsets(self.VALID + "@@\n+obsolete block\n")

if __name__ == "__main__":
    unittest.main()
