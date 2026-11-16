/* Player and Warp. Matches Solitaire/StrongAI.java, WarpMode.java, and WideBound.java.
   Legal player votes across shuffled hidden layouts and counts only proved wins.
   Warp searches the true layout, then retries complementary move orderings
   if the default 100,000-node search cannot prove a win. */
(function (S) {
	var DRAW = S.DRAW, TO_TAB = S.TO_TAB, TO_FOUND = S.TO_FOUND, WASTE = S.WASTE, FOUND = S.FOUND;
	var LEGAL_NODE_LIMIT = 25000;
	var WARP_NODE_LIMIT = 100000;
	var VOTE_SAMPLES = 8;
	var VOTE_NODE_LIMIT = 6000;
	var ENUM_HIDDEN = 4;
	var ORDER_HEURISTIC = 0;
	var ORDER_REVERSE_REST = 1;
	var ORDER_AS_IS = 2;
	var ORDER_FOUNDATION = 3;
	var ORDER_SHUFFLE1 = 4;
	var ORDER_SHUFFLE2 = 5;
	var RETRY_ORDERS = [ORDER_REVERSE_REST, ORDER_AS_IS, ORDER_FOUNDATION, ORDER_SHUFFLE1, ORDER_SHUFFLE2];
	var lastRoot = null;

	function safeFound(g, card) {
		var s = S.suit(card);
		var r = S.rank(card);
		if (r !== g.found[s]) return false;
		var twin = s ^ 1;
		var o1 = s < 2 ? 2 : 0;
		return r <= Math.min(g.found[o1], g.found[o1 + 1]) + 1 && r <= g.found[twin] + 2;
	}

	function uselessKing(g, from, index, to) {
		return g.piles[to].length === 0 && S.rank(g.piles[from][index]) === 12 && g.down[from] === 0 && index === 0;
	}

	function reveals(g) {
		var ms = [];
		for (var col = 0; col < 7; col++) {
			if (g.down[col] === 0 || g.down[col] >= g.piles[col].length) continue;
			var index = g.down[col];
			var card = g.piles[col][index];
			for (var to = 0; to < 7; to++) {
				if (to !== col && S.canPlace(g, card, to)) {
					ms.push({ kind: TO_TAB, from: col, index: index, to: to });
				}
			}
		}
		return ms;
	}

	function deepestReveal(g) {
		var revs = reveals(g);
		var best = null, bestScore = -1;
		for (var i = 0; i < revs.length; i++) {
			var depth = g.down[revs[i].from];
			var score = depth * 100 + g.down[revs[i].to] + (depth === 1 ? 180 : 0);
			if (score > bestScore) {
				bestScore = score;
				best = revs[i];
			}
		}
		return best;
	}

	function exposesHome(g, src, exposed) {
		for (var col = 0; col < 7; col++) {
			if (col === src || g.down[col] === 0 || g.down[col] >= g.piles[col].length) continue;
			var head = g.piles[col][g.down[col]];
			if (S.rank(head) + 1 === S.rank(exposed) && S.red(head) !== S.red(exposed)) return true;
		}
		return false;
	}

	function freeFoundation(g) {
		for (var col = 0; col < 7; col++) {
			var pile = g.piles[col];
			for (var index = g.down[col]; index < pile.length - 1; index++) {
				if (!S.canFound(g, pile[index])) continue;
				var run = pile[index + 1];
				for (var to = 0; to < 7; to++) {
					if (to !== col && S.canPlace(g, run, to) && !uselessKing(g, col, index + 1, to)) {
						return { kind: TO_TAB, from: col, index: index + 1, to: to };
					}
				}
			}
		}
		return null;
	}

	function worry(g) {
		if (!g.waste.length) return null;
		var drawn = S.wasteTop(g);
		for (var s = 0; s < 4; s++) {
			if (!g.found[s]) continue;
			var top = s * 13 + g.found[s] - 1;
			if (S.rank(drawn) + 1 !== S.rank(top) || S.red(drawn) === S.red(top)) continue;
			for (var to = 0; to < 7; to++) {
				if (S.canPlace(g, top, to)) return { kind: TO_TAB, from: FOUND, index: s, to: to };
			}
		}
		return null;
	}

	function heuristic(g) {
		var reveal = deepestReveal(g);
		if (reveal) return reveal;
		for (var pile = 0; pile < 7; pile++) {
			for (var index = g.down[pile]; index < g.piles[pile].length; index++) {
				if (index > 0 && index - 1 >= g.down[pile]) continue;
				for (var to = 0; to < 7; to++) {
					if (S.canPlace(g, g.piles[pile][index], to) && !uselessKing(g, pile, index, to)) {
						return { kind: TO_TAB, from: pile, index: index, to: to };
					}
				}
			}
		}
		if (g.waste.length) {
			var card = S.wasteTop(g);
			var hold = !g.stockKnown && S.rank(card) >= 9;
			if (!hold) {
				for (var dest = 0; dest < 7; dest++) {
					if (S.canPlace(g, card, dest)) return { kind: TO_TAB, from: WASTE, index: -1, to: dest };
				}
			}
		}
		for (var c = 0; c < 7; c++) {
			if (g.piles[c].length && S.canFound(g, g.piles[c][g.piles[c].length - 1])) {
				var tail = g.piles[c][g.piles[c].length - 1];
				return { kind: TO_FOUND, from: c, index: g.piles[c].length - 1, to: S.suit(tail) };
			}
		}
		var split = freeFoundation(g);
		if (split) return split;
		if (g.waste.length && S.canFound(g, S.wasteTop(g))) {
			return { kind: TO_FOUND, from: WASTE, index: -1, to: S.suit(S.wasteTop(g)) };
		}
		var back = worry(g);
		if (back) return back;
		if (S.canDraw(g)) return { kind: DRAW, from: -1, index: -1, to: -1 };
		return null;
	}

	function sameMove(a, b) {
		return a && b && a.kind === b.kind && a.from === b.from && a.index === b.index && a.to === b.to;
	}

	function options(g) {
		var ms = reveals(g);
		if (ms.length) return ms;
		ms = [];
		if (g.waste.length) {
			var card = S.wasteTop(g);
			for (var to = 0; to < 7; to++) {
				if (S.canPlace(g, card, to)) ms.push({ kind: TO_TAB, from: WASTE, index: -1, to: to });
			}
			if (S.canFound(g, card)) ms.push({ kind: TO_FOUND, from: WASTE, index: -1, to: S.suit(card) });
		}
		for (var c = 0; c < 7; c++) {
			if (!g.piles[c].length) continue;
			var tail = g.piles[c][g.piles[c].length - 1];
			if (safeFound(g, tail)) ms.push({ kind: TO_FOUND, from: c, index: g.piles[c].length - 1, to: S.suit(tail) });
		}
		if (S.canDraw(g)) ms.push({ kind: DRAW, from: -1, index: -1, to: -1 });
		if (!ms.length) {
			var h = heuristic(g);
			if (h) ms.push(h);
		}
		return ms;
	}

	function preferFirst(g, opts) {
		return orderedOptions(g, opts, ORDER_HEURISTIC);
	}

	function xorshift(seed) {
		var x = seed | 0;
		if (!x) x = 1;
		x ^= x << 13;
		x ^= x >>> 17;
		x ^= x << 5;
		return x | 0;
	}

	function hashStyle(g, salt) {
		var key = S.positionKey(g);
		var h = 0x811c9dc5;
		for (var i = 0; i < key.length; i++) {
			h ^= key.charCodeAt(i);
			h = Math.imul(h, 0x01000193);
		}
		return xorshift(h ^ (salt * 0x9e3779b9));
	}

	function shuffleMoves(opts, seed) {
		var s = seed | 0;
		for (var i = opts.length - 1; i > 0; i--) {
			s = xorshift(s);
			var j = (s >>> 0) % (i + 1);
			var t = opts[i];
			opts[i] = opts[j];
			opts[j] = t;
		}
	}

	function byKind(opts) {
		var found = [], tab = [], other = [];
		for (var i = 0; i < opts.length; i++) {
			if (opts[i].kind === TO_FOUND) found.push(opts[i]);
			else if (opts[i].kind === TO_TAB) tab.push(opts[i]);
			else other.push(opts[i]);
		}
		return found.concat(tab, other);
	}

	function orderedOptions(g, opts, style) {
		opts = opts ? opts.slice() : options(g);
		if (style === ORDER_AS_IS) return opts;
		if (style === ORDER_FOUNDATION) return byKind(opts);
		if (style === ORDER_SHUFFLE1 || style === ORDER_SHUFFLE2) {
			shuffleMoves(opts, hashStyle(g, style === ORDER_SHUFFLE1 ? 1 : 2));
			return opts;
		}
		var prefer = heuristic(g);
		if (prefer) {
			var found = false;
			for (var i = 0; i < opts.length; i++) {
				if (sameMove(opts[i], prefer)) {
					opts.splice(i, 1);
					opts.unshift(prefer);
					found = true;
					break;
				}
			}
			if (!found) opts.unshift(prefer);
		}
		if (style === ORDER_REVERSE_REST && opts.length > 2) {
			var a = 1, b = opts.length - 1;
			while (a < b) {
				var t = opts[a];
				opts[a] = opts[b];
				opts[b] = t;
				a++;
				b--;
			}
		}
		return opts;
	}

	function winningMove(g, nodeLimit, style) {
		var proved = winningMoveProved(g, nodeLimit, style || ORDER_HEURISTIC);
		return proved || heuristic(g);
	}

	function winningMoveProved(g, nodeLimit, style) {
		lastRoot = g;
		if (S.won(g)) return { kind: S.AUTO, from: -1, index: -1, to: -1 };
		style = style || ORDER_HEURISTIC;
		var opts = orderedOptions(g, options(g), style);
		var limit = nodeLimit || LEGAL_NODE_LIMIT;
		for (var i = 0; i < opts.length; i++) {
			var copy = S.clone(g);
			S.apply(copy, opts[i]);
			if (solved(copy, limit, style)) return opts[i];
		}
		return null;
	}

	function evaluateRoots(g, nodeLimit, style) {
		lastRoot = g;
		style = style || ORDER_HEURISTIC;
		var opts = orderedOptions(g, options(g), style);
		var result = [];
		for (var i = 0; i < opts.length; i++) {
			var copy = S.clone(g);
			S.apply(copy, opts[i]);
			result[i] = searchResult(copy, nodeLimit, style);
		}
		return { opts: opts, result: result };
	}

	function solved(g, nodeLimit, style) {
		return searchResult(g, nodeLimit, style || ORDER_HEURISTIC) === 1;
	}

	function searchResult(g, nodeLimit, style) {
		var memo = {};
		var stack = {};
		var nodes = 0;
		function dfs(state) {
			if (S.won(state)) return 1;
			if (nodes++ > nodeLimit) return -1;
			var h = S.positionKey(state);
			if (memo[h] !== undefined) return memo[h];
			if (stack[h]) return 0;
			stack[h] = true;
			var opts = orderedOptions(state, options(state), style);
			if (!opts.length) {
				delete stack[h];
				memo[h] = 0;
				return 0;
			}
			var unknown = false;
			for (var i = 0; i < opts.length; i++) {
				var copy = S.clone(state);
				S.apply(copy, opts[i]);
				var r = dfs(copy);
				if (r === 1) {
					delete stack[h];
					memo[h] = 1;
					return 1;
				}
				if (r < 0) unknown = true;
			}
			delete stack[h];
			if (!unknown) memo[h] = 0;
			return unknown ? -1 : 0;
		}
		return dfs(g);
	}

	function factorial(n) {
		var f = 1;
		for (var i = 2; i <= n; i++) f *= i;
		return f;
	}

	function permutation(index, src) {
		var rest = src.slice();
		var dest = [];
		var n = src.length;
		for (var i = 0; i < n; i++) {
			var f = factorial(n - 1 - i);
			var pick = f === 0 ? 0 : Math.floor(index / f);
			if (f !== 0) index %= f;
			dest.push(rest[pick]);
			rest.splice(pick, 1);
		}
		return dest;
	}

	function sameMoveListIndex(opts, want) {
		for (var i = 0; i < opts.length; i++) {
			if (sameMove(opts[i], want)) return i;
		}
		return -1;
	}

	function clearProvedLeader(proved, examined, rounds) {
		var best = 0, second = 0;
		for (var i = 0; i < proved.length; i++) {
			if (proved[i] > best) {
				second = best;
				best = proved[i];
			} else if (proved[i] > second) second = proved[i];
		}
		if (best <= 0) return false;
		return best > second + (rounds - examined);
	}

	function hiddenCount(g) {
		var n = 0;
		for (var c = 0; c < 7; c++) n += g.down[c];
		if (!g.stockKnown) n += g.stock.length;
		return n;
	}

	function fillHidden(g) {
		var ids = [];
		for (var c = 0; c < 7; c++) {
			for (var i = 0; i < g.down[c]; i++) ids.push(g.piles[c][i]);
		}
		if (!g.stockKnown) {
			for (var j = 0; j < g.stock.length; j++) ids.push(g.stock[j]);
		}
		return ids;
	}

	function assignHidden(g, ids) {
		var k = 0;
		for (var c = 0; c < 7; c++) {
			for (var i = 0; i < g.down[c]; i++) g.piles[c][i] = ids[k++];
		}
		if (!g.stockKnown) {
			for (var j = 0; j < g.stock.length; j++) g.stock[j] = ids[k++];
		}
	}

	function shuffle(ids, rng) {
		for (var i = ids.length - 1; i > 0; i--) {
			var j = Math.floor(rng() * (i + 1));
			var t = ids[i];
			ids[i] = ids[j];
			ids[j] = t;
		}
	}

	function strongChoose(g, rng) {
		if (S.won(g)) return { kind: S.AUTO, from: -1, index: -1, to: -1 };
		var hidden = hiddenCount(g);
		if (hidden <= 0) {
			var seen = winningMove(g, LEGAL_NODE_LIMIT);
			return seen || heuristic(g);
		}
		var opts = preferFirst(g, options(g));
		if (!opts.length) return heuristic(g);
		var base = fillHidden(g);
		var enumerate = hidden <= ENUM_HIDDEN;
		var rounds = enumerate ? factorial(hidden) : VOTE_SAMPLES;
		var proved = [];
		for (var p = 0; p < opts.length; p++) proved[p] = 0;
		var examined = 0;
		for (var r = 0; r < rounds; r++) {
			var ids = enumerate ? permutation(r, base) : base.slice();
			if (!enumerate) shuffle(ids, rng);
			var world = S.clone(g);
			assignHidden(world, ids);
			var ev = evaluateRoots(world, VOTE_NODE_LIMIT, ORDER_HEURISTIC);
			for (var i = 0; i < opts.length; i++) {
				var found = sameMoveListIndex(ev.opts, opts[i]);
				if (found >= 0 && ev.result[found] === 1) proved[i]++;
			}
			examined++;
			if (!enumerate && clearProvedLeader(proved, examined, rounds)) break;
		}
		var best = 0;
		for (var b = 1; b < proved.length; b++) {
			if (proved[b] > proved[best]) best = b;
		}
		if (proved[best] > 0) return opts[best];
		var fallbackIds = fillHidden(g);
		shuffle(fallbackIds, rng);
		var fallback = S.clone(g);
		assignHidden(fallback, fallbackIds);
		return winningMove(fallback, LEGAL_NODE_LIMIT) || heuristic(g);
	}

	/** Perfect-information move. Reads face-down cards and undrawn stock. */
	function warpChoose(g) {
		if (S.won(g)) return { kind: S.AUTO, from: -1, index: -1, to: -1 };
		var proved = winningMoveProved(g, WARP_NODE_LIMIT, ORDER_HEURISTIC);
		if (proved) return proved;
		for (var i = 0; i < RETRY_ORDERS.length; i++) {
			proved = winningMoveProved(g, WARP_NODE_LIMIT, RETRY_ORDERS[i]);
			if (proved) return proved;
		}
		return heuristic(g);
	}

	function strongPlay(g, rng) {
		var seen = {};
		var moves = 0;
		while (moves < 5000) {
			if (S.won(g)) {
				S.finish(g);
				return { won: true, moves: moves };
			}
			var key = S.positionKey(g);
			if (seen[key]) return { won: false, moves: moves };
			seen[key] = true;
			var m = strongChoose(g, rng);
			if (!m || m.kind === S.AUTO) {
				if (S.won(g)) S.finish(g);
				return { won: S.won(g), moves: moves };
			}
			S.apply(g, m);
			moves++;
		}
		return { won: false, moves: moves };
	}

	S.heuristic = heuristic;
	S.strongChoose = strongChoose;
	S.strongPlay = strongPlay;
	S.warpChoose = warpChoose;
	S.searchRoot = function () { return lastRoot; };
	S.safeFound = safeFound;
})(typeof module === "object" && module.exports ? require("./engine.js") : window.Solitaire);
