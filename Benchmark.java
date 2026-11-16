import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Headless deal generator and win-rate comparison for the two players. */
final class Benchmark {

	public static void main(String[] args) {
		if (args.length == 0 || args[0].equals("selftest")) {
			selfTest();
			if (args.length == 0 || args[0].equals("selftest")) {
				if (args.length == 0) {
					System.out.println("Self-test passed. Running a short comparison (40 deals)...");
					compare(40, 1);
				} else {
					System.out.println("Self-test passed.");
				}
			}
			return;
		}
		if (args[0].equals("legacy")) {
			int n = Integer.parseInt(args[1]);
			long startSeed = args.length > 2 ? Long.parseLong(args[2]) : 1L;
			runLegacy(n, startSeed);
			return;
		}
		if (args[0].equals("smart")) {
			int n = Integer.parseInt(args[1]);
			long startSeed = args.length > 2 ? Long.parseLong(args[2]) : 1L;
			runSmart(n, startSeed);
			return;
		}
		if (args[0].equals("trace")) {
			long seed = Long.parseLong(args[1]);
			Klondike g = Klondike.deal(seed);
			System.out.print("DECK");
			for (int c = 0; c < 7; c++) {
				for (int i = 0; i < g.pn[c]; i++) {
					System.out.print(" " + g.pile[c][i]);
				}
			}
			for (int i = 0; i < g.sn; i++) {
				System.out.print(" " + g.stock[i]);
			}
			System.out.println();
			int guard = 0;
			while (guard++ < 5000 && !g.won()) {
				Klondike.Move m = SmartAI.choose(g);
				if (m == null) {
					System.out.println("STOP " + g.faceDown());
					return;
				}
				System.out.println(m.kind + " " + m.from + " " + m.index + " " + m.to);
				g.apply(m);
			}
			System.out.println(g.won() ? "WIN " + g.points : "LOSS");
			return;
		}
		if (args[0].equals("compare")) {
			int n = Integer.parseInt(args[1]);
			long startSeed = args.length > 2 ? Long.parseLong(args[2]) : 1L;
			compare(n, startSeed);
			return;
		}
		System.out.println("Usage: java Benchmark selftest | legacy N [seed] | smart N [seed] | compare N [seed]");
	}

	static void selfTest() {
		Klondike empty = new Klondike();
		empty.found[0] = 0;
		if (!empty.safeFound(0)) {
			throw new AssertionError("ace of spades should be safe");
		}
		// Foundations at 8S, 8C, 7D, 9H: 10H is safe, JH is not (Blake & Gent).
		Klondike sample = new Klondike();
		sample.found[0] = 8;
		sample.found[1] = 8;
		sample.found[2] = 9;
		sample.found[3] = 7;
		int tenH = 2 * 13 + 9;
		if (!sample.safeFound(tenH)) {
			throw new AssertionError("10H should be a safe foundation build");
		}
		sample.found[2] = 10;
		int jackH = 2 * 13 + 10;
		if (sample.safeFound(jackH)) {
			throw new AssertionError("JH should not be a safe foundation build");
		}

		for (long seed = 1; seed <= 15; seed++) {
			Klondike g = Klondike.deal(seed);
			g.checkInvariants();
			if (g.faceDown() != 21 || g.sn != 24 || g.wn != 0) {
				throw new AssertionError("bad deal " + seed);
			}
			Random rnd = new Random(seed);
			for (int step = 0; step < 80; step++) {
				Klondike before = g.copy();
				List<Klondike.Move> moves = probeMoves(g);
				if (moves.isEmpty()) {
					break;
				}
				Klondike.Move m = moves.get(rnd.nextInt(moves.size()));
				Klondike.Undo u = g.apply(m);
				g.checkInvariants();
				g.undo(u);
				if (!g.same(before)) {
					throw new AssertionError("undo mismatch seed " + seed + " move " + m);
				}
			}
		}

		// Recycle puts the oldest waste card on top of the new stock.
		Klondike rec = Klondike.deal(3);
		int first = rec.stock[rec.sn - 1];
		while (rec.sn > 0) {
			rec.apply(Klondike.Move.draw());
		}
		if (rec.waste[0] != first) {
			throw new AssertionError("waste bottom should be the first drawn card");
		}
		int points = rec.points;
		rec.apply(Klondike.Move.draw());
		if (rec.points != points - 25) {
			throw new AssertionError("recycle should cost 25");
		}
		if (rec.wasteTop() != first) {
			throw new AssertionError("first card after a recycle should be the oldest waste card");
		}
		System.out.println("Self-test passed.");
	}

