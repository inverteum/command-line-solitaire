import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 1-card-draw Klondike with the same rules, deal order, and scoring as
 * {@link Game}. Face-down tableau cards and unseen stock cards are real, but
 * solvers are expected to ignore them until a draw or a reveal shows them.
 *
 * <p>Win condition matches {@code Game.winnable()}: the stock and waste are
 * empty and every tableau card is face up. The CLI then autocompletes the
 * foundations. Building every foundation by hand also wins.
 */
final class Klondike {

	static final int DRAW = 0;
	static final int TO_TAB = 1;
	static final int TO_FOUND = 2;
	static final int AUTO = 3;

	static final int WASTE = -1;
	static final int FOUND = -2;

	/** A single legal action. Fields depend on {@link #kind}. */
	static final class Move {
		final int kind;
		/** Tableau index, {@link #WASTE}, or {@link #FOUND}. */
		final int from;
		/** Start index in a tableau pile, or the foundation suit for a worry-back. */
		final int index;
		/** Destination tableau, or the suit for a foundation move. */
		final int to;

		Move(int kind, int from, int index, int to) {
			this.kind = kind;
			this.from = from;
			this.index = index;
			this.to = to;
		}

		static Move draw() {
			return new Move(DRAW, -1, -1, -1);
		}

		static Move auto() {
			return new Move(AUTO, -1, -1, -1);
		}

		@Override
		public String toString() {
			if (kind == DRAW) {
				return "draw";
			}
			if (kind == AUTO) {
				return "auto";
			}
			if (kind == TO_FOUND) {
				return "found from " + from + " idx " + index;
			}
			return "tab from " + from + " idx " + index + " to " + to;
		}
	}

	/** Undo token returned by {@link #apply}. */
	static final class Undo {
		int kind;
		int from, index, to, count;
		int card = -1;
		boolean revealed;
		int pointsBefore;
		boolean stockKnownBefore;
		boolean recycled;
		int snBefore, wnBefore;
		int[] stockBefore;
		int[] wasteBefore;
	}

	final int[][] pile = new int[7][52];
	final int[] pn = new int[7];
	/** Face-down prefix length of each pile. */
	final int[] dn = new int[7];
	/** Cards already built on each suit foundation (0-13). */
	final int[] found = new int[4];
	/** Stock, index 0 = bottom, {@code sn - 1} = top (next draw). */
	final int[] stock = new int[24];
	int sn;
	/** Waste, index 0 = oldest, {@code wn - 1} = playable top. */
	final int[] waste = new int[24];
	int wn;
	int points;
	/** True once every original stock card has been drawn at least once. */
	boolean stockKnown;

	static int suit(int card) {
		return card / 13;
	}

	static int rank(int card) {
		return card % 13;
	}

	static boolean red(int card) {
		return card >= 26;
	}

	static String name(int card) {
		String ranks = "A23456789TJQK";
		String suits = "SCHD";
		String r = rank(card) == 9 ? "10" : String.valueOf(ranks.charAt(rank(card)));
		return r + suits.charAt(suit(card));
	}

	static int[] shuffled(long seed) {
		return new Random(seed).ints(0, 52).distinct().limit(52).toArray();
	}

	static Klondike deal(long seed) {
		int[] deck = shuffled(seed);
		Klondike g = new Klondike();
		int idx = 0;
		for (int col = 0; col < 7; col++) {
			int size = col + 1;
			for (int i = 0; i < size; i++) {
				g.pile[col][i] = deck[idx++];
			}
			g.pn[col] = size;
			g.dn[col] = size - 1;
		}
		// Game pushes deck[28] .. deck[51], so deck[51] is the first draw.
		for (int i = 28; i < 52; i++) {
			g.stock[i - 28] = deck[i];
		}
		g.sn = 24;
		g.stockKnown = false;
		return g;
	}

	int faceDown() {
		int n = 0;
		for (int i = 0; i < 7; i++) {
			n += dn[i];
		}
		return n;
	}

	int emptyColumns() {
		int n = 0;
		for (int i = 0; i < 7; i++) {
			if (pn[i] == 0) {
				n++;
			}
		}
		return n;
	}

	int foundationTotal() {
		return found[0] + found[1] + found[2] + found[3];
	}

