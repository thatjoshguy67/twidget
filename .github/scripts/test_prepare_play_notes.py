import unittest
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "prepare_play_notes", Path(__file__).with_name("prepare-play-notes.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
parse_notes = module.parse_notes


class PlayNotesTests(unittest.TestCase):
    def test_localized_notes_preserve_unicode_and_line_breaks(self):
        self.assertEqual(
            parse_notes("<en-GB>\n• Fixes\nMore changes\n</en-GB>\n\n<fr-FR>\nAméliorations\n</fr-FR>\n"),
            {"en-GB": "• Fixes\nMore changes", "fr-FR": "Améliorations"},
        )

    def test_character_limit(self):
        self.assertEqual(len(parse_notes(f"<en-GB>\n{'é' * 500}\n</en-GB>")["en-GB"]), 500)
        with self.assertRaises(ValueError):
            parse_notes(f"<en-GB>\n{'é' * 501}\n</en-GB>")

    def test_malformed_or_ambiguous_notes_are_rejected(self):
        for text in (
            "", "Plain notes", "<en-GB>\n\n</en-GB>",
            "<en-GB>\nFixes\n</de-DE>",
            "<en-GB>\nFixes\n</en-GB>\nUnexpected text",
            "<en-GB>\nOne\n</en-GB>\n<en-GB>\nTwo\n</en-GB>",
            "<../secret>\nFixes\n</../secret>",
        ):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_notes(text)


if __name__ == "__main__":
    unittest.main()
