import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Upper bound if every reasonable move can be tried and a winning one kept. */
public class WideBound {
	public static void main(String[] args) {
		int n = Integer.parseInt(args[0]);
		int blind = 0;
		int proved = 0;
		int unknown = 0;
		long t0 = System.nanoTime();
		for (int seed = 1; seed <= n; seed++) {
			Klondike g = Klondike.deal(seed);
			if (play(g.copy(), false)) {
				blind++;
			}
			int r = new Search(LEGAL_NODE_LIMIT).go(g);
			if (r == 1) {
				proved++;
			} else if (r < 0) {
				unknown++;
			}
			if (seed % 5 == 0) {
				System.out.printf("seed %d blind %d proved %d unknown %d%n", seed, blind, proved, unknown);
			}
		}
		double sec = (System.nanoTime() - t0) / 1e9;
		System.out.printf("blind %d/%d proved %d unknown %d in %.1fs%n", blind, n, proved, unknown, sec);
	}

	static boolean play(Klondike g, boolean ignore) {
		HashSet<Long> seen = new HashSet<Long>();
		for (int i = 0; i < 4000; i++) {
			if (g.won()) {
				return true;
			}
			if (!seen.add(g.hash())) {
				return false;
			}
			Klondike.Move m = SmartAI.heuristic(g);
			if (m == null) {
				return false;
			}
			g.apply(m);
		}
		return g.won();
	}