	boolean won() {
		if (sn > 0 || wn > 0) {
			return false;
		}
		for (int i = 0; i < 7; i++) {
			if (dn[i] > 0) {
				return false;
			}
		}
		return true;
	}

	boolean foundationsComplete() {
		return found[0] == 13 && found[1] == 13 && found[2] == 13 && found[3] == 13;
	}

	/**
	 * Apply the CLI autocomplete: award 10 points per card still on the tableau
	 * and build every foundation. Stock and waste are already empty when this
	 * is legal.
	 */
	void finish() {
		int n = 0;
		for (int c = 0; c < 7; c++) {
			n += pn[c];
			pn[c] = 0;
			dn[c] = 0;
		}
		points += n * 10;
		for (int s = 0; s < 4; s++) {
			found[s] = 13;
		}
		sn = 0;
		wn = 0;
	}

	boolean canDraw() {
		return sn > 0 || wn > 0;
	}

	int wasteTop() {
		return waste[wn - 1];
	}

	int tail(int col) {
		return pile[col][pn[col] - 1];
	}

	boolean canPlace(int card, int col) {
		if (pn[col] == 0) {
			return rank(card) == 12;
		}
		int onto = tail(col);
		return rank(card) + 1 == rank(onto) && red(card) != red(onto);
	}

	boolean canFound(int card) {
		return rank(card) == found[suit(card)];
	}

	/**
	 * Safe-to-foundation dominance for draw-1 with unlimited redeals and
	 * worrying back (Blake &amp; Gent): the card is at most two ranks above the
	 * opposite-color foundations and at most three above the same-color twin.
	 * Aces and twos always qualify.
	 */
	boolean safeFound(int card) {
		int s = suit(card);
		int r = rank(card);
		if (r != found[s]) {
			return false;
		}
		int twin = s ^ 1;
		int o1 = (s < 2) ? 2 : 0;
		int o2 = o1 + 1;
		return r <= Math.min(found[o1], found[o2]) + 1 && r <= found[twin] + 2;
	}

	/** Worrying a foundation card back is pointless when it is still safe up. */
	boolean worryBackPointless(int s) {
		if (found[s] == 0) {
			return true;
		}
		int card = s * 13 + (found[s] - 1);
		found[s]--;
		boolean safe = safeFound(card);
		found[s]++;
		return safe;
	}

	boolean uselessKingSlide(int from, int index, int to) {
		if (pn[to] != 0 || rank(pile[from][index]) != 12) {
			return false;
		}
		// Sliding an entire face-up king pile between empty columns does nothing.
		return dn[from] == 0 && index == 0;
	}

	Klondike copy() {
		Klondike g = new Klondike();
		for (int i = 0; i < 7; i++) {
			System.arraycopy(pile[i], 0, g.pile[i], 0, pn[i]);
			g.pn[i] = pn[i];
			g.dn[i] = dn[i];
		}
		System.arraycopy(found, 0, g.found, 0, 4);
		System.arraycopy(stock, 0, g.stock, 0, sn);
		g.sn = sn;
		System.arraycopy(waste, 0, g.waste, 0, wn);
		g.wn = wn;
		g.points = points;
		g.stockKnown = stockKnown;
		return g;
	}

