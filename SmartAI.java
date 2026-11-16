import java.util.HashSet;

/**
 * Legal player for this 1-card Klondike.
 *
 * <p>It never reads a face-down tableau card or an unseen stock card. The
 * original hint bot always takes the first legal build and gives up after the
 * score falls 75 points. This player still uses only legal moves, but it
 * uncovers the deepest buried pile first (onto the pile that is itself burying
 * the most cards) and only then falls back to the hint order: waste builds,
 * foundation builds, a split that frees a foundation card, worrying a
 * foundation card back when that lets the waste card sit on it, and finally a
 * draw. Repeated positions end the game, so it does not recycle forever.
 */
final class SmartAI {

	/** Added to a reveal that uncovers exactly one buried card. Tuned by benchmark. */
	static int singletonBonus = 180;
	/** Before the stock is known, waste cards of this rank or higher are not parked. 13 disables it. */
	static int holdWasteRank = 9;
	/** Subtracted when a reveal lands on a fully face-up pile. */
	static int openPilePenalty = 0;

	private SmartAI() {
	}

	static final class Outcome {
		final boolean won;
		final int moves;
		final int faceDownLeft;
		final String reason;

		Outcome(boolean won, int moves, int faceDownLeft, String reason) {
			this.won = won;
			this.moves = moves;
			this.faceDownLeft = faceDownLeft;
			this.reason = reason;
		}
	}

	static Outcome play(Klondike g) {
		StrongAI.Outcome outcome = new StrongAI(g.visibleHash()).play(g);
		return new Outcome(outcome.won, outcome.moves, g.faceDown(), outcome.won ? "win" : "loss");
	}

	/** One interactive step. The session remembers positions so a loop can stop. */
	static final class Session {
		private final HashSet<Long> seen = new HashSet<Long>();
		final StrongAI brain = new StrongAI(System.nanoTime());

		Klondike.Move next(Klondike g) {
			if (g.won()) {
				return Klondike.Move.auto();
			}
			if (!seen.add(g.hash())) {
				return null;
			}
			return brain.choose(g);
		}
	}

	static void runCli(boolean slow, boolean warpMode) {
		System.out.println(warpMode ? "warp" : "player");
		Game.revealHiddenTableau = warpMode;
		try {
		Klondike g = Klondike.fromGame();
		Session session = new Session();
		HashSet<Long> seen = new HashSet<Long>();
		int guard = 0;
		while (guard++ < 5000) {
			if (g.won()) {
				celebrate(g);
				return;
			}
			if (!seen.add(g.hash())) {
				System.out.println("Computer is done. Computer can go no further.");
				g.writeIntoGame();
				Game.print(0, false);
				return;
			}
			Klondike.Move move = warpMode ? WarpMode.choose(g) : session.brain.choose(g);
			if (move == null || move.kind == Klondike.AUTO) {
				if (g.won() || (move != null && move.kind == Klondike.AUTO)) {
					celebrate(g);
				} else {
					System.out.println("Computer is done. Computer can go no further.");
					g.writeIntoGame();
					Game.print(0, false);
				}
				return;
			}
			int before = g.points;
			String said = g.describe(move);
			g.apply(move);
			int delta = g.points - before;
			System.out.print("Computer: " + said + ".");
			if (delta != 0) {
				System.out.print(" Points " + (delta > 0 ? "+" : "") + delta + ".");
			}
			System.out.println();
			if (g.won()) {
				celebrate(g);
				return;
			}
			g.writeIntoGame();
			Game.print(1, true);
			if (slow) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
		}
		System.out.println("Computer is done. Computer can go no further.");
		g.writeIntoGame();
		Game.print(0, false);
		} finally {
			Game.revealHiddenTableau = false;
		}
	}

	static void hintCli() {
		Klondike g = Klondike.fromGame();
		Klondike.Move move = choose(g);
		if (move == null) {
			Game.printNotice("This is going to be a hard game.");
		} else {
			Game.printNotice("Hint: " + g.describe(move) + ".");
		}
		Game.print(0, false);
	}

	private static void celebrate(Klondike g) {
		g.finish();
		g.writeIntoGame();
		Game.print(0, true);
	}

	/**
	 * Next legal move. Public to the browser port's Java twin and the CLI hint.
	 */
	/** Fast legal policy used when a sampled search is not worth it, and as a fallback. */
	static Klondike.Move heuristic(Klondike g) {
		Klondike.Move reveal = bestReveal(g);
		if (reveal != null) {
			return reveal;
		}
		return legacyStep(g);
	}

