"""Tests for axis_chess. Engine tests run only if Stockfish is installed.

    python3 -m unittest test_axis_chess -v
"""
import unittest

import axis_chess as ac

START_ROWS = ['rnbqkbnr', 'pppppppp', '........', '........', '........', '........', 'PPPPPPPP', 'RNBQKBNR']


class BoardTests(unittest.TestCase):
    def test_white_bottom_start_position(self):
        board = ac.rows_to_board(START_ROWS, white_bottom=True)
        self.assertEqual(ac.to_fen(board, True), 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1')

    def test_black_bottom_is_rotated(self):
        # Same start position seen from black's side: rows reversed and mirrored.
        rows = [r[::-1] for r in reversed(START_ROWS)]
        board = ac.rows_to_board(rows, white_bottom=False)
        self.assertEqual(ac.to_fen(board, False), 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1')

    def test_normalizes_fen_digits_and_spaces(self):
        rows = ['r n b q k b n r', 'pppppppp', '8', '8', '4P3', '8', 'PPPP.PPP', 'RNBQKBNR']
        board = ac.rows_to_board(rows, True)
        self.assertEqual(board[3][4], 'P')

    def test_rejects_bad_positions(self):
        with self.assertRaises(ac.ChessError):
            ac.validate(ac.rows_to_board(['........'] * 7 + ['....K...'], True))  # no black king
        with self.assertRaises(ac.ChessError):
            ac.rows_to_board(['rnbqkbnr'] * 7, True)  # 7 rows
        with self.assertRaises(ac.ChessError):
            ac.validate(ac.rows_to_board(['P...k...'] + ['........'] * 6 + ['....K...'], True))  # pawn on 8th

    def test_side_to_move_flips_when_opponent_in_check(self):
        # White (bottom) to move would leave black king in check by the rook — so it must be black's move.
        rows = ['....k...', '........', '........', '........', '........', '........', '........', 'K...R...']
        board = ac.rows_to_board(rows, True)
        self.assertTrue(ac.king_in_check(board, white=False))
        self.assertFalse(ac.side_to_move(board, bottom_is_white=True))

    def test_attacks(self):
        board = ac.rows_to_board(['....k...', '........', '........', '...p....', '....K...', '........',
                                  '........', '........'], True)
        self.assertTrue(ac.king_in_check(board, white=True))  # black pawn d5 attacks e4

    def test_castling_rights_from_placement(self):
        rows = ['r...k..r', '........', '........', '........', '........', '........', '........', '....K..R']
        self.assertEqual(ac.to_fen(ac.rows_to_board(rows, True), True).split()[2], 'Kkq')

    def test_move_text(self):
        board = ac.rows_to_board(START_ROWS, True)
        self.assertEqual(ac.move_text(board, 'g1f3'), 'Кg1–f3')
        self.assertEqual(ac.move_text(board, 'e2e4'), 'e2–e4')
        self.assertEqual(ac.move_text(board, 'e1g1'), '0-0')

    def test_parse_recognition(self):
        data = ac.parse_recognition('Вот: {"found": true, "white_bottom": false, "rows": []} конец')
        self.assertFalse(data['white_bottom'])
        with self.assertRaises(ac.ChessError):
            ac.parse_recognition('{"found": false}')


@unittest.skipUnless(ac.stockfish_path(), 'Stockfish not installed')
class EngineTests(unittest.TestCase):
    def test_alternative_filter(self):
        self.assertFalse(ac.good_alternative({'mate': 1}, {'cp': 300}))
        self.assertTrue(ac.good_alternative({'cp': 50}, {'cp': 0}))
        self.assertFalse(ac.good_alternative({'cp': 50}, {'cp': -200}))

    def test_finds_mate_in_one(self):
        # Scholar's mate pattern: Qh5xf7#.
        rows = ['r.bqkb.r', 'pppp.ppp', '..n..n..', '....p..Q', '..B.P...', '........', 'PPPP.PPP',
                'RNB.K.NR']
        ans = ac.solve({'rows': rows, 'white_bottom': True}, 0.5)
        self.assertEqual(ans['best'], 'h5f7')
        self.assertIn('мат в 1', ans['text'])
        self.assertIn('Фh5×f7', ans['text'])
        self.assertEqual(ans['alternatives'], [])  # other moves lose — no blue arrows

    def test_black_bottom_gets_black_move(self):
        # Position after 1.e4 seen from black's side (h-file on the left): black to move.
        rows = ['RNBKQBNR', 'PPP.PPPP', '........', '...P....', '........', '........', 'pppppppp', 'rnbkqbnr']
        ans = ac.solve({'rows': rows, 'white_bottom': False}, 0.3)
        self.assertEqual(ans['fen'], 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1')
        self.assertTrue(ans['best'][1] in '78')  # a black move starts on rank 7 or 8
        self.assertTrue(len(ans['alternatives']) >= 1)

    def test_checkmated_position(self):
        # Fool's mate: white is mated, white at bottom.
        rows = ['rnb.kbnr', 'pppp.ppp', '........', '....p...', '......Pq', '.....P..', 'PPPPP..P', 'RNBQKBNR']
        ans = ac.solve({'rows': rows, 'white_bottom': True}, 0.2)
        self.assertIsNone(ans['best'])
        self.assertIn('мат', ans['text'])


if __name__ == '__main__':
    unittest.main()
