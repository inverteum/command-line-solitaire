import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Scanner;
import java.util.Stack;

// Apache POI: Allows high score to be saved in 
// Excel spreadsheet for later reference 
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;

public class Game {

	// Standard deck of 52 playing cards (Requirement 1.0.0)
	/* There is a space in front of all cards besides the 10 of each suit
	 * for output alignment in the tableau pile.
	 * The 10 occupies 3 places, i.e. "10D", while the rest of the cards 
	 * occupy only 2 spaces, i.e. "AS"
	 */
	static String[] cards = { " AS", " 2S", " 3S", " 4S", " 5S", " 6S", " 7S",
			" 8S", " 9S", "10S", " JS", " QS", " KS", " AC", " 2C", " 3C",
			" 4C", " 5C", " 6C", " 7C", " 8C", " 9C", "10C", " JC", " QC",
			" KC", " AH", " 2H", " 3H", " 4H", " 5H", " 6H", " 7H", " 8H",
			" 9H", "10H", " JH", " QH", " KH", " AD", " 2D", " 3D", " 4D",
			" 5D", " 6D", " 7D", " 8D", " 9D", "10D", " JD", " QD", " KD" };	
	static int[] random = new int[52];
	static ArrayList<Integer>[] layout = (ArrayList<Integer>[]) new ArrayList[7];
	static Stack<Integer>[] foundationStacks = (Stack<Integer>[]) new Stack[4];
	static Stack<Integer> drawPile = new Stack<Integer>();
	static Stack<Integer> drawnCards = new Stack<Integer>();
	static ArrayList<Integer> faceUp = new ArrayList<Integer>();
	/** Cards the human or AI has actually seen (dealt face up, drawn, or revealed). */
	static boolean[] seenCards = new boolean[52];
	static Long dealSeed = null;
	static boolean headless = false;
	static boolean gameWon = false;
	
	static int[] switches = new int[52];
	static boolean[] kingsPiles = new boolean[4];
	static int highestScore = 0;
	static int nDrawPileRestarts = 0;
	static int nKings = 0;
	
	/* The game can be exited or restarted if the user desires.
	 * (Requirements 6.3.0 and 6.4.0) */
	static Scanner scanner = new Scanner(System.in);
	static int points = 0;
	static int highScore = 0;
	static int leaderboardSize = 10;
	static Integer[] top10Scores = new Integer[leaderboardSize];
	static String[] top10Names = new String[leaderboardSize];
	static boolean noWinnable = false;

	// Output when player wins solitaire
	static String asciiArtSmiley = "888888888888888888888888888888888\n"
			+ "88888888888888888____88888____888\n"
			+ "8888888888888888______888_____888\n"
			+ "8888888888888888______888_____888\n"
			+ "8888888888888888______888_____888\n"
			+ "8888888888888888______88_____8888\n"
			+ "8888888888888888______88_____8888\n"
			+ "8888888888888888______88_____8888\n"
			+ "8888888888_____8______88_____8888\n"
			+ "8888___88______8______8_____88888\n"
			+ "888_____8______8______8_____88888\n"
			+ "888_____8______8______8_____88888\n"
			+ "888_____8______8______8_____88888\n"
			+ "888_____8____88888888888888888888\n"
			+ "8_8_____8___88________________888\n"
			+ "8_8_____8__88__________________88\n"
			+ "8__888888_888_________888_______8\n"
			+ "8_________88________8___________8\n"
			+ "8____________8888888____________8\n"
			+ "88_____________88_______________8\n"
			+ "88_______________88_____________8\n"
			+ "888______________8_____________88\n"
			+ "888_______________8___________888\n"
			+ "88888_____________8__________8888\n"
			+ "888888_____________________888888\n"
			+ "888888____________________8888888\n";

	static HSSFWorkbook workbook;
	static HSSFSheet sheet;
	static HSSFRow row;
	static String fileName = ".scores.xls";
	static File excelSpreadsheet = new File(fileName);
	static int lastRow = -1;

	public static void main(String[] args) {
		if (args.length >= 2 && args[0].equals("--benchmark-legacy")) {
			int n = Integer.parseInt(args[1]);
			long startSeed = args.length > 2 ? Long.parseLong(args[2]) : 1L;
			runLegacyBenchmark(n, startSeed);
			return;
		}

		// If a spreadsheet with the high score exists,
		// get the current sheet and row
		if (excelSpreadsheet.exists()) {
			try {
				workbook = new HSSFWorkbook(new FileInputStream(fileName));
				sheet = workbook.getSheetAt(0);
				row = sheet.getRow(0);
			} catch (IOException ioe) {
			}
		}

		// If the spreadsheet with the high score does not exist,
		// create a workbook, sheet, and row.
		else {
			workbook = new HSSFWorkbook();
			sheet = workbook.createSheet();
			row = sheet.createRow(0);
		}

		startGame();
	}

	/**
	 * Plays the original hint-based AI on seeded deals with no console and no
	 * spreadsheet. A strict win is four kings on the foundations. An
	 * autocomplete-ready loss is a position the human prompt would offer to finish.
	 */
	static void runLegacyBenchmark(int n, long startSeed) {
		headless = true;
		java.io.PrintStream previous = System.out;
		System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
		workbook = new HSSFWorkbook();
		sheet = workbook.createSheet();
		row = sheet.createRow(0);
		int wins = 0;
		int exposed = 0;
		int errors = 0;
		long started = System.nanoTime();
		for (int i = 0; i < n; i++) {
			dealSeed = Long.valueOf(startSeed + i);
			gameWon = false;
			nKings = 0;
			try {
				startGame();
				highestScore = 0;
				hint(1);
			} catch (Throwable t) {
				errors++;
			}
			if (gameWon || nKings >= 4) {
				wins++;
			} else if (winnable()) {
				exposed++;
			}
		}
		System.setOut(previous);
		double seconds = (System.nanoTime() - started) / 1e9;
		System.out.println("Game.java legacy AI: " + wins + "/" + n
				+ " strict wins, " + exposed + " autocomplete-ready losses, "
				+ errors + " errors, " + String.format("%.1f", seconds) + "s, seeds "
				+ startSeed + "-" + (startSeed + n - 1));
	}

