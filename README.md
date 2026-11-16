# Command Line Solitaire

A 1-card-draw Solitaire game you play from the keyboard. There is a Java command-line program and a browser version that uses the same commands.

Play the browser version at [inverteum.com/coding/solitaire-command-line](https://www.inverteum.com/coding/solitaire-command-line/).

Cards are written with the value first and the suit last: H heart, C club, S spade, D diamond. For example, `10S` is the 10 of spades, and `QH` is the queen of hearts. Commands are not case-sensitive.

* Draw cards, move cards to a tableau pile or a foundation stack, or undo a draw
* Choose a card by entering its name, for example `AD` for the ace of diamonds
* Score changes with the usual actions, such as moving a card to a foundation, restarting the draw pile, or revealing a tableau card
* Hint, for example `Move 6S to pile 7`
* Autocomplete when the game is clearly won (all tableau cards are face up, and the draw and waste piles are empty)
* A top-10 score list, stored in a local Excel file (`.scores.xls`) with Apache POI

## Run the Java game

Java 17 or later, from this folder:

```bash
javac -cp lib/poi-3.12.jar *.java
java -cp ".:lib/poi-3.12.jar" Game
```

On Windows, use `;` instead of `:` in the classpath.

## Computer players

Two modes are available. The names are exact.

* **Player** cannot see cards that are not flipped over. It does not look at face-down tableau cards or undrawn stock. When several moves are possible it tries several possible layouts for the hidden cards and picks the move that wins most often (it counts only proved wins). If nothing is proved, it falls back to one sample with a larger search. Fully visible positions are searched directly.
* **Warp** can see the face-down cards and hidden stock, and it uses that knowledge to pick a move. If the default 100,000-node heuristic-first search cannot prove a win, it retries the same budget with complementary move orderings. While Warp is playing, face-down tableau cards are shown.

On the command line, type `player` or `warp`. Hint stays with the Player. Auto-play lines are prefixed with `Computer:`.

Win rates are completed wins on this 1-card-draw ruleset, not generic Klondike. The test used 1,000 random deals, seeds 1–1000, shuffled with Java’s `Random`:

* Player: **568/1000 (56.8%)** in 80.7 seconds (8 samples, 6,000 nodes per root move). An older one-sample 25,000-node Player won **529/1000 (52.9%)**
* Warp: **808/1000 (80.8%)** in 345.4 seconds with a 100,000-node budget and complementary order restarts. An earlier single-ordering Warp won **800/1000 (80.0%)**

Benchmark, from this folder:

```bash
javac -cp lib/poi-3.12.jar *.java
java -Xmx4g -cp ".:lib/poi-3.12.jar" StrongAI legal 1000
java -Xmx4g -cp ".:lib/poi-3.12.jar" WarpMode 1000
```

`StrongAI legal 1000` is the Player benchmark (`legal` is the program argument).

## Browser

Static files in `web/` (`index.html`, `engine.js`, `solver.js`, `terminal.js`, `terminal.css`). Open `web/index.html` in a browser, or from `web` run:

```bash
python3 -m http.server 8000
```

Then visit `http://localhost:8000`. No build step and no other dependencies are required. Type `player` or `warp` in the terminal the same way as in the Java program.

## Sample game

```
Drawn:  3D          Foundation Stacks:  AH       Points: 20
 1    2    3    4    5    6    7
     ---  ---  ---  ---  ---  ---
      6S  ---   QH  ---  ---  ---
           5C       ---  ---  ---
                    ---  ---  ---
                     5D  ---  ---
```