	static Klondike.Move choose(Klondike g) {
		return new StrongAI(g.visibleHash()).choose(g);
	}

	/** Uncover the largest buried pile, building onto the pile that hides the most cards. */
	private static Klondike.Move bestReveal(Klondike g) {
		Klondike.Move best = null;
		int bestScore = -1;
		for (int col = 0; col < 7; col++) {
			if (g.dn[col] == 0 || g.dn[col] >= g.pn[col]) {
				continue;
			}
			int index = g.dn[col];
			int card = g.pile[col][index];
			for (int to = 0; to < 7; to++) {
				if (to == col || !g.canPlace(card, to)) {
					continue;
				}
				int score = g.dn[col] * 100 + g.dn[to] + (g.dn[col] == 1 ? singletonBonus : 0)
						- (g.dn[to] == 0 ? openPilePenalty : 0);
				if (score > bestScore) {
					bestScore = score;
					best = new Klondike.Move(Klondike.TO_TAB, col, index, to);
				}
			}
		}
		return best;
	}

	/**
	 * Hint-bot order, minus the moves {@link #bestReveal} already covers and
	 * minus the 75-point resignation. King slides that only swap empty columns
	 * are skipped.
	 */
	private static Klondike.Move legacyStep(Klondike g) {
		for (int pile = 0; pile < 7; pile++) {
			for (int index = g.dn[pile]; index < g.pn[pile]; index++) {
				if (index > 0 && index - 1 >= g.dn[pile]) {
					continue;
				}
				for (int to = 0; to < 7; to++) {
					if (g.canPlace(g.pile[pile][index], to) && !g.uselessKingSlide(pile, index, to)) {
						return new Klondike.Move(Klondike.TO_TAB, pile, index, to);
					}
				}
			}
		}
		if (g.wn > 0) {
			int card = g.wasteTop();
			// High waste cards are poor early builds; keep drawing until the stock is known.
			boolean hold = !g.stockKnown && Klondike.rank(card) >= holdWasteRank;
			if (!hold) {
				for (int to = 0; to < 7; to++) {
					if (g.canPlace(card, to)) {
						return new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to);
					}
				}
			}
		}
		for (int col = 0; col < 7; col++) {
			if (g.pn[col] > 0 && g.canFound(g.tail(col))) {
				return new Klondike.Move(Klondike.TO_FOUND, col, g.pn[col] - 1, Klondike.suit(g.tail(col)));
			}
		}
		Klondike.Move split = freeFoundation(g);
		if (split != null) {
			return split;
		}
		if (g.wn > 0 && g.canFound(g.wasteTop())) {
			return new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(g.wasteTop()));
		}
		Klondike.Move worry = worryForWaste(g);
		if (worry != null) {
			return worry;
		}
		if (g.canDraw()) {
			return Klondike.Move.draw();
		}
		return null;
	}

	/** Move the cards sitting on a foundation-ready card so it can be built next. */
	private static Klondike.Move freeFoundation(Klondike g) {
		for (int col = 0; col < 7; col++) {
			for (int index = g.dn[col]; index < g.pn[col] - 1; index++) {
				if (!g.canFound(g.pile[col][index])) {
					continue;
				}
				int run = g.pile[col][index + 1];
				for (int to = 0; to < 7; to++) {
					if (to != col && g.canPlace(run, to) && !g.uselessKingSlide(col, index + 1, to)) {
						return new Klondike.Move(Klondike.TO_TAB, col, index + 1, to);
					}
				}
			}
		}
		return null;
	}

	/** Pull a foundation card down only when the waste card can then sit on it. */
	private static Klondike.Move worryForWaste(Klondike g) {
		if (g.wn == 0) {
			return null;
		}
		int drawn = g.wasteTop();
		for (int suit = 0; suit < 4; suit++) {
			if (g.found[suit] == 0) {
				continue;
			}
			int top = suit * 13 + g.found[suit] - 1;
			if (Klondike.rank(drawn) + 1 != Klondike.rank(top) || Klondike.red(drawn) == Klondike.red(top)) {
				continue;
			}
			for (int to = 0; to < 7; to++) {
				if (!g.canPlace(top, to)) {
					continue;
				}
				Klondike copy = g.copy();
				copy.apply(new Klondike.Move(Klondike.TO_TAB, Klondike.FOUND, suit, to));
				if (copy.wn > 0 && copy.canPlace(copy.wasteTop(), to)) {
					return new Klondike.Move(Klondike.TO_TAB, Klondike.FOUND, suit, to);
				}
			}
		}
		return null;
	}
}