	/*
	 * startGame() is called from: 
	 * - main(): to start the game 
	 * - print(): when the user has won and chooses to play again 
	 * - playGame(choice): when the user chooses to restart the game (choice == 5)
	 */
	static void startGame() {
		ArrayList<Integer> scoresAL = new ArrayList<Integer>();
		ArrayList<String> namesAL = new ArrayList<String>();
		
		// Get previous top 10 scores and names from Excel spreadsheet
		// and store them into scores and names ArrayLists
		for (int i = 0; i < sheet.getPhysicalNumberOfRows(); i++) {
			HSSFRow newRow = sheet.getRow(i);
			HSSFCell scoreCell = newRow.getCell(0);
			HSSFCell nameCell = newRow.getCell(1);
			
			int score = 0;
			if (scoreCell != null) {
				// If a score is invalid (spreadsheet has been tampered with),
				// skips over that score
				try {
					score = (int) scoreCell.getNumericCellValue();
				} catch (Exception nfe) {
					continue;
				}
			}
			
			String name = "";
			if (nameCell != null) {
				name = nameCell.getStringCellValue();
			}
			
			if (score > 0 && name.length() > 0) {
				scoresAL.add(score);
				namesAL.add(name);
			}
		}
		
		// Convert scores and names ArrayLists to arrays
		Integer[] scores = new Integer[scoresAL.size()];
		scoresAL.toArray(scores);
		String[] names = new String[namesAL.size()];
		namesAL.toArray(names);
		
		// Sort scores (and names) in descending order from 
		// highest to lowest
		sortScoresAndNames(scores, names);		

		// Initialize point total to 0
		points = 0;
		noWinnable = false;
		
		// Clear draw pile, drawn cards, and face up cards
		// just in case if a previous game was played
		drawPile.clear();
		drawnCards.clear();
		faceUp.clear();
		gameWon = false;
		for (int i = 0; i < seenCards.length; i++) {
			seenCards[i] = false;
		}

		// "Shuffle the deck" by generating 52 random numbers from 0-51 and
		// doling the cards out in that order
		// (Requirement 2.5.0)
		Random rng = dealSeed == null ? new Random() : new Random(dealSeed.longValue());
		random = rng.ints(0, 52).distinct().limit(52).toArray();

		// Take the last 24 cards and place them into the draw pile
		// (the first 28 cards are used for the tableau piles)
		// (Requirement 2.3.0)
		for (int i = 28; i < random.length; i++) {
			int key = random[i];
			drawPile.push(key);
		}

		// layout is an array of ArrayLists that keeps track of the tableau
		// piles in Solitaire
		// This loop initializes an ArrayList for each tableau pile
		for (int i = 0; i < layout.length; i++) {
			layout[i] = new ArrayList<Integer>();
		}

		// foundationStacks is an array of Stacks that keeps track of the
		// foundation stack in Solitaire
		// This loop initializes an empty stack for each foundation stack
		// (Requirement 2.2.0)
		for (int i = 0; i < foundationStacks.length; i++) {
			foundationStacks[i] = new Stack<Integer>();
		}

		int deckSize = 1;
		int cardIndex = 0;

		// Initialize the tableau layout in Solitaire, with piles of 1, 2, 3,
		// 4, 5, 6, 7 cards each
		// (Requirement 2.1.0)
		for (int deck = 0; deck < layout.length; deck++) {

			// Add cards to each pile, in line with the necessary deck size
			for (int card = 0; card < deckSize; card++) {
				// Cards are added to each pile according to the randomly
				// generated array of 52 integers
				layout[deck].add(random[cardIndex]);

				// If the card is the bottom card in the tableau pile,
				// the card is turned face up
				// (Requirement 2.1.1)
				if ((card + 1) == deckSize) {
					faceUp.add(random[cardIndex]);
					seenCards[random[cardIndex]] = true;
				}

				cardIndex++;
			}

			// For each pile in the tableau, the deck size is increased
			// progressively from 1-7
			deckSize++;
		}
		
		for (int i = 0; i < switches.length; i++) {
			switches[i] = -1;
		}
		
		for (int i = 0; i < kingsPiles.length; i++) {
			kingsPiles[i] = false;
		}
		
		highestScore = 0;
		nDrawPileRestarts = 0;
		nKings = 0;

		if (!headless) {
			System.out.println("Welcome to Solitaire!");

			// Outputs the Solitaire tableau layout
			// 0 is normal mode
			print(0, false);
		}
	}

	/*
	 * print() shows the board and, unless mode is 1, asks for the next action.
	 * Called from startGame(), playGame(), draw(), and the exit/restart prompt.
	 */
	static boolean revealHiddenTableau = false;

	/** Print a rejected move or similar result so it stands out from the prompt. */
	static void printNotice(String message) {
		System.out.println("*** " + message + " ***");
	}

	static void print(int mode, boolean AI) {
		// Outputs the Solitaire tableau layout

		if (headless) {
			nKings = 0;
			for (int checkStack = 0; checkStack < foundationStacks.length; checkStack++) {
				Stack<Integer> currentStack = foundationStacks[checkStack];
				if (!currentStack.empty() && currentStack.peek() % 13 == 12) {
					nKings++;
				}
			}
			if (nKings >= 4) {
				gameWon = true;
			}
			return;
		}
		
		// Do not continue printing the layout if game has been won
		// (4 kings in foundation stacks)
		if (nKings >= 4) {
			return;
		}
			
		/* Checks if a game is winnable and if the user has not already chosen
		 * to win manually (Requirement 5.4.1)
		 */
		if (!AI && winnable() && !noWinnable) {
			System.out.println();
			// Reprints tableau layout
			// print(1);
			int finishGame = 0;				
			
			/* If game is winnable, prompt user if they want to the program to
			 * automatically fill in all the foundation stacks.
			 * 
			 * If the user accidentally enters invalid input, the program 
			 * should reprompt them for valid input. (Requirement 6.1.0)
			 */
			while (finishGame < 1 || finishGame > 2) {
				System.out.println("You can win Solitaire right now.\n"
						+ "Do you want to automatically fill all the foundation stacks? 1) Yes 2) No");
				
				try {
					finishGame = Integer.parseInt(scanner.nextLine());
				} catch (NumberFormatException nfe) {
					System.out.println("Please enter a number from 1-2.\n");
					continue;
				}
	
				// If user enters number not equal to 1 or 2,
				// re-prompt them
				if (finishGame < 1 || finishGame > 2) {
					System.out.println("Please enter a number from 1-2.\n");
				}
			}
			
			// If user says yes,
			// add kings to every foundation stack
			if (finishGame == 1) {
				int[] kings = {12, 25, 38, 51};
				int king = 0;
									
				for (int stack = 0; stack < foundationStacks.length; stack++) {
					foundationStacks[stack].push(kings[king]);
					king++;
				}
				
				/* Count up the number of cards in the tableau piles
				 * and multiply by 10 to figure out the number that should be 
				 * added to the user's score.
				 */
				int addedPts = numFaceUpCards() * 10;
				
				// Add to the user's score
				points += addedPts;
				
				// Output message 
				System.out.println("\nAll cards moved to foundation stack. Points +" + addedPts + ".");
			} 
			
			// If user chooses to win manually, 
			else {
				// prevent program from prompting them again to win automatically
				noWinnable = true;
				System.out.println("You chose not to fill in all the foundation stacks automatically.\n");
			}
		}
			
		System.out.print("\nDrawn: ");
		// If cards have been drawn, the program shows that card to the
		// user.
		if (!drawnCards.empty()) {
			int card = drawnCards.peek();
			System.out.print(cards[card] + "          ");
		} else {
			System.out.print("[none]       ");
		}

		System.out.print("Foundation Stacks: ");
		nKings = 0;
		
		// Checks if foundation stacks have cards in them
		// If so, outputs the top card in the stack
		for (int checkStack = 0; checkStack < foundationStacks.length; checkStack++) {
			Stack<Integer> currentStack = foundationStacks[checkStack];
			
			if (!currentStack.empty()) {				
				// If the foundation stack is not empty, prints the top card
				int card = currentStack.peek();

				// If the top card is a king, counts it
				if (card % 13 == 12) {
					nKings++;
				}

				// Add a space in front of the card if number is 10
				// The 10 card uses all three available spaces "10S"
				// (compared with other cards, which only take 2
				// spaces: " AS")
				if (card % 13 == 9) {
					System.out.print(" ");
				}

				System.out.print(cards[card]);
						
				/* When all 4 foundation piles are completely filled 
				 * (A - K), the game ends in a win.
				 * (Requirement 5.4.0)
				 */
				// If the number of kings in all the stacks equal 4, the
				// user has won and the current game ends
				if (nKings == 4) {
					// Output awesome ASCII art smiley
					System.out.println("\n\n" + asciiArtSmiley);
					System.out
							.println("You won Solitaire! Congratulations!");
					
					// Save score into Excel spreadsheet
					saveScore(points);
					
					int playAgain = 0;

					/* If the user accidentally enters invalid input, the program should 
					 * reprompt them for valid input.
					 * (Requirement 6.1.0)
					 */
					do {
						// Ask user if they want to play again, taking in 1
						// or 2
						System.out
								.println("Do you want to play again? 1) Yes 2) No");

						try {
							playAgain = Integer
									.parseInt(scanner.nextLine());
							System.out.println();
						}
						// If user enters a non-numeric character,
						// re-prompts them
						catch (NumberFormatException nfe) {
							System.out
									.println("Please enter a number from 1-2.\n");
							continue;
						}

						// If user enters number not equal to 1 or 2,
						// re-prompts them
						if (playAgain < 1 || playAgain > 2) {
							System.out
									.println("Please enter a number from 1-2.\n");
						}
						
					} while (playAgain < 1 || playAgain > 2);

					// Start game if user chooses to play again
					if (playAgain == 1) {
						startGame();
					}
					// End game if user chooses to
					else {
						System.out.println("Thank you for playing Solitaire.");
					}

					return;
				}
			}
		}
		
		System.out.println("       Points: " + points);
		
		

		// Find the longest tableau pile
		// Tableau piles are output to the console using a 2D array
		// The number of rows in the 2D array is determined by the longest
		// tableau pile
		int longestPileLength = 1;
		for (int pile = 0; pile < layout.length; pile++) {
			int length = layout[pile].size();
			if (length > longestPileLength) {
				longestPileLength = length;
			}
		}

		// 2D array for output to the console
		// # rows = longest tableau pile
		// # columns = 7 (# of tableau piles)
		String[][] layoutArr = new String[longestPileLength + 1][layout.length];
		for (int row = 0; row < layoutArr.length; row++) {
			for (int col = 0; col < layoutArr[row].length; col++) {
				// Cells in top row of 2D array: displays the tableau pile
				// number for referencing when the player wants to move a card
				if (row == 0) {
					layoutArr[row][col] = " " + (col + 1) + " ";
				}

				// Cells in the other rows are initialized to 3 spaces
				// so that the card values in each tableau pile are lined up
				// even if there are empty cells
				else {
					layoutArr[row][col] = "   ";
				}
			}
		}

		// Take card values from the array of ArrayLists that store the cards
		// that are in the tableau piles
		// and place them into the 2D array for output
		for (int deck = 0; deck < layout.length; deck++) {
			// The first card in the tableau pile is always placed in the first
			// row
			int row = 1;

			// Iterate through tableau pile
			for (int card = 0; card < layout[deck].size(); card++) {
				// Get the index of the card in the pile
				int key = layout[deck].get(card);

				// Save the card's name, i.e. AS
				String cardName = cards[key];

				// If the card is not supposed to be shown, obfuscate it
				// unless Warp is playing and hidden tableau cards should be visible.
				if (!isFaceUp(key) && !revealHiddenTableau) {
					cardName = "---";
				}

				// Save card's name or obfuscated representation to 2D array
				layoutArr[row][deck] = cardName;

				// Increment the row number for the next card
				row++;
			}
		}

		// Output the 2D array with the tableau piles to the console
		for (int row = 0; row < layoutArr.length; row++) {
			for (int col = 0; col < layoutArr[row].length; col++) {
				System.out.print(layoutArr[row][col] + "  ");
			}
			System.out.println();
		}
		System.out.println();

		// Mode 1 is for methods that want to show the tableau piles to
		// the user but continue to take in their own input (i.e. pickPile())
		if (mode == 1) {
			return;
		}

		// Prompt the user for their next move
		// (Requirement 5.0.0)
		play();
	}