	boolean same(Klondike o) {
		if (sn != o.sn || wn != o.wn || points != o.points || stockKnown != o.stockKnown) {
			return false;
		}
		if (!Arrays.equals(found, o.found) || !Arrays.equals(pn, o.pn) || !Arrays.equals(dn, o.dn)) {
			return false;
		}
		for (int i = 0; i < sn; i++) {
			if (stock[i] != o.stock[i]) {
				return false;
			}
		}
		for (int i = 0; i < wn; i++) {
			if (waste[i] != o.waste[i]) {
				return false;
			}
		}
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < pn[c]; i++) {
				if (pile[c][i] != o.pile[c][i]) {
					return false;
				}
			}
		}
		return true;
	}

	long hash() {
		long h = 0xcbf29ce484222325L;
		for (int c = 0; c < 7; c++) {
			h = mix(h, pn[c]);
			h = mix(h, dn[c]);
			for (int i = 0; i < pn[c]; i++) {
				h = mix(h, pile[c][i]);
			}
		}
		for (int s = 0; s < 4; s++) {
			h = mix(h, found[s]);
		}
		h = mix(h, sn);
		for (int i = 0; i < sn; i++) {
			h = mix(h, stock[i]);
		}
		h = mix(h, wn);
		for (int i = 0; i < wn; i++) {
			h = mix(h, waste[i]);
		}
		h = mix(h, stockKnown ? 1 : 0);
		return h;
	}

	/** Hash of everything a player is allowed to know. */
	long visibleHash() {
		long h = 0xcbf29ce484222325L;
		for (int c = 0; c < 7; c++) {
			h = mix(h, pn[c]);
			h = mix(h, dn[c]);
			for (int i = dn[c]; i < pn[c]; i++) {
				h = mix(h, pile[c][i]);
			}
		}
		for (int s = 0; s < 4; s++) {
			h = mix(h, found[s]);
		}
		h = mix(h, sn);
		if (stockKnown) {
			for (int i = 0; i < sn; i++) {
				h = mix(h, stock[i]);
			}
		}
		h = mix(h, wn);
		for (int i = 0; i < wn; i++) {
			h = mix(h, waste[i]);
		}
		h = mix(h, stockKnown ? 1 : 0);
		return h;
	}

	private static long mix(long h, int x) {
		h ^= (x + 1) * 0x9e3779b97f4a7c15L;
		h *= 0x100000001b3L;
		return h;
	}

	/**
	 * Replace unknown cards with a random consistent assignment.
	 * Used only inside hypothetical rollouts, never to read the true deal.
	 */
	/** Cards the player cannot see, in slot order: face-down rows, then unseen stock. */
	int hiddenCount() {
		int n = faceDown();
		if (!stockKnown) {
			n += sn;
		}
		return n;
	}

	void fillHidden(int[] into) {
		int k = 0;
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < dn[c]; i++) {
				into[k++] = pile[c][i];
			}
		}
		if (!stockKnown) {
			for (int i = 0; i < sn; i++) {
				into[k++] = stock[i];
			}
		}
	}

	void assignHidden(int[] ids) {
		int k = 0;
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < dn[c]; i++) {
				pile[c][i] = ids[k++];
			}
		}
		if (!stockKnown) {
			for (int i = 0; i < sn; i++) {
				stock[i] = ids[k++];
			}
		}
	}

	void shuffleUnknown(Random rng) {
		boolean[] used = new boolean[52];
		for (int c = 0; c < 7; c++) {
			for (int i = dn[c]; i < pn[c]; i++) {
				used[pile[c][i]] = true;
			}
		}
		for (int i = 0; i < wn; i++) {
			used[waste[i]] = true;
		}
		for (int s = 0; s < 4; s++) {
			for (int r = 0; r < found[s]; r++) {
				used[s * 13 + r] = true;
			}
		}
		if (stockKnown) {
			for (int i = 0; i < sn; i++) {
				used[stock[i]] = true;
			}
		}
		List<Integer> ids = new ArrayList<Integer>();
		for (int card = 0; card < 52; card++) {
			if (!used[card]) {
				ids.add(card);
			}
		}
		for (int i = ids.size() - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int tmp = ids.get(i);
			ids.set(i, ids.get(j));
			ids.set(j, tmp);
		}
		int k = 0;
		for (int c = 0; c < 7; c++) {
			for (int i = 0; i < dn[c]; i++) {
				pile[c][i] = ids.get(k++);
			}
		}
		if (!stockKnown) {
			for (int i = 0; i < sn; i++) {
				stock[i] = ids.get(k++);
			}
		}
		if (k != ids.size()) {
			throw new IllegalStateException("hidden slots " + k + " vs ids " + ids.size());
		}
	}

	Undo apply(Move m) {
		Undo u = new Undo();
		u.kind = m.kind;
		u.from = m.from;
		u.index = m.index;
		u.to = m.to;
		u.pointsBefore = points;
		u.stockKnownBefore = stockKnown;
		if (m.kind == DRAW) {
			applyDraw(u);
			return u;
		}
		if (m.kind == AUTO) {
			int n = 0;
			for (int c = 0; c < 7; c++) {
				n += pn[c];
			}
			points += n * 10;
			return u;
		}
		if (m.kind == TO_FOUND) {
			applyToFound(m, u);
			return u;
		}
		applyToTab(m, u);
		return u;
	}

	private void applyDraw(Undo u) {
		u.snBefore = sn;
		u.wnBefore = wn;
		u.stockBefore = Arrays.copyOf(stock, sn);
		u.wasteBefore = Arrays.copyOf(waste, wn);
		if (sn == 0) {
			u.recycled = true;
			points -= 25;
			// Turn the waste over: oldest waste card (index 0) becomes the new top.
			for (int i = 0; i < wn; i++) {
				stock[i] = waste[wn - 1 - i];
			}
			sn = wn;
			wn = 0;
			stockKnown = true;
		}
		int card = stock[--sn];
		waste[wn++] = card;
		if (sn == 0) {
			stockKnown = true;
		}
	}

	private void applyToFound(Move m, Undo u) {
		int card;
		if (m.from == WASTE) {
			card = waste[--wn];
		} else {
			card = pile[m.from][pn[m.from] - 1];
			pn[m.from]--;
			if (pn[m.from] == dn[m.from] && dn[m.from] > 0) {
				dn[m.from]--;
				u.revealed = true;
				points += 5;
			}
		}
		u.card = card;
		u.to = suit(card);
		found[suit(card)]++;
		points += 10;
	}

	private void applyToTab(Move m, Undo u) {
		if (m.from == WASTE) {
			int card = waste[--wn];
			pile[m.to][pn[m.to]++] = card;
			points += 5;
			u.count = 1;
			return;
		}
		if (m.from == FOUND) {
			int s = m.index;
			int card = s * 13 + (--found[s]);
			pile[m.to][pn[m.to]++] = card;
			points -= 15;
			u.count = 1;
			return;
		}
		int cnt = pn[m.from] - m.index;
		u.count = cnt;
		if (m.index == dn[m.from] && dn[m.from] > 0) {
			dn[m.from]--;
			u.revealed = true;
			points += 5;
		}
		for (int i = 0; i < cnt; i++) {
			pile[m.to][pn[m.to]++] = pile[m.from][m.index + i];
		}
		pn[m.from] = m.index;
	}

	void undo(Undo u) {
		if (u.kind == DRAW) {
			sn = u.snBefore;
			wn = u.wnBefore;
			System.arraycopy(u.stockBefore, 0, stock, 0, sn);
			System.arraycopy(u.wasteBefore, 0, waste, 0, wn);
			points = u.pointsBefore;
			stockKnown = u.stockKnownBefore;
			return;
		}
		if (u.kind == AUTO) {
			points = u.pointsBefore;
			return;
		}
		if (u.kind == TO_FOUND) {
			undoToFound(u);
			return;
		}
		undoToTab(u);
	}

	private void undoToFound(Undo u) {
		points = u.pointsBefore;
		found[suit(u.card)]--;
		if (u.from == WASTE) {
			waste[wn++] = u.card;
			return;
		}
		if (u.revealed) {
			dn[u.from]++;
		}
		pile[u.from][pn[u.from]++] = u.card;
	}

	private void undoToTab(Undo u) {
		points = u.pointsBefore;
		if (u.from == WASTE) {
			int card = pile[u.to][--pn[u.to]];
			waste[wn++] = card;
			return;
		}
		if (u.from == FOUND) {
			int card = pile[u.to][--pn[u.to]];
			found[suit(card)]++;
			return;
		}
		int cnt = u.count;
		pn[u.to] -= cnt;
		for (int i = 0; i < cnt; i++) {
			pile[u.from][u.index + i] = pile[u.to][pn[u.to] + i];
		}
		pn[u.from] = u.index + cnt;
		if (u.revealed) {
			dn[u.from]++;
		}
	}

	/** Copy this position into the CLI's static tables and return. */
	void writeIntoGame() {
		Game.points = points;
		Game.drawPile.clear();
		for (int i = 0; i < sn; i++) {
			Game.drawPile.push(stock[i]);
		}
		Game.drawnCards.clear();
		for (int i = 0; i < wn; i++) {
			Game.drawnCards.push(waste[i]);
		}
		Game.faceUp.clear();
		for (int c = 0; c < 7; c++) {
			Game.layout[c].clear();
			for (int i = 0; i < pn[c]; i++) {
				Game.layout[c].add(pile[c][i]);
				if (i >= dn[c]) {
					Game.faceUp.add(pile[c][i]);
				}
			}
		}
		for (int s = 0; s < 4; s++) {
			Game.foundationStacks[s].clear();
			for (int r = 0; r < found[s]; r++) {
				Game.foundationStacks[s].push(s * 13 + r);
			}
		}
	}

	static Klondike fromGame() {
		Klondike g = new Klondike();
		g.points = Game.points;
		g.sn = Game.drawPile.size();
		for (int i = 0; i < g.sn; i++) {
			g.stock[i] = Game.drawPile.get(i);
		}
		g.wn = Game.drawnCards.size();
		for (int i = 0; i < g.wn; i++) {
			g.waste[i] = Game.drawnCards.get(i);
		}
		for (int s = 0; s < 4; s++) {
			g.found[s] = Game.foundationStacks[s].size();
		}
		boolean allStockSeen = true;
		for (int i = 0; i < g.sn; i++) {
			if (!Game.seenCards[g.stock[i]]) {
				allStockSeen = false;
				break;
			}
		}
		g.stockKnown = allStockSeen;
		for (int c = 0; c < 7; c++) {
			int n = Game.layout[c].size();
			g.pn[c] = n;
			int down = 0;
			for (int i = 0; i < n; i++) {
				int card = Game.layout[c].get(i);
				g.pile[c][i] = card;
				if (i == down && !Game.faceUp.contains(card)) {
					down++;
				}
			}
			g.dn[c] = down;
		}
		return g;
	}

	String describe(Move m) {
		if (m.kind == DRAW) {
			if (sn == 0) {
				return "Restart the draw pile and draw a card";
			}
			return "Draw a card";
		}
		if (m.kind == AUTO) {
			return "Autocomplete the foundation stacks";
		}
		if (m.kind == TO_FOUND) {
			int card = m.from == WASTE ? wasteTop() : tail(m.from);
			return "Move " + name(card) + " to the foundation";
		}
		if (m.from == WASTE) {
			return "Move " + name(wasteTop()) + " to pile " + (m.to + 1);
		}
		if (m.from == FOUND) {
			int card = m.index * 13 + (found[m.index] - 1);
			return "Move " + name(card) + " to pile " + (m.to + 1);
		}
		return "Move " + name(pile[m.from][m.index]) + " to pile " + (m.to + 1);
	}

	void checkInvariants() {
		boolean[] seen = new boolean[52];
		for (int c = 0; c < 7; c++) {
			if (pn[c] < 0 || dn[c] < 0 || dn[c] > pn[c]) {
				throw new IllegalStateException("bad pile " + c);
			}
			for (int i = 0; i < pn[c]; i++) {
				int card = pile[c][i];
				if (card < 0 || card > 51 || seen[card]) {
					throw new IllegalStateException("bad card " + card);
				}
				seen[card] = true;
			}
			for (int i = dn[c]; i + 1 < pn[c]; i++) {
				int a = pile[c][i];
				int b = pile[c][i + 1];
				if (!(rank(b) + 1 == rank(a) && red(a) != red(b))) {
					throw new IllegalStateException("broken run on pile " + c);
				}
			}
		}
		for (int s = 0; s < 4; s++) {
			if (found[s] < 0 || found[s] > 13) {
				throw new IllegalStateException("bad foundation");
			}
			for (int r = 0; r < found[s]; r++) {
				int card = s * 13 + r;
				if (seen[card]) {
					throw new IllegalStateException("dup foundation " + card);
				}
				seen[card] = true;
			}
		}
		if (sn < 0 || wn < 0 || sn > 24 || wn > 24) {
			throw new IllegalStateException("bad stock/waste");
		}
		for (int i = 0; i < sn; i++) {
			if (seen[stock[i]]) {
				throw new IllegalStateException("dup stock");
			}
			seen[stock[i]] = true;
		}
		for (int i = 0; i < wn; i++) {
			if (seen[waste[i]]) {
				throw new IllegalStateException("dup waste");
			}
			seen[waste[i]] = true;
		}
		for (boolean b : seen) {
			if (!b) {
				throw new IllegalStateException("missing card");
			}
		}
	}
}
