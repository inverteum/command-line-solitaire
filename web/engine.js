/* 1-card Klondike, same rules and AI as Solitaire/SmartAI.java.
   No dependencies. Works in the browser and under Node. */
(function (root, factory) {
	var api = factory();
	if (typeof module === "object" && module.exports) {
		module.exports = api;
	} else {
		root.Solitaire = api;
	}
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
	var DRAW = 0;
	var TO_TAB = 1;
	var TO_FOUND = 2;
	var AUTO = 3;
	var WASTE = -1;
	var FOUND = -2;
	var RANKS = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"];
	var SUITS = ["S", "C", "H", "D"];
	var SUIT_GLYPH = ["\u2660", "\u2663", "\u2665", "\u2666"];

	function suit(card) { return (card / 13) | 0; }
	function rank(card) { return card % 13; }
	function red(card) { return card >= 26; }

	function cardName(card) {
		return RANKS[rank(card)] + SUITS[suit(card)];
	}

	function cardLabel(card) {
		return RANKS[rank(card)] + SUIT_GLYPH[suit(card)];
	}

	function mulberry32(seed) {
		var a = seed >>> 0;
		return function () {
			a |= 0;
			a = (a + 0x6D2B79F5) | 0;
			var t = Math.imul(a ^ (a >>> 15), 1 | a);
			t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
			return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
		};
	}

	function shuffle(rng) {
		var deck = [];
		for (var i = 0; i < 52; i++) deck.push(i);
		for (var j = 51; j > 0; j--) {
			var k = Math.floor(rng() * (j + 1));
			var tmp = deck[j];
			deck[j] = deck[k];
			deck[k] = tmp;
		}
		return deck;
	}

	function deal(rng) {
		var deck = shuffle(rng || Math.random);
		return fromDeck(deck);
	}

	/* deck[0..27] fill the tableau left to right, growing 1..7.
	   deck[28] is the bottom of the stock and deck[51] is the first draw. */
	function fromDeck(deck) {
		var piles = [];
		var down = [];
		var idx = 0;
		for (var col = 0; col < 7; col++) {
			var size = col + 1;
			var pile = [];
			for (var i = 0; i < size; i++) pile.push(deck[idx++]);
			piles.push(pile);
			down.push(size - 1);
		}
		var stock = [];
		for (var s = 28; s < 52; s++) stock.push(deck[s]);
		return {
			piles: piles,
			down: down,
			found: [0, 0, 0, 0],
			stock: stock,
			waste: [],
			points: 0,
			stockKnown: false
		};
	}

	function clone(g) {
		return {
			piles: g.piles.map(function (p) { return p.slice(); }),
			down: g.down.slice(),
			found: g.found.slice(),
			stock: g.stock.slice(),
			waste: g.waste.slice(),
			points: g.points,
			stockKnown: g.stockKnown
		};
	}

	function faceDown(g) {
		var n = 0;
		for (var i = 0; i < 7; i++) n += g.down[i];
		return n;
	}

	function won(g) {
		if (g.stock.length || g.waste.length) return false;
		for (var i = 0; i < 7; i++) if (g.down[i] > 0) return false;
		return true;
	}

	function canDraw(g) {
		return g.stock.length > 0 || g.waste.length > 0;
	}

	function wasteTop(g) {
		return g.waste[g.waste.length - 1];
	}

	function tail(g, col) {
		return g.piles[col][g.piles[col].length - 1];
	}

	function canPlace(g, card, col) {
		var pile = g.piles[col];
		if (pile.length === 0) return rank(card) === 12;
		var onto = pile[pile.length - 1];
		return rank(card) + 1 === rank(onto) && red(card) !== red(onto);
	}

	function canFound(g, card) {
		return rank(card) === g.found[suit(card)];
	}

	function uselessKingSlide(g, from, index, to) {
		if (g.piles[to].length !== 0 || rank(g.piles[from][index]) !== 12) return false;
		return g.down[from] === 0 && index === 0;
	}

	function drawMove() { return { kind: DRAW, from: -1, index: -1, to: -1 }; }
	function autoMove() { return { kind: AUTO, from: -1, index: -1, to: -1 }; }

	function apply(g, m) {
		if (m.kind === DRAW) return applyDraw(g);
		if (m.kind === AUTO) return finish(g);
		if (m.kind === TO_FOUND) return applyFound(g, m);
		return applyTab(g, m);
	}

	function applyDraw(g) {
		var delta = 0;
		if (g.stock.length === 0) {
			g.points -= 25;
			delta -= 25;
			var next = [];
			for (var i = 0; i < g.waste.length; i++) next.push(g.waste[g.waste.length - 1 - i]);
			g.stock = next;
			g.waste = [];
			g.stockKnown = true;
		}
		var card = g.stock.pop();
		g.waste.push(card);
		if (g.stock.length === 0) g.stockKnown = true;
		return delta;
	}

	function applyFound(g, m) {
		var delta = 0;
		var card;
		if (m.from === WASTE) {
			card = g.waste.pop();
		} else {
			var pile = g.piles[m.from];
			card = pile.pop();
			if (pile.length === g.down[m.from] && g.down[m.from] > 0) {
				g.down[m.from]--;
				g.points += 5;
				delta += 5;
			}
		}
		g.found[suit(card)]++;
		g.points += 10;
		return delta + 10;
	}

	function applyTab(g, m) {
		if (m.from === WASTE) {
			g.piles[m.to].push(g.waste.pop());
			g.points += 5;
			return 5;
		}
		if (m.from === FOUND) {
			var s = m.index;
			var card = s * 13 + (--g.found[s]);
			g.piles[m.to].push(card);
			g.points -= 15;
			return -15;
		}
		var src = g.piles[m.from];
		var revealed = m.index === g.down[m.from] && g.down[m.from] > 0;
		var moved = src.splice(m.index);
		if (revealed) {
			g.down[m.from]--;
			g.points += 5;
		}
		var dest = g.piles[m.to];
		for (var i = 0; i < moved.length; i++) dest.push(moved[i]);
		return revealed ? 5 : 0;
	}

	function finish(g) {
		var n = 0;
		for (var c = 0; c < 7; c++) {
			n += g.piles[c].length;
			g.piles[c] = [];
			g.down[c] = 0;
		}
		g.points += n * 10;
		g.found = [13, 13, 13, 13];
		g.stock = [];
		g.waste = [];
		return n * 10;
	}

	/* Undo only the latest waste card, matching the CLI. Costs 10 points. */
	function undoDraw(g) {
		if (!g.waste.length) return false;
		g.stock.push(g.waste.pop());
		g.points -= 10;
		return true;
	}

	function bestReveal(g) {
		var best = null;
		var bestScore = -1;
		for (var col = 0; col < 7; col++) {
			if (g.down[col] === 0 || g.down[col] >= g.piles[col].length) continue;
			var index = g.down[col];
			var card = g.piles[col][index];
			for (var to = 0; to < 7; to++) {
				if (to === col || !canPlace(g, card, to)) continue;
				var score = g.down[col] * 100 + g.down[to];
				if (score > bestScore) {
					bestScore = score;
					best = { kind: TO_TAB, from: col, index: index, to: to };
				}
			}
		}
		return best;
	}

	function freeFoundation(g) {
		for (var col = 0; col < 7; col++) {
			var pile = g.piles[col];
			for (var index = g.down[col]; index < pile.length - 1; index++) {
				if (!canFound(g, pile[index])) continue;
				var run = pile[index + 1];
				for (var to = 0; to < 7; to++) {
					if (to !== col && canPlace(g, run, to) && !uselessKingSlide(g, col, index + 1, to)) {
						return { kind: TO_TAB, from: col, index: index + 1, to: to };
					}
				}
			}
		}
		return null;
	}

	function worryForWaste(g) {
		if (!g.waste.length) return null;
		var drawn = wasteTop(g);
		for (var s = 0; s < 4; s++) {
			if (g.found[s] === 0) continue;
			var top = s * 13 + g.found[s] - 1;
			if (rank(drawn) + 1 !== rank(top) || red(drawn) === red(top)) continue;
			for (var to = 0; to < 7; to++) {
				if (!canPlace(g, top, to)) continue;
				var copy = clone(g);
				apply(copy, { kind: TO_TAB, from: FOUND, index: s, to: to });
				if (copy.waste.length && canPlace(copy, wasteTop(copy), to)) {
					return { kind: TO_TAB, from: FOUND, index: s, to: to };
				}
			}
		}
		return null;
	}

	function legacyStep(g) {
		for (var pile = 0; pile < 7; pile++) {
			for (var index = g.down[pile]; index < g.piles[pile].length; index++) {
				if (index > 0 && index - 1 >= g.down[pile]) continue;
				for (var to = 0; to < 7; to++) {
					if (canPlace(g, g.piles[pile][index], to) && !uselessKingSlide(g, pile, index, to)) {
						return { kind: TO_TAB, from: pile, index: index, to: to };
					}
				}
			}
		}
		if (g.waste.length) {
			var card = wasteTop(g);
			for (var dest = 0; dest < 7; dest++) {
				if (canPlace(g, card, dest)) return { kind: TO_TAB, from: WASTE, index: -1, to: dest };
			}
		}
		for (var col = 0; col < 7; col++) {
			if (g.piles[col].length && canFound(g, tail(g, col))) {
				return { kind: TO_FOUND, from: col, index: g.piles[col].length - 1, to: suit(tail(g, col)) };
			}
		}
		var split = freeFoundation(g);
		if (split) return split;
		if (g.waste.length && canFound(g, wasteTop(g))) {
			return { kind: TO_FOUND, from: WASTE, index: -1, to: suit(wasteTop(g)) };
		}
		var worry = worryForWaste(g);
		if (worry) return worry;
		if (canDraw(g)) return drawMove();
		return null;
	}

	function choose(g) {
		return bestReveal(g) || legacyStep(g);
	}

	function describe(g, m) {
		if (!m) return "No legal move";
		if (m.kind === DRAW) {
			return g.stock.length ? "Draw a card" : "Restart the draw pile and draw a card";
		}
		if (m.kind === AUTO) return "Autocomplete the foundations";
		if (m.kind === TO_FOUND) {
			var card = m.from === WASTE ? wasteTop(g) : tail(g, m.from);
			return "Move " + cardName(card) + " to the foundation";
		}
		if (m.from === WASTE) return "Move " + cardName(wasteTop(g)) + " to pile " + (m.to + 1);
		if (m.from === FOUND) {
			var back = m.index * 13 + g.found[m.index] - 1;
			return "Move " + cardName(back) + " to pile " + (m.to + 1);
		}
		return "Move " + cardName(g.piles[m.from][m.index]) + " to pile " + (m.to + 1);
	}

	function sameMove(a, b) {
		return !!a && !!b && a.kind === b.kind && a.from === b.from && a.index === b.index && a.to === b.to;
	}

	function play(g) {
		var seen = {};
		var moves = 0;
		while (moves < 5000) {
			if (won(g)) {
				finish(g);
				return { won: true, moves: moves };
			}
			var key = hash(g);
			if (seen[key]) return { won: false, moves: moves, faceDown: faceDown(g) };
			seen[key] = true;
			var m = choose(g);
			if (!m) return { won: false, moves: moves, faceDown: faceDown(g) };
			apply(g, m);
			moves++;
		}
		return { won: false, moves: moves, faceDown: faceDown(g) };
	}

	function hash(g) {
		var parts = [];
		for (var c = 0; c < 7; c++) {
			parts.push(g.down[c]);
			parts.push(g.piles[c].join("."));
		}
		parts.push(g.found.join("."));
		parts.push(g.stock.join("."));
		parts.push(g.waste.join("."));
		return parts.join("|");
	}

	function check(g) {
		var seen = {};
		function take(card) {
			if (card < 0 || card > 51 || seen[card]) throw new Error("bad card " + card);
			seen[card] = true;
		}
		for (var c = 0; c < 7; c++) {
			var pile = g.piles[c];
			if (g.down[c] < 0 || g.down[c] > pile.length) throw new Error("bad down");
			for (var i = 0; i < pile.length; i++) take(pile[i]);
			for (var j = g.down[c]; j + 1 < pile.length; j++) {
				var a = pile[j], b = pile[j + 1];
				if (!(rank(b) + 1 === rank(a) && red(a) !== red(b))) throw new Error("broken run");
			}
		}
		for (var s = 0; s < 4; s++) {
			for (var r = 0; r < g.found[s]; r++) take(s * 13 + r);
		}
		g.stock.forEach(take);
		g.waste.forEach(take);
		for (var k = 0; k < 52; k++) if (!seen[k]) throw new Error("missing " + k);
	}

	return {
		DRAW: DRAW,
		TO_TAB: TO_TAB,
		TO_FOUND: TO_FOUND,
		AUTO: AUTO,
		WASTE: WASTE,
		FOUND: FOUND,
		RANKS: RANKS,
		SUITS: SUITS,
		SUIT_GLYPH: SUIT_GLYPH,
		suit: suit,
		rank: rank,
		red: red,
		cardName: cardName,
		cardLabel: cardLabel,
		mulberry32: mulberry32,
		deal: deal,
		fromDeck: fromDeck,
		clone: clone,
		faceDown: faceDown,
		won: won,
		canDraw: canDraw,
		wasteTop: wasteTop,
		canPlace: canPlace,
		canFound: canFound,
		apply: apply,
		finish: finish,
		undoDraw: undoDraw,
		choose: choose,
		describe: describe,
		sameMove: sameMove,
		play: play,
		check: check,
		positionKey: hash
	};
});