	/*
	 * play() is called from print(): to prompt user for input as to what
	 * they want to do next
	 */
	static void play() {
		int choice = 0;
		int upperLimit = 5;

		/* If the user accidentally enters invalid input, the program should 
		 * reprompt them for valid input.
		 * (Requirement 6.1.0)
		 */
		do {
			// 1) draw, 2) pick a card (destination is chosen automatically),
			// 3) hint, 4) exit, or 5) restart (Requirement 5.0.0)
			System.out.println("Would you like to draw or pick a card?");
			System.out.println("1) draw 2) pick card 3) hint 4) exit 5) restart");
			System.out.println("Computer play: \"player\" or \"warp\". Add \" slow\" to pause between moves.");

			try {
				// Take in user's choice and make it uppercase
				String choiceStr = scanner.nextLine().trim().toUpperCase();
				
				if (choiceStr.length() == 0) {
					continue;
				}

				boolean slow = choiceStr.endsWith(" SLOW");
				String modeName = slow ? choiceStr.substring(0, choiceStr.length() - 5).trim() : choiceStr;
				// "AI", "START", and "LEGAL PLAYER" remain aliases for Player.
				if (choiceStr.equals("AI") || choiceStr.equals("START") || modeName.equals("PLAYER") || modeName.equals("LEGAL PLAYER")) {
					SmartAI.runCli(slow || choiceStr.equals("START"), false);
					return;
				}
				if (modeName.equals("WARP") || modeName.equals("WARP MODE")) {
					SmartAI.runCli(slow, true);
					return;
				}

				// "2QH" picks QH from the menu. The destination is automatic.
				if (choiceStr.length() > 1 && choiceStr.charAt(0) == '2') {
					String rest = choiceStr.substring(1).trim();
					if (isCard(rest)) {
						System.out.println();
						playGame(2, rest, false);
						return;
					}
				}
				
				choice = Integer.parseInt(choiceStr);
			}
			// If the user enters non-numeric input,
			// loop continues and user is reprompted
			catch (NumberFormatException nfe) {
				System.out.println("Please enter a number from 1-" + upperLimit + ", or type player or warp.\n");
				continue;
			}

			// If the user enters a number out of range,
			// loop continues and user is reprompted
			if (choice < 1 || choice > upperLimit) {
				System.out.println("Please enter a number from 1-" + upperLimit + ", or type player or warp.\n");
			} else {
				System.out.println();
			}
			
		} while (choice < 1 || choice > upperLimit);

		// Call a different playGame method with the user's choice as a
		// parameter
		playGame(choice, null, false);
	}

