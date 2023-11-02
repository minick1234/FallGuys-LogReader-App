import java.io.*;
import java.util.*;
import java.lang.SecurityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Main {
    private static int currentPlayerID = -1, numberOfSessions = 0, roundInfoCounter = 10, PlayerPositionInThisRound = 1, LevelsLoaded = 0;
    private static String pathToPlayerLog, currentLine, gameModeSelected;
    private static long lastKnownLength = 0;
    private static boolean foundPlayerFinishedStatus = false, printRoundInfo = false, AddNewSession = true, JustLaunchedFirstSkip = false, PrintedEndOfMatchInfo = false, playerQualifiedThisRound = false, checkForQualificationTextFound = false, MadeNewSession = false, gameModeFound = false, checkForGameMode = false;
    private static File roundFile = null, SessionFolderDirectory = null, MatchFolderDirectory = null;
    private static RandomAccessFile raf = null;

    public static void main(String[] args) throws IOException {

        File DefaultDirectory = CheckForDefaultLogsStorageFolder();

        DeleteOldOrInvalidGameSessionFolders(DefaultDirectory);

        while (true) {

            SessionFolderDirectory = null;
            MatchFolderDirectory = null;
            File AfterMatchEndRoundFileToWriteTo = null;
            boolean MakeRoundFiles = true;
            boolean matchStarted = false;

            boolean DisconnectedPrematurely = false;

            if (raf == null) {
                PrepareReadFile();
                raf = new RandomAccessFile(pathToPlayerLog, "r");
            }

            if (IsProcessStillRunning() && JustLaunchedFirstSkip) {
                JustLaunchedFirstSkip = false;
                PerformThreadSleep(2000);
            }

            while (IsProcessStillRunning()) {

                CheckForValidOldSessionFolder(DefaultDirectory);

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

                        if (currentLine.contains("[HandleSuccessfulLogin] Selected show is") && !checkForGameMode && matchStarted) {
                            String pattern = "(\\w+)$";
                            Pattern regex = Pattern.compile(pattern);
                            Matcher matcher = regex.matcher(currentLine);

                            String lastPart = "";

                            if (matcher.find()) {
                                lastPart = matcher.group(1);
                                System.out.println(lastPart);
                            } else {
                                System.out.println("No match found");
                            }

                            switch (lastPart) {
                                case "main_show":
                                    gameModeSelected = "Solos";
                                    gameModeFound = true;
                                    checkForGameMode = true;
                                    break;
                                case "squads_2player_template":
                                    gameModeSelected = "Duos";
                                    gameModeFound = true;
                                    checkForGameMode = true;
                                    break;
                                case "squads_4player":
                                    gameModeSelected = "Squads";
                                    gameModeFound = true;
                                    checkForGameMode = true;
                                    break;
                            }
                        }

                        if (currentLine.contains("ClientGameManager::HandleServerPlayerProgress PlayerId=") && matchStarted) {

                            String CurrLinePlayerId = "";
                            Pattern pattern = Pattern.compile("PlayerId=(\\d+)");
                            Matcher matcher = pattern.matcher(currentLine);

                            if (matcher.find()) {
                                CurrLinePlayerId = matcher.group(1);
                            }

                            if (Integer.parseInt(CurrLinePlayerId) == currentPlayerID) {
                                checkForQualificationTextFound = true;
                                playerQualifiedThisRound = Boolean.parseBoolean(currentLine.split("=")[2]);

                                foundPlayerFinishedStatus = true;
                                System.out.println("The player qualified this round: " + playerQualifiedThisRound);
                                WriteToFile(roundFile, "Qualified: " + playerQualifiedThisRound);

                                if (!playerQualifiedThisRound) {
                                    WriteToFile(roundFile, "Position: " + -1);

                                } else {
                                    WriteToFile(roundFile, "Position: " + PlayerPositionInThisRound);
                                }

                            } else if (Integer.parseInt(CurrLinePlayerId) != currentPlayerID && Boolean.parseBoolean(currentLine.split("=")[2])) {
                                PlayerPositionInThisRound++;
                            }
                        }

                        if (currentLine.contains("[Hazel] [HazelNetworkTransport] Disconnect request received for connection 0. Reason: HazelNetworkTransport - Disconnect")) {

                            matchStarted = false;
                            System.out.println("We have left the lobby.");
                            checkForGameMode = false;
                            foundPlayerFinishedStatus = false;
                            LevelsLoaded = 0;

                            //if we are here we can assume the player(s) didn't qualify for the next round and left early.
                            if ((!playerQualifiedThisRound && checkForQualificationTextFound && !PrintedEndOfMatchInfo) || !MakeRoundFiles) {
                                System.out.println("Left because we didn't qualify for the next round!");
                                continue;
                            }

                            long savedNormalPosition = raf.getFilePointer();

                            if (PrintedEndOfMatchInfo) {
                                PerformThreadSleep(7500);
                            }

                            for (int i = 0; i < 10; i++) {
                                String nextLine = raf.readLine();
                                if (nextLine != null) {
                                    System.out.println("Checking for reward line...");

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

                            System.out.println("Printed end of match info variable " + PrintedEndOfMatchInfo);

                            if (DisconnectedPrematurely && !PrintedEndOfMatchInfo) {
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
                            LevelsLoaded = 0;
                            System.out.println("The player id for this game is: " + currentPlayerID);
                            foundPlayerFinishedStatus = false;
                            checkForGameMode = false;
                            MakeRoundFiles = true;
                            MatchFolderDirectory = CreateNewMatchFolder(SessionFolderDirectory);
                            matchStarted = true;

                        } else if (matchStarted) {
                            if (currentLine.contains("[StateGameLoading] Finished loading game level, assumed to be") && MakeRoundFiles) {
                                ++LevelsLoaded;

                                if (LevelsLoaded > 1 && !foundPlayerFinishedStatus && gameModeFound && (gameModeSelected.equals("Duos") || gameModeSelected.equals("Squads"))) {
                                    System.out.println("Correcting the squad or due - not qualified information in the logs.");
                                    WriteToFile(roundFile, "Qualified: false");
                                    WriteToFile(roundFile, "Position: -1");
                                }

                                foundPlayerFinishedStatus = false;
                                playerQualifiedThisRound = false;
                                checkForQualificationTextFound = false;
                                roundFile = CreateNewRoundFile(MatchFolderDirectory);
                                PlayerPositionInThisRound = 1;

                                // Define a regular expression pattern to match the desired text
                                Pattern pattern = Pattern.compile("assumed to be (\\S+)");
                                Matcher matcher = pattern.matcher(currentLine);

                                String MapNameFromLog = "";

                                if (matcher.find()) {
                                    MapNameFromLog = matcher.group(1);
                                } else {
                                    MapNameFromLog = "a non existant map";
                                }

                                String tempMapNameFromLog = MapNameFromLog.substring(0, MapNameFromLog.length() - 1);
                                String[] MapNameSplit = tempMapNameFromLog.trim().split("_");

                                if (!gameModeFound && !checkForGameMode) {
                                    if (MapNameSplit[MapNameSplit.length - 1].equals("40")) {
                                        gameModeSelected = "Solos";
                                    } else if (MapNameSplit[MapNameSplit.length - 1].equals("squads")) {
                                        gameModeSelected = "Squads";
                                    } else if (MapNameSplit[MapNameSplit.length - 1].equals("duos")) {
                                        gameModeSelected = "Duos";
                                    }

                                    gameModeFound = true;
                                    checkForGameMode = true;
                                }

                                if (gameModeSelected.contains("Duos") || gameModeSelected.contains("Solos") || gameModeSelected.contains("Squads")) {
                                    WriteToFile(roundFile, "Gamemode: " + gameModeSelected);
                                }

                                String RealMapName = "";

                                boolean ValidMapALLMAPS = false;

                                if (LevelStats.ALLMAPS.get(tempMapNameFromLog) == null) {
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
                                        RealMapName = tempMapNameFromLog;
                                    }
                                } else {
                                    RealMapName = tempMapNameFromLog;
                                    ValidMapALLMAPS = true;
                                }

                                System.out.println("Map name from log is: " + tempMapNameFromLog);

                                if (ValidMapALLMAPS) {
                                    WriteToFile(roundFile, "Map Name: " + LevelStats.ALLMAPS.get(RealMapName).name);
                                } else {
                                    WriteToFile(roundFile, "Map Name: " + RealMapName);
                                    System.out.println("Unfortunately this is not a valid map. That can be converted");
                                }
                            }
                        }

                        if (currentLine.contains("[Round")) {
                            gameModeFound = false;
                            playerQualifiedThisRound = false;
                            MakeRoundFiles = false;
                            matchStarted = false;

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

                        if (currentLine.contains("[ClientGlobalGameState] Client has been disconnected") && matchStarted) {
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

            lastKnownLength = 0;
            AddNewSession = true;

            //Let this loop sleep for 2 seconds before rechecking if the process is open.
            PerformThreadSleep(2000);
        }
    }

    public static void WriteToFile(File fileToWriteTo, String informationToWrite) {
        try {
            FileWriter writer = new FileWriter(fileToWriteTo, true);
            try {
                writer.write(informationToWrite + "\n");
            } catch (IOException e) {
                System.out.println("An error occured writing to the file");
            }
            writer.close();
        } catch (IOException e) {
            System.out.println("an error occured closing the writer.");
        }
    }

    public static void PerformThreadSleep(long sleepTimeInMilliseconds) {
        try {
            Thread.sleep(sleepTimeInMilliseconds);
        } catch (IllegalArgumentException | InterruptedException e) {
            System.out.println(e.getMessage());
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

    public static void ReadEndOfMatchRoundInfo(String currentLine, File currentRoundFile) {
        System.out.println(currentLine);

        boolean qualifiedFound = ReadRoundFileFor_QualificationOrPosition(currentRoundFile, "Qualified:");
        boolean positionFound = ReadRoundFileFor_QualificationOrPosition(currentRoundFile, "Position:");

        if ((positionFound && qualifiedFound) && (currentLine.contains("Position:") || currentLine.contains("Qualified:"))) {
            return;
        }

        if (currentLine.length() > 0) {
            WriteToFile(currentRoundFile, currentLine.substring(2, currentLine.length()));
        } else {
            WriteToFile(currentRoundFile, currentLine);
        }
    }

    public static boolean ReadRoundFileFor_QualificationOrPosition(File currentRoundFile, String qualifiedOrPosition) {
        try {
            BufferedReader roundFileReader = new BufferedReader(new FileReader(currentRoundFile));
            String currRoundFileLine;
            while ((currRoundFileLine = roundFileReader.readLine()) != null) {
                if (currRoundFileLine.contains(qualifiedOrPosition)) {
                    return true;
                }
            }
        } catch (FileNotFoundException ex) {
            System.out.println("The file was not able to be found!");
        } catch (IOException ex) {
            System.out.println("The file is not able to be read correctly.");
        }
        return false;
    }

    public static void UpdatePlayerID(String currentLine) {

        String[] splitParts = currentLine.split("player ID =");
        currentPlayerID = Integer.parseInt(splitParts[1].trim());
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
        int numberOfRounds = 0;
        File[] roundFiles = matchFolderDirectory.listFiles(File::isFile);
        File newRoundTextFile = null;
        if (roundFiles != null && roundFiles.length > 0) {
            for (File roundFile : roundFiles) {
                numberOfRounds++;
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

        int numberOfMatches = 0;
        File[] matchFolders = writeSessionsFolder.listFiles(File::isDirectory);
        File newMatchFolder = null;
        if (matchFolders != null && matchFolders.length > 0) {
            for (File folder : matchFolders) {
                numberOfMatches++;
            }
            System.out.println("Number of matches: " + numberOfMatches);
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

    public static void CheckForValidOldSessionFolder(File DefaultDirectory) {
        if (!MadeNewSession && SessionFolderDirectory == null) {
            String currLineFirstRead;
            try {
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
            } catch (IOException e) {
                System.out.println("We caught a error while reading the player log.");
            }
        }
    }

}