import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Stack;

/**
 * Picking a card moves it. Foundation builds win over tableau builds. Among
 * tableau piles, the card moves to the next legal pile to the right of where
 * it sits, wrapping around to the leftmost legal pile. A face-up run moves whole.
 */
public class AutoMoveTest {
	public static void main(String[] args) {
		testFoundationPreferredOverTableau();
		testTableauMove();
		testNextPileToTheRight();
		testNoMoveLeavesBoard();
		testSequenceMove();
		testSequenceStaysOffFoundation();
		testSamePileIsNotADestination();
		testWastePrefersFoundation();
		testFoundationCardMovesToTableau();
		testHintNamesTheCard();
		testMenuDrawPickExitRestart();
		testShortcutAndUnmovableCard();
		testHintCommand();
		System.out.println("AutoMoveTest passed");
	}

	/** AH can sit on 2S and can also start the hearts foundation. Foundation wins. */
	static void testFoundationPreferredOverTableau() {
		reset();
		col(0, 26);
		col(1, 1);
		up(26, 1);
		runMove(new Runnable() {
			public void run() {
				Game.autoMove(26, 0);
			}
		});
		expect(0);
		expect(1, 1);
		if (Game.foundationStacks[2].size() != 1 || Game.foundationStacks[2].peek() != 26) {
			throw new AssertionError("AH should move to the hearts foundation");
		}
		if (Game.points != 10) {
			throw new AssertionError("foundation points " + Game.points);
		}
	}

	static void testTableauMove() {
		reset();
		col(0, 5);
		col(1, 32);
		up(5, 32);
		String out = runMove(new Runnable() {
			public void run() {
				Game.autoMove(5, 0);
			}
		});
		expect(0);
		expect(1, 32, 5);
		if (Game.points != 0) {
			throw new AssertionError("tableau move should not score");
		}
		has(out, "6S moved successfully.");
		lacks(out, "Which pile");
	}

	/**
	 * 6S fits pile 3 (7H) and pile 5 (7D). From pile 1 it goes to the leftmost
	 * legal pile (3). Picking it again moves it to the next pile to the right
	 * (5), then wraps back to pile 3. A 6S from the waste also starts on the
	 * leftmost legal pile.
	 */
	static void testNextPileToTheRight() {
		reset();
		col(0, 5);
		col(2, 32);
		col(4, 45);
		up(5, 32, 45);
		String first = runMove(new Runnable() {
			public void run() {
				Game.autoMove(5, 0);
			}
		});
		lacks(first, "Which pile");
		expect(0);
		expect(2, 32, 5);
		expect(4, 45);

		String second = runMove(new Runnable() {
			public void run() {
				Game.autoMove(5, 0);
			}
		});
		lacks(second, "Which pile");
		expect(0);
		expect(2, 32);
		expect(4, 45, 5);

		runMove(new Runnable() {
			public void run() {
				Game.autoMove(5, 0);
			}
		});
		expect(0);
		expect(2, 32, 5);
		expect(4, 45);

		reset();
		Game.drawnCards.push(5);
		col(2, 32);
		col(4, 45);
		up(32, 45);
		runMove(new Runnable() {
			public void run() {
				Game.autoMove(5, 1);
			}
		});
		if (!Game.drawnCards.empty()) {
			throw new AssertionError("waste card was not played");
		}
		expect(2, 32, 5);
		expect(4, 45);
	}

	static void testNoMoveLeavesBoard() {
		reset();
		col(0, 5);
		col(1, 6);
		col(2, 30);
		up(5, 6, 30);
		String out = runMove(new Runnable() {
			public void run() {
				Game.autoMove(5, 0);
			}
		});
		expect(0, 5);
		expect(1, 6);
		expect(2, 30);
		if (Game.points != 0) {
			throw new AssertionError("points changed");
		}
		for (int s = 0; s < 4; s++) {
			if (!Game.foundationStacks[s].empty()) {
				throw new AssertionError("foundation changed");
			}
		}
		has(out, "6S cannot be moved.");
		lacks(out, "Which pile");
	}

