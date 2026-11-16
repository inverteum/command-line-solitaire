/**
 * Faithful port of {@link Game#hint(int)} for headless win-rate measurement.
 * Same priorities, same king-slide block, same 75-point failsafe, and the same
 * lack of autocomplete. A win is four completed foundations, not merely a
 * position {@link Game#winnable()} would offer to finish.
 */
final class LegacyHintAI {

	private final boolean[] kingsPlaced = new boolean[4];
	private final java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
	private int highestScore;
	private int steps;
	/** When set, keep going past the 75-point failsafe and stop on a repeated position. */
	boolean relaxed;
	String reason = "";

	boolean win(Klondike g) {
		kingsPlaced[0] = kingsPlaced[1] = kingsPlaced[2] = kingsPlaced[3] = false;
		highestScore = 0;
		steps = 0;
		seen.clear();
		return hint(g);
	}

	private boolean hint(Klondike g) {
		if (steps++ > 8000) {
			reason = "limit";
			return false;
		}
		if (g.foundationsComplete() || (relaxed && g.won())) {
			reason = "win";
			return true;
		}
		if (relaxed && !seen.add(g.hash())) {
			reason = "cycle";
			return false;
		}
		if (g.points > highestScore) {
			highestScore = g.points;
		}
		if (!relaxed && g.points < highestScore - 75) {
			reason = "failsafe";
			return false;
		}

		for (int pile = 0; pile < 7; pile++) {
			for (int cardIndex = 0; cardIndex < g.pn[pile]; cardIndex++) {
				if (cardIndex < g.dn[pile]) {
					continue;
				}
				for (int pile2 = 0; pile2 < 7; pile2++) {
					if (tableauToTableau(g, pile, cardIndex, pile2, false, 0)) {
						return hint(g);
					}
				}
			}
		}

		if (g.wn > 0) {
			for (int pile = 0; pile < 7; pile++) {
				if (tableauToTableau(g, Klondike.WASTE, -1, pile, false, 1)) {
					return hint(g);
				}
			}
		}

		for (int pile = 0; pile < 7; pile++) {
			if (g.pn[pile] == 0) {
				continue;
			}
			if (tableauToFoundation(g, pile)) {
				return hint(g);
			}
		}

		for (int pile = 0; pile < 7; pile++) {
			for (int cardIndex = 0; cardIndex < g.pn[pile] - 1; cardIndex++) {
				if (cardIndex < g.dn[pile]) {
					continue;
				}
				int card = g.pile[pile][cardIndex];
				if (!g.canFound(card)) {
					continue;
				}
				for (int pile1 = 0; pile1 < 7; pile1++) {
					if (tableauToTableau(g, pile, cardIndex + 1, pile1, true, 0)) {
						int where = find(g, card);
						if (where >= 0 && g.pn[where] > 0 && g.tail(where) == card && g.canFound(card)) {
							g.apply(new Klondike.Move(Klondike.TO_FOUND, where, g.pn[where] - 1, Klondike.suit(card)));
							return hint(g);
						}
					}
				}
			}
		}

		if (g.wn > 0 && g.canFound(g.wasteTop())) {
			int card = g.wasteTop();
			g.apply(new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(card)));
			return hint(g);
		}

		if (g.wn > 0) {
			int drawn = g.wasteTop();
			for (int s = 0; s < 4; s++) {
				if (g.found[s] == 0) {
					continue;
				}
				int top = s * 13 + g.found[s] - 1;
				if (!canSit(drawn, top)) {
					continue;
				}
				boolean movedOff = false;
				for (int pile = 0; pile < 7; pile++) {
					if (!g.canPlace(top, pile)) {
						continue;
					}
					// The CLI AI peeks an empty foundation if a second pile also fits
					// after the card has already been moved. That crashes the deal.
					if (movedOff) {
						reason = "crash";
						return false;
					}
					g.apply(new Klondike.Move(Klondike.TO_TAB, Klondike.FOUND, s, pile));
					movedOff = true;
					if (g.wn > 0 && g.canPlace(g.wasteTop(), pile)) {
						g.apply(new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, pile));
						return hint(g);
					}
				}
			}
		}

		if (g.canDraw()) {
			g.apply(Klondike.Move.draw());
			return hint(g);
		}
		reason = g.foundationsComplete() ? "win" : "stuck";
		return g.foundationsComplete();
	}

	private boolean tableauToTableau(Klondike g, int from, int index, int to, boolean inner, int drawnKind) {
		int card;
		if (from == Klondike.WASTE) {
			if (g.wn == 0 || !g.canPlace(g.wasteTop(), to)) {
				return false;
			}
			card = g.wasteTop();
		} else {
			if (index < 0 || index >= g.pn[from] || !g.canPlace(g.pile[from][index], to)) {
				return false;
			}
			card = g.pile[from][index];
		}

		if (!inner) {
			if (g.pn[to] == 0) {
				if (kingsPlaced[card % 4]) {
					return false;
				}
				kingsPlaced[card % 4] = true;
			} else if (drawnKind == 0 && index > 0 && index - 1 >= g.dn[from]) {
				return false;
			}
		}

		if (from == Klondike.WASTE) {
			g.apply(new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to));
		} else {
			g.apply(new Klondike.Move(Klondike.TO_TAB, from, index, to));
		}
		return true;
	}

	private boolean tableauToFoundation(Klondike g, int pile) {
		if (g.pn[pile] == 0) {
			return false;
		}
		int card = g.tail(pile);
		if (!g.canFound(card)) {
			return false;
		}
		g.apply(new Klondike.Move(Klondike.TO_FOUND, pile, g.pn[pile] - 1, Klondike.suit(card)));
		return true;
	}

	private static int find(Klondike g, int card) {
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < g.pn[c]; i++) {
				if (g.pile[c][i] == card) {
					return c;
				}
			}
		}
		return -1;
	}

	private static boolean canSit(int card, int onto) {
		return Klondike.rank(card) + 1 == Klondike.rank(onto) && Klondike.red(card) != Klondike.red(onto);
	}
}
