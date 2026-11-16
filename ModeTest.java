/**
 * Warp searches the true hidden cards. Legal player searches a shuffled copy.
 */
public class ModeTest {
	public static void main(String[] args) {
		Klondike deal = Klondike.deal(9);
		WarpMode.choose(deal);
		if (WideBound.lastRoot != deal) {
			throw new AssertionError("Warp must search the real deal");
		}

		Klondike legalDeal = Klondike.deal(9);
		new StrongAI(9L).choose(legalDeal);
		if (WideBound.lastRoot == legalDeal) {
			throw new AssertionError("Legal player must not search the real hidden layout");
		}
		if (sameHidden(legalDeal, WideBound.lastRoot)) {
			throw new AssertionError("Legal sample matched the true hidden cards");
		}

		boolean differed = false;
		for (int seed = 1; seed <= 12 && !differed; seed++) {
			Klondike game = Klondike.deal(seed);
			java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
			for (int step = 0; step < 80 && !differed; step++) {
				if (game.won() || !seen.add(game.hash())) {
					break;
				}
				Klondike.Move blind = SmartAI.heuristic(game);
				Klondike.Move warp = WarpMode.choose(game);
				if (!same(blind, warp)) {
					Klondike swapped = game.copy();
					if (swapHidden(swapped)) {
						Klondike.Move after = WarpMode.choose(swapped);
						if (!same(warp, after)) {
							differed = true;
						}
					}
				}
				if (blind == null) {
					break;
				}
				game.apply(blind);
			}
		}
		if (!differed) {
			throw new AssertionError("Warp ignored a change to hidden cards");
		}
		System.out.println("ModeTest passed");
	}

	private static boolean swapHidden(Klondike g) {
		int firstPile = -1;
		int firstIndex = -1;
		for (int c = 0; c < 7; c++) {
			if (g.dn[c] > 0) {
				firstPile = c;
				firstIndex = 0;
				break;
			}
		}
		if (firstPile < 0) {
			return false;
		}
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < g.dn[c]; i++) {
				if (c == firstPile && i == firstIndex) {
					continue;
				}
				int tmp = g.pile[firstPile][firstIndex];
				g.pile[firstPile][firstIndex] = g.pile[c][i];
				g.pile[c][i] = tmp;
				return true;
			}
		}
		return false;
	}

	private static boolean sameHidden(Klondike a, Klondike b) {
		if (a.faceDown() != b.faceDown() || a.stockKnown != b.stockKnown) {
			return false;
		}
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < a.dn[c]; i++) {
				if (a.pile[c][i] != b.pile[c][i]) {
					return false;
				}
			}
		}
		if (!a.stockKnown) {
			for (int i = 0; i < a.sn; i++) {
				if (a.stock[i] != b.stock[i]) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean same(Klondike.Move a, Klondike.Move b) {
		if (a == null || b == null) {
			return a == b;
		}
		return a.kind == b.kind && a.from == b.from && a.index == b.index && a.to == b.to;
	}
}
