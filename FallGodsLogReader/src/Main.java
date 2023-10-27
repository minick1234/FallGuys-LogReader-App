import java.io.*;
import java.util.*;
import java.lang.SecurityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Main {
    private static int currentPlayerID = -1, currentPartyID = -1, gamesPlayed = 0, numberOfSessions = 0, numberOfMatches = 0, numberOfRounds = 0;
    private static String pathToPlayerLog;
    private static ArrayList<String> MapPlayedSoFarInOrder = new ArrayList<String>();
    private static long lastKnownLength = 0, lineCount = 0;

    private static int CurrRoundNumWriting = 0;

    public static void main(String[] args) throws IOException {

        //The current line we are reading from the file.
        String currentLine;

        //when round is found - read the next 19 lines and associate that with round information.
        int roundInfoCounter = 10;

        boolean printRoundInfo = false;

        File DefaultDirectory = CheckForDefaultLogsStorageFolder();

        boolean AddNewSession = true;
        File roundFile = null;

        boolean JustLaunchedFirstSkip = false;
        RandomAccessFile raf = null;
        boolean PrintedEndOfMatchInfo = false;

        boolean playerQualifiedThisRound = false;
        boolean checkForQualificationTextFound = false;

        int PlayerPositionInThisRound = 1;

        DeleteOldOrInvalidGameSessionFolders(DefaultDirectory);

        //Two fixes to be made.
        //First i need to fix that when you are disqualified, we stop making new round files and make sure it doesn't allow us to make any others until we go back to the main menu.
        //Second we need to fix so that it doesnt print out the qualified true or false and position again at the end of the mmatch because we do this in real time.
        //Thirdly we need to make sure that we fix the issue that if we didn't qualify that the player qualfiication number is like -1 or 0 to make sure it can really know when we qualified vs when we were eliminated. - then we can handle that -1 or 0 value in the backend.
        //We need to work on refactoring some code, and making some things into functions to shorten everything, and finally clean up some other parts.
        //All in all it is basically finished and works very well in a bit of a spaghetti mess right now.
        //Final and last but not least thing is fixing and working on the duration so it appropriately shows how long a round took to finish.

        //Figure out if there is a way to know if a map is a survival or not because the position aspect of the log doesnt really make sense for that type of map. So as of right now i dont know how to handle it
        boolean MadeNewSession = false;

        while (true) {

            File SessionFolderDirectory = null;
            File MatchFolderDirectory = null;
            File AfterMatchEndRoundFileToWriteTo = null;
            boolean MakeRoundFiles = true;
            boolean matchStarted = false;

            boolean DisconnectedPrematurely = false;

            if (raf == null) {
                PrepareReadFile();
                raf = new RandomAccessFile(pathToPlayerLog, "r");
            }

            //Only continue checking this file if we are still playing fall guys.
            while (IsProcessStillRunning()) {


                //first go over the file and see if they played a match at any point inside it, if there is match info in the file already we can assume it was read already.
                if (!MadeNewSession && SessionFolderDirectory == null) {
                    String currLineFirstRead;
                    while ((currLineFirstRead = raf.readLine()) != null) {
                        if (currLineFirstRead.contains("[Hazel] [HazelNetworkTransport] Disconnect request received for connection 0. Reason: HazelNetworkTransport - Disconnect")
                                || currLineFirstRead.contains("== [CompletedEpisodeDto] ==")
                                || currLineFirstRead.contains("[StateGameLoading] Finished loading game level, assumed to be")
                                || currLineFirstRead.contains("[StateConnectToGame] We're connected to the server!")) {
                            System.out.println("We have found round / match info which means this session has already been happening so dont make a new one and just reuse this one.");
                            if (Files.isDirectory(DefaultDirectory.toPath())) {
                                try {
                                    Stream<Path> GameSessionFolders = Files.list(DefaultDirectory.toPath());
                                    Optional<Path> lastGameSessionFolder = GameSessionFolders.filter(Files::isDirectory).max(Comparator.naturalOrder());

                                    if (lastGameSessionFolder.isPresent()) {
                                        System.out.println("We have a old game session folder, we will now use that.");
                                        SessionFolderDirectory = lastGameSessionFolder.get().toFile();
                                    } else {
                                        System.out.println("The old session might have been empty and deleted so create a new one.");
                                        SessionFolderDirectory = CreateNewSessionFolder(DefaultDirectory, SessionFolderDirectory);
                                    }
                                } catch (IOException e) {
                                    System.out.println("An error occured during the directory grabbing: " + e.getMessage());
                                }
                            }
                            AddNewSession = false;
                            break;
                        }
                    }
                }

                //Add a new session
                if (AddNewSession) {
                    AddNewSession = false;
                    SessionFolderDirectory = CreateNewSessionFolder(DefaultDirectory, SessionFolderDirectory);
                    MadeNewSession = true;
                }

                if (!JustLaunchedFirstSkip) {
                    raf.seek(raf.length());
                    JustLaunchedFirstSkip = true;
                    lastKnownLength = raf.length();
                }

                if (raf.length() > lastKnownLength) {
                    raf.seek(lastKnownLength);
                    while ((currentLine = raf.readLine()) != null) {
                        //count up the line count for debugging purposes.
                        ++lineCount;

                        //Final two things to check for is when state of the game changes from countdown to play - and grab that time.
                        //also when the player suceedes below we write to the file if they suceeded or not and grab that time as well and do a comparison so we can print the duration

                        if (currentLine.contains("ClientGameManager::HandleServerPlayerProgress PlayerId=")) {
                            String CurrLinePlayerId = "";
                            // Define a regular expression pattern to match PlayerId=value
                            Pattern pattern = Pattern.compile("PlayerId=(\\d+)");
                            Matcher matcher = pattern.matcher(currentLine);

                            if (matcher.find()) {
                                // Extract the matched PlayerId value
                                CurrLinePlayerId = matcher.group(1);
                            }

                            if (Integer.parseInt(CurrLinePlayerId) == currentPlayerID) {
                                checkForQualificationTextFound = true;
                                //Parse this qualified round text line to get if we succeeded or not.
                                playerQualifiedThisRound = Boolean.parseBoolean(currentLine.split("=")[2]);
                                System.out.println("The player qualification status this round: " + playerQualifiedThisRound);

                                try {
                                    FileWriter writer = new FileWriter(roundFile, true);
                                    try {
                                        writer.write("Qualified: " + playerQualifiedThisRound + "\n");
                                        if (!playerQualifiedThisRound) {
                                            writer.write("Position: " + "0" + "\n");
                                            MakeRoundFiles = false;
                                        } else {
                                            writer.write("Position: " + PlayerPositionInThisRound + "\n");
                                        }

                                    } catch (IOException e) {
                                        System.out.println("An error occured writing to the file");
                                    }
                                    writer.close();
                                } catch (IOException e) {
                                    System.out.println("an error occured closing the writer.");
                                }
                            } else if (Integer.parseInt(CurrLinePlayerId) != currentPlayerID && Boolean.parseBoolean(currentLine.split("=")[2])) {
                                PlayerPositionInThisRound++;
                            }
                        }

                        if (currentLine.contains("[Hazel] [HazelNetworkTransport] Disconnect request received for connection 0. Reason: HazelNetworkTransport - Disconnect")) {
                            System.out.println("We have left the lobby.");

                            //if we are here we can assume the player(s) didn't qualify for the next round and left early.
                            if (!playerQualifiedThisRound && checkForQualificationTextFound && !PrintedEndOfMatchInfo) {
                                System.out.println("Left early because we didn't qualify for the next round!");
                                continue;
                            }

                            long savedNormalPosition = raf.getFilePointer();

                            if (PrintedEndOfMatchInfo) {
                                try {
                                    Thread.sleep(7500);
                                } catch (InterruptedException e) {
                                    System.out.println("There was an error on thread sleep: \n" + e.getMessage());
                                }
                            }

                            for (int i = 0; i < 10; i++) {
                                String nextLine = raf.readLine();
                                if (nextLine != null) {
                                    System.out.println("Checking for reward line...");

                                    //If we are in the disconnect and we have finished the match and the info printed.
                                    //we want to sleep for 10 seconds to let the file update.
                                    //then we can read what it finds.

                                    if (nextLine.contains("[StateWaitingForRewards] Init: waitingForRewards = ")) {
                                        System.out.println("We have found the rewards line.");
                                        DisconnectedPrematurely = false;
                                        break;
                                    } else {
                                        DisconnectedPrematurely = true;
                                    }
                                } else {
                                    System.out.println("Next line is null so we stop reading - waiting for rewards area.");
                                }
                            }

                            if (DisconnectedPrematurely && !PrintedEndOfMatchInfo) {
                                System.out.println("No reward line found.");
                                System.out.println("We have disconnected prematurely");
                                DisconnectedPrematurely = false;
                                //If we leave the match early we want to be sure to stop the current match check.
                                matchStarted = false;
                                if (MatchFolderDirectory != null) {
                                    if (Files.isDirectory(MatchFolderDirectory.toPath())) {
                                        try (Stream<Path> files = Files.list(MatchFolderDirectory.toPath())) {
                                            files.filter(Files::isRegularFile).forEach(file -> {
                                                try {
                                                    Files.deleteIfExists(file);
                                                } catch (IOException e) {
                                                    throw new UncheckedIOException(e);
                                                }
                                            });
                                        }
                                    }

                                    Files.deleteIfExists(MatchFolderDirectory.toPath());
                                }

                                //Reset the round and match folder to null to allow for appropriate creation again.
                                MatchFolderDirectory = null;
                                roundFile = null;
                                AfterMatchEndRoundFileToWriteTo = null;
                            }

                            PrintedEndOfMatchInfo = false;
                            System.out.println("\nReturning to normal file search position - and reading normally again");
                            raf.seek(savedNormalPosition);
                        }

                        if (printRoundInfo && roundInfoCounter > 0) {
                            ReadEndOfMatchRoundInfo(currentLine, AfterMatchEndRoundFileToWriteTo);

                            roundInfoCounter--;
                        } else if (printRoundInfo) {
                            printRoundInfo = false;
                            roundInfoCounter = 10;
                        }

                        if (currentLine.contains("[StateConnectToGame] We're connected to the server!")) {
                            MakeRoundFiles = true;
                            MatchFolderDirectory = CreateNewMatchFolder(SessionFolderDirectory);
                            matchStarted = true;

                        } else if (matchStarted) {
                            if (currentLine.contains("[StateGameLoading] Finished loading game level, assumed to be") && MakeRoundFiles) {
                                System.out.println(currentPlayerID);
                                playerQualifiedThisRound = false;
                                checkForQualificationTextFound = false;
                                roundFile = CreateNewRoundFile(MatchFolderDirectory);
                                PlayerPositionInThisRound = 1;
                                // Define a regular expression pattern to match the desired text
                                Pattern pattern = Pattern.compile("assumed to be (\\S+)");
                                Matcher matcher = pattern.matcher(currentLine);

                                String MapNameFromLog = "";

                                if (matcher.find()) {
                                    // Extract the matched part
                                    MapNameFromLog = matcher.group(1);
                                    System.out.println("Extracted: " + MapNameFromLog);
                                } else {
                                    System.out.println("Pattern not found in the log entry.");
                                    MapNameFromLog = "a non existant map";
                                }

                                String tempMapNameFromLog = MapNameFromLog.substring(0, MapNameFromLog.length() - 1);
                                String[] MapNameSplit = tempMapNameFromLog.trim().split("_");

                                String RealMapName = "";

                                boolean ValidMapALLMAPS = false;

                                if (LevelStats.ALLMAPS.get(MapNameFromLog.trim().substring(0, MapNameFromLog.length() - 3)) == null) {
                                    for (int i = 0; i < MapNameSplit.length; i++) {
                                        RealMapName += MapNameSplit[i];

                                        if (LevelStats.ALLMAPS.get(RealMapName) != null) {
                                            ValidMapALLMAPS = true;
                                            break;
                                        }
                                        System.out.println("Map name so far: #1 " + RealMapName);

                                        if (i < MapNameSplit.length - 1) {
                                            RealMapName += "_";
                                        }
                                        System.out.println("Map name so far: #2 " + RealMapName);

                                    }
                                    if (!ValidMapALLMAPS) {
                                        RealMapName = MapNameFromLog.trim().substring(0, MapNameFromLog.length() - 3);
                                    }
                                } else {
                                    RealMapName = MapNameFromLog.trim().substring(0, MapNameFromLog.length() - 3);
                                    ValidMapALLMAPS = true;
                                }

                                System.out.println("Map name from log is: " + MapNameFromLog);

                                try {
                                    FileWriter writer = new FileWriter(roundFile, true);
                                    try {
                                        if (ValidMapALLMAPS) {
                                            System.out.println("Map name converted to correct value: " + LevelStats.ALLMAPS.get(RealMapName).name);
                                            writer.write("Map Name: " + LevelStats.ALLMAPS.get(RealMapName).name + "\n");

                                        } else {
                                            writer.write("Map Name: " + RealMapName + "\n");
                                            System.out.println("Unfortunately this is not a valid map. That can be converted");
                                        }

                                    } catch (IOException e) {
                                        System.out.println("An error occured writing to the file");
                                    }
                                    writer.close();
                                } catch (IOException e) {
                                    System.out.println("an error occured closing the writer.");
                                }
                            }
                        }
                        if (currentLine.contains("== [CompletedEpisodeDto] ==")) {
                            gamesPlayed++;
                        }

                        if (currentLine.contains("[Round")) {
                            PrintedEndOfMatchInfo = true;
                            String[] roundLineInTwo = currentLine.split("\\|");
                            int RoundNum = Integer.parseInt(roundLineInTwo[0].split(" ")[1].trim());

                            roundInfoCounter--;
                            printRoundInfo = true;

                            if (MatchFolderDirectory.isDirectory()) {
                                File[] roundFilesInMatchFolder = MatchFolderDirectory.listFiles();

                                if (roundFilesInMatchFolder.length > 0 && roundFilesInMatchFolder != null) {
                                    for (File matchRoundFile : roundFilesInMatchFolder) {
                                        int tempMatchRoundNum = Integer.parseInt(matchRoundFile.getName().split("_")[1].split("\\.")[0]);
                                        if (RoundNum == tempMatchRoundNum) {
                                            AfterMatchEndRoundFileToWriteTo = matchRoundFile;
                                            System.out.println("Found the valid round number for this info in the match folder - using it for writing info.");
                                        }

                                    }
                                }
                            }
                        }

                        if (currentLine.contains("[ClientGlobalGameState] Client has been disconnected")) {
                            matchStarted = false;
                        }

                        if (currentLine.contains("[CreateLocalPlayerInstances] Added new player as Participant, player ID = ")) {
                            UpdatePlayerID(currentLine);
                        }
                    }
                    lastKnownLength = raf.getFilePointer();
                }
            }

            DeleteOldOrInvalidGameSessionFolders(DefaultDirectory);

            //Close the file reader and buffered reader.
            if (raf != null) {
                raf.close();
                raf = null;
            }

            //At this point, the session has stopped, so reset the number of rounds and matches to 0, for the new session when it starts.
            numberOfMatches = 0;
            numberOfRounds = 0;
            lastKnownLength = 0;
            AddNewSession = true;

            //Let this loop sleep for 2 seconds before rechecking if the process is open.
            try {
                Thread.sleep(2000);
            } catch (IllegalArgumentException | InterruptedException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    public static void DeleteOldOrInvalidGameSessionFolders(File defaultDirectory) {
        try {
            if (Files.isDirectory(defaultDirectory.toPath())) {
                if (Files.list(defaultDirectory.toPath()).count() > 0) {
                    Stream<Path> gameSessionFolders = Files.list(defaultDirectory.toPath());
                    gameSessionFolders.forEach(gameSession -> {
                        try {
                            // Check if there are any matches folders inside this game session
                            try (Stream<Path> matchFolders = Files.list(gameSession)) {
                                if (!matchFolders.anyMatch(Files::isDirectory)) {
                                    // No match folders found; delete the game session
                                    Files.walk(gameSession)
                                            .sorted(Comparator.reverseOrder()) // Start deleting from leaf nodes
                                            .forEach(path -> {
                                                try {
                                                    System.out.println("i am deleting the old folder.");
                                                    Files.delete(path);
                                                } catch (IOException e) {
                                                    throw new UncheckedIOException(e);
                                                }
                                            });
                                }
                            }
                        } catch (IOException e) {
                            System.out.println("There was an issue traversing the gamesessions/matches folder print the error: " + e.getMessage());
                        }
                    });
                }
            }
        } catch (IOException e) {
            System.out.println("Error listing out all the match folders.");
        }
    }

    public static void PrepareReadFile() {
        //Attempt to get the %AppData%
        try {
            pathToPlayerLog = System.getenv("AppData") + "\\..\\LocalLow\\Mediatonic\\FallGuys_client\\Player.log";
        } catch (SecurityException | NullPointerException e) {
            System.out.println("The environment path cannot be found");
        }
    }

    //Checks for if the process is running, this determines if we should continue checking the file for updates.
    public static boolean IsProcessStillRunning() {

        String processName = "FallGuys_client_game.exe";

        ProcessBuilder fallGuysProcessBuilder = new ProcessBuilder("tasklist");

        Process fallGuysProcess = null;
        try {
            fallGuysProcess = fallGuysProcessBuilder.start();
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.out.println("Can't find the tasklist.");
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(fallGuysProcess.getInputStream()));
        String lineRead;

        try {
            while ((lineRead = reader.readLine()) != null) {
                if (lineRead.contains(processName)) {
                    reader.close();
                    return true;
                }
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
            System.out.println("Currently no lines to read.");
        }

        System.out.println("The process is not running.");
        return false;
    }

    //Reads the current lines round info and assigns it to the values for the correct object.
    public static void ReadEndOfMatchRoundInfo(String currentLine, File currentRoundFile) {
        System.out.println(currentLine);

        boolean qualifiedFound = false;
        boolean positionFound = false;

        try {
            BufferedReader roundFileReader = new BufferedReader(new FileReader(currentRoundFile));
            String currRoundFileLine;
            while ((currRoundFileLine = roundFileReader.readLine()) != null) {
                if (currRoundFileLine.contains("Qualified:")) {
                    qualifiedFound = true;
                } else if (currRoundFileLine.contains("Position:")) {
                    positionFound = true;
                }
            }
        } catch (FileNotFoundException ex) {
            System.out.println("The file was not able to be found!");
        } catch (IOException ex) {
            System.out.println("The file is not able to be read correctly.");
        }

        try {
            FileWriter writer = new FileWriter(currentRoundFile, true);
            try {
                if ((positionFound && qualifiedFound) && currentLine.contains("Position:") || currentLine.contains("Qualified:")) {
                    return;
                }

                writer.write(currentLine + "\n");
            } catch (IOException e) {
                System.out.println("An error occurred writing to the file");
            }
            writer.close();
        } catch (IOException e) {
            System.out.println("an error occurred closing the writer.");
        }
    }

    //This returns the new player id and is only called if player id has changed in the logs.
    public static long UpdatePlayerID(String currentLine) {

        String[] splitParts = currentLine.split("player ID =");
        currentPlayerID = Integer.parseInt(splitParts[1].trim());
        return currentPlayerID;
    }

    public static boolean CheckIfFileOrPathExists(Path path) {
        if (Files.isDirectory(path)) {
            return true;
        } else if (Files.isRegularFile(path)) {
            return true;
        }

        System.out.println("This path does not exist");
        return false;
    }

    public static File CreateNewRoundFile(File matchFolderDirectory) {
        File[] roundFiles = matchFolderDirectory.listFiles(File::isFile);
        File newRoundTextFile = null;
        if (roundFiles != null && roundFiles.length > 0) {
            for (File roundFile : roundFiles) {
                String roundFileWithExtension = roundFile.getName().trim().split("_")[1];
                numberOfRounds = Integer.parseInt(roundFileWithExtension.trim().split("\\.")[0]);

            }
            newRoundTextFile = new File(matchFolderDirectory.getPath() + "\\Round_" + ++numberOfRounds + ".txt");
            try {
                newRoundTextFile.createNewFile();

            } catch (IOException e) {
                System.out.println("There was a error creating the file.");
            }

        } else {
            newRoundTextFile = new File(matchFolderDirectory.getPath() + "\\Round_0.txt");
            try {
                newRoundTextFile.createNewFile();

            } catch (IOException e) {
                System.out.println("There was a error creating the file.");
            }
        }

        return newRoundTextFile;
    }

    public static File CreateNewMatchFolder(File writeSessionsFolder) {

        File[] matchFolders = writeSessionsFolder.listFiles(File::isDirectory);
        File newMatchFolder = null;
        if (matchFolders != null && matchFolders.length > 0) {
            for (File folder : matchFolders) {
                numberOfMatches = Integer.parseInt(folder.getName().split("_")[1]);
            }
            newMatchFolder = new File(writeSessionsFolder.getPath() + "\\Match_" + ++numberOfMatches);
            newMatchFolder.mkdir();

        } else {
            newMatchFolder = new File(writeSessionsFolder.getPath() + "\\Match_1");
            newMatchFolder.mkdir();
        }

        return newMatchFolder;
    }

    public static File CreateNewSessionFolder(File defaultDirectory, File writeSessions) {

        File[] folders = defaultDirectory.listFiles(File::isDirectory);
        if (folders != null && folders.length > 0) {
            for (File folder : folders) {
                numberOfSessions = Integer.parseInt(folder.getName().split("_")[1]);
            }
            writeSessions = new File(defaultDirectory.getPath() + "\\GameSession_" + ++numberOfSessions);
            writeSessions.mkdir();

        } else {
            writeSessions = new File(defaultDirectory.getPath() + "\\GameSession_1");
            writeSessions.mkdir();
        }

        return writeSessions;
    }

    public static File CheckForDefaultLogsStorageFolder() {
        //Make sure the default directory for where we store data exists and if not we then create it.
        File DefaultDirectory = new File("C:\\FallGuysTracker_Logs");
        if (!CheckIfFileOrPathExists(DefaultDirectory.toPath())) {
            DefaultDirectory.mkdir();
        }
        return DefaultDirectory;
    }

}