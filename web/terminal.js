/* Command-line Solitaire UI. Same prompts and board layout as Game.java. */
(function () {
	"use strict";

	var S = window.Solitaire;
	var outputEl = document.getElementById("output");
	var formEl = document.getElementById("prompt-form");
	var inputEl = document.getElementById("prompt");
	var termEl = document.getElementById("terminal");

	var SMILEY = [
		"888888888888888888888888888888888",
		"88888888888888888____88888____888",
		"8888888888888888______888_____888",
		"8888888888888888______888_____888",
		"8888888888888888______888_____888",
		"8888888888888888______88_____8888",
		"8888888888888888______88_____8888",
		"8888888888888888______88_____8888",
		"8888888888_____8______88_____8888",
		"8888___88______8______8_____88888",
		"888_____8______8______8_____88888",
		"888_____8______8______8_____88888",
		"888_____8______8______8_____88888",
		"888_____8____88888888888888888888",
		"8_8_____8___88________________888",
		"8_8_____8__88__________________88",
		"8__888888_888_________888_______8",
		"8_________88________8___________8",
		"8____________8888888____________8",
		"88_____________88_______________8",
		"88_______________88_____________8",
		"888______________8_____________88",
		"888_______________8___________888",
		"88888_____________8__________8888",
		"888888_____________________888888",
		"888888____________________8888888"
	].join("\n");

	var game = null;
	var noWinnable = false;
	var waiting = null;
	var aiTimer = null;
	var revealHiddenTableau = false;
	var closed = false;

	function paddedName(card) {
		var name = S.cardName(card);
		return name.length === 3 ? name : " " + name;
	}

	function bareName(card) {
		return S.cardName(card);
	}

	function print(text) {
		outputEl.textContent += text;
		outputEl.scrollTop = outputEl.scrollHeight;
	}

	function println(text) {
		print((text == null ? "" : String(text)) + "\n");
	}

	function printNotice(message) {
		println("");
		println("*** " + message + " ***");
	}

	function focusInput() {
		if (!inputEl.disabled) inputEl.focus();
	}

	function setInputEnabled(on) {
		inputEl.disabled = !on;
		if (on) focusInput();
	}

	function ask(handler) {
		waiting = handler;
		setInputEnabled(true);
	}

	function parseYesNo(raw) {
		var trimmed = String(raw || "").trim();
		if (!/^\d+$/.test(trimmed)) return null;
		var n = parseInt(trimmed, 10);
		if (n === 1 || n === 2) return n;
		return 0;
	}

	function requireYesNo(raw, onValid) {
		var n = parseYesNo(raw);
		if (n === 1 || n === 2) {
			onValid(n);
			return;
		}
		println("Please enter a number from 1-2.\n");
	}

	function firstExampleCard() {
		for (var col = 0; col < 7; col++) {
			var pile = game.piles[col];
			for (var i = game.down[col]; i < pile.length; i++) {
				return bareName(pile[i]);
			}
		}
		if (game.waste.length) return bareName(S.wasteTop(game));
		return "AD";
	}

	function locate(card) {
		if (game.waste.length && S.wasteTop(game) === card) {
			return { from: S.WASTE, index: -1, kind: 1 };
		}
		for (var s = 0; s < 4; s++) {
			if (game.found[s] > 0 && s * 13 + game.found[s] - 1 === card) {
				return { from: S.FOUND, index: s, kind: 2 };
			}
		}
		for (var col = 0; col < 7; col++) {
			var pile = game.piles[col];
			for (var i = game.down[col]; i < pile.length; i++) {
				if (pile[i] === card) {
					return { from: col, index: i, kind: 0 };
				}
			}
		}
		return null;
	}

	function parseCardToken(raw) {
		var text = String(raw || "").trim().toUpperCase();
		if (!text) return null;
		if (text.length === 2 || (text.length === 3 && text.slice(0, 2) === "10")) {
			for (var c = 0; c < 52; c++) {
				if (S.cardName(c) === text) return c;
			}
		}
		return null;
	}

	function isCardToken(raw) {
		return parseCardToken(raw) != null;
	}

	function formatBoard() {
		var lines = [];
		var drawn = game.waste.length ? paddedName(S.wasteTop(game)) + "          " : "[none]       ";
		var found = "";
		for (var s = 0; s < 4; s++) {
			if (game.found[s] > 0) {
				var top = s * 13 + game.found[s] - 1;
				found += paddedName(top);
			}
		}
		lines.push("Drawn: " + drawn + "Foundation Stacks: " + found + "       Points: " + game.points);

		var longest = 1;
		for (var col = 0; col < 7; col++) {
			if (game.piles[col].length > longest) longest = game.piles[col].length;
		}
		var header = [];
		for (var h = 0; h < 7; h++) header.push(" " + (h + 1) + " ");
		lines.push(header.join("  "));
		for (var row = 0; row < longest; row++) {
			var cells = [];
			for (var c = 0; c < 7; c++) {
				var pile = game.piles[c];
				if (row < pile.length) {
					cells.push(row < game.down[c] && !revealHiddenTableau ? "---" : paddedName(pile[row]));
				} else {
					cells.push("   ");
				}
			}
			lines.push(cells.join("  "));
		}
		lines.push("");
		return lines.join("\n");
	}

	function foundationsComplete() {
		return game.found[0] === 13 && game.found[1] === 13 && game.found[2] === 13 && game.found[3] === 13;
	}

	function printMenu() {
		var line = "Would you like to draw, or move a card to a tableau pile or to a foundation \nstack? 1) draw 2) tableau 3) foundation 4) hint 5) exit 6) restart";
		if (game.waste.length) line += " 7) undo last draw";
		println(line);
		println("Computer play: \"player\" or \"warp\".");
		ask(onMain);
	}

	function showBoardAndPrompt() {
		if (closed) return;
		if (!noWinnable && S.won(game) && !foundationsComplete()) {
			println("");
			println("You can win Solitaire right now.");
			println("Do you want to automatically fill all the foundation stacks? 1) Yes 2) No");
			ask(function (raw) {
				requireYesNo(raw, function (choice) {
					if (choice === 1) {
						var added = S.finish(game);
						println("\nAll cards moved to foundation stack. Points +" + added + ".");
					} else {
						noWinnable = true;
						println("You chose not to fill in all the foundation stacks automatically.\n");
					}
					continueAfterBoard();
				});
			});
			return;
		}
		continueAfterBoard();
	}

	function continueAfterBoard() {
		print("\n" + formatBoard());
		if (foundationsComplete()) {
			onWin();
			return;
		}
		printMenu();
	}

	function rememberScore(score) {
		var board = [];
		try {
			board = JSON.parse(localStorage.getItem("solitaire-top-scores") || "[]");
		} catch (err) {
			board = [];
		}
		if (!Array.isArray(board)) board = [];
		board.push({ score: score, fresh: true });
		board.sort(function (a, b) { return b.score - a.score; });
		board = board.slice(0, 10);
		try {
			localStorage.setItem("solitaire-top-scores", JSON.stringify(board.map(function (row) {
				return { score: row.score };
			})));
		} catch (err2) {}
		return board;
	}

	function onWin() {
		println("");
		println(SMILEY);
		println("You won Solitaire! Congratulations!");
		var board = rememberScore(game.points);
		if (board.length) {
			var ranks = board.map(function (row, i) {
				return (i + 1) + ". " + row.score + (row.fresh ? "  (this game)" : "");
			});
			println("Top 10 scores on this browser:");
			println(ranks.join("\n"));
		}
		println("Do you want to play again? 1) Yes 2) No");
		ask(function (raw) {
			requireYesNo(raw, function (choice) {
				if (choice === 1) startGame();
				else {
					println("Thank you for playing Solitaire.");
					closed = true;
					println("Type 1 to play again.");
					ask(waitToReplay);
				}
			});
		});
	}

	function waitToReplay(again) {
		if (String(again || "").trim() === "1") startGame();
		else {
			println("Type 1 to play again.");
			ask(waitToReplay);
		}
	}

	function startGame() {
		if (aiTimer) {
			clearTimeout(aiTimer);
			aiTimer = null;
		}
		revealHiddenTableau = false;
		closed = false;
		noWinnable = false;
		waiting = null;
		game = S.deal(Math.random);
		println("Welcome to Solitaire!");
		showBoardAndPrompt();
	}

	function onMain(raw) {
		var choiceStr = String(raw || "").trim().toUpperCase();
		if (!choiceStr) {
			ask(onMain);
			return;
		}

		var upperLimit = game.waste.length ? 7 : 6;
		var slow = choiceStr.endsWith(" SLOW");
		var modeName = slow ? choiceStr.slice(0, choiceStr.length - 5).trim() : choiceStr;

		if (choiceStr === "AI" || choiceStr === "START" || modeName === "PLAYER" || modeName === "LEGAL PLAYER") {
			runAi(slow || choiceStr === "START", false);
			return;
		}
		if (modeName === "WARP" || modeName === "WARP MODE") {
			runAi(slow, true);
			return;
		}

		try {
			if (choiceStr.length > 1 && choiceStr.charAt(0) === "3" && isCardToken(choiceStr.slice(1))) {
				println("");
				playFoundation(choiceStr.slice(1));
				return;
			}
			var lastIndex = choiceStr.length - 1;
			if (lastIndex > 0 && choiceStr.charAt(0) === "2" && isCardToken(choiceStr.slice(1, lastIndex))) {
				var pile = parseInt(choiceStr.charAt(lastIndex), 10);
				if (pile >= 1 && pile <= 7) {
					println("");
					playTableau(choiceStr.slice(1, lastIndex), pile);
					return;
				}
			}
			var choice = parseInt(choiceStr, 10);
			if (String(choice) !== choiceStr || choice < 1 || choice > upperLimit) {
				println("Please enter a number from 1-" + upperLimit + ", or type player or warp.\n");
				ask(onMain);
				return;
			}
			println("");
			playChoice(choice);
		} catch (err) {
			println("Please enter a number from 1-" + upperLimit + ", or type player or warp.\n");
			ask(onMain);
		}
	}

	function playChoice(choice) {
		if (choice === 1) return playDraw();
		if (choice === 2) return playTableau(null, -1);
		if (choice === 3) return playFoundation(null);
		if (choice === 4) return playHint();
		if (choice === 5) return confirmAction("exit");
		if (choice === 6) return confirmAction("restart");
		if (choice === 7) return playUndo();
	}

	function playDraw() {
		if (!game.stock.length) {
			if (!game.waste.length) {
				printNotice("There are no cards left to draw.");
				showBoardAndPrompt();
				return;
			}
			println("All cards have been drawn. Would you like to restart the draw pile? 1) Yes 2) No");
			println("Your points will be deducted by 25.");
			ask(function (raw) {
				requireYesNo(raw, function (choice) {
					if (choice === 1) {
						println("You have restarted the draw pile. Points -25.");
						S.apply(game, { kind: S.DRAW, from: -1, index: -1, to: -1 });
						println("You have drawn a new card.");
					} else {
						println("You chose not to restart the draw pile.");
					}
					showBoardAndPrompt();
				});
			});
			return;
		}
		S.apply(game, { kind: S.DRAW, from: -1, index: -1, to: -1 });
		println("You have drawn a new card.");
		showBoardAndPrompt();
	}

	function playHint() {
		if (S.won(game)) {
			println("Autocomplete will finish the game.");
		} else {
			var move = S.heuristic(game);
			if (!move) println("This is going to be a hard game.");
			else println(S.describe(game, move) + ".");
		}
		showBoardAndPrompt();
	}

	function confirmAction(action) {
		println("Are you sure you want to " + action + "? 1) Yes 2) No");
		ask(function (raw) {
			requireYesNo(raw, function (choice) {
				if (choice === 2) {
					println("You chose not to " + action + ".");
					showBoardAndPrompt();
					return;
				}
				if (action === "exit") {
					try { rememberScore(game.points); } catch (err) {}
					println("You have chosen to leave Solitaire.");
					println("Thank you for playing Solitaire.");
					println("\nThis window will not close. Type 1 to play again.");
					closed = true;
					ask(waitToReplay);
					return;
				}
				try { rememberScore(game.points); } catch (err2) {}
				println("You have chosen to restart the game.");
				startGame();
			});
		});
	}

	function playUndo() {
		if (!game.waste.length) {
			showBoardAndPrompt();
			return;
		}
		var warning = game.points < 10
			? "Your score will be reset to 0"
			: "You will lose 10 points";
		println(warning + " if you undo your last draw.");
		println("Are you sure you want to undo your last draw? 1) Yes 2) No");
		ask(function (raw) {
			requireYesNo(raw, function (choice) {
				if (choice === 2) {
					println("You chose not to undo your last draw.");
				} else {
					S.undoDraw(game);
					print("You have undone your last draw. Points -10.");
				}
				showBoardAndPrompt();
			});
		});
	}

	function promptForCard(mode, shortcut, pile) {
		if (shortcut != null) {
			return handleCardInput(mode, shortcut, pile, true);
		}
		println("Which card, i.e. \"" + firstExampleCard() + "\"? Type \"c\" to cancel.");
		ask(function (raw) {
			handleCardInput(mode, raw, pile, false);
		});
	}

	function handleCardInput(mode, raw, pile, fromShortcut) {
		var text = String(raw || "").trim().toUpperCase();
		if (text === "C") {
			println("You chose not to pick a card.");
			if (!fromShortcut) println("");
			showBoardAndPrompt();
			return;
		}
		var card = parseCardToken(text);
		if (card == null) {
			printNotice("Invalid input. Please enter a card that can be moved.");
			if (fromShortcut) {
				showBoardAndPrompt();
				return;
			}
			println("");
			promptForCard(mode, null, pile);
			return;
		}
		var loc = locate(card);
		if (!loc) {
			var dest = mode === 2
				? (pile > 0 ? "to tableau pile " + pile + "." : "to a tableau pile.")
				: "to a foundation stack.";
			printNotice(bareName(card) + " cannot be moved " + dest);
			println("Please enter a card that can be moved.");
			if (fromShortcut) {
				showBoardAndPrompt();
				return;
			}
			println("");
			promptForCard(mode, null, pile);
			return;
		}
		if (mode === 3) {
			if (!S.canFound(game, card) || (loc.from !== S.WASTE && loc.from === S.FOUND)) {
				printNotice(bareName(card) + " could not be moved to foundation stack.");
				showBoardAndPrompt();
				return;
			}
			if (loc.from === S.FOUND) {
				printNotice(bareName(card) + " could not be moved to foundation stack.");
				showBoardAndPrompt();
				return;
			}
			S.apply(game, { kind: S.TO_FOUND, from: loc.from, index: loc.index, to: -1 });
			println(bareName(card) + " moved to foundation stack. Points +10.");
			showBoardAndPrompt();
			return;
		}
		if (pile > 0) {
			moveToPile(card, loc, pile - 1);
			return;
		}
		print(formatBoard());
		promptForPile(card, loc);
	}

	function playTableau(shortcut, pile) {
		promptForCard(2, shortcut, pile);
	}

	function playFoundation(shortcut) {
		promptForCard(3, shortcut, -1);
	}

	function promptForPile(card, loc) {
		println("Which pile do you want to move " + bareName(card) + " to? Choose a pile from 1-7.");
		println("Type \"c\" to cancel.");
		ask(function (raw) {
			var pile = String(raw || "").trim().toLowerCase();
			if (pile === "c") {
				println("You chose to cancel.");
				showBoardAndPrompt();
				return;
			}
			if (!/^\d+$/.test(pile)) {
				printNotice("Enter a number from 1-7.");
				println("");
				promptForPile(card, loc);
				return;
			}
			var n = parseInt(pile, 10);
			if (n < 1 || n > 7) {
				printNotice("Enter a number from 1-7.");
				println("");
				promptForPile(card, loc);
				return;
			}
			moveToPile(card, loc, n - 1);
		});
	}

	function moveToPile(card, loc, dest) {
		if (!S.canPlace(game, card, dest)) {
			printNotice(bareName(card) + " cannot be placed in pile " + (dest + 1) + ".");
			showBoardAndPrompt();
			return;
		}
		S.apply(game, { kind: S.TO_TAB, from: loc.from, index: loc.index, to: dest });
		if (loc.kind === 1) {
			println("Moved " + bareName(card) + " from draw pile to tableau. Points +5.");
		} else if (loc.kind === 2) {
			println("Moved " + paddedName(card) + " from foundation stack to tableau. Points -15.");
		}
		println(bareName(card) + " moved successfully. ");
		showBoardAndPrompt();
	}

	function runAi(slow, warp) {
		var delay = slow ? 700 : 20;
		revealHiddenTableau = !!warp;
		setInputEnabled(false);
		function stopAi() {
			aiTimer = null;
			revealHiddenTableau = false;
			setInputEnabled(true);
		}
		function step() {
			if (S.won(game) || foundationsComplete()) {
				if (!foundationsComplete() && S.won(game)) S.finish(game);
				stopAi();
				showBoardAndPrompt();
				return;
			}
			var move = warp ? S.warpChoose(game) : S.strongChoose(game, Math.random);
			if (!move || move.kind === S.AUTO) {
				if (S.won(game) && !foundationsComplete()) S.finish(game);
				stopAi();
				showBoardAndPrompt();
				return;
			}
			var said = S.describe(game, move);
			S.apply(game, move);
			println("Computer: " + said + ".");
			print(formatBoard());
			if (foundationsComplete()) {
				stopAi();
				onWin();
				return;
			}
			aiTimer = setTimeout(step, delay);
		}
		step();
	}

	formEl.addEventListener("submit", function (event) {
		event.preventDefault();
		if (inputEl.disabled) return;
		var value = inputEl.value;
		inputEl.value = "";
		println(value);
		var handler = waiting;
		waiting = null;
		if (handler) handler(value);
	});

	termEl.addEventListener("click", function () {
		focusInput();
	});

	startGame();
})();
