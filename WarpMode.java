import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Perfect-information player. It may read face-down tableau cards and undrawn
 * stock cards, then searches for a win from that true layout. While Warp is
 * playing, face-down tableau cards are shown.
 *
 * When the default 100,000-node heuristic-first search cannot prove a win,
 * it retries the same budget with complementary move orderings. Sampling
 * hidden cards does not apply: Warp already sees the true layout.
 */
final class WarpMode {

	/** Larger than the legal player's search so buried lines can be proved. */
	static final int NODE_LIMIT = 100000;

	/** Alternate search orders used only after the default order fails to prove. */
	static final int[] RETRY_ORDERS = {
			WideBound.ORDER_REVERSE_REST,
			WideBound.ORDER_AS_IS,
			WideBound.ORDER_FOUNDATION,
			WideBound.ORDER_SHUFFLE1,
			WideBound.ORDER_SHUFFLE2
	};

	/** When false, play is identical to the original 800/1000 Warp. */
	static boolean USE_ORDER_RESTARTS = true;

	private WarpMode() {
	}

	static Klondike.Move choose(Klondike real) {
		return choose(real, NODE_LIMIT);
	}

	static Klondike.Move choose(Klondike real, int nodeLimit) {
		if (real.won()) {
			return Klondike.Move.auto();
		}
		Klondike.Move proved = WideBound.winningMoveProved(real, nodeLimit, WideBound.ORDER_HEURISTIC);
		if (proved != null) {
			return proved;
		}
		if (USE_ORDER_RESTARTS) {
			for (int i = 0; i < RETRY_ORDERS.length; i++) {
				proved = WideBound.winningMoveProved(real, nodeLimit, RETRY_ORDERS[i]);
				if (proved != null) {
					return proved;
				}
			}
		}
		Klondike.Move fallback = SmartAI.heuristic(real);
		return fallback != null ? fallback : WideBound.winningMove(real, nodeLimit);
	}

	static boolean play(Klondike g) {
		return play(g, NODE_LIMIT);
	}

	static boolean play(Klondike g, int nodeLimit) {
		HashSet<Long> seen = new HashSet<Long>();
		for (int step = 0; step < 5000; step++) {
			if (g.won()) {
				g.finish();
				return true;
			}
			if (!seen.add(g.hash())) {
				return false;
			}
			Klondike.Move move = choose(g, nodeLimit);
			if (move == null || move.kind == Klondike.AUTO) {
				if (g.won()) {
					g.finish();
				}
				return g.won();
			}
			g.apply(move);
		}
		return g.won();
	}

	public static void main(String[] args) {
		int n = args.length > 0 ? Integer.parseInt(args[0]) : 40;
		int startSeed = 1;
		boolean parallel = true;
		for (int i = 1; i < args.length; i++) {
			if ("-seq".equals(args[i]) || "--sequential".equals(args[i])) {
				parallel = false;
			} else if ("baseline".equals(args[i]) || "norestart".equals(args[i])) {
				USE_ORDER_RESTARTS = false;
			} else if ("restart".equals(args[i])) {
				USE_ORDER_RESTARTS = true;
			} else if (!args[i].startsWith("-")) {
				startSeed = Integer.parseInt(args[i]);
			}
		}
		long started = System.nanoTime();
		int last = startSeed + n - 1;
		boolean[] wonAt = new boolean[n];
		if (!parallel || n < 4) {
			int wins = 0;
			for (int i = 0; i < n; i++) {
				wonAt[i] = play(Klondike.deal(startSeed + i));
				if (wonAt[i]) {
					wins++;
				}
				int done = i + 1;
				if (done % 10 == 0 || done == n) {
					double sec = (System.nanoTime() - started) / 1e9;
					System.out.printf("Warp %d/%d (%.1f%%) %.1fs%n", wins, done, 100.0 * wins / done, sec);
				}
			}
		} else {
			int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			AtomicInteger wins = new AtomicInteger();
			AtomicInteger done = new AtomicInteger();
			Future<?>[] futures = new Future<?>[n];
			for (int i = 0; i < n; i++) {
				final int idx = i;
				final int seed = startSeed + i;
				futures[i] = pool.submit(() -> {
					boolean won = play(Klondike.deal(seed));
					wonAt[idx] = won;
					if (won) {
						wins.incrementAndGet();
					}
					int d = done.incrementAndGet();
					if (d % 10 == 0 || d == n) {
						int w = wins.get();
						double sec = (System.nanoTime() - started) / 1e9;
						System.out.printf("Warp %d/%d (%.1f%%) %.1fs%n", w, d, 100.0 * w / d, sec);
					}
				});
			}
			pool.shutdown();
			try {
				for (int i = 0; i < n; i++) {
					futures[i].get();
				}
			} catch (Exception e) {
				pool.shutdownNow();
				throw new RuntimeException(e);
			}
		}
		int wins = 0;
		for (int i = 0; i < n; i++) {
			if (wonAt[i]) {
				wins++;
			}
		}
		double sec = (System.nanoTime() - started) / 1e9;
		System.out.printf("Warp %d/%d (%.1f%%) in %.1fs (seeds %d-%d, restarts=%s)%n",
				wins, n, 100.0 * wins / n, sec, startSeed, last, USE_ORDER_RESTARTS);
	}
}
