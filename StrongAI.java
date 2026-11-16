import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * Legal player. It never reads a face-down card or an unseen stock card on the
 * real deal. When several moves are possible it samples deals consistent with
 * what it has seen and counts only proved wins (search shows a forced win after
 * that candidate in that sample). Losing or unproved samples cannot outvote a
 * move that proved a win elsewhere. Fully visible positions are searched
 * directly. {@link #chooseOneSample} is the older one-sample 52.9% path.
 */
final class StrongAI {

	private static final int SAMPLES = 20;
	private static final int SWITCH_MARGIN = 3;
	private static final boolean USE_EXACT = false;
	private static final int ORACLE_STEPS = 180;
	private static final int EXACT_BUDGET = 120000;

	/** When false, {@link #choose} uses the original one-sample Legal path. */
	static boolean USE_VOTING = true;
	/** Shuffled hidden layouts voted on when hidden cards remain. Tuned on a small seed set. */
	static int VOTE_SAMPLES = 8;
	/** Independent per-move, per-sample node budget for proved-win searches. */
	static int VOTE_NODE_LIMIT = 6000;

	/**
	 * V2 Legal options. Default off so {@code legal} / {@code vote:8:6000} still
	 * reproduce the 568/1000 player until a v2 config beats that full-set result.
	 */
	static boolean USE_V2 = false;
	/** 568/1000 first pass plus extras that do not consume the instance rng. */
	static boolean USE_PLUS = false;
	/** Keep layouts that still match every card revealed so far; never peek at remaining hidden cards. */
	static boolean USE_PARTICLES = false;
	/** If no sample proves a win, score candidates by rollout progress across samples instead of one shuffle. */
	static boolean USE_PROGRESS_FALLBACK = false;
	/** Add extra samples only when the proved-win vote is tied/close or empty. */
	static boolean USE_ADAPTIVE = false;
	static int EXTRA_SAMPLES = 8;
	/** When hidden cards are at most this many, enumerate every layout. */
	static int ENUM_HIDDEN = 4;
	/** If no prove after the first pass, retry the same layouts at this budget (0 = skip). */
	static int SECOND_PASS_LIMIT = 0;
	/** Node budget when the position is fully visible. */
	static int VISIBLE_NODE_LIMIT = WideBound.LEGAL_NODE_LIMIT;

	private final Random rng;
	private final long agentSeed;
	private int switches;
	/** Posterior samples of the current hidden slots; identities never read from the true deal. */
	private final ArrayList<int[]> particles = new ArrayList<int[]>();

	StrongAI(long seed) {
		agentSeed = seed;
		rng = new Random(seed);
	}

	static final class Outcome {
		final boolean won;
		final int moves;

		Outcome(boolean won, int moves) {
			this.won = won;
			this.moves = moves;
		}
	}

	Outcome play(Klondike g) {
		HashSet<Long> seen = new HashSet<Long>();
		int moves = 0;
		particles.clear();
		while (moves < 5000) {
			if (g.won()) {
				g.finish();
				return new Outcome(true, moves);
			}
			long h = g.hash();
			if (!seen.add(h)) {
				return new Outcome(false, moves);
			}
			Klondike before = USE_PARTICLES ? g.copy() : null;
			Klondike.Move move = choose(g);
			if (move == null) {
				return new Outcome(false, moves);
			}
			g.apply(move);
			if (USE_PARTICLES && before != null) {
				filterParticles(before, move, g);
			}
			moves++;
		}
		return new Outcome(false, moves);
	}

	Klondike.Move choose(Klondike real) {
		if (USE_V2) {
			return chooseVotingV2(real);
		}
		if (USE_VOTING) {
			return chooseVoting(real);
		}
		return chooseOneSample(real);
	}

	/**
	 * Original Legal path that won 529/1000 (52.9%): one shuffled sample, then
	 * {@link WideBound#winningMove} with {@link WideBound#LEGAL_NODE_LIMIT}.
	 */
	Klondike.Move chooseOneSample(Klondike real) {
		if (real.won()) {
			return Klondike.Move.auto();
		}
		if (real.hiddenCount() <= 0) {
			Klondike.Move seen = WideBound.winningMove(real);
			return seen != null ? seen : SmartAI.heuristic(real);
		}
		int hidden = real.hiddenCount();
		int[] base = new int[hidden];
		real.fillHidden(base);
		int[] perm = base.clone();
		shuffle(perm);
		Klondike world = real.copy();
		world.assignHidden(perm);
		Klondike.Move advised = WideBound.winningMove(world);
		return advised != null ? advised : SmartAI.heuristic(real);
	}

	/**
	 * Multi-sample Legal path. Hidden cards are completed only from the unseen
	 * remainder (the Legal player already knows those identities by elimination);
	 * the true slot assignment is shuffled away and never searched. Each
	 * candidate is scored by how many samples it proves as a forced win.
	 */
	Klondike.Move chooseVoting(Klondike real) {
		if (real.won()) {
			return Klondike.Move.auto();
		}
		if (real.hiddenCount() <= 0) {
			int budget = USE_VOTING && VISIBLE_NODE_LIMIT > 0 ? VISIBLE_NODE_LIMIT : WideBound.LEGAL_NODE_LIMIT;
			// Default VISIBLE_NODE_LIMIT is LEGAL_NODE_LIMIT, so legal/vote:8:6000 stay at 25k.
			Klondike.Move seen = WideBound.winningMove(real, budget);
			return seen != null ? seen : SmartAI.heuristic(real);
		}
		List<Klondike.Move> opts = WideBound.orderedOptions(real);
		if (opts.isEmpty()) {
			return SmartAI.heuristic(real);
		}
		int hidden = real.hiddenCount();
		int[] base = new int[hidden];
		real.fillHidden(base);
		int cap = (USE_V2 || USE_PLUS) ? ENUM_HIDDEN : 4;
		int rounds;
		boolean enumerate = hidden <= cap;
		if (enumerate) {
			rounds = factorial(hidden);
		} else {
			rounds = VOTE_SAMPLES;
		}
		int[] proved = new int[opts.size()];
		int[] unknown = new int[opts.size()];
		int[] lost = new int[opts.size()];
		int[] progress = new int[opts.size()];
		ArrayList<int[]> worlds = new ArrayList<int[]>();
		int examined = 0;
		for (int r = 0; r < rounds; r++) {
			int[] perm = new int[hidden];
			if (enumerate) {
				permutation(r, base, perm);
			} else {
				System.arraycopy(base, 0, perm, 0, hidden);
				shuffle(perm);
			}
			worlds.add(perm);
			scoreOneWorld(real, opts, perm, VOTE_NODE_LIMIT, proved, unknown, lost, progress);
			examined++;
			if (!enumerate && clearProvedLeader(proved, examined, rounds)) {
				break;
			}
		}
		// Add-ons use only already-drawn layouts or a visible-hash RNG, so they
		// do not consume the instance rng. If they leave the decision unchanged,
		// chooseOneSample and later moves stay identical to the 568/1000 player.
		if (USE_PLUS) {
			if (!enumerate && USE_ADAPTIVE && EXTRA_SAMPLES > 0 && needsAdaptive(proved)) {
				for (int i = 0; i < EXTRA_SAMPLES; i++) {
					int[] perm = makeSample(real, base, VOTE_SAMPLES + i, false);
					worlds.add(perm);
					scoreOneWorld(real, opts, perm, VOTE_NODE_LIMIT, proved, unknown, lost, progress);
				}
			}
			int bestNow = bestProved(proved);
			if (proved[bestNow] == 0 && SECOND_PASS_LIMIT > VOTE_NODE_LIMIT) {
				int[] proved2 = new int[opts.size()];
				int[] unknown2 = new int[opts.size()];
				int[] lost2 = new int[opts.size()];
				int[] progress2 = new int[opts.size()];
				for (int r = 0; r < worlds.size(); r++) {
					scoreOneWorld(real, opts, worlds.get(r), SECOND_PASS_LIMIT, proved2, unknown2, lost2, progress2);
				}
				if (proved2[bestProved(proved2)] > 0) {
					proved = proved2;
					unknown = unknown2;
					lost = lost2;
					progress = progress2;
				}
			}
		}
		int best = bestProved(proved);
		if (proved[best] > 0) {
			return opts.get(best);
		}
		if (USE_PLUS && USE_PROGRESS_FALLBACK) {
			return opts.get(bestProgress(progress, lost, unknown));
		}
		return chooseOneSample(real);
	}

	private void scoreOneWorld(Klondike real, List<Klondike.Move> opts, int[] perm, int budget,
			int[] proved, int[] unknown, int[] lost, int[] progress) {
		Klondike world = real.copy();
		world.assignHidden(perm);
		WideBound.RootEval ev = WideBound.evaluateRoots(world, budget);
		for (int i = 0; i < opts.size(); i++) {
			int found = provedIndex(ev, opts.get(i));
			int result = found < 0 ? -1 : ev.result[found];
			if (result == 1) {
				proved[i]++;
			} else if (result == 0) {
				lost[i]++;
			} else {
				unknown[i]++;
			}
			if (USE_PROGRESS_FALLBACK && result != 0) {
				Klondike roll = real.copy();
				roll.assignHidden(perm.clone());
				progress[i] += rollout(roll, opts.get(i));
			}
		}
	}

	private static int provedIndex(WideBound.RootEval ev, Klondike.Move want) {
		for (int i = 0; i < ev.opts.size(); i++) {
			if (sameMove(ev.opts.get(i), want)) {
				return i;
			}
		}
		return -1;
	}

	/** True when one move's proved-win count cannot be caught by the remaining samples. */
	private static boolean clearProvedLeader(int[] proved, int examined, int rounds) {
		int best = 0;
		int second = 0;
		for (int i = 0; i < proved.length; i++) {
			if (proved[i] > best) {
				second = best;
				best = proved[i];
			} else if (proved[i] > second) {
				second = proved[i];
			}
		}
		if (best <= 0) {
			return false;
		}
		int remaining = rounds - examined;
		return best > second + remaining;
	}

	/**
	 * Stronger Legal path: optional consistent particles, adaptive extra samples
	 * on close or empty votes, a deeper second pass, and a progress vote when
	 * nothing is proved. Hidden cards are never read except as an unseen
	 * multiset that is then shuffled or filtered against newly visible cards.
	 */
	Klondike.Move chooseVotingV2(Klondike real) {
		if (real.won()) {
			return Klondike.Move.auto();
		}
		if (real.hiddenCount() <= 0) {
			Klondike.Move seen = WideBound.winningMove(real, VISIBLE_NODE_LIMIT);
			return seen != null ? seen : SmartAI.heuristic(real);
		}
		List<Klondike.Move> opts = WideBound.orderedOptions(real);
		if (opts.isEmpty()) {
			return SmartAI.heuristic(real);
		}
		int hidden = real.hiddenCount();
		int[] base = new int[hidden];
		real.fillHidden(base);
		boolean enumerate = hidden <= ENUM_HIDDEN;
		int planned = enumerate ? factorial(hidden) : VOTE_SAMPLES;
		ArrayList<int[]> worlds = new ArrayList<int[]>();
		if (USE_PARTICLES && !enumerate) {
			keepMatchingParticles(hidden);
			for (int i = 0; i < particles.size() && worlds.size() < planned; i++) {
				worlds.add(particles.get(i).clone());
			}
		}
		while (worlds.size() < planned) {
			worlds.add(makeSample(real, base, worlds.size(), enumerate));
			if (USE_PARTICLES && !enumerate) {
				particles.add(worlds.get(worlds.size() - 1).clone());
			}
		}
		int[] proved = new int[opts.size()];
		int[] unknown = new int[opts.size()];
		int[] lost = new int[opts.size()];
		int[] progress = new int[opts.size()];
		int examined = scoreWorlds(real, opts, worlds, 0, worlds.size(), VOTE_NODE_LIMIT,
				proved, unknown, lost, progress, !enumerate);

		if (!enumerate && USE_ADAPTIVE && EXTRA_SAMPLES > 0 && needsAdaptive(proved)) {
			int from = worlds.size();
			for (int i = 0; i < EXTRA_SAMPLES; i++) {
				worlds.add(makeSample(real, base, worlds.size(), false));
				if (USE_PARTICLES) {
					particles.add(worlds.get(worlds.size() - 1).clone());
				}
			}
			scoreWorlds(real, opts, worlds, from, worlds.size(), VOTE_NODE_LIMIT,
					proved, unknown, lost, progress, true);
		}

		int best = bestProved(proved);
		if (proved[best] == 0 && SECOND_PASS_LIMIT > VOTE_NODE_LIMIT) {
			int[] proved2 = new int[opts.size()];
			int[] unknown2 = new int[opts.size()];
			int[] lost2 = new int[opts.size()];
			int[] progress2 = new int[opts.size()];
			scoreWorlds(real, opts, worlds, 0, worlds.size(), SECOND_PASS_LIMIT,
					proved2, unknown2, lost2, progress2, false);
			if (proved2[bestProved(proved2)] > 0) {
				proved = proved2;
				unknown = unknown2;
				lost = lost2;
				progress = progress2;
				best = bestProved(proved);
			}
		}

		if (proved[best] > 0) {
			return opts.get(best);
		}
		if (USE_PROGRESS_FALLBACK && worlds.size() > 0) {
			return opts.get(bestProgress(progress, lost, unknown));
		}
		// Deterministic one-sample 25k search on the first layout, not a new rng shuffle.
		Klondike fallback = real.copy();
		fallback.assignHidden(worlds.size() > 0 ? worlds.get(0) : makeSample(real, base, 0, enumerate));
		Klondike.Move advised = WideBound.winningMove(fallback);
		return advised != null ? advised : SmartAI.heuristic(real);
	}

	/** A hidden layout consistent with the visible cards. Seeded only from visible state. */
	private int[] makeSample(Klondike real, int[] base, int index, boolean enumerate) {
		int[] perm = new int[base.length];
		if (enumerate) {
			permutation(index, base, perm);
		} else {
			System.arraycopy(base, 0, perm, 0, base.length);
			shuffleWith(perm, new Random(sampleSeed(real, index)));
		}
		return perm;
	}

	private long sampleSeed(Klondike real, int index) {
		return agentSeed * 0xD1B54A32D192ED03L
				^ real.visibleHash() * 0x9E3779B97F4A7C15L
				^ ((index + 1L) * 0x2127599BF432BE03L);
	}

	private static void shuffleWith(int[] ids, Random source) {
		for (int i = ids.length - 1; i > 0; i--) {
			int j = source.nextInt(i + 1);
			int tmp = ids[i];
			ids[i] = ids[j];
			ids[j] = tmp;
		}
	}

	private int scoreWorlds(Klondike real, List<Klondike.Move> opts, List<int[]> worlds, int from, int to,
			int budget, int[] proved, int[] unknown, int[] lost, int[] progress, boolean earlyStop) {
		int examined = from;
		for (int r = from; r < to; r++) {
			Klondike world = real.copy();
			world.assignHidden(worlds.get(r));
			WideBound.RootEval ev = WideBound.evaluateRoots(world, budget);
			for (int i = 0; i < opts.size(); i++) {
				int found = provedIndex(ev, opts.get(i));
				int result = found < 0 ? -1 : ev.result[found];
				if (result == 1) {
					proved[i]++;
				} else if (result == 0) {
					lost[i]++;
				} else {
					unknown[i]++;
				}
				if (USE_PROGRESS_FALLBACK && result != 0) {
					Klondike roll = real.copy();
					roll.assignHidden(worlds.get(r).clone());
					progress[i] += rollout(roll, opts.get(i));
				}
			}
			examined = r + 1;
			if (earlyStop && clearProvedLeader(proved, examined, to)) {
				break;
			}
		}
		return examined;
	}

	private static boolean needsAdaptive(int[] proved) {
		int best = bestProved(proved);
		if (proved[best] == 0) {
			return true;
		}
		int second = 0;
		for (int i = 0; i < proved.length; i++) {
			if (i != best && proved[i] > second) {
				second = proved[i];
			}
		}
		return proved[best] <= second + 1;
	}

	private static int bestProved(int[] proved) {
		int best = 0;
		for (int i = 1; i < proved.length; i++) {
			if (proved[i] > proved[best]) {
				best = i;
			}
		}
		return best;
	}

	/** Prefer more progress, then fewer proved losses, then more unproved (not-lost) samples. */
	private static int bestProgress(int[] progress, int[] lost, int[] unknown) {
		int best = 0;
		for (int i = 1; i < progress.length; i++) {
			if (progress[i] > progress[best]) {
				best = i;
			} else if (progress[i] == progress[best]) {
				if (lost[i] < lost[best] || (lost[i] == lost[best] && unknown[i] > unknown[best])) {
					best = i;
				}
			}
		}
		return best;
	}

	private void keepMatchingParticles(int hidden) {
		for (int i = particles.size() - 1; i >= 0; i--) {
			if (particles.get(i).length != hidden) {
				particles.remove(i);
			}
		}
	}

	/**
	 * Drop hypothesized layouts that would have revealed a different visible card
	 * than the one just seen. Remaining hidden slots are taken from the particle,
	 * not from the true deal.
	 */
	private void filterParticles(Klondike before, Klondike.Move move, Klondike after) {
		int hiddenBefore = before.hiddenCount();
		ArrayList<int[]> kept = new ArrayList<int[]>();
		for (int i = 0; i < particles.size(); i++) {
			int[] perm = particles.get(i);
			if (perm.length != hiddenBefore) {
				continue;
			}
			Klondike world = before.copy();
			world.assignHidden(perm);
			world.apply(move);
			if (!visibleAgree(world, after)) {
				continue;
			}
			int hidden = after.hiddenCount();
			int[] next = new int[hidden];
			world.fillHidden(next);
			kept.add(next);
		}
		particles.clear();
		particles.addAll(kept);
	}

	/** Compare only cards a Legal player can see. Face-down and unseen stock are ignored. */
	private static boolean visibleAgree(Klondike a, Klondike b) {
		if (a.wn != b.wn || a.sn != b.sn || a.stockKnown != b.stockKnown) {
			return false;
		}
		for (int s = 0; s < 4; s++) {
			if (a.found[s] != b.found[s]) {
				return false;
			}
		}
		for (int c = 0; c < 7; c++) {
			if (a.pn[c] != b.pn[c] || a.dn[c] != b.dn[c]) {
				return false;
			}
			for (int i = a.dn[c]; i < a.pn[c]; i++) {
				if (a.pile[c][i] != b.pile[c][i]) {
					return false;
				}
			}
		}
		for (int i = 0; i < a.wn; i++) {
			if (a.waste[i] != b.waste[i]) {
				return false;
			}
		}
		if (a.stockKnown) {
			for (int i = 0; i < a.sn; i++) {
				if (a.stock[i] != b.stock[i]) {
					return false;
				}
			}
		}
		return true;
	}

	private Klondike.Move unusedEnumeration(Klondike real) {
		Klondike.Move usual = SmartAI.heuristic(real);
		int hidden = real.hiddenCount();
		if (usual == null || !real.stockKnown || hidden <= 0 || hidden > 5) {
			return usual;
		}
		List<Klondike.Move> opts = reveals(real);
		if (opts.size() <= 1) {
			return usual;
		}
		int worlds = factorial(hidden);
		int[] base = new int[hidden];
		real.fillHidden(base);
		int[] perm = new int[hidden];
		int[] wins = new int[opts.size()];
		for (int w = 0; w < worlds; w++) {
			permutation(w, base, perm);
			for (int i = 0; i < opts.size(); i++) {
				Klondike world = real.copy();
				world.assignHidden(perm);
				if (rollout(world, opts.get(i)) >= 100000) {
					wins[i]++;
				}
			}
		}
		int best = 0;
		for (int i = 1; i < opts.size(); i++) {
			if (wins[i] > wins[best]) {
				best = i;
			} else if (wins[i] == wins[best] && sameMove(opts.get(i), usual)) {
				best = i;
			}
		}
		return opts.get(best);
	}

	/** Move recommended by the full search in every random layout, or null. */
	private Klondike.Move consensus(Klondike real) {
		int hidden = real.hiddenCount();
		int[] base = new int[hidden];
		real.fillHidden(base);
		Klondike.Move agreed = null;
		for (int s = 0; s < 3; s++) {
			int[] perm = base.clone();
			shuffle(perm);
			Klondike world = real.copy();
			world.assignHidden(perm);
			Klondike.Move advised = WideBound.winningMove(world);
			if (advised == null) {
				return null;
			}
			if (agreed == null) {
				agreed = advised;
			} else if (!sameMove(agreed, advised)) {
				return null;
			}
		}
		return agreed;
	}

	private int[] sampleWins(Klondike real, List<Klondike.Move> revs) {
		int hidden = real.hiddenCount();
		int[] base = new int[hidden];
		real.fillHidden(base);
		int[] perm = new int[hidden];
		int[] wins = new int[revs.size()];
		for (int r = 0; r < SAMPLES; r++) {
			System.arraycopy(base, 0, perm, 0, hidden);
			shuffle(perm);
			for (int i = 0; i < revs.size(); i++) {
				Klondike world = real.copy();
				world.assignHidden(perm);
				if (rollout(world, revs.get(i)) >= 100000) {
					wins[i]++;
				}
			}
		}
		return wins;
	}

	private static boolean sameMove(Klondike.Move a, Klondike.Move b) {
		return a.kind == b.kind && a.from == b.from && a.index == b.index && a.to == b.to;
	}

	private int winsFor(Klondike real, Klondike.Move move, int rounds) {
		int hidden = real.hiddenCount();
		int[] base = new int[hidden];
		real.fillHidden(base);
		int[] perm = new int[hidden];
		int wins = 0;
		for (int r = 0; r < rounds; r++) {
			System.arraycopy(base, 0, perm, 0, hidden);
			shuffle(perm);
			Klondike world = real.copy();
			world.assignHidden(perm);
			if (rollout(world, move) >= 100000) {
				wins++;
			}
		}
		return wins;
	}

	private static List<Klondike.Move> candidates(Klondike g, Klondike.Move usual) {
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
		ms.add(usual);
		List<Klondike.Move> revs = reveals(g);
		for (int i = 0; i < revs.size(); i++) {
			addUnique(ms, revs.get(i));
		}
		if (g.wn > 0) {
			int card = g.wasteTop();
			for (int to = 0; to < 7; to++) {
				if (g.canPlace(card, to)) {
					addUnique(ms, new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to));
				}
			}
			if (g.canFound(card)) {
				addUnique(ms, new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(card)));
			}
		}
		for (int c = 0; c < 7; c++) {
			if (g.pn[c] > 0 && g.safeFound(g.tail(c))) {
				addUnique(ms, new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c))));
			}
		}
		if (g.canDraw()) {
			addUnique(ms, Klondike.Move.draw());
		}
		return ms;
	}

	private static void addUnique(List<Klondike.Move> ms, Klondike.Move m) {
		for (int i = 0; i < ms.size(); i++) {
			Klondike.Move o = ms.get(i);
			if (o.kind == m.kind && o.from == m.from && o.index == m.index && o.to == m.to) {
				return;
			}
		}
		ms.add(m);
	}

	/**
	 * Pick a reveal by how often a perfect choice of later reveals wins in a
	 * random layout of the unseen cards. Ties go to the deepest buried pile.
	 */
	private Klondike.Move voteReveals(Klondike real, List<Klondike.Move> revs) {
		int hidden = real.hiddenCount();
		Klondike.Move prior = deepestReveal(real);
		if (hidden <= 0) {
			return prior != null ? prior : revs.get(0);
		}
		int[] base = new int[hidden];
		real.fillHidden(base);
		int[] perm = new int[hidden];
		int[] wins = new int[revs.size()];
		int priorIndex = 0;
		for (int i = 0; i < revs.size(); i++) {
			if (prior != null && moveKey(revs.get(i)) == moveKey(prior)) {
				priorIndex = i;
			}
		}
		for (int r = 0; r < SAMPLES; r++) {
			System.arraycopy(base, 0, perm, 0, hidden);
			shuffle(perm);
			for (int i = 0; i < revs.size(); i++) {
				Klondike world = real.copy();
				world.assignHidden(perm);
				if (rollout(world, revs.get(i)) >= 100000) {
					wins[i]++;
				}
			}
		}
		int best = priorIndex;
		// Only leave the usual move when samples say it is losing and another
		// reveal wins clearly more often.
		if (wins[priorIndex] * 2 < SAMPLES) {
			for (int i = 0; i < revs.size(); i++) {
				if (wins[i] >= wins[best] + SWITCH_MARGIN) {
					best = i;
				}
			}
		}
		return revs.get(best);
	}

	/** Same reveal ranking as the fast policy: deepest buried pile, then buried destination. */
	private static Klondike.Move deepestReveal(Klondike g) {
		List<Klondike.Move> revs = reveals(g);
		Klondike.Move best = null;
		int bestScore = -1;
		for (int i = 0; i < revs.size(); i++) {
			Klondike.Move m = revs.get(i);
			int score = g.dn[m.from] * 100 + g.dn[m.to];
			if (score > bestScore) {
				bestScore = score;
				best = m;
			}
		}
		return best;
	}

	/**
	 * Shortest sequence of already-visible moves, including draws from a stock
	 * whose order is known, that uncovers a card. Face-down identities are not read.
	 */
	private static Klondike.Move planReveal(Klondike start, int maxDepth, int cap) {
		ArrayList<Klondike> states = new ArrayList<Klondike>();
		ArrayList<Klondike.Move> firsts = new ArrayList<Klondike.Move>();
		ArrayList<Integer> depths = new ArrayList<Integer>();
		HashSet<Long> seen = new HashSet<Long>();
		states.add(start);
		firsts.add(null);
		depths.add(0);
		seen.add(start.visibleHash());
		int fd = start.faceDown();
		int nodes = 0;
		for (int cursor = 0; cursor < states.size(); cursor++) {
			Klondike g = states.get(cursor);
			int depth = depths.get(cursor);
			if (depth >= maxDepth) {
				continue;
			}
			List<Klondike.Move> moves = planMoves(g);
			for (int i = 0; i < moves.size(); i++) {
				if (nodes++ > cap) {
					return null;
				}
				Klondike child = g.copy();
				child.apply(moves.get(i));
				Klondike.Move first = firsts.get(cursor) == null ? moves.get(i) : firsts.get(cursor);
				if (child.faceDown() < fd || child.won()) {
					return first;
				}
				long h = child.visibleHash();
				if (!seen.add(h)) {
					continue;
				}
				states.add(child);
				firsts.add(first);
				depths.add(depth + 1);
			}
		}
		return null;
	}

	private static List<Klondike.Move> planMoves(Klondike g) {
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
		ms.addAll(setups(g));
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
			if (g.pn[c] > 0 && g.canFound(g.tail(c))) {
				ms.add(new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c))));
			}
		}
		Klondike.Move column = makeColumn(g);
		if (column != null) {
			ms.add(column);
		}
		if (g.stockKnown && g.canDraw()) {
			ms.add(Klondike.Move.draw());
		}
		return ms;
	}

	/** Moves the fast policy is actually choosing between. */
	private static List<Klondike.Move> branch(Klondike g) {
		List<Klondike.Move> revs = reveals(g);
		if (!revs.isEmpty()) {
			return revs;
		}
		List<Klondike.Move> parks = new ArrayList<Klondike.Move>();
		if (g.wn > 0) {
			int card = g.wasteTop();
			for (int to = 0; to < 7; to++) {
				if (g.canPlace(card, to)) {
					parks.add(new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to));
				}
			}
		}
		if (parks.size() > 1) {
			return parks;
		}
		return parks;
	}

	/**
	 * Score each candidate by how the fast legal policy finishes in random
	 * layouts of the unseen cards. The true layout is not one of the samples.
	 */
	private Klondike.Move vote(Klondike real, List<Klondike.Move> options) {
		int hidden = real.hiddenCount();
		if (hidden <= 0) {
			return options.get(0);
		}
		int rounds = real.stockKnown && hidden <= 4 ? factorial(hidden) : SAMPLES;
		int[] base = new int[hidden];
		real.fillHidden(base);
		int[] score = new int[options.size()];
		int[] perm = new int[hidden];
		for (int r = 0; r < rounds; r++) {
			if (rounds == SAMPLES) {
				System.arraycopy(base, 0, perm, 0, hidden);
				shuffle(perm);
			} else {
				permutation(r, base, perm);
			}
			for (int i = 0; i < options.size(); i++) {
				Klondike world = real.copy();
				world.assignHidden(perm);
				score[i] += rollout(world, options.get(i));
			}
		}
		int best = 0;
		for (int i = 1; i < options.size(); i++) {
			if (score[i] > score[best]) {
				best = i;
			}
		}
		return options.get(best);
	}

	private int rollout(Klondike g, Klondike.Move first) {
		g.apply(first);
		HashSet<Long> seen = new HashSet<Long>();
		for (int i = 0; i < ORACLE_STEPS; i++) {
			if (g.won()) {
				return 100000 + (ORACLE_STEPS - i);
			}
			if (!seen.add(g.hash())) {
				break;
			}
			Klondike.Move m = SmartAI.heuristic(g);
			if (m == null) {
				break;
			}
			g.apply(m);
		}
		return (21 - g.faceDown()) * 40 + g.foundationTotal();
	}

	/** Perfect-information reveal choice, then the fast policy. Ceiling only. */
	private static Klondike.Move thoughtful(Klondike g) {
		List<Klondike.Move> revs = reveals(g);
		if (revs.size() > 1) {
			Klondike.Move best = revs.get(0);
			int bestScore = Integer.MIN_VALUE;
			for (int i = 0; i < revs.size(); i++) {
				int score = underLooksUseful(g, revs.get(i)) * 10 + g.dn[revs.get(i).from] * 5 + g.dn[revs.get(i).to];
				if (score > bestScore) {
					bestScore = score;
					best = revs.get(i);
				}
			}
			return best;
		}
		return SmartAI.heuristic(g);
	}

	/**
	 * Advisor that may read every card. Callers pass either a fully visible
	 * position or a sample, never the raw hidden deal.
	 */
	static Klondike.Move oracleMove(Klondike g) {
		if (g.won()) {
			return null;
		}
		Klondike.Move reveal = bestCascade(g);
		if (reveal != null) {
			return reveal;
		}
		Klondike.Move setup = bestSetup(g);
		if (setup != null) {
			return setup;
		}
		Klondike.Move safe = firstSafe(g);
		if (safe != null) {
			return safe;
		}
		Klondike.Move park = bestPark(g, true);
		if (park != null) {
			return park;
		}
		if (g.sn > 0 && nextCardHelps(g)) {
			return Klondike.Move.draw();
		}
		Klondike.Move column = makeColumn(g);
		if (column != null) {
			return column;
		}
		if (g.canDraw()) {
			return Klondike.Move.draw();
		}
		Klondike.Move low = lowFoundation(g);
		if (low != null) {
			return low;
		}
		return worry(g);
	}

	private static Klondike.Move bestCascade(Klondike g) {
		Klondike.Move best = null;
		int bestScore = 0;
		List<Klondike.Move> revs = reveals(g);
		for (int i = 0; i < revs.size(); i++) {
			Klondike copy = g.copy();
			copy.apply(revs.get(i));
			int score = 100 + cascade(copy);
			if (score > bestScore) {
				bestScore = score;
				best = revs.get(i);
			}
		}
		return best;
	}

	/** Reveals that follow from this position, using cards the advisor can see. */
	private static int cascade(Klondike g) {
		int n = 0;
		HashSet<Long> seen = new HashSet<Long>();
		while (n < 20 && seen.add(g.hash())) {
			if (g.won()) {
				return 5000 + n * 10;
			}
			List<Klondike.Move> revs = reveals(g);
			if (revs.isEmpty()) {
				break;
			}
			Klondike.Move pick = revs.get(0);
			int pickScore = -1;
			for (int i = 0; i < revs.size(); i++) {
				int underBonus = underLooksUseful(g, revs.get(i));
				int score = g.dn[revs.get(i).from] * 10 + underBonus;
				if (score > pickScore) {
					pickScore = score;
					pick = revs.get(i);
				}
			}
			g.apply(pick);
			n++;
		}
		return n * 10 - g.faceDown();
	}

	/** Bonus for the card that a reveal would turn up. Advisor-only. */
	private static int underLooksUseful(Klondike g, Klondike.Move reveal) {
		int col = reveal.from;
		if (g.dn[col] <= 0) {
			return 0;
		}
		int under = g.pile[col][g.dn[col] - 1];
		int bonus = 0;
		if (g.canFound(under) || g.safeFound(under)) {
			bonus += 25;
		}
		for (int to = 0; to < 7; to++) {
			if (to != col && g.canPlace(under, to)) {
				bonus += 15;
				break;
			}
		}
		if (Klondike.rank(under) == 12 && g.emptyColumns() > 0) {
			bonus += 20;
		}
		return bonus;
	}

	private static Klondike.Move bestSetup(Klondike g) {
		List<Klondike.Move> setups = setups(g);
		Klondike.Move best = null;
		int bestScore = 0;
		int limit = Math.min(setups.size(), 18);
		for (int i = 0; i < limit; i++) {
			Klondike copy = g.copy();
			copy.apply(setups.get(i));
			if (reveals(copy).isEmpty() && !copy.won()) {
				continue;
			}
			int score = cascade(copy);
			if (score > bestScore) {
				bestScore = score;
				best = setups.get(i);
			}
		}
		return best;
	}

	private static List<Klondike.Move> setups(Klondike g) {
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
		for (int col = 0; col < 7; col++) {
			for (int index = g.dn[col] + 1; index < g.pn[col]; index++) {
				int exposed = g.pile[col][index - 1];
				if (!exposesHome(g, col, exposed) && !g.canFound(exposed)) {
					continue;
				}
				int card = g.pile[col][index];
				for (int to = 0; to < 7; to++) {
					if (to != col && g.canPlace(card, to) && !g.uselessKingSlide(col, index, to)) {
						ms.add(new Klondike.Move(Klondike.TO_TAB, col, index, to));
						break;
					}
				}
			}
		}
		if (buriedKing(g) && g.emptyColumns() == 0) {
			for (int col = 0; col < 7; col++) {
				if (g.dn[col] != 0 || g.pn[col] == 0) {
					continue;
				}
				for (int to = 0; to < 7; to++) {
					if (to != col && g.canPlace(g.pile[col][0], to) && !g.uselessKingSlide(col, 0, to)) {
						ms.add(new Klondike.Move(Klondike.TO_TAB, col, 0, to));
						break;
					}
				}
			}
		}
		return ms;
	}

	private static boolean exposesHome(Klondike g, int src, int exposed) {
		for (int col = 0; col < 7; col++) {
			if (col == src || g.dn[col] == 0 || g.dn[col] >= g.pn[col]) {
				continue;
			}
			int head = g.pile[col][g.dn[col]];
			if (Klondike.rank(head) + 1 == Klondike.rank(exposed) && Klondike.red(head) != Klondike.red(exposed)) {
				return true;
			}
		}
		return false;
	}

	private static boolean buriedKing(Klondike g) {
		for (int col = 0; col < 7; col++) {
			if (g.dn[col] > 0 && g.dn[col] < g.pn[col] && Klondike.rank(g.pile[col][g.dn[col]]) == 12) {
				return true;
			}
		}
		return false;
	}

	private static Klondike.Move makeColumn(Klondike g) {
		if (!buriedKing(g) || g.emptyColumns() > 0) {
			return null;
		}
		for (int col = 0; col < 7; col++) {
			if (g.dn[col] != 0 || g.pn[col] == 0) {
				continue;
			}
			for (int to = 0; to < 7; to++) {
				if (to != col && g.canPlace(g.pile[col][0], to) && !g.uselessKingSlide(col, 0, to)) {
					return new Klondike.Move(Klondike.TO_TAB, col, 0, to);
				}
			}
		}
		return null;
	}

	private static boolean nextCardHelps(Klondike g) {
		int next = g.stock[g.sn - 1];
		if (g.canFound(next) || g.safeFound(next)) {
			return true;
		}
		for (int to = 0; to < 7; to++) {
			if (g.canPlace(next, to)) {
				return true;
			}
		}
		return false;
	}

	private static Klondike.Move bestPark(Klondike g, boolean avoidCoveringFuture) {
		if (g.wn == 0) {
			return null;
		}
		int card = g.wasteTop();
		Klondike.Move best = null;
		int bestScore = -1;
		for (int to = 0; to < 7; to++) {
			if (!g.canPlace(card, to)) {
				continue;
			}
			if (avoidCoveringFuture && coversFutureHome(g, to)) {
				continue;
			}
			int score = g.pn[to] == 0 ? 30 : g.dn[to] * 10 + 1;
			if (score > bestScore) {
				bestScore = score;
				best = new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to);
			}
		}
		return best;
	}

	/** Don't cover the only future parent of a card the advisor knows is buried. */
	private static boolean coversFutureHome(Klondike g, int to) {
		if (g.pn[to] == 0) {
			return false;
		}
		int parent = g.tail(to);
		for (int col = 0; col < 7; col++) {
			if (g.dn[col] <= 0) {
				continue;
			}
			int under = g.pile[col][g.dn[col] - 1];
			if (Klondike.rank(under) + 1 != Klondike.rank(parent) || Klondike.red(under) == Klondike.red(parent)) {
				continue;
			}
			boolean alt = false;
			for (int other = 0; other < 7; other++) {
				if (other != to && g.pn[other] > 0 && g.canPlace(under, other)) {
					alt = true;
					break;
				}
			}
			if (!alt) {
				return true;
			}
		}
		return false;
	}

	private static Klondike.Move firstSafe(Klondike g) {
		Klondike.Move reveal = null;
		int bestDn = -1;
		for (int c = 0; c < 7; c++) {
			if (g.pn[c] == 0 || !g.safeFound(g.tail(c))) {
				continue;
			}
			boolean flips = g.dn[c] > 0 && g.pn[c] - 1 == g.dn[c];
			if (flips && g.dn[c] > bestDn) {
				bestDn = g.dn[c];
				reveal = new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c)));
			}
		}
		if (reveal != null) {
			return reveal;
		}
		if (g.wn > 0 && g.safeFound(g.wasteTop())) {
			return new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(g.wasteTop()));
		}
		for (int c = 0; c < 7; c++) {
			if (g.pn[c] > 0 && g.safeFound(g.tail(c))) {
				return new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c)));
			}
		}
		return null;
	}

	private static Klondike.Move lowFoundation(Klondike g) {
		Klondike.Move best = null;
		int bestRank = 3;
		for (int c = 0; c < 7; c++) {
			if (g.pn[c] == 0 || !g.canFound(g.tail(c))) {
				continue;
			}
			int r = Klondike.rank(g.tail(c));
			if (r <= bestRank) {
				bestRank = r;
				best = new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c)));
			}
		}
		if (g.wn > 0 && g.canFound(g.wasteTop()) && Klondike.rank(g.wasteTop()) <= bestRank) {
			return new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(g.wasteTop()));
		}
		return best;
	}

	private static Klondike.Move worry(Klondike g) {
		if (g.wn == 0) {
			return null;
		}
		int drawn = g.wasteTop();
		for (int s = 0; s < 4; s++) {
			if (g.found[s] == 0 || g.worryBackPointless(s)) {
				continue;
			}
			int top = s * 13 + g.found[s] - 1;
			if (Klondike.rank(drawn) + 1 != Klondike.rank(top) || Klondike.red(drawn) == Klondike.red(top)) {
				continue;
			}
			for (int to = 0; to < 7; to++) {
				if (g.canPlace(top, to)) {
					return new Klondike.Move(Klondike.TO_TAB, Klondike.FOUND, s, to);
				}
			}
		}
		return null;
	}

	static List<Klondike.Move> reveals(Klondike g) {
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
		for (int col = 0; col < 7; col++) {
			if (g.dn[col] == 0 || g.dn[col] >= g.pn[col]) {
				continue;
			}
			int index = g.dn[col];
			int card = g.pile[col][index];
			for (int to = 0; to < 7; to++) {
				if (to != col && g.canPlace(card, to)) {
					ms.add(new Klondike.Move(Klondike.TO_TAB, col, index, to));
				}
			}
		}
		return ms;
	}

	private static Klondike.Move exactFirst(Klondike g, int budget) {
		Klondike copy = g.copy();
		ArrayList<Klondike.Move> path = new ArrayList<Klondike.Move>();
		HashSet<Long> loss = new HashSet<Long>();
		int result = exact(copy, path, new int[] { budget }, loss, new HashSet<Long>());
		if (result == 1 && !path.isEmpty()) {
			return path.get(0);
		}
		return null;
	}

	/** 1 win, 0 proven loss, -1 unknown. Sees every card; caller guarantees that is allowed. */
	private static int exact(Klondike g, List<Klondike.Move> path, int[] budget, HashSet<Long> loss, HashSet<Long> stack) {
		if (g.won()) {
			return 1;
		}
		int mark = path.size();
		ArrayList<Klondike.Undo> forced = new ArrayList<Klondike.Undo>();
		while (true) {
			Klondike.Move safe = firstSafe(g);
			if (safe == null) {
				break;
			}
			forced.add(g.apply(safe));
			path.add(safe);
			if (g.won()) {
				return 1;
			}
		}
		if (budget[0]-- <= 0 || path.size() > 420) {
			rollback(g, path, forced, mark);
			return -1;
		}
		long h = g.hash();
		if (!stack.add(h)) {
			rollback(g, path, forced, mark);
			return -1;
		}
		if (loss.contains(h)) {
			stack.remove(h);
			rollback(g, path, forced, mark);
			return 0;
		}
		boolean unknown = false;
		List<Klondike.Move> moves = exactMoves(g);
		for (int i = 0; i < moves.size(); i++) {
			Klondike.Undo undo = g.apply(moves.get(i));
			path.add(moves.get(i));
			int result = exact(g, path, budget, loss, stack);
			if (result == 1) {
				stack.remove(h);
				return 1;
			}
			path.remove(path.size() - 1);
			g.undo(undo);
			if (result < 0) {
				unknown = true;
			}
		}
		stack.remove(h);
		rollback(g, path, forced, mark);
		if (!unknown) {
			loss.add(h);
			return 0;
		}
		return -1;
	}

	private static void rollback(Klondike g, List<Klondike.Move> path, List<Klondike.Undo> forced, int mark) {
		while (path.size() > mark) {
			path.remove(path.size() - 1);
		}
		for (int i = forced.size() - 1; i >= 0; i--) {
			g.undo(forced.get(i));
		}
	}

	private static List<Klondike.Move> exactMoves(Klondike g) {
		List<Klondike.Move> ms = new ArrayList<Klondike.Move>();
		List<Klondike.Move> revs = reveals(g);
		for (int i = 0; i < revs.size(); i++) {
			ms.add(revs.get(i));
		}
		if (g.wn > 0) {
			int card = g.wasteTop();
			for (int to = 0; to < 7; to++) {
				if (g.canPlace(card, to)) {
					ms.add(new Klondike.Move(Klondike.TO_TAB, Klondike.WASTE, -1, to));
				}
			}
		}
		List<Klondike.Move> setups = setups(g);
		int cap = Math.min(setups.size(), 8);
		for (int i = 0; i < cap; i++) {
			ms.add(setups.get(i));
		}
		if (g.wn > 0 && g.canFound(g.wasteTop())) {
			ms.add(new Klondike.Move(Klondike.TO_FOUND, Klondike.WASTE, -1, Klondike.suit(g.wasteTop())));
		}
		for (int c = 0; c < 7; c++) {
			if (g.pn[c] > 0 && g.canFound(g.tail(c))) {
				ms.add(new Klondike.Move(Klondike.TO_FOUND, c, g.pn[c] - 1, Klondike.suit(g.tail(c))));
			}
		}
		if (g.canDraw()) {
			ms.add(Klondike.Move.draw());
		}
		return ms;
	}

	private static int factorial(int n) {
		int f = 1;
		for (int i = 2; i <= n; i++) {
			f *= i;
		}
		return f;
	}

	/** Lexicographic permutation {@code index} of {@code src} into {@code dest}. */
	private static void permutation(int index, int[] src, int[] dest) {
		int[] rest = Arrays.copyOf(src, src.length);
		int n = src.length;
		for (int i = 0; i < n; i++) {
			int f = factorial(n - 1 - i);
			int pick = f == 0 ? 0 : index / f;
			if (f != 0) {
				index %= f;
			}
			dest[i] = rest[pick];
			for (int j = pick; j < n - i - 1; j++) {
				rest[j] = rest[j + 1];
			}
		}
	}

	private void shuffle(int[] ids) {
		for (int i = ids.length - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int tmp = ids[i];
			ids[i] = ids[j];
			ids[j] = tmp;
		}
	}

	private static long moveKey(Klondike.Move m) {
		return (((long) m.kind) << 42) | (((long) (m.from + 4)) << 28) | (((long) (m.index + 4)) << 14) | (m.to + 4);
	}

	public static void main(String[] args) {
		String mode = args.length > 0 ? args[0] : "legal";
		int n = args.length > 1 ? Integer.parseInt(args[1]) : 40;
		int startSeed = args.length > 2 && !args[2].startsWith("-") ? Integer.parseInt(args[2]) : 1;
		boolean parallel = true;
		for (int i = 0; i < args.length; i++) {
			if ("-seq".equals(args[i]) || "--sequential".equals(args[i])) {
				parallel = false;
			}
		}
		if (mode.startsWith("vote:")) {
			// vote:SAMPLES:BUDGET  — the 568/1000 Legal path
			String[] parts = mode.split(":");
			USE_VOTING = true;
			USE_V2 = false;
			USE_PLUS = false;
			VOTE_SAMPLES = Integer.parseInt(parts[1]);
			VOTE_NODE_LIMIT = Integer.parseInt(parts[2]);
			mode = "vote:" + VOTE_SAMPLES + ":" + VOTE_NODE_LIMIT;
		} else if ("onesample".equals(mode)) {
			USE_VOTING = false;
			USE_V2 = false;
			USE_PLUS = false;
		} else if ("legal".equals(mode)) {
			// Keep the measured 568/1000 player as default until a plus/v2 config beats it.
			USE_VOTING = true;
			USE_V2 = false;
			USE_PLUS = false;
		} else if (mode.startsWith("v2")) {
			applyV2Mode(mode);
		} else if (mode.startsWith("plus")) {
			applyPlusMode(mode);
		}
		if ("oracle".equals(mode) || "blind".equals(mode)) {
			runLegacyModes(mode, n, startSeed);
			return;
		}
		runBatch(mode, n, startSeed, parallel);
	}

	private static void runLegacyModes(String mode, int n, int startSeed) {
		int wins = 0;
		long t0 = System.nanoTime();
		int last = startSeed + n - 1;
		for (int seed = startSeed; seed <= last; seed++) {
			Klondike g = Klondike.deal(seed);
			boolean won = "oracle".equals(mode) ? playOracle(g) : SmartAI.play(g).won;
			if (won) {
				wins++;
			}
			int done = seed - startSeed + 1;
			if (done % 10 == 0 || done == n) {
				System.out.printf("%s %d/%d (%.1f%%)%n", mode, wins, done, 100.0 * wins / done);
			}
		}
		double sec = (System.nanoTime() - t0) / 1e9;
		System.out.printf("%s %d/%d (%.1f%%) in %.1fs%n", mode, wins, n, 100.0 * wins / n, sec);
	}

	private static void runBatch(String mode, int n, int startSeed, boolean parallel) {
		long t0 = System.nanoTime();
		int last = startSeed + n - 1;
		boolean[] wonAt = new boolean[n];
		if (!parallel || n < 4) {
			int wins = 0;
			for (int i = 0; i < n; i++) {
				int seed = startSeed + i;
				wonAt[i] = new StrongAI(seed * 17L).play(Klondike.deal(seed)).won;
				if (wonAt[i]) {
					wins++;
				}
				int done = i + 1;
				if (done % 10 == 0 || done == n) {
					System.out.printf("%s %d/%d (%.1f%%)%n", mode, wins, done, 100.0 * wins / done);
				}
			}
		} else {
			int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
			java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
			java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
			java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
			java.util.concurrent.Future<?>[] futures = new java.util.concurrent.Future<?>[n];
			for (int i = 0; i < n; i++) {
				final int idx = i;
				final int seed = startSeed + i;
				futures[i] = pool.submit(() -> {
					boolean won = new StrongAI(seed * 17L).play(Klondike.deal(seed)).won;
					wonAt[idx] = won;
					if (won) {
						wins.incrementAndGet();
					}
					int d = done.incrementAndGet();
					if (d % 10 == 0 || d == n) {
						int w = wins.get();
						System.out.printf("%s %d/%d (%.1f%%)%n", mode, w, d, 100.0 * w / d);
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
		double sec = (System.nanoTime() - t0) / 1e9;
		System.out.printf("%s %d/%d (%.1f%%) in %.1fs (seeds %d-%d, voting=%s v2=%s particles=%s progress=%s adaptive=%s enum=%d pass2=%d samples=%d budget=%d)%n",
				mode, wins, n, 100.0 * wins / n, sec, startSeed, last, USE_VOTING, USE_V2,
				USE_PARTICLES, USE_PROGRESS_FALLBACK, USE_ADAPTIVE, ENUM_HIDDEN, SECOND_PASS_LIMIT,
				VOTE_SAMPLES, VOTE_NODE_LIMIT);
	}

	/**
	 * plus or plus:flags on top of the 568/1000 first pass. Flags: adaptive,
	 * pass2, enum5, progress, visible50k, all.
	 */
	private static void applyPlusMode(String mode) {
		USE_VOTING = true;
		USE_V2 = false;
		USE_PLUS = true;
		USE_PARTICLES = false;
		USE_PROGRESS_FALLBACK = false;
		USE_ADAPTIVE = false;
		EXTRA_SAMPLES = 8;
		ENUM_HIDDEN = 4;
		SECOND_PASS_LIMIT = 0;
		VISIBLE_NODE_LIMIT = WideBound.LEGAL_NODE_LIMIT;
		VOTE_SAMPLES = 8;
		VOTE_NODE_LIMIT = 6000;
		String flagsPart = mode.startsWith("plus:") ? mode.substring(5) : "";
		if (flagsPart.isEmpty() || "all".equals(flagsPart)) {
			USE_ADAPTIVE = true;
			SECOND_PASS_LIMIT = 12000;
			ENUM_HIDDEN = 5;
			return;
		}
		String[] flags = flagsPart.split(",");
		for (int i = 0; i < flags.length; i++) {
			String f = flags[i].trim();
			if (f.isEmpty() || "none".equals(f)) {
				continue;
			} else if ("adaptive".equals(f)) {
				USE_ADAPTIVE = true;
			} else if ("pass2".equals(f)) {
				SECOND_PASS_LIMIT = 12000;
			} else if ("enum5".equals(f)) {
				ENUM_HIDDEN = 5;
			} else if ("progress".equals(f)) {
				USE_PROGRESS_FALLBACK = true;
			} else if ("visible50k".equals(f)) {
				VISIBLE_NODE_LIMIT = 50000;
			} else if ("all".equals(f)) {
				USE_ADAPTIVE = true;
				SECOND_PASS_LIMIT = 12000;
				ENUM_HIDDEN = 5;
				VISIBLE_NODE_LIMIT = 50000;
			} else {
				throw new IllegalArgumentException("unknown plus flag: " + f);
			}
		}
	}

	/**
	 * v2 or v2:samples:budget:flags. Flags are comma-separated:
	 * particles, progress, adaptive, enum5, pass2, visible50k, and the word "noX" to disable.
	 * Default v2 turns on particles, progress, and adaptive extras.
	 */
	private static void applyV2Mode(String mode) {
		USE_VOTING = true;
		USE_V2 = true;
		USE_PARTICLES = true;
		USE_PROGRESS_FALLBACK = true;
		USE_ADAPTIVE = true;
		EXTRA_SAMPLES = 8;
		ENUM_HIDDEN = 4;
		SECOND_PASS_LIMIT = 0;
		VISIBLE_NODE_LIMIT = WideBound.LEGAL_NODE_LIMIT;
		VOTE_SAMPLES = 8;
		VOTE_NODE_LIMIT = 6000;
		String[] parts = mode.split(":");
		if (parts.length >= 3) {
			VOTE_SAMPLES = Integer.parseInt(parts[1]);
			VOTE_NODE_LIMIT = Integer.parseInt(parts[2]);
		}
		if (parts.length >= 4) {
			USE_PARTICLES = false;
			USE_PROGRESS_FALLBACK = false;
			USE_ADAPTIVE = false;
			ENUM_HIDDEN = 4;
			SECOND_PASS_LIMIT = 0;
			VISIBLE_NODE_LIMIT = WideBound.LEGAL_NODE_LIMIT;
			String[] flags = parts[3].split(",");
			for (int i = 0; i < flags.length; i++) {
				String f = flags[i].trim();
				if (f.isEmpty()) {
					continue;
				}
				if ("particles".equals(f)) {
					USE_PARTICLES = true;
				} else if ("progress".equals(f)) {
					USE_PROGRESS_FALLBACK = true;
				} else if ("adaptive".equals(f)) {
					USE_ADAPTIVE = true;
				} else if ("enum5".equals(f)) {
					ENUM_HIDDEN = 5;
				} else if ("pass2".equals(f)) {
					SECOND_PASS_LIMIT = 12000;
				} else if ("visible50k".equals(f)) {
					VISIBLE_NODE_LIMIT = 50000;
				} else if ("none".equals(f) || "plain".equals(f)) {
					// leave everything off
				} else if ("all".equals(f)) {
					USE_PARTICLES = true;
					USE_PROGRESS_FALLBACK = true;
					USE_ADAPTIVE = true;
					ENUM_HIDDEN = 5;
					SECOND_PASS_LIMIT = 12000;
					VISIBLE_NODE_LIMIT = 50000;
				} else {
					throw new IllegalArgumentException("unknown v2 flag: " + f);
				}
			}
		}
	}

	/** Ceiling: the advisor plays the true cards. Not used for reported results. */
	private static boolean playOracle(Klondike g) {
		HashSet<Long> seen = new HashSet<Long>();
		for (int i = 0; i < 5000; i++) {
			if (g.won()) {
				return true;
			}
			if (!seen.add(g.hash())) {
				return false;
			}
			Klondike.Move m = g.faceDown() == 0 && (g.stockKnown || g.sn == 0) ? exactFirst(g, EXACT_BUDGET) : null;
			if (m == null) {
				m = thoughtful(g);
			}
			if (m == null) {
				return false;
			}
			g.apply(m);
		}
		return g.won();
	}
}