	/*
	 * playGame(choice) is called from 
	 * - play(): to continue the game after the user has chosen what 
	 *   they want to do 
	 * - print(): when the user has won and they choose to leave the game. 
	 *   The exit option (choice == 4) is called.
	 */
	static void playGame(int choice, String shortcutCard, boolean AI) {
		int AInum = 0;
		if (AI) {
			AInum = 1;
		}
		
		// User chooses to draw a card
		if (choice == 1) {			

			// When the user asks to draw a card and the draw pile is empty, 
			// the cards in the drawn cards pile should be placed back into the 
			// draw pile in reverse order compared to the way the cards were drawn.
			// (Requirement 3.3.1)
			if (drawPile.empty()) {
				
				// If there are no cards in either the draw pile or the drawn cards
				// pile, prevent the user from drawing more cards
				// (Requirement 3.3.2)
				if (drawnCards.empty()) {
					printNotice("There are no cards left to draw.");
					print(0, AI);
					return;
				}

				int yesNo = 0;

				if (!AI) {
					/* If the user accidentally enters invalid input, the program should 
					 * reprompt them for valid input.
					 * (Requirement 6.1.0)
					 */			
					do {
						// prompts the user for valid input
						// to see if they would like to restart the draw pile
						System.out
								.println("All cards have been drawn. Would you like to restart the draw pile? 1) Yes 2) No");
						System.out.println("Your points will be deducted by 25.");
	
						try {
							yesNo = Integer.parseInt(scanner.nextLine());
							System.out.println();
						} catch (NumberFormatException nfe) {
							System.out.println("Please enter a number from 1-2.\n");
							continue;
						}
	
						if (yesNo < 1 || yesNo > 2) {
							System.out.println("Please enter a number from 1-2.\n");
						}
					} while (yesNo < 1 || yesNo > 2);
				} else {
					yesNo = 1;
				}

				// If the user chooses to restart the draw pile,
				// the game takes all the cards that have been drawn,
				// places them into the draw pile, and draws a card
				if (yesNo == 1) {
					nDrawPileRestarts++;
					
					while (!drawnCards.empty()) {
						int card = drawnCards.pop();
						drawPile.push(card);
					}

					System.out.println("You have restarted the draw pile. Points -25.");
					// Deduct 25 points for restarting draw pile
					// (Requirement 4.4.0)
					points -= 25;

					// Draw new card
					draw(AInum);
				}

				// If the user chooses not to restart the draw pile,
				// prints out tableau pile and continues the game
				else {
					System.out
							.println("You chose not to restart the draw pile.");
					print(0, AI);
				}
			}

			// If there are cards to draw, a card is drawn
			else {
				draw(AInum);
			}
		}

		// User picks a card. A legal foundation build is taken when one exists.
		// Otherwise the card moves to the next legal tableau pile to the right
		// of where it sits, wrapping around to the leftmost legal pile.
		else if (choice == 2) {
			int[] result = pickACard(shortcutCard);

			if (result != null) {
				autoMove(result[0], result[1]);
			}

			print(0, AI);
		}
		
		// User asks for hint. The suggestion names a card to pick, or a draw.
		else if (choice == 3) {
			SmartAI.hintCli();
			return;
		}

		/* The game can be exited if the user desires.
		 * (Requirement 6.3.0)
		 */
		// User chooses to exit
		else if (choice == 4) {
			// Make sure user did not accidentally chose to exit
			// (Requirement 6.2.0)
			int areYouSure = areYouSure(1);
			
			// If user wants to exit, end the game.	
			if (areYouSure == 1) {				
				saveScore(points);
				System.out.println("You have chosen to leave Solitaire.");
				System.out.println("Thank you for playing Solitaire.");
				System.out
						.println("\nThis window will close automatically in 5 seconds.");
	
				// Add 5 second delay, so command prompt does not immediately
				// close after the user exits the game.
				try {
					Thread.sleep(5000);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
				
				// Terminates all program execution
				// http://stackoverflow.com/questions/7600812/which-method-is-used-to-terminate-the-execution-of-java-program-in-between
				System.exit(0);
			}
		}

		/* The game can be restarted by a redeal if the user desires.
		 * (Requirement 6.4.0)
		 */
		// User chooses to restart
		else if (choice == 5) {
			// Make sure user did not accidentally chose to restart
			// (Requirement 6.2.0)
			int areYouSure = areYouSure(2);
			
			// If user wants to restart, restart the game
			if (areYouSure == 1) {		
				// Save score
				saveScore(points);
				System.out.println("You have chosen to restart the game.");
	
				// Restart game
				startGame();
			}
		}
	}

	/*
	 * canMoveToFoundation() is called from playGame(choice) when the user
	 * chooses to move a card to a foundation stack to check if that card can be
	 * moved
	 */
	static boolean canMoveToFoundation(int card) {
		// Check if a card can be placed into a foundation stack

		// Determine which foundation stack (spades, clubs, hearts, diamonds)
		// the card should be placed into
		// and the value of the ace for that suit
		int[] result = findStack(card);
		int stackNum = result[0];
		int ace = result[1];

		Stack<Integer> currentStack = foundationStacks[stackNum];

		// If there are cards in the stack,
		// the card at the top should be one less than the card
		// the player wants to place on the stack
		// (Requirement 3.2.0)
		if (!currentStack.empty()) {
			int topCard = currentStack.peek();

			if ((card - 1) == topCard) {
				return true;
			}
		}

		// If the stack is empty,
		// the card must be the ace for that suit
		// (Requirement 3.2.1)
		if (card == ace) {
			return true;
		}
		
		return false;
	}

	/*
	 * moveToFoundation() is called from playGame(choice) when the user chooses
	 * to move a card to a foundation stack if that card can be moved
	 */
	static void moveToFoundation(int card, int drawnFoundation) {
		// Move card to foundation stack
		String cardOutput = removeUnnecessarySpace(cards[card]);
		System.out.println(cardOutput
				+ " moved to foundation stack. Points +10.");

		// Moving a card to a foundation stack adds 10 points to the score
		// (Requirement 4.2.0)
		points += 10;

		// If the card was drawn, remove card from drawn cards pile
		if (drawnFoundation == 1) {
			drawnCards.pop();
		}

		// If the card is in a tableau pile,
		else {
			// find which pile the card is in and remove the card from that
			// pile.
			int pile = findPile(card);
			int cardIndex = layout[pile].lastIndexOf(card);
			layout[pile].remove(cardIndex);

			// find the index of the card directly above it in the tableau pile
			int nextCardIndex = layout[pile].size() - 1;

			// If the card exists, the card gets turned face up
			if (nextCardIndex >= 0) {
				revealNextCard(pile, nextCardIndex);
			}
		}

		// Determine which foundation stack the chosen card should
		// be placed into (spades, clubs, hearts, diamonds)
		int[] result = findStack(card);
		int stackNum = result[0];

		// Places the chosen card into that stack
		Stack<Integer> currentStack = foundationStacks[stackNum];
		currentStack.push(card);
	}

	/*
	 * findStack() is called to determine which suit stack a card should be
	 * moved to from canMoveToFoundation() and moveToFoundation()
	 */
	static int[] findStack(int card) {
		// Determine which foundation stack (spades, clubs, hearts, diamonds)
		// the card should be placed into
		// and the value of the ace for that suit

		int stackNum = -1;
		int ace = -1;

		// spade
		if (card < 13) {
			stackNum = 0;
			ace = 0;
		}

		// clubs
		else if (card < 26) {
			stackNum = 1;
			ace = 13;
		}

		// hearts
		else if (card < 39) {
			stackNum = 2;
			ace = 26;
		}

		// diamonds
		else {
			stackNum = 3;
			ace = 39;
		}

		return new int[] { stackNum, ace };
	}

	/*
	 * pickACard() is called from playGame(choice) when the user picks a card.
	 * Returns {card, origin} or null. Origin is 0 for a tableau card, 1 for the
	 * waste top, and 2 for a foundation top. The destination is not chosen here.
	 */
	static int[] pickACard(String shortcutCard) {
		HashMap<String, Integer> cardsAndCardNames = new HashMap<String, Integer>();
		int firstCard = -1;
		int drawnCard = -1;
		ArrayList<Integer> foundationCards = new ArrayList<Integer>();

		if (!drawnCards.empty()) {
			int card = drawnCards.peek();
			cardsAndCardNames.put(cards[card], card);
			drawnCard = card;
		}

		for (int deck = 0; deck < layout.length; deck++) {
			for (int cardIndex = 0; cardIndex < layout[deck].size(); cardIndex++) {
				int card = layout[deck].get(cardIndex);
				// Only face-up cards can be picked. A face-up card may head a run.
				if (isFaceUp(card)) {
					cardsAndCardNames.put(cards[card], card);
					firstCard = card;
				}
			}
		}

		for (int checkStack = 0; checkStack < foundationStacks.length; checkStack++) {
			if (!foundationStacks[checkStack].empty()) {
				int card = foundationStacks[checkStack].peek();
				cardsAndCardNames.put(cards[card], card);
				foundationCards.add(card);
			}
		}

		if (firstCard < 0 && drawnCard >= 0) {
			firstCard = drawnCard;
		}

		String pickedCard = shortcutCard;

		/* If the user accidentally enters invalid input, the program should
		 * reprompt them for valid input.
		 * (Requirement 6.1.0)
		 */
		while (true) {
			if (pickedCard == null) {
				String example = "AD";
				if (firstCard >= 0) {
					example = removeUnnecessarySpace(cards[firstCard]);
				}
				/* Cancel returns to the main prompt. (Requirement 6.2.0) */
				System.out.println("Which card, i.e. \"" + example + "\"? Type \"c\" to cancel.");

				pickedCard = scanner.nextLine().trim().toUpperCase();
				System.out.println();

				if (pickedCard.equals("C")) {
					System.out.println("You chose not to pick a card.");
					return null;
				}
			} else {
				pickedCard = pickedCard.trim().toUpperCase();
			}

			int length = pickedCard.length();
			boolean shaped = length == 2 || (length == 3 && pickedCard.startsWith("10"));
			if (shaped) {
				String key = length == 2 ? " " + pickedCard : pickedCard;
				if (cardsAndCardNames.containsKey(key)) {
					int pickedCardIndex = cardsAndCardNames.get(key);
					int drawnFoundation = 0;
					if (pickedCardIndex == drawnCard) {
						drawnFoundation = 1;
					} else if (foundationCards.contains(pickedCardIndex)) {
						drawnFoundation = 2;
					}
					return new int[] { pickedCardIndex, drawnFoundation };
				}
				if (isCard(pickedCard)) {
					printNotice(removeUnnecessarySpace(key) + " cannot be moved.");
					return null;
				}
			}

			printNotice("Invalid input. Please enter a card that can be moved.");
			if (shortcutCard != null) {
				return null;
			}
			System.out.println();
			pickedCard = null;
		}
	}

	// findPile() is called to determine which tableau pile a card being
	// moved used to belong to from move(), moveToFoundation(), and autoDestination()
	static int findPile(int pickedCard) {
		for (int deck = 0; deck < layout.length; deck++) {
			if (layout[deck].contains(pickedCard)) {
				return deck;
			}
		}
		return -1;
	}

	/**
	 * Legal tableau destinations for a picked card, leftmost first.
	 * The pile the card already occupies is never a destination.
	 */
	static ArrayList<Integer> tableauDestinations(int card, int drawnFoundation) {
		ArrayList<Integer> dests = new ArrayList<Integer>();
		int source = drawnFoundation == 0 ? findPile(card) : -1;
		for (int pile = 0; pile < layout.length; pile++) {
			if (pile == source) {
				continue;
			}
			if (canMove(card, pile)) {
				dests.add(pile);
			}
		}
		return dests;
	}

	/**
	 * Next legal tableau pile to the right of the card's current pile,
	 * wrapping around to the leftmost legal pile. Cards from the waste or a
	 * foundation start at the leftmost legal pile. Returns 0-6, or -1 when
	 * no legal tableau move exists.
	 */
	static int nextTableauPile(int card, int drawnFoundation) {
		ArrayList<Integer> dests = tableauDestinations(card, drawnFoundation);
		if (dests.isEmpty()) {
			return -1;
		}
		int source = drawnFoundation == 0 ? findPile(card) : -1;
		for (int i = 0; i < dests.size(); i++) {
			int pile = dests.get(i);
			if (pile > source) {
				return pile;
			}
		}
		return dests.get(0);
	}

	/**
	 * Where a picked card should go. A free card (waste top, or the exposed
	 * end of a tableau pile) moves to the foundation when that build is legal.
	 * Otherwise it moves to the next legal tableau pile to the right of where
	 * it sits, wrapping around to the leftmost legal pile. Returns -2 for the
	 * foundation, 0-6 for a tableau pile, or -1 when no legal move exists.
	 */
	static int autoDestination(int card, int drawnFoundation) {
		if (canAutoFound(card, drawnFoundation)) {
			return -2;
		}
		return nextTableauPile(card, drawnFoundation);
	}

	/** Foundation only when the card is free and the next build of its suit. */
	static boolean canAutoFound(int card, int drawnFoundation) {
		if (drawnFoundation == 2) {
			return false;
		}
		if (drawnFoundation == 0) {
			int pile = findPile(card);
			if (pile < 0) {
				return false;
			}
			ArrayList<Integer> column = layout[pile];
			if (column.isEmpty() || column.get(column.size() - 1).intValue() != card) {
				return false;
			}
		}
		return canMoveToFoundation(card);
	}

	/**
	 * Move a picked card. A legal foundation build is taken when one exists.
	 * Otherwise the card moves to the next legal tableau pile to the right of
	 * where it sits, wrapping around to the leftmost legal pile. A tableau
	 * run moves together. The board is left unchanged when nothing is legal.
	 */
	static void autoMove(int card, int drawnFoundation) {
		int dest = autoDestination(card, drawnFoundation);
		if (dest == -1) {
			printNotice(removeUnnecessarySpace(cards[card]) + " cannot be moved.");
			return;
		}
		if (dest == -2) {
			moveToFoundation(card, drawnFoundation);
			return;
		}
		move(card, dest, drawnFoundation);
	}

	// canMove() is called from autoDestination(): to see if a certain card can be
	// moved to a certain pile
	// Check if a picked card can be moved to a picked pile
	static boolean canMove(int pickedCard, int pickedPile) {
		// Find the index of the bottom card in the picked tableau pile
		int lastCardIndex = layout[pickedPile].size() - 1;
		
		// If the picked card is a king, return true based on whether 
		// the picked pile is empty (Requirement 3.1.1)
		if (pickedCard % 13 == 12) {
			return (lastCardIndex == -1);
		}

		// If the picked tableau pile is empty and the card being moved
		// is not a king, the card cannot be moved (Requirement 3.1.1)
		if (lastCardIndex == -1) {
			return false;
		}
		
		// Find the bottom card in the picked tableau pile
		int lastCard = layout[pickedPile].get(lastCardIndex);
		
		return (isMatch(pickedCard, lastCard));
	}
	
	// Checks if 2 cards can be matched together (opposite color in descending order)
	static boolean isMatch(int pickedCard, int lastCard) {
		int possibleMatch1 = -1;
		int possibleMatch2 = -1;

		/*
		 * Generate the 2 possible cards that can be moved to a pile 
		 * These cards must be one number lower and of the opposite
		 * color compared with the bottom card of that tableau pile. 
		 * (Requirement 3.1.0)
		 */

		// If the bottom card is a...
		// ...spade, it can only be matched with a heart or diamond
		// 1 number lower
		if (lastCard < 13) {
			possibleMatch1 = lastCard + 25;
			possibleMatch2 = lastCard + 38;
		}

		// ...club, it can only be matched with a heart or diamond
		// 1 number lower
		else if (lastCard < 26) {
			possibleMatch1 = lastCard + 12;
			possibleMatch2 = lastCard + 25;
		}

		// ...heart, it can only be matched with a spade or club
		// 1 number lower
		else if (lastCard < 39) {
			possibleMatch1 = lastCard - 14;
			possibleMatch2 = lastCard - 27;
		}

		// ...diamond, it can only be matched with a spade or club
		// 1 number lower
		else {
			possibleMatch1 = lastCard - 27;
			possibleMatch2 = lastCard - 40;
		}

		// If the picked card is one of the 2 matches, it can be moved
		if (pickedCard == possibleMatch1 || pickedCard == possibleMatch2) {
			return true;
		}

		// Otherwise, it cannot
		return false;
	}

	// move() is called from pickPile(): if a card can be moved to a certain
	// pile, move card to the right pile, according to what the user picked
	static void move(int pickedCard, int pickedPile, int drawnFoundation) {
		// If the picked card is a drawn card, turn it face up
		if (!isFaceUp(pickedCard)) {
			faceUp.add(pickedCard);
		}

		// If the card was drawn,
		// it is removed from the drawnCards stack and
		// added to the right tableau pile.
		// Points increase by 5 points
		// (Requirement 4.1.0)
		if (drawnFoundation == 1) {
			drawnCards.pop();
			layout[pickedPile].add(pickedCard);
			points += 5;
			String cardOutput = removeUnnecessarySpace(cards[pickedCard]);
			System.out.println("Moved " + cardOutput
					+ " from draw pile to tableau. Points +5.");
		}

		// If the card is from a foundation stack,
		else if (drawnFoundation == 2) {
			int stackNum = -1;

			// find which foundation stack it is from
			int[] result = findStack(pickedCard);
			int checkStack = result[0];
			if (pickedCard == foundationStacks[checkStack].peek()) {
				stackNum = checkStack;
			}

			// pop it off the foundation stack
			foundationStacks[stackNum].pop();

			// add it to the appropriate tableau pile
			layout[pickedPile].add(pickedCard);
			
			// Output message that card has been moved
			System.out.println("Moved " + cards[pickedCard]
					+ " from foundation stack to tableau. Points -15.");

			// Points decrease by 15 points
			// (Requirement 4.3.0)
			points -= 15;		
		}

		// If the card was from another tableau pile
		else {
			// Find which pile the card used to be in and its position in that
			// pile
			int previousPile = findPile(pickedCard);
			int placeInPile = layout[previousPile].indexOf(pickedCard);

			// Add that card and all of the cards below it to the newly picked
			// pile (Requirement 3.1.3)
			for (int i = placeInPile; i < layout[previousPile].size(); i++) {
				int card = layout[previousPile].get(i);
				layout[pickedPile].add(card);
			}

			// Remove that card and all of the cards below it from the previous
			// pile (Requirement 3.1.3)
			int lastCardInPrevPile = layout[previousPile].size() - 1;
			for (int i = lastCardInPrevPile; i >= placeInPile; i--) {
				layout[previousPile].remove(i);
			}

			// Reveal the card right above it in the previous pile (if there is
			// one) (Requirement 3.1.4)
			int newLastCardIndex = layout[previousPile].size() - 1;
			if (newLastCardIndex >= 0) {
				revealNextCard(previousPile, newLastCardIndex);
			}
		}

		String cardOutput = removeUnnecessarySpace(cards[pickedCard]);
		System.out.println(cardOutput + " moved successfully. ");
	}

	/*
	 * revealNextCard() is called to reveal the card above the card that was
	 * moved 
	 * - moveToFoundation(): after a card has been moved from a tableau
	 *   pile to a foundation stack 
	 * - move(): after a card has been moved between tableau piles
	 */
	static void revealNextCard(int pile, int nextCardIndex) {
		// When a bottom card is moved in a tableau pile,
		// get the second-to-last card
		int nextCard = layout[pile].get(nextCardIndex);

		// and turn the card face up (if it already isn't face up)
		// (Requirement 3.1.4)
		if (!isFaceUp(nextCard)) {
			faceUp.add(nextCard);
			seenCards[nextCard] = true;

			// 5 points for revealing a previously face down card
			// (Requirement 4.5.0)
			String cardOutput = removeUnnecessarySpace(cards[nextCard]);
			System.out.println(cardOutput + " revealed. Points +5.");
			points += 5;
		}
	}

	// draw() called from playGame(choice): when the user chooses to draw a card
	static void draw(int mode) {
		if (!drawPile.empty()) {
			// Draw one card from draw pile
			// Add that card to drawn cards pile
			// (Requirement 3.3.0)
			int card = drawPile.pop();		
			drawnCards.push(card);
			seenCards[card] = true;
	
			// Output message to user
			System.out.println("You have drawn a new card.");
		} 
		
		// When draw() is called, the draw pile should not be empty, this is output just in case. 
		else {
			System.out.println("There are no cards to draw. (draw())");
		}

		boolean AI = false;
		if (mode == 1) {
			AI = true;
		}
		
		// Continue game
		print(mode, AI);
	}

	/*
	 * isFaceUp() called from 
	 * - print(): when tableau piles are saved into 2D output array 
	 * - pickACard(): to determine which cards the user can pick 
	 * - move(): to determine if a card is from the draw pile and if so, turn it face up 
	 * - revealNextCard(): to determine if a card at the bottom of a tableau pile is 
	 *   face down and if so, turn it face up
	 */
	// Check if a card is face up by looking through the
	// faceUp ArrayList
	static boolean isFaceUp(int card) {
		return faceUp.contains(card);
	}

	// saveScore() is called from
	// - print() if user has won
	// - playGame(choice) if user has chosen to exit (choice == 4) or restart
	// (choice == 5)
	// Save score into Excel spreadsheet
	static void saveScore(int score) {
		/* If the user's score exceeds any score in the top 10, they are notified 
		 * and asked for their name to display on the leaderboard.
		 * (Requirement 5.5.1)
		 */		
		// the > symbol ensures that scores that equal 0 are not saved
		// this is because empty entries in the top10Scores array are set to 0 
		if (score > top10Scores[leaderboardSize - 1]) {			
			// Output message
			System.out.println("Your score of " + score + " is a new high score in the top " + leaderboardSize + "!");
			
			// Prompt for name
			System.out.println("Let's put you on the leaderboard! What's your name?");
			String name = "";
			
			/* Take in user's name
			 * If the user accidentally presses Enter instead of entering their name,
			 * reprompt them. (Requirement 6.1.0)
			 */
			while (name.length() == 0) {
				name = scanner.nextLine();
				
				if (name.length() == 0) {
					System.out.println("Please enter your name.");
				}
			}
			
			// Save this new top 10 high score into the global top 10 score and name arrays 
			top10Scores[leaderboardSize - 1] = score;
			top10Names[leaderboardSize - 1] = name;
			
			// Sort the scores in descending order
			sortScoresAndNames(top10Scores, top10Names);
		} 
		
		/* If the user does not achieve a high score in the top 10,
		 * display their score. (Requirement 5.5.0)
		 */
		else {
			System.out.println("Your score: " + score);
		}
		
		/* Find the length of the longest name to ensure the scores 
		 * are properly aligned when output
		 */
		int longestNameLength = 0;
		for (int i = 0; i < top10Names.length; i++) {
			int length = top10Names[i].length();
			
			if (length > longestNameLength) {
				longestNameLength = length;
			}
		}		
		
		// Output the top 10 scores leaderboard (Requirement 5.5.0)
		for (int name = 0; name < top10Names.length; name++) {
			
			// Only output name and score if it exists
			// (Scores that equal 0 are not saved)
			if (top10Scores[name] > 0) {
				// Outputs the text "Leaderboard" only if the top10Scores
				// array is not empty
				if (name == 0) {
					System.out.println("\nLeaderboard");
				}
				
				/* If there is a 10th highest score,
				 * output a space for #1-9 to preserve alignment
				 */
				if (top10Scores[9] > 0 && name < 9) {
					System.out.print(" ");
				}
				
				// Output the ranking and name
				System.out.print( (name + 1) + ") " + top10Names[name] + ": ");
				
				// Output the number of spaces required to preserve alignment
				int nSpaces = longestNameLength - top10Names[name].length();			
				for (int space = 0; space < nSpaces; space++) {
					System.out.print(" ");
				}
				
				// Output the score
				System.out.println(top10Scores[name]);
			}
		}
		System.out.println();
		
		// Save the top 10 scores and names into the Excel spreadsheet
		for (int i = 0; i < top10Scores.length; i++) {
			HSSFRow newRow = sheet.createRow(i);
			newRow.createCell(0).setCellValue(top10Scores[i]);
			newRow.createCell(1).setCellValue(top10Names[i]);
		}

		// Add this change to the Excel spreadsheet that contains the scores
		try {
			FileOutputStream outputStream = new FileOutputStream(
					excelSpreadsheet);
			workbook.write(outputStream);
			outputStream.close();
		} catch (IOException e) {}
	}
	
	// Called from playGame(choice) when user chooses to exit or restart
	// to make sure they really want to exit
	// (Requirement 6.2.0)
	static int areYouSure(int mode) {
		int areYouSure = 0;
		
		// Action changes based on what user chose
		String action = "restart";
		
		if (mode == 1) {
			action = "exit";
		}
		
		// Reprompts user if invalid input is given
		// (Requirement 6.1.0)
		do {
			System.out.println("Are you sure you want to " + action + "? 1) Yes 2) No");
			
			try {
				areYouSure = Integer.parseInt(scanner.nextLine());
				System.out.println();
			} catch (NumberFormatException nfe) {
				System.out.println("Please enter a number from 1-2.\n");
				continue;
			}
			
			if (areYouSure < 1 || areYouSure > 2) {
				System.out.println("Please enter a number from 1-2.\n");
			}
			
		} while (areYouSure < 1 || areYouSure > 2);
		
		// If player chose not to exit/restart, output message and continue game
		if (areYouSure == 2) {
			System.out.println("You chose not to " + action + ".");
			print(0, false);
		}
		
		return areYouSure;
	}
	
	/* sortScoresAndNames is called from startGame() and saveScore()
	 * to get the top 10 scores and names sorted in descending order
	 */
	static void sortScoresAndNames(Integer[] scores, String[] names) {
		/* Uses insertion sort to sort scores in descending order
		 * 
		 * Whenever a swap needs to be done, both the score in the 
		 * scores array and the name in the parallel names array 
		 * are swapped to make sure the right score is associated with
		 * the right player.
		 */
		for (int i = 1; i < scores.length; i++) {
			if (scores[i - 1] < scores[i]) {
				int j = i - 1;
				int k = i;
				
				while (j >= 0 && scores[j] < scores[k]) {
					int tempScore = scores[j];
					scores[j] = scores[k];
					scores[k] = tempScore;
					
					String tempName = names[j];
					names[j] = names[k];
					names[k] = tempName;
					
					j--;
					k--;
				}
			}
		}
		
		/* Add the top 10 scores and names to globally accessible 
		 * arrays for other methods to use
		 * If there are fewer than 10 scores, fill the rest of the
		 * values with 0 and [blank].
		 */ 
		for (int i = 0; i < leaderboardSize; i++) {
			if (i < scores.length && i < names.length) {
				top10Scores[i] = scores[i];
				top10Names[i] = names[i];
			} else {
				top10Scores[i] = 0;
				top10Names[i] = "";
			}
		}
	}
	
	/* Called from pickACard to differentiate if a card is not face up
	 * and cannot be picked or if the card is not a card at all
	 */
	static boolean isCard(String card) {
		// Adds a space to the front if the card is not a 10
		if (card.length() == 2) {
			card = " " + card;
		}
		
		/* Checks if a given String is a card by looking through 
		 * the cards array and comparing the string to each card 
		 */
		for (int i = 0; i < cards.length; i++) {
			if (card.equals(cards[i])) {
				return true;
			}
		}
		
		return false;
	}
	
	/* There is a space in front of all cards besides the 10 of each suit
	 * for output alignment in the tableau pile.
	 * The 10 occupies 3 places, i.e. "10D", while the rest of the cards 
	 * occupy only 2 spaces, i.e. "AS"
	 */
	// Removes the unnecessary space in front of the card, i.e. " AD"
	// for output alignment
	static String removeUnnecessarySpace(String card) {
		if (!card.substring(0, 2).equals("10")) {
			card = card.substring(1);
		}
		
		return card;
	}
	
	// Called from print() to check if game can be won at this stage
	// (Requirement 5.4.1)
	static boolean winnable() {
		// If the draw and drawn cards piles are empty
		if (drawPile.empty() && drawnCards.empty()) {
			
			// Iterate through the tableau piles
			for (int deck = 0; deck < layout.length; deck++) {
				ArrayList<Integer> currentList = layout[deck];

				for (int cardIndex = 0; cardIndex < currentList.size(); cardIndex++) {
					int card = currentList.get(cardIndex);
					
					// If a single card is not face up, the game cannot be won yet
					if (!isFaceUp(card)) {
						return false;
					}
				}
			}
			
			// If all cards are face up, the game can be won
			return true;
		}
		
		// If the draw or drawn cards piles still have cards,
		// game cannot be won yet
		return false;
	}
	
	// Called from print() to count the number of cards in the tableau layout
	// used to calculate the amount of pts to add to the player's score
	// when the current game can be won at its current state
	static int numFaceUpCards() {
		int nCards = 0;
		
		// Iterate through tableau piles and add the number of cards in each
		for (int deck = 0; deck < layout.length; deck++) {
			nCards += layout[deck].size();
		}
		
		return nCards;
	}
	
	// Called from playGame(choice)
	// Allows user to undo their last draw
	static void undoDraw() {
		int choice = 0;
		boolean lessThan10 = false;
		String warning = "You will lose 10 points";
		
		// If user's score is less than 10, change the warning
		if (points < 10) {
			lessThan10 = true;
			warning = "Your score will be reset to 0";
		}
		
		// Reprompts user if invalid input is given
		// (Requirement 6.1.0)
		while (choice < 1 || choice > 2) {
			// Confirm that the user wants to undo their last draw
			// Warn them of loss of points or score reset
			System.out.println(warning + " if you undo your last draw.");
			System.out.println("Are you sure you want to undo your last draw? 1) Yes 2) No");
			
			try {
				// Take in user choice
				choice = Integer.parseInt(scanner.nextLine());
				System.out.println();
			} 
			// catch non-numeric input
			catch (NumberFormatException nfe) {
				System.out.println("Please enter a number from 1-2.\n");
				continue;
			}
			
			// catch out of range numeric input
			if (choice < 1 || choice > 2) {
				System.out.println("Please enter a number from 1-2.\n");
			}
		}
		
		// If the user chooses not to undo their last draw
		if (choice == 2) {
			System.out.println("You chose not to undo your last draw.");
		} 
		
		// If the user chooses to undo their last draw
		else {
			// Pop the card off the drawn cards pile
			// Push it onto the draw pile
			// (Requirement 3.4.0)
			int card = drawnCards.pop();
			drawPile.push(card);
			
			// Notify user of score reset (if score < 10)
			// or point deduction (Requirement 4.6.0)
			System.out.print("You have undone your last draw. Points -10.");
			points -= 10;
		}
		
		// Output main Solitaire prompt and continue game
		// (Requirement 5.0.0)
		print(0, false);
	}
	
	/* Offers hints to players
	 * Hints are also used by AI bot to play the game
	 * Called from the play() method 
	 */
	static void hint(int AI) {
		if (gameWon || nKings >= 4) {
			return;
		}

		// AI mode 2: slow AI
		// Waits a second (1000 ms) before each move so user can see what the bot is doing
		if (AI == 2) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		}
		
		// If the current point total is higher than the highest score this game,
		// set it as the highest score
		if (points > highestScore) {
			highestScore = points;
		}	
		
		// Fail safe: if the score is more than 75 points below the high 
		// score in this game (b/c of draw pile redraws), the game is likely unwinnable.
		// AI bot is stopped
		if (points < (highestScore - 75)) {
			System.out.println("Fail safe: Computer has been stopped because " + 
					(highestScore - points) + " points have been deducted.");
			print(0, false);
			return;
		}
				
		// Check if any of the cards within the tableau piles can be moved to a different tableau pile
		for (int tableauPile = 0; tableauPile < layout.length; tableauPile++) {
			for (int cardIndex = 0; cardIndex < layout[tableauPile].size(); cardIndex++) {
				int card = layout[tableauPile].get(cardIndex);
				
				// If a card is face up, go through each tableau pile and check if that card can 
				// be moved to that pile
				if (isFaceUp(card)) {
					for (int tableauPile2 = 0; tableauPile2 < layout.length; tableauPile2++) {
						if (tableauToTableau(card, tableauPile2, AI, false, 0)) {
							return;
						}
					}
				}
			}
		}
		
		// For the visible drawn card
		if (!drawnCards.empty()) {
			int drawnCard = drawnCards.peek();
			
			// Check if the card can be moved to any of the tableau piles
			for (int tableauPile = 0; tableauPile < layout.length; tableauPile++) {
				if (tableauToTableau(drawnCard, tableauPile, AI, false, 1)) {
					return;
				}
			}
		}
		
		// Check to see if any of the cards at the bottom of each tableau pile can be moved to the foundation stacks 		
		for (int pile = 0; pile < layout.length; pile++) {
			int lastIndex = layout[pile].size() - 1;
			
			if (lastIndex >= 0) {
				int bottomCard = layout[pile].get(lastIndex);
				
				if (tableauToFoundation(bottomCard, AI, false, 0)) {
					return;
				}
			}
		}
		
		// Check for inner card move: when a card that is not on the bottom of a tableau pile
		// can be moved to a foundation stack
		for (int pile = 0; pile < layout.length; pile++) {
			
			// Iterate through each card in each pile, except for the bottom card
			for (int cardIndex = 0; cardIndex < layout[pile].size() - 1; cardIndex++) {
				// Get values for the current card and the one below it
				int card = layout[pile].get(cardIndex);
				int belowCard = layout[pile].get(cardIndex + 1);
				
				// If the current card is face up and can be moved to the foundation stacks
				if (isFaceUp(card) && 
						canMoveToFoundation(card)) {
					for (int pile1 = 0; pile1 < layout.length; pile1++) {
						// If the below card can be moved to another tableau pile
						if (tableauToTableau(belowCard, pile1, AI, true, 0)) {
							
							// move the current card to the foundation stacks 
							if (tableauToFoundation(card, AI, true, 0)) {
								return;
							}
						}
					}
				}
			}
		}
		
		// For the visible drawn card
		if (!drawnCards.empty()) {
			int drawnCard = drawnCards.peek();
			
			// Check if it can be moved to the foundation stacks
			if (tableauToFoundation(drawnCard, AI, false, 1)) {
				return;
			}
		}	
		
		// For the visible drawn card,
		// see if moving a card from the foundation to tableau will allow the 
		// drawn card to be moved to the tableau 
		if (!drawnCards.empty()) {
			int drawnCard = drawnCards.peek();
			
			// Iterate through each foundation stack
			for (int stack = 0; stack < foundationStacks.length; stack++) {
				Stack<Integer> currentStack = foundationStacks[stack];
				
				if (!currentStack.empty()) {
					int topCard = currentStack.peek();
					
					// If the drawn card and a foundation card can be matched
					if (isMatch(drawnCard, topCard)) {
						for (int pile = 0; pile < layout.length; pile++) {
							// see if foundation card can be moved to tableau
							if (tableauToTableau(topCard, pile, AI, true, 2)) {
								// move drawn card to tableau underneath foundation card
								if (tableauToTableau(drawnCard, pile, AI, false, 1)) {
									return;
								}
							}
						}
					}
				}
			}
		}
		
		// Offer to draw a card
		if (!drawPile.empty() || !drawnCards.empty()) {
			if (AI > 0) {
				playGame(1, null, true);
				hint(AI);
				return;
			}
			
			// Hints for user
			if (!drawPile.empty()) {
				System.out.println("Draw a card.");
			} else {
				System.out.println("Draw a card. You will need to restart the draw pile.");
			}
			
			print(0, false);
			return;
		}		
		
		// If user has won solitaire, no need for more hints
		if (nKings == 4) {
			return;
		}
		
		// No more hints
		if (AI > 0) {
			System.out.println("Computer is done. Computer can go no further.");
		} else {	
			System.out.println("This is going to be a hard game.");
		}
		
		print(0, false);
	}
	