	/** 8H heads 8H-7S. Both cards move, and the buried king is revealed. */
	static void testSequenceMove() {
		reset();
		col(0, 12, 33, 6);
		col(1, 8);
		up(33, 6, 8);
		String out = runMove(new Runnable() {
			public void run() {
				Game.autoMove(33, 0);
			}
		});
		expect(0, 12);
		expect(1, 8, 33, 6);
		if (!Game.faceUp.contains(Integer.valueOf(12))) {
			throw new AssertionError("KS was not revealed");
		}
		if (Game.points != 5) {
			throw new AssertionError("reveal points " + Game.points);
		}
		has(out, "KS revealed.");
		has(out, "8H moved successfully.");
	}

	/**
	 * 8H would be the next heart, but 7S is sitting on it, so the run moves to
	 * the tableau instead of pulling 8H out of the sequence.
	 */
	static void testSequenceStaysOffFoundation() {
		reset();
		for (int rank = 0; rank < 7; rank++) {
			Game.foundationStacks[2].push(26 + rank);
		}
		col(0, 33, 6);
		col(1, 8);
		up(33, 6, 8);
		runMove(new Runnable() {
			public void run() {
				Game.autoMove(33, 0);
			}
		});
		if (Game.foundationStacks[2].size() != 7) {
			throw new AssertionError("hearts foundation changed");
		}
		expect(0);
		expect(1, 8, 33, 6);
	}

	/** 7H matches the 6S already under it. That pile is not a destination. */
	static void testSamePileIsNotADestination() {
		reset();
		col(0, 32, 5);
		col(3, 7);
		up(32, 5, 7);
		runMove(new Runnable() {
			public void run() {
				Game.autoMove(32, 0);
			}
		});
		expect(0);
		expect(3, 7, 32, 5);
	}

	static void testWastePrefersFoundation() {
		reset();
		Game.drawnCards.push(0);
		col(0, 27);
		up(27);
		runMove(new Runnable() {
			public void run() {
				Game.autoMove(0, 1);
			}
		});
		expect(0, 27);
		if (!Game.drawnCards.empty()) {
			throw new AssertionError("waste card was not played");
		}
		if (Game.foundationStacks[0].size() != 1 || Game.foundationStacks[0].peek() != 0) {
			throw new AssertionError("AS should start the spade foundation");
		}
		if (Game.points != 10) {
			throw new AssertionError("points " + Game.points);
		}
	}

	static void testFoundationCardMovesToTableau() {
		reset();
		Game.foundationStacks[0].push(0);
		col(0, 27);
		up(27);
		String out = runMove(new Runnable() {
			public void run() {
				Game.autoMove(0, 2);
			}
		});
		expect(0, 27, 0);
		if (!Game.foundationStacks[0].empty()) {
			throw new AssertionError("ace still on foundation");
		}
		if (Game.points != -15) {
			throw new AssertionError("worry-back points " + Game.points);
		}
		has(out, "moved successfully.");
	}

	static void testHintNamesTheCard() {
		Klondike g = new Klondike();
		g.pile[0][0] = 5;
		g.pn[0] = 1;
		g.pile[1][0] = 32;
		g.pn[1] = 1;
		Klondike.Move tab = new Klondike.Move(Klondike.TO_TAB, 0, 0, 1);
		if (!g.describePick(tab).equals("Pick 6S")) {
			throw new AssertionError(g.describePick(tab));
		}
		if (!g.describe(tab).contains("pile 2")) {
			throw new AssertionError(g.describe(tab));
		}
		g.pile[0][0] = 26;
		Klondike.Move toFound = new Klondike.Move(Klondike.TO_FOUND, 0, 0, 2);
		if (!g.describePick(toFound).equals("Pick AH")) {
			throw new AssertionError(g.describePick(toFound));
		}
		g.sn = 2;
		if (!g.describePick(Klondike.Move.draw()).equals("Draw a card")) {
			throw new AssertionError(g.describePick(Klondike.Move.draw()));
		}
	}