	private static List<Klondike.Move> probeMoves(Klondike g) {
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
		if (g.canDraw()) {
			ms.add(Klondike.Move.draw());
		}
		if (g.wn > 0) {
			int card = g.wasteTop();
			for (int to = 0; to < 7; to++) {
				if (g.canPlace(card, to)) {
					ms.add(new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to));
				}
			}
			if (g.canFound(card)) {
				ms.add(new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(card)));
			}
		}
		for (int c = 0; c < 7; c++) {
			if (g.pn[c] == 0) {
				continue;
			}
			if (g.canFound(g.tail(c))) {
				ms.add(new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c))));
			}
			for (int i = g.dn[c]; i < g.pn[c]; i++) {
				for (int to = 0; to < 7; to++) {
					if (to != c && g.canPlace(g.pile[c][i], to) && !g.uselessKingSlide(c, i, to)) {
						ms.add(new Klondike.Move(Klondike.TO_TAB, c, i, to));
					}
				}
			}
		}
		return ms;
	}

	static void runLegacy(int n, long startSeed) {
		int wins = 0;
		int exposed = 0;
		long t0 = System.nanoTime();
		for (int i = 0; i < n; i++) {
			long seed = startSeed + i;
			Klondike g = Klondike.deal(seed);
			boolean won = new LegacyHintAI().win(g);
			if (won) {
				wins++;
			} else if (g.won()) {
				exposed++;
			}
		}
		double sec = (System.nanoTime() - t0) / 1e9;
		System.out.printf("Legacy hint AI: %d/%d wins (%.1f%%), plus %d stuck in an autocomplete position, %.1fs, seeds %d-%d%n",
				wins, n, 100.0 * wins / n, exposed, sec, startSeed, startSeed + n - 1);
	}

	static void runSmart(int n, long startSeed) {
		int wins = 0;
		int buried = 0;
		int cycles = 0;
		int limit = 0;
		long t0 = System.nanoTime();
		for (int i = 0; i < n; i++) {
			long seed = startSeed + i;
			Klondike g = Klondike.deal(seed);
			SmartAI.Outcome out = SmartAI.play(g);
			if (out.won) {
				wins++;
			} else if ("cycle".equals(out.reason)) {
				cycles++;
			} else if ("limit".equals(out.reason)) {
				limit++;
			} else {
				buried++;
			}
		}
		double sec = (System.nanoTime() - t0) / 1e9;
		System.out.printf("Smart AI: %d/%d wins (%.1f%%) in %.1fs, seeds %d-%d%n",
				wins, n, 100.0 * wins / n, sec, startSeed, startSeed + n - 1);
		System.out.printf("  losses: %d repeated position, %d no legal move, %d move limit%n", cycles, buried, limit);
	}

	static void compare(int n, long startSeed) {
		System.out.println("Same " + n + " deals, seeds " + startSeed + " through " + (startSeed + n - 1) + ".");
		System.out.println("Rules: 1-card draw, unlimited redeals, autocomplete when the stock, waste,");
		System.out.println("and every face-down card are clear. Legacy does not take autocomplete.");
		runLegacy(n, startSeed);
		runSmart(n, startSeed);
	}
}