	// For moving cards to tableau piles
	// Called from hint()
	static boolean tableauToTableau(int card, int tableauPile2, int AI, boolean innerCardMove, int drawnFoundation) {
		
		// If the card can be moved to the new tableau pile
		if (canMove(card, tableauPile2)) {
			int tableauPile2CardLastIndex = layout[tableauPile2].size() - 1;
			
			// Inner card move: when a card that is not on the bottom of a tableau pile
			// can be moved to a foundation stack
			// The cards below that card need to be moved onto a different tableau pile
			// to make way for that card, so it doesn't matter if the bot has already tried 
			// to switch the below cards to that tableau pile. 
			if (!innerCardMove) {
				// Kings to be moved to empty tableau piles
				if (tableauPile2CardLastIndex < 0) {
					// If a King has already been placed in an empty pile, 
					// prevent it from constantly being switched to a different empty pile.
					if (kingsPiles[card % 4]) {
						return false;
					}
					
					// Since an empty pile has been found for this King, prevent 
					// it from being switched again.
					kingsPiles[card % 4] = true;
				} 
				
				// Other cards
				else if (drawnFoundation == 0) {
					int currentPile = findPile(card);
					int cardIndex = layout[currentPile].lastIndexOf(card);
					
					if (cardIndex > 0) {
						int prevCard = layout[currentPile].get(cardIndex - 1);
						
						if (isFaceUp(prevCard)) {
							return false;
						}
					}
				}
			}
			
			// AI mode
			if (AI > 0) {
				// Move the card to the intended tableau pile
				move(card, tableauPile2, drawnFoundation);
				
				// Show the outcome of that move
				print(1, true);
				
				// For inner card moves, the above card needs to be moved to the 
				// foundation stacks, so don't ask for a new hint yet.
				if (!innerCardMove) {
					hint(AI);
				}
				
				return true;
			}
			
			// Hint for user
			System.out.println("Pick " + removeUnnecessarySpace(cards[card]) + ".");
			print(0, false);
			return true;
		}
		
		return false;
	}
	
	// For moving cards to foundation stacks
	// Called from hint()
	static boolean tableauToFoundation(int card, int AI, boolean foundationMove, int drawnFoundation) {
		
		// If the card can be moved to a foundation stack
		if (canMoveToFoundation(card)) {
			
			// AI mode
			if (AI > 0) {
				// Move the card to the foundation stack
				moveToFoundation(card, drawnFoundation);
				
				// Show the user the outcome of the move
				print(1, true);
				
				// Ask for a new hint
				hint(AI);
				
				return true;
			}
			
			// Give the user a hint on how to proceed in the game			
			System.out.println("Pick " + removeUnnecessarySpace(cards[card]) + ".");
			print(0, false);
			return true;
		}
		
		return false;
	}
}