	static void testMenuDrawPickExitRestart() {
		reset();
		col(0, 5);
		col(1, 32);
		up(5, 32);
		Game.drawPile.push(51);
		String out = playScript("foo\n1\n2\n6S\n4\n2\n5\n2\n");
		has(out, "Would you like to draw or pick a card?\n1) draw 2) pick card 3) hint 4) exit 5) restart");
		has(out, "Please enter a number from 1-5, or type player or warp.");
		has(out, "You have drawn a new card.");
		has(out, "Which card, i.e.");
		has(out, "6S moved successfully.");
		has(out, "Are you sure you want to exit?");
		has(out, "You chose not to exit.");
		has(out, "Are you sure you want to restart?");
		has(out, "You chose not to restart.");
		lacks(out, "Which pile");
		lacks(out, "2) tableau");
		lacks(out, "3) foundation");
		expect(0);
		expect(1, 32, 5);
		if (!Game.drawPile.empty() || Game.drawnCards.size() != 1 || Game.drawnCards.peek() != 51) {
			throw new AssertionError("draw pile was not used in the usual way");
		}
	}

	/** Option 3 still asks the Player for a hint and does not name a pile. */
	static void testHintCommand() {
		reset();
		col(0, 5);
		col(1, 32);
		up(5, 32);
		Game.drawPile.push(51);
		Game.seenCards[51] = false;
		String out = playScript("3\n");
		has(out, "Hint:");
		lacks(out, "to pile");
		lacks(out, "Which pile");
		lacks(out, "Which card");
		expect(0, 5);
		expect(1, 32);
	}

	static void testShortcutAndUnmovableCard() {
		reset();
		col(0, 9);
		col(1, 36);
		up(9, 36);
		Game.drawPile.push(3);
		String shortcut = playScript("210S\n");
		has(shortcut, "10S moved successfully.");
		lacks(shortcut, "Which card");
		lacks(shortcut, "Which pile");
		expect(0);
		expect(1, 36, 9);

		reset();
		col(0, 5);
		up(5);
		Game.drawPile.push(3);
		String bad = playScript("2\nZZ\nAS\n");
		has(bad, "Which card, i.e.");
		has(bad, "Invalid input. Please enter a card that can be moved.");
		has(bad, "AS cannot be moved.");
		lacks(bad, "Which pile");
		expect(0, 5);
	}

	static void reset() {
		Game.points = 0;
		Game.nKings = 0;
		Game.noWinnable = false;
		Game.gameWon = false;
		Game.headless = false;
		Game.revealHiddenTableau = false;
		Game.drawPile.clear();
		Game.drawnCards.clear();
		Game.faceUp.clear();
		for (int i = 0; i < 7; i++) {
			if (Game.layout[i] == null) {
				Game.layout[i] = new ArrayList<Integer>();
			} else {
				Game.layout[i].clear();
			}
		}
		for (int i = 0; i < 4; i++) {
			if (Game.foundationStacks[i] == null) {
				Game.foundationStacks[i] = new Stack<Integer>();
			} else {
				Game.foundationStacks[i].clear();
			}
		}
	}

	static void col(int pile, int... cards) {
		for (int card : cards) {
			Game.layout[pile].add(card);
		}
	}

	static void up(int... cards) {
		for (int card : cards) {
			Game.faceUp.add(card);
		}
	}

	static void expect(int pile, int... cards) {
		ArrayList<Integer> got = Game.layout[pile];
		if (got.size() != cards.length) {
			throw new AssertionError("pile " + (pile + 1) + " is " + got + " want " + cards.length + " cards");
		}
		for (int i = 0; i < cards.length; i++) {
			if (got.get(i).intValue() != cards[i]) {
				throw new AssertionError("pile " + (pile + 1) + " is " + got);
			}
		}
	}

	static String runMove(Runnable action) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		PrintStream prev = System.out;
		System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
		try {
			action.run();
			return buf.toString(StandardCharsets.UTF_8);
		} finally {
			System.setOut(prev);
		}
	}

	static String playScript(String script) {
		Game.scanner = new Scanner(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)));
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		PrintStream prev = System.out;
		System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
		try {
			try {
				Game.play();
				throw new AssertionError("menu returned before the script ended");
			} catch (NoSuchElementException done) {
				return buf.toString(StandardCharsets.UTF_8);
			}
		} finally {
			System.setOut(prev);
		}
	}

	static void has(String text, String part) {
		if (!text.contains(part)) {
			throw new AssertionError("missing [" + part + "] in:\n" + text);
		}
	}

	static void lacks(String text, String part) {
		if (text.contains(part)) {
			throw new AssertionError("unexpected [" + part + "] in:\n" + text);
		}
	}
}