	static List<Klondike.Move> options(Klondike g) {
		List<Klondike.Move> revs = StrongAI.reveals(g);
		if (!revs.isEmpty()) {
			return revs;
		}
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
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
			if (g.pn[c] > 0 && g.safeFound(g.tail(c))) {
				ms.add(new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c))));
			}
		}
		if (g.canDraw()) {
			ms.add(Klondike.Move.draw());
		}
		if (ms.isEmpty()) {
			Klondike.Move h = SmartAI.heuristic(g);
			if (h != null) {
				ms.add(h);
			}
		}
		return ms;
	}

	/** Player budget. Warp passes a larger limit. */
	static final int LEGAL_NODE_LIMIT = 25000;

	/** The position last passed to {@link #winningMove}. Tests use this to see which layout was searched. */
	static Klondike lastRoot;

	/** First proved winning move, otherwise the heuristic. Sees whatever cards are in {@code g}. */
	static Klondike.Move winningMove(Klondike g) {
		return winningMove(g, LEGAL_NODE_LIMIT);
	}

	static Klondike.Move winningMove(Klondike g, int nodeLimit) {
		lastRoot = g;
		if (g.won()) {
			return Klondike.Move.auto();
		}
		List<Klondike.Move> opts = orderedOptions(g);
		for (int i = 0; i < opts.size(); i++) {
			Klondike copy = g.copy();
			copy.apply(opts.get(i));
			if (new Search(nodeLimit).go(copy) == 1) {
				return opts.get(i);
			}
		}
		return SmartAI.heuristic(g);
	}

	/** Outcome of trying every root move in {@code g} with an independent node budget. */
	static final class RootEval {
		final List<Klondike.Move> opts;
		final int[] result;

		RootEval(List<Klondike.Move> opts, int[] result) {
			this.opts = opts;
			this.result = result;
		}
	}

	/**
	 * For each root option, search the child with a fresh {@code nodeLimit} budget.
	 * {@code result[i]} is 1 if that move is a proved win, 0 if a proved loss, or
	 * -1 if the budget ran out. Independent budgets keep later moves from being
	 * starved when an earlier option exhausts a shared pool without proving.
	 */
	static RootEval evaluateRoots(Klondike g, int nodeLimit) {
		lastRoot = g;
		List<Klondike.Move> opts = orderedOptions(g);
		int[] result = new int[opts.size()];
		for (int i = 0; i < opts.size(); i++) {
			Klondike copy = g.copy();
			copy.apply(opts.get(i));
			result[i] = new Search(nodeLimit).go(copy);
		}
		return new RootEval(opts, result);
	}

	/** Default Warp/Player search order: heuristic first, then {@link #options}. */
	static final int ORDER_HEURISTIC = 0;
	/** Heuristic first, remaining options reversed. */
	static final int ORDER_REVERSE_REST = 1;
	/** Raw {@link #options} order, no heuristic bump. */
	static final int ORDER_AS_IS = 2;
	/** Foundation builds, then tableau, then draws. */
	static final int ORDER_FOUNDATION = 3;
	/** Deterministic shuffle of options (seed 1), no heuristic bump. */
	static final int ORDER_SHUFFLE1 = 4;
	/** Deterministic shuffle of options (seed 2), no heuristic bump. */
	static final int ORDER_SHUFFLE2 = 5;

	static List<Klondike.Move> orderedOptions(Klondike g) {
		return orderedOptions(g, ORDER_HEURISTIC);
	}

	static List<Klondike.Move> orderedOptions(Klondike g, int style) {
		List<Klondike.Move> opts = options(g);
		if (style == ORDER_AS_IS) {
			return opts;
		}
		if (style == ORDER_FOUNDATION) {
			return byKind(opts);
		}
		if (style == ORDER_SHUFFLE1 || style == ORDER_SHUFFLE2) {
			long salt = style == ORDER_SHUFFLE1 ? 1L : 2L;
			shuffleMoves(opts, new Random(g.hash() ^ (salt * 0x9E3779B97F4A7C15L)));
			return opts;
		}
		opts = preferHeuristic(g, opts);
		if (style == ORDER_REVERSE_REST && opts.size() > 2) {
			int i = 1;
			int j = opts.size() - 1;
			while (i < j) {
				Klondike.Move tmp = opts.get(i);
				opts.set(i, opts.get(j));
				opts.set(j, tmp);
				i++;
				j--;
			}
		}
		return opts;
	}

	private static List<Klondike.Move> preferHeuristic(Klondike g, List<Klondike.Move> opts) {
		Klondike.Move prefer = SmartAI.heuristic(g);
		if (prefer == null) {
			return opts;
		}
		for (int i = 0; i < opts.size(); i++) {
			Klondike.Move m = opts.get(i);
			if (m.kind == prefer.kind && m.from == prefer.from && m.index == prefer.index && m.to == prefer.to) {
				opts.remove(i);
				opts.add(0, prefer);
				return opts;
			}
		}
		opts.add(0, prefer);
		return opts;
	}

	private static List<Klondike.Move> byKind(List<Klondike.Move> opts) {
		List<Klondike.Move> found = new ArrayList<Klondike.Move>();
		List<Klondike.Move> tab = new ArrayList<Klondike.Move>();
		List<Klondike.Move> other = new ArrayList<Klondike.Move>();
		for (int i = 0; i < opts.size(); i++) {
			Klondike.Move m = opts.get(i);
			if (m.kind == Klondike.TO_FOUND) {
				found.add(m);
			} else if (m.kind == Klondike.TO_TAB) {
				tab.add(m);
			} else {
				other.add(m);
			}
		}
		found.addAll(tab);
		found.addAll(other);
		return found;
	}

	private static void shuffleMoves(List<Klondike.Move> opts, Random rng) {
		for (int i = opts.size() - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			Klondike.Move tmp = opts.get(i);
			opts.set(i, opts.get(j));
			opts.set(j, tmp);
		}
	}

	/**
	 * First proved winning move under the given option order, or null if the
	 * budget cannot prove a win. Unlike {@link #winningMove}, this does not
	 * fall back to the heuristic.
	 */
	static Klondike.Move winningMoveProved(Klondike g, int nodeLimit, int style) {
		lastRoot = g;
		if (g.won()) {
			return Klondike.Move.auto();
		}
		List<Klondike.Move> opts = orderedOptions(g, style);
		for (int i = 0; i < opts.size(); i++) {
			Klondike copy = g.copy();
			copy.apply(opts.get(i));
			if (new Search(nodeLimit, style).go(copy) == 1) {
				return opts.get(i);
			}
		}
		return null;
	}

	static final class Search {
		final HashMap<Long, Integer> memo = new HashMap<Long, Integer>();
		final HashSet<Long> stack = new HashSet<Long>();
		final int nodeLimit;
		final int style;
		int nodes;

		Search(int nodeLimit) {
			this(nodeLimit, ORDER_HEURISTIC);
		}

		Search(int nodeLimit, int style) {
			this.nodeLimit = nodeLimit;
			this.style = style;
		}

		int go(Klondike g) {
			return dfs(g);
		}

		int dfs(Klondike g) {
			if (g.won()) {
				return 1;
			}
			if (nodes++ > nodeLimit) {
				return -1;
			}
			long h = g.hash();
			Integer known = memo.get(h);
			if (known != null) {
				return known;
			}
			if (!stack.add(h)) {
				return 0;
			}
			List<Klondike.Move> opts = orderedOptions(g, style);
			if (opts.isEmpty()) {
				stack.remove(h);
				memo.put(h, 0);
				return 0;
			}
			boolean unknown = false;
			for (int i = 0; i < opts.size(); i++) {
				Klondike.Undo u = g.apply(opts.get(i));
				int r = dfs(g);
				g.undo(u);
				if (r == 1) {
					stack.remove(h);
					memo.put(h, 1);
					return 1;
				}
				if (r < 0) {
					unknown = true;
				}
			}
			stack.remove(h);
			if (!unknown) {
				memo.put(h, 0);
				return 0;
			}
			return -1;
		}
	}
}
